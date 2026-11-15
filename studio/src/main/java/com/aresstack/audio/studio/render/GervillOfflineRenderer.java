package com.aresstack.audio.studio.render;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Render an SF2-backed MIDI track using the Gervill engine already included in the JDK.
 * Isolate access to the JDK-internal openStream method in this dedicated process.
 */
public final class GervillOfflineRenderer {
    private static final int SAMPLE_RATE = 44100;
    private static final long TAIL_MICROS = 2500000L;
    private static final int DEFAULT_TEMPO_MICROS = 500000;

    private GervillOfflineRenderer() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3 && arguments.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: GervillOfflineRenderer input.mid soundfont.sf2 output.wav [max-seconds]");
        }
        RenderLimits limits = arguments.length == 4
                ? new RenderLimits(java.time.Duration.ofSeconds(Long.parseLong(arguments[3])),
                RenderLimits.STANDARD.minProcess())
                : RenderLimits.STANDARD;
        render(Paths.get(arguments[0]), Paths.get(arguments[1]), Paths.get(arguments[2]), limits);
    }

    public static void render(Path midiFile, Path soundFont, Path output) throws Exception {
        render(midiFile, soundFont, output, RenderLimits.STANDARD);
    }

    public static void render(Path midiFile, Path soundFont, Path output, RenderLimits limits) throws Exception {
        requireFile(midiFile, "MIDI arrangement");
        requireFile(soundFont, "SF2 soundbank");
        if (!soundFont.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".sf2")) {
            throw new IOException("Gervill supports SF2 here; use FluidSynth for SF3 or sfizz for SFZ.");
        }

        Sequence sequence = MidiSystem.getSequence(midiFile.toFile());
        if (sequence.getDivisionType() != Sequence.PPQ) {
            throw new IOException("Only MIDI with a musical ticks-per-quarter timebase is supported.");
        }
        if (sequence.getResolution() < 1) {
            throw new IOException("Invalid MIDI tick resolution.");
        }
        Soundbank bank = MidiSystem.getSoundbank(soundFont.toFile());
        Synthesizer synthesizer = MidiSystem.getSynthesizer();
        if (synthesizer == null) {
            throw new IOException("The Java runtime does not provide a MIDI synthesizer.");
        }

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
        AudioInputStream synthesized = null;
        try {
            synthesized = openGervillStream(synthesizer, format);
            if (!synthesizer.isSoundbankSupported(bank)) {
                throw new IOException("Gervill cannot load this SF2 SoundFont: " + soundFont);
            }
            Soundbank defaultBank = synthesizer.getDefaultSoundbank();
            if (defaultBank != null) {
                synthesizer.unloadAllInstruments(defaultBank);
            }
            if (!synthesizer.loadAllInstruments(bank)) {
                throw new IOException("Gervill rejected the instruments in " + soundFont);
            }

            long endMicros = sendEvents(sequence, synthesizer.getReceiver());
            limits.checkPiece(endMicros / 1000000.0, "The MIDI file");
            long frames = (long) Math.ceil(
                    (endMicros + TAIL_MICROS) * (double) SAMPLE_RATE / 1000000.0);
            Path destination = output.toAbsolutePath().normalize();
            Files.createDirectories(destination.getParent());
            try (AudioInputStream limited = new AudioInputStream(synthesized, format, frames)) {
                AudioSystem.write(limited, AudioFileFormat.Type.WAVE, destination.toFile());
                synthesized = null;
            }
            if (!Files.isRegularFile(destination) || Files.size(destination) < 1024L) {
                throw new IOException("Gervill produced no playable WAV: " + destination);
            }
            System.out.println("Gervill SF2: " + bank.getName() + " -> " + destination);
        } finally {
            if (synthesized != null) {
                synthesized.close();
            }
            synthesizer.close();
        }
    }

    private static AudioInputStream openGervillStream(Synthesizer synthesizer, AudioFormat format)
            throws Exception {
        try {
            Method openStream = synthesizer.getClass().getMethod(
                    "openStream", AudioFormat.class, Map.class);
            return (AudioInputStream) openStream.invoke(synthesizer, format, null);
        } catch (IllegalAccessException denied) {
            throw new IOException("The JDK denied Gervill's offline stream. "
                    + "Launch the renderer with --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED "
                    + "on Java 9+, or use Java 8.", denied);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IOException("Cannot open the built-in Gervill audio stream.", failed);
        } catch (NoSuchMethodException unavailable) {
            throw new IOException("The selected Java synthesizer has no Gervill offline stream.", unavailable);
        }
    }

    private static long sendEvents(Sequence sequence, Receiver receiver) throws Exception {
        List<TimedEvent> events = gatherEvents(sequence);
        long previousTick = 0;
        long micros = 0;
        int tempo = DEFAULT_TEMPO_MICROS;

        for (TimedEvent event : events) {
            long tick = event.midiEvent.getTick();
            micros += ((tick - previousTick) * (long) tempo) / sequence.getResolution();
            previousTick = tick;
            MidiMessage message = event.midiEvent.getMessage();
            if (message instanceof MetaMessage) {
                MetaMessage meta = (MetaMessage) message;
                if (meta.getType() == 0x51 && meta.getData().length >= 3) {
                    byte[] bytes = meta.getData();
                    tempo = ((bytes[0] & 255) << 16) | ((bytes[1] & 255) << 8) | (bytes[2] & 255);
                    if (tempo < 1) {
                        throw new IOException("Invalid MIDI tempo event.");
                    }
                }
            } else {
                receiver.send(message, micros);
            }
        }
        return Math.max(micros, sequence.getMicrosecondLength());
    }

    private static List<TimedEvent> gatherEvents(Sequence sequence) {
        List<TimedEvent> events = new ArrayList<TimedEvent>();
        int sequenceNumber = 0;
        for (Track track : sequence.getTracks()) {
            for (int index = 0; index < track.size(); index++) {
                events.add(new TimedEvent(track.get(index), sequenceNumber++));
            }
        }
        Collections.sort(events, new Comparator<TimedEvent>() {
            @Override
            public int compare(TimedEvent first, TimedEvent second) {
                int byTick = Long.compare(first.midiEvent.getTick(), second.midiEvent.getTick());
                return byTick != 0 ? byTick
                        : Integer.compare(first.sequenceNumber, second.sequenceNumber);
            }
        });
        return events;
    }

    private static void requireFile(Path file, String description) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(description + " does not exist: " + file);
        }
    }

    private static final class TimedEvent {
        private final MidiEvent midiEvent;
        private final int sequenceNumber;

        private TimedEvent(MidiEvent midiEvent, int sequenceNumber) {
            this.midiEvent = midiEvent;
            this.sequenceNumber = sequenceNumber;
        }
    }
}
