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
 * @param answer  the whole answer as the model gave it, for what it says beyond the notes; may be {@code null}
 */
public record GeneratedNotes(String partId, String sectionId, List<RawNote> notes, List<String> skipped,
                             JsonObject answer) {
    /** Notes without the answer they came in. */
    public GeneratedNotes(String partId, String sectionId, List<RawNote> notes, List<String> skipped) {
        this(partId, sectionId, notes, skipped, null);
    }

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

    /** What asks the model, or takes its kept answer. */
    public interface Asking {
        /**
         * @param problem why the answer before could not be used, to be told to the model; {@code null}
         *                when asked for the first time
         */
        JsonObject ask(String problem) throws IOException;
    }

    /**
     * Asks and reads the answer. An answer that cannot be read is never kept: its copy is thrown
     * away and the model is asked again, told what was wrong, after a pause that grows up to its
     * longest ({@link Backoff}). It is never given up; only cancelling ends it.
     */
    public static GeneratedNotes ask(Asking asking, java.nio.file.Path cache, String partId, String sectionId)
            throws IOException {
        String problem = null;
        for (int asked = 1; ; asked++) {
            try {
                return read(asking.ask(problem), partId, sectionId);
            } catch (UnreadableAnswerException | ForeignPartException unusable) {
                forget(cache);
                if (Backoff.givesUp(asked)) {
                    throw unusable;
                }
                problem = unusable.getMessage();
                Backoff.tell("Unbrauchbare KI-Antwort für " + partId + " (" + sectionId + "): " + problem
                        + " · Versuch " + (asked + 1) + " in " + Backoff.words(Backoff.delay(asked)) + ".");
                Backoff.pause(asked, null);
            }
        }
    }

    /** The words added to a prompt when the answer before could not be used. */
    public static String again(String problem) {
        return problem == null ? "" : "\nYour previous answer could not be used: " + problem
                + " Answer again: one JSON object with the field part and the list notes.";
    }

    private static void forget(java.nio.file.Path cache) {
        if (cache == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(cache);
        } catch (IOException kept) {
            // the next answer is asked for anyway; the file is only a copy
        }
    }

    /**
     * The list of notes of an answer: under "notes", else the first list of note objects in it,
     * also one level deeper (a model may write {"glockenspiel": {"notes": [...]}}); {@code null}
     * if there is none.
     */
    private static JsonArray notesIn(JsonObject answer, int depth) {
        JsonElement list = answer.get("notes");
        if (list != null && list.isJsonArray()) {
            return list.getAsJsonArray();
        }
        for (java.util.Map.Entry<String, JsonElement> entry : answer.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonArray() && value.getAsJsonArray().size() > 0
                    && value.getAsJsonArray().get(0).isJsonObject()
                    && value.getAsJsonArray().get(0).getAsJsonObject().has("pitch")) {
                return value.getAsJsonArray();
            }
        }
        if (depth > 1) {
            for (java.util.Map.Entry<String, JsonElement> entry : answer.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    JsonArray inner = notesIn(entry.getValue().getAsJsonObject(), depth - 1);
                    if (inner != null) {
                        return inner;
                    }
                }
            }
        }
        return null;
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
        // An answer that names no part is the answer to this request, for this part: only an answer
        // that names another part is turned down.
        if (part != null && !partId.equals(part)) {
            throw new ForeignPartException("Antwort für fremden Part \"" + part + "\" statt \"" + partId
                    + "\" abgelehnt.");
        }
        String section = text(answer, "section");
        if (section != null && !section.equals(sectionId)) {
            throw new ForeignPartException("Antwort für fremden Abschnitt \"" + section + "\" statt \"" + sectionId
                    + "\" abgelehnt.");
        }
        JsonArray array = notesIn(answer, 2);
        if (array == null) {
            throw new UnreadableAnswerException("Die Antwort für " + partId + " enthält keine Notenliste.");
        }
        if (array.size() > MAX_NOTES) {
            throw new UnreadableAnswerException("Zu viele Noten für " + partId + " in " + sectionId + ": "
                    + array.size());
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
        return new GeneratedNotes(partId, sectionId, notes, skipped, answer);
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
