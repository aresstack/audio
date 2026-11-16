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

    /**
     * The notes with their bars counted from the start of the frame. A model that counts the bars
     * of the piece instead (it sees them so in the plan and in what the part played before) writes
     * bar 297 for the first bar of a frame that begins at bar 297; such an answer is counted back
     * to the frame when more of its notes lie in the frame by the bars of the piece than by its own.
     * Unchanged when both counts would overlap (a frame near the start of the piece).
     *
     * @param firstBar the bar the frame begins with, counted from 0
     */
    public GeneratedNotes inFrame(int firstBar, int bars) {
        if (firstBar < bars || notes.isEmpty()) {
            return this;
        }
        int own = 0;
        int ofPiece = 0;
        for (RawNote note : notes) {
            if (note.bar() >= 1 && note.bar() <= bars) {
                own++;
            } else if (note.bar() >= firstBar + 1 && note.bar() <= firstBar + bars) {
                ofPiece++;
            }
        }
        if (ofPiece <= own) {
            return this;
        }
        List<RawNote> moved = new ArrayList<>();
        for (RawNote note : notes) {
            moved.add(new RawNote(note.index(), note.bar() - firstBar, note.beat(), note.duration(), note.pitch(),
                    note.velocity(), note.voice(), note.articulation()));
        }
        List<String> said = new ArrayList<>(skipped);
        said.add(sectionId + "\t-\tbar\t" + (firstBar + 1) + "\t1\tTakte des Stücks statt des Abschnitts gezählt;"
                + " um " + firstBar + " zurückgerechnet");
        return new GeneratedNotes(partId, sectionId, moved, said, answer);
    }

    /** How many notes begin in a frame of so many bars (bars counted from 1 at its start). */
    public int inBars(int bars) {
        int count = 0;
        for (RawNote note : notes) {
            if (note.bar() >= 1 && note.bar() <= bars && note.bar() == Math.rint(note.bar())) {
                count++;
            }
        }
        return count;
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
        return ask(asking, cache, partId, sectionId, null);
    }

    /** How often an answer turned down by a {@link Check} is asked for again before it is taken. */
    public static final int CHECKED_TRIES = 3;

    /** What makes an answer that could be read unusable for its request, or {@code null} if it is fine. */
    public interface Check {
        String problem(GeneratedNotes notes);
    }

    /**
     * As {@link #ask(Asking, java.nio.file.Path, String, String)}, and an answer the check turns
     * down is treated as one that cannot be read: thrown away and asked again, told why.
     */
    public static GeneratedNotes ask(Asking asking, java.nio.file.Path cache, String partId, String sectionId,
                                     Check check) throws IOException {
        String problem = null;
        for (int asked = 1; ; asked++) {
            String seen = "";
            try {
                GeneratedNotes notes = read(asking.ask(problem), partId, sectionId);
                seen = glimpse(notes.answer());
                // A check is a wish, not a must: after so many tries the answer is taken as it is
                // (and the composer reports what is missing) instead of asking for ever.
                String wrong = check == null || asked > CHECKED_TRIES ? null : check.problem(notes);
                if (wrong != null) {
                    throw new UnreadableAnswerException(wrong);
                }
                return notes;
            } catch (UnreadableAnswerException | ForeignPartException unusable) {
                forget(cache);
                if (Backoff.givesUp(asked)) {
                    throw unusable;
                }
                problem = unusable.getMessage();
                String from = KipitzChatClient.lastAnswer();
                Backoff.tell("Unbrauchbare KI-Antwort für " + partId + " (" + sectionId + ")"
                        + (from.isEmpty() ? "" : " [" + from + "]") + ": " + problem
                        + (seen.isEmpty() ? "" : " · Die Antwort beginnt mit: " + seen)
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
        array = flat(array);
        if (array.size() > MAX_NOTES) {
            throw new UnreadableAnswerException("Zu viele Noten für " + partId + " in " + sectionId + ": "
                    + array.size());
        }
        List<RawNote> notes = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = row(array.get(i));
            if (!element.isJsonObject()) {
                skipped.add(sectionId + "\t" + (i + 1) + "\tnote\tpresent\tskipped\tkein Objekt");
                continue;
            }
            JsonObject note = named(element.getAsJsonObject());
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

    /** The short names a model takes from the lists of notes in its prompt ("bar beat dur pitch vel"). */
    private static final String[][] OTHER_NAMES = {
            {"beat", "start", "offset", "pos", "position"},
            {"duration", "dur", "length", "len"},
            {"pitch", "note", "midi", "key"},
            {"velocity", "vel"}};

    /** A note with its fields under the names the studio reads: other usual names are taken for them. */
    private static JsonObject named(JsonObject note) {
        JsonObject read = note;
        for (String[] names : OTHER_NAMES) {
            if (read.has(names[0])) {
                continue;
            }
            for (int i = 1; i < names.length; i++) {
                JsonElement value = read.get(names[i]);
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    if (read == note) {
                        read = note.deepCopy();
                    }
                    read.add(names[0], value);
                    break;
                }
            }
        }
        return read;
    }

    /**
     * A note written as a row like the lists of the prompt, {@code [bar, beat, dur, pitch, vel]} or
     * {@code "1 0 0.5 36 90"}, as an object; anything else as it is.
     */
    private static JsonElement row(JsonElement element) {
        List<Double> numbers = new ArrayList<>();
        try {
            if (element.isJsonArray()) {
                for (JsonElement value : element.getAsJsonArray()) {
                    numbers.add(value.getAsDouble());
                }
            } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                for (String value : element.getAsString().trim().split("[\\s,;|]+")) {
                    numbers.add(Double.parseDouble(value));
                }
            } else {
                return element;
            }
        } catch (RuntimeException noRow) {
            return element;
        }
        if (numbers.size() < 4 || numbers.size() > 5) {
            return element;
        }
        JsonObject note = new JsonObject();
        note.addProperty("bar", numbers.get(0));
        note.addProperty("beat", numbers.get(1));
        note.addProperty("duration", numbers.get(2));
        note.addProperty("pitch", (int) Math.round(numbers.get(3)));
        if (numbers.size() > 4) {
            note.addProperty("velocity", (int) Math.round(numbers.get(4)));
        }
        return note;
    }

    /**
     * The notes of a list grouped by bar, {@code [{"bar":1,"notes":[...]}]}, as one list in which
     * every note carries its bar; any other list as it is.
     */
    private static JsonArray flat(JsonArray list) {
        boolean grouped = false;
        for (JsonElement element : list) {
            if (element.isJsonObject() && !element.getAsJsonObject().has("pitch")
                    && element.getAsJsonObject().has("notes") && element.getAsJsonObject().get("notes").isJsonArray()) {
                grouped = true;
                break;
            }
        }
        if (!grouped) {
            return list;
        }
        JsonArray notes = new JsonArray();
        for (JsonElement element : list) {
            if (!element.isJsonObject() || !element.getAsJsonObject().has("notes")
                    || !element.getAsJsonObject().get("notes").isJsonArray()) {
                notes.add(element);
                continue;
            }
            JsonElement bar = element.getAsJsonObject().get("bar");
            for (JsonElement inner : element.getAsJsonObject().getAsJsonArray("notes")) {
                JsonElement note = row(inner);
                if (note.isJsonObject() && bar != null && !note.getAsJsonObject().has("bar")) {
                    note = note.deepCopy();
                    note.getAsJsonObject().add("bar", bar);
                }
                notes.add(note);
            }
        }
        return notes;
    }

    /** The first characters of the list of notes of an answer, for the log when it cannot be used. */
    static String glimpse(JsonObject answer) {
        if (answer == null) {
            return "";
        }
        JsonArray list = notesIn(answer, 2);
        String said = list == null ? answer.toString() : list.toString();
        return said.length() <= 240 ? said : said.substring(0, 240) + " …";
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
