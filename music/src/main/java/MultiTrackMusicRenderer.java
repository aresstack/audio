import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.music.synth.BuiltinSynthesis;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Render an original multitrack arrangement to a MIDI file and synthesized preview WAVs. */
public final class MultiTrackMusicRenderer {
    private static final int PPQ = 480;
    private static final int SAMPLE_RATE = BuiltinSynthesis.SAMPLE_RATE;

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

    /**
     * Reads the arrangement with the shared reader and refuses anything it cannot play: an
     * unknown line, a track or note with the wrong number of columns, a bad or repeated track ID,
     * a note of a track not named before it, or a note outside the eight bars.
     */
    private static Arrangement parseArrangement(Path input) throws IOException {
        Arrangement arrangement = new Arrangement();
        for (ArrangementFile.Entry entry : ArrangementFile.read(input).entries()) {
            if (entry instanceof ArrangementFile.Tempo tempo) {
                arrangement.tempo = Integer.parseInt(tempo.value().trim());
            } else if (entry instanceof ArrangementFile.Track track && track.columns() <= 5) {
                if (!track.id().matches("[a-z][a-z0-9_-]{0,28}")
                        || arrangement.parts.containsKey(track.id())) {
                    throw new IllegalArgumentException("Invalid or duplicate track ID: " + track.id());
                }
                String role = track.role() != null ? track.role() : track.id();
                arrangement.parts.put(track.id(), new Part(track.id(), Integer.parseInt(track.program().trim()),
                        Integer.parseInt(track.channel().trim()), role));
            } else if (entry instanceof ArrangementFile.Note line && line.columns() == 6) {
                Part part = arrangement.parts.get(line.trackId());
                if (part == null) {
                    throw new IllegalArgumentException("Unknown part: " + line.trackId());
                }
                if (line.tick() < 0 || line.length() < 1 || line.pitch() < 0 || line.pitch() > 127
                        || line.velocity() < 1 || line.velocity() > 127
                        || line.tick() + line.length() > PPQ * 4 * 8) {
                    throw new IllegalArgumentException("Invalid note: " + line.text());
                }
                part.notes.add(new Note((int) line.tick(), (int) line.length(), line.pitch(), line.velocity()));
            } else {
                throw new IllegalArgumentException("Invalid input line: " + entry.text());
            }
        }
        if (arrangement.tempo < 60 || arrangement.tempo > 180
                || arrangement.parts.isEmpty()) {
            throw new IllegalArgumentException("Require tempo 60..180 and at least one track.");
        }
        // More tracks than a port has channels share channels; each then goes to a port of its own.
        java.util.Map<Integer, Integer> used = new java.util.HashMap<Integer, Integer>();
        for (Part part : arrangement.parts.values()) {
            boolean isDrums = "drums".equals(part.role);
            if (part.notes.isEmpty() || part.program < 0 || part.program > 127 || part.channel < 0
                    || part.channel > 15 || (part.channel == 9) != isDrums) {
                throw new IllegalArgumentException("Invalid or empty track: " + part.name);
            }
            Integer before = used.get(part.channel);
            part.port = before == null ? 0 : before;
            used.put(part.channel, part.port + 1);
            if (part.port > 127) {
                throw new IllegalArgumentException("Too many tracks on one MIDI channel: " + part.name);
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
            if (part.port > 0) {
                // "MIDI port": programs that read the file keep tracks apart that share a channel.
                addMeta(track, 0x21, new byte[]{(byte) part.port}, 0);
            }
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
        double totalSeconds = 8 * 4 * (60.0 / arrangement.tempo);
        int frames = BuiltinSynthesis.frames(totalSeconds);
        double[] mixed = new double[frames];
        for (Part part : arrangement.parts.values()) {
            List<BuiltinSynthesis.Tone> tones = new ArrayList<BuiltinSynthesis.Tone>();
            for (Note note : part.notes) {
                tones.add(new BuiltinSynthesis.Tone(note.tick / (double) PPQ * 60.0 / arrangement.tempo,
                        note.length / (double) PPQ * 60.0 / arrangement.tempo, note.pitch, note.velocity, note.tick));
            }
            double[] samples = BuiltinSynthesis.part(part.role, tones, frames);
            for (int i = 0; i < frames; i++) {
                mixed[i] += samples[i];
            }
            BuiltinSynthesis.writeWave(output.resolve(part.name + ".wav"), samples);
        }
        // Add a quiet single-tap echo to glue the generated timbres together.
        int delay = (int) (0.19 * SAMPLE_RATE);
        for (int i = frames - 1; i >= delay; i--) {
            mixed[i] += mixed[i - delay] * 0.11;
        }
        BuiltinSynthesis.writeWave(output.resolve("arrangement.wav"), mixed);
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
        private int port;
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
