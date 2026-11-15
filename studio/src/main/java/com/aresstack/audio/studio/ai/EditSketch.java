package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.progress.SketchProgress;
import com.aresstack.audio.studio.project.TsvExport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The piece while the AI works on it: the piece as it is, with the notes the models have written
 * so far where they will be. Shown a few times a second at most as the lines of an arrangement
 * ({@link SketchProgress#sketch}); nothing of it is kept, the change itself is made from the
 * whole answers. Where a part gets notes in a frame, the notes it had there are left out of the
 * picture, as a rewritten passage replaces them.
 */
public final class EditSketch {
    private static final long EVERY = 250;
    private final SketchProgress shown;
    private final TimeMap time;
    private final List<String> head = new ArrayList<>();
    private final List<String> tracks = new ArrayList<>();
    /** The notes of the piece as {@code {part, tick, line}}. */
    private final List<Object[]> notes = new ArrayList<>();
    private final List<String> partIds = new ArrayList<>();
    private final Map<String, String> newTracks = new LinkedHashMap<>();
    /** What was written so far, by part and frame: the frame and the lines of its notes. */
    private final Map<String, Object[]> written = new LinkedHashMap<>();
    private final long baseEnd;
    private long last;

    private EditSketch(Composition piece, SketchProgress shown) {
        this.shown = shown;
        this.time = piece.timeMap();
        for (String line : TsvExport.lines(piece)) {
            String[] cells = line.split("\t");
            if (cells[0].equals("note") && cells.length > 2) {
                notes.add(new Object[]{cells[1], Long.parseLong(cells[2]), line});
            } else if (cells[0].equals("track") || cells[0].equals("staff")) {
                tracks.add(line);
            } else if (!cells[0].equals("bars")) {
                head.add(line);
            }
        }
        for (Part part : piece.parts()) {
            partIds.add(part.id());
        }
        this.baseEnd = piece.endTick();
    }

    /**
     * The sketch of a piece for a listener that wants to see it, else {@code null}: for a listener
     * that only reads lines, and for a piece the lines of an arrangement cannot say.
     */
    public static EditSketch of(Composition piece, Progress progress) {
        if (!(progress instanceof SketchProgress)) {
            return null;
        }
        try {
            return new EditSketch(piece, (SketchProgress) progress);
        } catch (RuntimeException unsayable) {
            return null;
        }
    }

    /** Tells the listener which step runs before the notes, see {@link SketchProgress#preparing}. */
    public static Listening.Scope preparing(Progress progress, String what) {
        if (!(progress instanceof SketchProgress)) {
            return Listening.to(null);
        }
        SketchProgress watched = (SketchProgress) progress;
        watched.preparing(what, 0);
        return Listening.to(text -> watched.preparing(what, text.length()));
    }

    /**
     * Who looks at the answer for a part in a frame while it is written.
     *
     * @param program the General MIDI program of a part the piece does not have yet
     * @param role    its role, or {@code null}
     */
    public Consumer<String> hearing(String partId, int program, String role, VoiceFrame frame) {
        return text -> put(partId, program, role, frame, PartialNotes.read(text), false);
    }

    /** The whole answer for a part in a frame. */
    public void written(String partId, int program, String role, VoiceFrame frame, GeneratedNotes generated) {
        JsonArray array = new JsonArray();
        for (GeneratedNotes.RawNote raw : generated.notes()) {
            JsonObject note = new JsonObject();
            note.addProperty("bar", raw.bar());
            note.addProperty("beat", raw.beat());
            note.addProperty("duration", raw.duration());
            note.addProperty("pitch", raw.pitch());
            note.addProperty("velocity", raw.velocity());
            array.add(note);
        }
        put(partId, program, role, frame, array, true);
    }

    private synchronized void put(String partId, int program, String role, VoiceFrame frame, JsonArray answer,
                                  boolean now) {
        try {
            if (!partIds.contains(partId) && !newTracks.containsKey(partId)) {
                boolean drums = Role.DRUMS.equals(role);
                newTracks.put(partId, "track\t" + partId + "\t" + (drums ? 0 : Math.max(0, Math.min(127, program)))
                        + "\t" + (drums ? 9 : 0) + (role == null ? "" : "\t" + role));
            }
            List<String> lines = new ArrayList<>();
            for (JsonElement element : answer) {
                String line = line(partId, frame, element);
                if (line != null) {
                    lines.add(line);
                }
            }
            written.put(partId + "\n" + frame.sectionId(), new Object[]{partId, frame, lines});
            long at = System.currentTimeMillis();
            if (!now && at - last < EVERY) {
                return;
            }
            last = at;
            shown.sketch(lines());
        } catch (RuntimeException unshown) {
            // a picture of the piece on its way; the change itself does not depend on it
        }
    }

    private String line(String partId, VoiceFrame frame, JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject note = element.getAsJsonObject();
        try {
            double bar = note.has("bar") ? note.get("bar").getAsDouble() : 1;
            double beat = note.get("beat").getAsDouble();
            double duration = note.get("duration").getAsDouble();
            int pitch = note.get("pitch").getAsInt();
            int velocity = note.has("velocity") ? note.get("velocity").getAsInt() : 80;
            if (bar != Math.rint(bar) || bar < 1 || bar > frame.bars() || beat < 0 || duration <= 0 || pitch < 0
                    || pitch > 127) {
                return null;
            }
            long tick = time.tickOfBar(frame.firstBar() + (int) bar - 1) + Math.round(beat * TimeMap.PPQ);
            if (tick >= frame.endTick()) {
                return null;
            }
            long length = Math.max(1, Math.min(Math.round(duration * TimeMap.PPQ), frame.endTick() - tick));
            return "note\t" + partId + "\t" + tick + "\t" + length + "\t" + pitch + "\t"
                    + Math.max(1, Math.min(127, velocity));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private List<String> lines() {
        long end = baseEnd;
        for (Object[] entry : written.values()) {
            end = Math.max(end, ((VoiceFrame) entry[1]).endTick());
        }
        List<String> lines = new ArrayList<>(head);
        lines.add("bars\t" + time.barsCovering(end));
        lines.addAll(tracks);
        lines.addAll(newTracks.values());
        for (Object[] note : notes) {
            if (!replaced((String) note[0], (Long) note[1])) {
                lines.add((String) note[2]);
            }
        }
        for (Object[] entry : written.values()) {
            @SuppressWarnings("unchecked")
            List<String> added = (List<String>) entry[2];
            lines.addAll(added);
        }
        return lines;
    }

    private boolean replaced(String partId, long tick) {
        for (Object[] entry : written.values()) {
            VoiceFrame frame = (VoiceFrame) entry[1];
            if (partId.equals(entry[0]) && tick >= frame.startTick() && tick < frame.endTick()) {
                return true;
            }
        }
        return false;
    }
}
