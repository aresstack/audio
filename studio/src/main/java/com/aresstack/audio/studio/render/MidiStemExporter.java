package com.aresstack.audio.studio.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/** Export an isolated instrument track while keeping the song's tempo and timing. */
public final class MidiStemExporter {
    public Path isolate(Path original, String trackName, int program, Path destination) throws Exception {
        Sequence source = MidiSystem.getSequence(original.toFile());
        Track matched = findTrack(source, trackName);
        if (matched == null) {
            throw new IOException("MIDI track not found: " + trackName);
        }
        Sequence isolated = new Sequence(source.getDivisionType(), source.getResolution());
        Track metadata = isolated.createTrack();
        for (Track track : source.getTracks()) {
            for (int index = 0; index < track.size(); index++) {
                MidiEvent event = track.get(index);
                if (event.getMessage() instanceof MetaMessage) {
                    MetaMessage meta = (MetaMessage) event.getMessage();
                    if (meta.getType() == 0x51 || meta.getType() == 0x58) {
                        metadata.add(new MidiEvent((MidiMessage) meta.clone(), event.getTick()));
                    }
                }
            }
        }
        Track notes = isolated.createTrack();
        for (int index = 0; index < matched.size(); index++) {
            MidiEvent event = matched.get(index);
            MidiMessage sourceMessage = event.getMessage();
            if (sourceMessage instanceof ShortMessage) {
                ShortMessage message = (ShortMessage) sourceMessage;
                if (message.getCommand() == ShortMessage.PROGRAM_CHANGE && program >= 0) {
                    ShortMessage changed = new ShortMessage();
                    changed.setMessage(ShortMessage.PROGRAM_CHANGE, message.getChannel(), program, 0);
                    notes.add(new MidiEvent(changed, event.getTick()));
                } else {
                    notes.add(new MidiEvent((MidiMessage) message.clone(), event.getTick()));
                }
            }
        }
        Files.createDirectories(destination.toAbsolutePath().getParent());
        if (MidiSystem.write(isolated, 1, destination.toFile()) == 0) {
            throw new IOException("Cannot write isolated MIDI track: " + destination);
        }
        return destination;
    }

    private static Track findTrack(Sequence sequence, String trackName) {
        for (Track track : sequence.getTracks()) {
            for (int index = 0; index < track.size(); index++) {
                MidiMessage message = track.get(index).getMessage();
                if (message instanceof MetaMessage && ((MetaMessage) message).getType() == 3) {
                    String name = new String(((MetaMessage) message).getData(), StandardCharsets.UTF_8);
                    if (trackName.equals(name)) {
                        return track;
                    }
                }
            }
        }
        return null;
    }
}
