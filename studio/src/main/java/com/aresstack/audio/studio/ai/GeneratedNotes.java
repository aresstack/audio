package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Articulation;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The notes a model wrote for one part in one frame, read from its answer but not yet repaired.
 * Reading refuses an answer for another part or another frame as a whole; a single note that
 * cannot be read is skipped and named in {@link #skipped()}.
 *
 * @param notes   the notes in the order of the answer
 * @param skipped why notes were left out, one line each
 */
public record GeneratedNotes(String partId, String sectionId, List<RawNote> notes, List<String> skipped) {
    /** The most notes one answer may hold (as for the composer since the start). */
    public static final int MAX_NOTES = 256;

    /**
     * A note as written: bar from 1 at the start of the frame, beat and duration in quarter notes.
     *
     * @param index the position of the note in the answer, from 1
     * @param voice the voice named by the model, or {@code null}
     */
    public record RawNote(int index, double bar, double beat, double duration, int pitch, int velocity, String voice,
                          Articulation articulation) {
        public RawNote(int index, double bar, double beat, double duration, int pitch, int velocity, String voice) {
            this(index, bar, beat, duration, pitch, velocity, voice, null);
        }
    }

    /**
     * The sentence of a note prompt that lets a note carry an articulation, as a score marks it;
     * an articulation the studio does not know is left out when reading.
     */
    public static String articulationRule() {
        StringBuilder names = new StringBuilder();
        for (Articulation articulation : Articulation.values()) {
            names.append(names.length() == 0 ? "" : ", ").append('"').append(articulation.token()).append('"');
        }
        return "A note may also carry \"articulation\": one of " + names + ", where the music asks for it as in a "
                + "score (short notes, weight, bound lines); leave it out for plain notes.";
    }

    public GeneratedNotes {
        notes = List.copyOf(notes);
        skipped = List.copyOf(skipped);
    }

    /**
     * Reads the answer of a model for a part and a frame.
     *
     * @throws ForeignPartException if the answer, or a note in it, names another part, or the
     *                              answer names another frame
     * @throws IOException          if the answer has no list of notes or too many
     */
    public static GeneratedNotes read(JsonObject answer, String partId, String sectionId) throws IOException {
        String part = text(answer, "part");
        if (part == null) {
            throw new IOException("Die Antwort nennt keinen Part; erwartet war \"" + partId + "\".");
        }
        if (!partId.equals(part)) {
            throw new ForeignPartException("Antwort für fremden Part \"" + part + "\" statt \"" + partId
                    + "\" abgelehnt.");
        }
        String section = text(answer, "section");
        if (section != null && !section.equals(sectionId)) {
            throw new ForeignPartException("Antwort für fremden Abschnitt \"" + section + "\" statt \"" + sectionId
                    + "\" abgelehnt.");
        }
        JsonElement list = answer.get("notes");
        if (list == null || !list.isJsonArray()) {
            throw new IOException("Die Antwort für " + partId + " enthält keine Notenliste.");
        }
        JsonArray array = list.getAsJsonArray();
        if (array.size() > MAX_NOTES) {
            throw new IOException("Zu viele Noten für " + partId + " in " + sectionId + ": " + array.size());
        }
        List<RawNote> notes = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                skipped.add(sectionId + "\t" + (i + 1) + "\tnote\tpresent\tskipped\tkein Objekt");
                continue;
            }
            JsonObject note = element.getAsJsonObject();
            String notePart = text(note, "part");
            if (notePart != null && !notePart.equals(partId)) {
                throw new ForeignPartException("Note " + (i + 1) + " der Antwort gehört zu fremdem Part \"" + notePart
                        + "\"; Antwort abgelehnt.");
            }
            try {
                double bar = note.has("bar") ? note.get("bar").getAsDouble() : 1;
                notes.add(new RawNote(i + 1, bar, note.get("beat").getAsDouble(), note.get("duration").getAsDouble(),
                        note.get("pitch").getAsInt(), note.has("velocity") ? note.get("velocity").getAsInt() : 80,
                        text(note, "voice"), Articulation.parse(text(note, "articulation"))));
            } catch (RuntimeException malformed) {
                skipped.add(sectionId + "\t" + (i + 1) + "\tnote\tpresent\tskipped\tfehlende oder ungültige Werte");
            }
        }
        return new GeneratedNotes(partId, sectionId, notes, skipped);
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            return value.getAsString();
        } catch (RuntimeException notText) {
            return value.toString();
        }
    }
}
