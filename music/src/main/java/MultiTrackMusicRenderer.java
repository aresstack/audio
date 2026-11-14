import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Render an original multitrack arrangement to a MIDI file and synthesized preview WAVs. */
public final class MultiTrackMusicRenderer {
    private static final int PPQ = 480;
    private static final int SAMPLE_RATE = 22050;
    private static final double TWO_PI = Math.PI * 2.0;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: java -jar music-renderer.jar input.tsv output-directory");
        }
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        Files.createDirectories(output);
        Arrangement arrangement = parseArrangement(input);
        writeMidi(arrangement, output.resolve("arrangement.mid"));
        renderAudio(arrangement, output);
        System.out.println("Rendered " + arrangement.parts.size() + " tracks to " + output.toAbsolutePath());
    }

    private static Arrangement parseArrangement(Path input) throws IOException {
        Arrangement arrangement = new Arrangement();
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        for (String line : lines) {
            line = line.replace("\uFEFF", "");
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\t");
            if (fields.length == 2 && "tempo".equals(fields[0])) {
                arrangement.tempo = Integer.parseInt(fields[1]);
            } else if ((fields.length == 4 || fields.length == 5) && "track".equals(fields[0])) {
                if (!fields[1].matches("[a-z][a-z0-9_-]{0,28}")
                        || arrangement.parts.containsKey(fields[1])) {
                    throw new IllegalArgumentException("Invalid or duplicate track ID: " + fields[1]);
                }
                String role = fields.length == 5 ? fields[4] : fields[1];
                arrangement.parts.put(fields[1], new Part(fields[1], Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[3]), role));
            } else if (fields.length == 6 && "note".equals(fields[0])) {
                Part part = arrangement.parts.get(fields[1]);
                if (part == null) {
                    throw new IllegalArgumentException("Unknown part: " + fields[1]);
                }
                Note note = new Note(Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                        Integer.parseInt(fields[4]), Integer.parseInt(fields[5]));
                if (note.tick < 0 || note.length < 1 || note.pitch < 0 || note.pitch > 127
                        || note.velocity < 1 || note.velocity > 127 || note.tick + note.length > PPQ * 4 * 8) {
                    throw new IllegalArgumentException("Invalid note: " + line);
                }
                part.notes.add(note);
            } else {
                throw new IllegalArgumentException("Invalid input line: " + line);
            }
        }
        if (arrangement.tempo < 60 || arrangement.tempo > 180
                || arrangement.parts.isEmpty() || arrangement.parts.size() > 16) {
            throw new IllegalArgumentException("Require tempo 60..180 and 1..16 MIDI tracks.");
        }
        java.util.Set<Integer> channels = new java.util.HashSet<Integer>();
        for (Part part : arrangement.parts.values()) {
            boolean isDrums = "drums".equals(part.role);
            if (part.notes.isEmpty() || part.program < 0 || part.program > 127 || part.channel < 0
                    || part.channel > 15 || (part.channel == 9) != isDrums
                    || !channels.add(part.channel)) {
                throw new IllegalArgumentException("Invalid, empty or channel-conflicting track: "
                        + part.name);
            }
        }
        return arrangement;
    }

    private static void writeMidi(Arrangement arrangement, Path output) throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track tempoTrack = sequence.createTrack();
        int microseconds = 60000000 / arrangement.tempo;
        addMeta(tempoTrack, 0x51, new byte[]{(byte) (microseconds >>> 16),
                (byte) (microseconds >>> 8), (byte) microseconds}, 0);
        addMeta(tempoTrack, 0x58, new byte[]{4, 2, 24, 8}, 0);
        for (Part part : arrangement.parts.values()) {
            Track track = sequence.createTrack();
            addMeta(track, 0x03, part.name.getBytes(StandardCharsets.UTF_8), 0);
            if (part.channel != 9) {
                addMidi(track, ShortMessage.PROGRAM_CHANGE, part.channel, part.program, 0, 0);
            }
            for (Note note : part.notes) {
                addMidi(track, ShortMessage.NOTE_ON, part.channel, note.pitch, note.velocity, note.tick);
                addMidi(track, ShortMessage.NOTE_OFF, part.channel, note.pitch, 0,
                        note.tick + note.length);
            }
        }
        if (MidiSystem.write(sequence, 1, output.toFile()) <= 0) {
            throw new IOException("Unable to write MIDI type-1 file.");
        }
    }

    private static void addMidi(Track track, int command, int channel, int data1, int data2, long tick)
            throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(command, channel, data1, data2);
        track.add(new MidiEvent(message, tick));
    }

    private static void addMeta(Track track, int type, byte[] data, long tick)
            throws InvalidMidiDataException {
        MetaMessage message = new MetaMessage();
        message.setMessage(type, data, data.length);
        track.add(new MidiEvent(message, tick));
    }

    private static void renderAudio(Arrangement arrangement, Path output) throws IOException {
        double totalSeconds = 8 * 4 * (60.0 / arrangement.tempo) + 0.75;
        int frames = (int) Math.ceil(totalSeconds * SAMPLE_RATE);
        double[] mixed = new double[frames];
        for (Part part : arrangement.parts.values()) {
            double[] samples = new double[frames];
            for (Note note : part.notes) {
                synthesize(part, note, arrangement.tempo, samples);
            }
            double gain = "harmony".equals(part.role) ? 0.55 : "drums".equals(part.role) ? 0.67 : 0.75;
            for (int i = 0; i < frames; i++) {
                samples[i] *= gain;
                mixed[i] += samples[i];
            }
            writeWave(output.resolve(part.name + ".wav"), samples);
        }
        // Add a quiet single-tap echo to glue the generated timbres together.
        int delay = (int) (0.19 * SAMPLE_RATE);
        for (int i = frames - 1; i >= delay; i--) {
            mixed[i] += mixed[i - delay] * 0.11;
        }
        writeWave(output.resolve("arrangement.wav"), mixed);
    }

    private static void synthesize(Part part, Note note, int tempo, double[] samples) {
        double startSeconds = note.tick / (double) PPQ * 60.0 / tempo;
        double noteSeconds = note.length / (double) PPQ * 60.0 / tempo;
        int first = (int) Math.round(startSeconds * SAMPLE_RATE);
        double frequency = 440.0 * Math.pow(2.0, (note.pitch - 69) / 12.0);
        double volume = (note.velocity / 127.0) * 0.22;
        double tail = "harmony".equals(part.role) ? 0.30 : 0.14;
        int count = Math.min(samples.length - first, (int) ((noteSeconds + tail) * SAMPLE_RATE));
        Random noise = new Random(note.pitch * 97L + note.tick * 31L);
        double previousNoise = 0;
        double kickPhase = 0;
        for (int i = 0; i < count; i++) {
            double t = i / (double) SAMPLE_RATE;
            double env = envelope(part.role, t, noteSeconds, tail);
            double value;
            if ("drums".equals(part.role)) {
                double white = noise.nextDouble() * 2.0 - 1.0;
                double highPassed = white - previousNoise * 0.85;
                previousNoise = white;
                if (note.pitch == 36) {
                    kickPhase += TWO_PI * (55 + 105 * Math.exp(-t * 22)) / SAMPLE_RATE;
                    value = Math.sin(kickPhase) * Math.exp(-t * 19);
                } else if (note.pitch == 38) {
                    value = (highPassed * 0.75 + Math.sin(TWO_PI * 175 * t) * 0.25)
                            * Math.exp(-t * 18);
                } else {
                    value = highPassed * Math.exp(-t * 45) * 0.43;
                }
                env = 1.0;
            } else if ("melody".equals(part.role)) {
                value = (Math.sin(TWO_PI * frequency * t) * 0.70
                        + Math.sin(TWO_PI * frequency * 2.01 * t) * 0.22
                        + Math.sin(TWO_PI * frequency * 3.98 * t) * 0.08);
                env *= Math.exp(-t * 2.2);
            } else if ("harmony".equals(part.role)) {
                value = Math.sin(TWO_PI * frequency * t) * 0.71
                        + Math.sin(TWO_PI * frequency * 2.003 * t) * 0.18
                        + Math.sin(TWO_PI * frequency * 0.999 * t + 0.4) * 0.11;
            } else {
                value = Math.sin(TWO_PI * frequency * t) * 0.80
                        + Math.sin(TWO_PI * frequency * 2 * t) * 0.20;
            }
            samples[first + i] += value * volume * env;
        }
    }

    private static double envelope(String part, double t, double duration, double tail) {
        double attack = "harmony".equals(part) ? 0.15 : 0.012;
        double value = Math.min(1.0, t / attack);
        if (t > duration) {
            value *= Math.max(0.0, 1.0 - (t - duration) / tail);
        }
        return value;
    }

    private static void writeWave(Path output, double[] samples) throws IOException {
        double peak = 0;
        for (double sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        double gain = peak > 0.93 ? 0.93 / peak : 1.0;
        byte[] data = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            double sample = Math.max(-1.0, Math.min(1.0, samples[i] * gain));
            short pcm = (short) Math.round(sample * 32767.0);
            data[i * 2] = (byte) pcm;
            data[i * 2 + 1] = (byte) (pcm >> 8);
        }
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try (ByteArrayInputStream input = new ByteArrayInputStream(data);
             AudioInputStream audio = new AudioInputStream(input, format, samples.length)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
        }
    }

    private static final class Arrangement {
        private int tempo;
        private final Map<String, Part> parts = new LinkedHashMap<String, Part>();
    }

    private static final class Part {
        private final String name;
        private final int program;
        private final int channel;
        private final String role;
        private final List<Note> notes = new ArrayList<Note>();

        private Part(String name, int program, int channel, String role) {
            this.name = name;
            this.program = program;
            this.channel = channel;
            this.role = role;
        }
    }

    private static final class Note {
        private final int tick;
        private final int length;
        private final int pitch;
        private final int velocity;

        private Note(int tick, int length, int pitch, int velocity) {
            this.tick = tick;
            this.length = length;
            this.pitch = pitch;
            this.velocity = velocity;
        }
    }
}
