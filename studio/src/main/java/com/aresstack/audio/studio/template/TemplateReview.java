package com.aresstack.audio.studio.template;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.studio.ai.Backoff;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.InstrumentRange;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The second look at a template, by the AI: who plays each part (id, role, instrument, General
 * MIDI program, piano staves), what the piece is called and how it feels, the key where the
 * template names none, and a decision on each place {@link TemplateCleanup} found worth a look
 * (keep it, drop it, move it an octave, or another pitch). The AI does not write notes here: the
 * notes are the template's. Whatever the AI leaves out or answers wrongly is decided by the
 * studio from the template alone, so a template always becomes a piece. Afterwards a note that
 * the chosen instrument cannot play is moved by octaves into its range.
 */
public final class TemplateReview {
    /** The most places worth a look that are put before the AI in one request. */
    public static final int MOST_FLAGS = 80;

    static final String INSTRUCTION = "You prepare a given score for a music program. The notes are fixed: you do "
            + "not compose or rearrange. You decide who plays each part and correct only plain mistakes in the "
            + "places you are shown. Answer with one JSON object only.";

    /**
     * Who plays one part.
     *
     * @param grand whether it is written on a grand staff (piano, harp, organ)
     */
    public record Setup(String id, String role, String instrument, int program, boolean grand) {
    }

    /**
     * The template after the second look.
     *
     * @param score  its notes, with the decisions on the places worth a look applied
     * @param setups one per part of {@code score}, in the same order
     */
    public record Reviewed(TemplateScore score, List<Setup> setups, String title, String mood, Key key,
                           List<String> remarks) {
    }

    private TemplateReview() {
    }

    /**
     * @param model       the model asked; {@code null} decides everything from the template alone
     * @param description what the user wrote about the piece, may be empty
     * @param folder      the folder of the piece, where the answer is kept
     * @param heard       the characters of the answer so far; may be {@code null}
     */
    public static Reviewed review(TemplateScore score, String description, ChatModel model, int maxTokens,
                                  Path folder, Consumer<Long> heard) throws IOException {
        List<TemplateCleanup.Flag> flags = TemplateCleanup.flags(score, MOST_FLAGS);
        JsonObject answer = new JsonObject();
        if (model != null) {
            String prompt = prompt(score, description, flags);
            Path cache = folder.resolve(TextTemplate.CACHE_FOLDER)
                    .resolve("review-" + TextTemplate.hash(prompt) + ".json");
            Files.createDirectories(cache.getParent());
            for (int asked = 1; ; asked++) {
                JsonObject given = model.generate(INSTRUCTION, prompt, maxTokens, cache,
                        heard == null ? null : soFar -> heard.accept((long) soFar.length()));
                if (given != null && given.has("parts") && given.get("parts").isJsonArray()) {
                    answer = given;
                    break;
                }
                Files.deleteIfExists(cache);
                if (Backoff.givesUp(asked)) {
                    break; // then the studio decides from the template alone
                }
                Backoff.tell("Unbrauchbare KI-Antwort für die Besetzung der Vorlage · Versuch " + (asked + 1) + " in "
                        + Backoff.words(Backoff.delay(asked)) + ".");
                Backoff.pause(asked, null);
            }
        }
        return apply(score, answer, flags);
    }

    /** The template as the studio alone would set it up, without asking the AI: shown while the AI looks. */
    public static Reviewed asWritten(TemplateScore score) {
        return apply(score, new JsonObject(), List.of());
    }

    static String prompt(TemplateScore score, String description, List<TemplateCleanup.Flag> flags) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("A score was given as a template (").append(score.format()).append(")");
        if (!score.title().isEmpty()) {
            prompt.append(", titled \"").append(score.title()).append('"');
        }
        prompt.append(". It has ").append(score.bars()).append(" bars");
        prompt.append(score.numerator() > 0 ? " of " + score.numerator() + "/" + score.denominator() : "");
        prompt.append(score.bpm() > 0 ? ", " + score.bpm() + " BPM" : ", no tempo given");
        prompt.append(score.key() != null ? ", key " + score.key().name() : ", no key given (estimated "
                + TemplateKeys.estimate(score.parts()).name() + ")").append(".\n");
        if (description != null && !description.isBlank()) {
            prompt.append("What the user says about it: ").append(description.strip()).append('\n');
        }
        prompt.append("\nParts (index: name in the file, General MIDI program in the file or -1, notes, range):\n");
        for (int i = 0; i < score.parts().size(); i++) {
            ScorePart part = score.parts().get(i);
            int[] range = part.range();
            prompt.append("  ").append(i).append(": \"").append(part.name()).append("\", program ")
                    .append(part.program()).append(part.drums() ? ", drum channel" : "")
                    .append(part.staves() > 1 ? ", " + part.staves() + " staves" : "").append(", ")
                    .append(part.notes().size()).append(" notes, MIDI ").append(range[0]).append("-")
                    .append(range[1]).append('\n');
        }
        if (!flags.isEmpty()) {
            prompt.append("\nPlaces worth a second look (flag: part, bar, what):\n");
            long bar = score.barTicks();
            for (int f = 0; f < flags.size(); f++) {
                TemplateCleanup.Flag flag = flags.get(f);
                ScoreNote note = score.parts().get(flag.part()).notes().get(flag.note());
                prompt.append("  ").append(f).append(": part ").append(flag.part()).append(", bar ")
                        .append(note.tick() / bar + 1).append(", ").append(flag.why()).append('\n');
            }
        }
        prompt.append("\nAnswer with this JSON object:\n")
                .append("{\"title\": \"the title of the piece\", \"mood\": \"its mood in a few words\",\n")
                .append(" \"key\": \"e.g. E major\" (only if the score gives none),\n")
                .append(" \"parts\": [{\"index\": 0, \"id\": \"violin1\" (lower case a-z, 0-9, - or _, unique), ")
                .append("\"role\": \"melody|harmony|bass|drums|texture\", \"instrument\": \"Solo violin\", ")
                .append("\"program\": General MIDI program 0-127 (zero-based), \"grand\": true for piano, harp or ")
                .append("organ on two staves}, ... one for every part],\n")
                .append(" \"corrections\": [{\"flag\": 0, \"action\": \"keep|drop|octave_up|octave_down|pitch\", ")
                .append("\"pitch\": 64 (only for action pitch)}, ...]}\n")
                .append("Keep the instruments the score names; choose one where it names none. Keep a flagged note ")
                .append("unless it is plainly a mistake.");
        return prompt.toString();
    }

    static Reviewed apply(TemplateScore score, JsonObject answer, List<TemplateCleanup.Flag> flags) {
        List<String> remarks = new ArrayList<String>();
        // Decisions on the flagged places.
        Map<Integer, Map<Integer, Integer>> pitchOf = new HashMap<Integer, Map<Integer, Integer>>(); // -1 = drop
        int changed = 0;
        if (answer.has("corrections") && answer.get("corrections").isJsonArray()) {
            for (JsonElement element : answer.getAsJsonArray("corrections")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject correction = element.getAsJsonObject();
                int f = integer(correction.get("flag"), -1);
                if (f < 0 || f >= flags.size()) {
                    continue;
                }
                TemplateCleanup.Flag flag = flags.get(f);
                int pitch = score.parts().get(flag.part()).notes().get(flag.note()).pitch();
                String action = text(correction.get("action")).toLowerCase(Locale.ROOT);
                Integer now = switch (action) {
                    case "drop" -> -1;
                    case "octave_up" -> pitch + 12 <= 127 ? pitch + 12 : null;
                    case "octave_down" -> pitch - 12 >= 0 ? pitch - 12 : null;
                    case "pitch" -> {
                        int wanted = integer(correction.get("pitch"), -1);
                        yield wanted >= 0 && wanted <= 127 ? wanted : null;
                    }
                    default -> null;
                };
                if (now != null && now != pitch) {
                    pitchOf.computeIfAbsent(flag.part(), k -> new HashMap<Integer, Integer>()).put(flag.note(), now);
                    changed++;
                }
            }
        }
        if (changed > 0) {
            remarks.add("Die KI hat " + changed + " von " + flags.size() + " auffälligen Noten berichtigt.");
        } else if (!flags.isEmpty()) {
            remarks.add(flags.size() + " auffällige Noten geprüft und so gelassen.");
        }
        // Who plays each part.
        Map<Integer, JsonObject> told = new HashMap<Integer, JsonObject>();
        if (answer.has("parts") && answer.get("parts").isJsonArray()) {
            JsonArray parts = answer.getAsJsonArray("parts");
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).isJsonObject()) {
                    JsonObject part = parts.get(i).getAsJsonObject();
                    told.putIfAbsent(integer(part.get("index"), i), part);
                }
            }
        }
        List<ScorePart> parts = new ArrayList<ScorePart>();
        List<Setup> setups = new ArrayList<Setup>();
        Set<String> ids = new HashSet<String>();
        int highest = highestMelodicPart(score);
        int moved = 0;
        int unplayable = 0;
        for (int p = 0; p < score.parts().size(); p++) {
            ScorePart part = score.parts().get(p);
            JsonObject said = told.getOrDefault(p, new JsonObject());
            boolean drums = part.drums() || "drums".equals(text(said.get("role")).toLowerCase(Locale.ROOT));
            int program = drums ? 0 : integer(said.get("program"), -1);
            if (part.program() >= 0 && !part.drums()) {
                program = program >= 0 && program <= 127 ? program : part.program();
            }
            if (program < 0 || program > 127) {
                program = guessProgram(part);
            }
            String role = text(said.get("role")).toLowerCase(Locale.ROOT);
            if (drums) {
                role = Role.DRUMS;
            } else if (!Role.ALL.contains(role) || Role.DRUMS.equals(role)) {
                role = p == highest ? Role.MELODY : part.medianPitch() < 48 ? Role.BASS : Role.HARMONY;
            }
            String instrument = plain(text(said.get("instrument")));
            if (instrument.isEmpty()) {
                instrument = plain(part.name());
            }
            if (instrument.isEmpty()) {
                instrument = drums ? "Schlagzeug" : "GM " + program;
            }
            String id = uniqueId(text(said.get("id")).isEmpty() ? part.name() : text(said.get("id")), ids, p);
            boolean grand = !drums && (part.staves() > 1 || (said.has("grand") && bool(said.get("grand"))
                    && keyboardLike(program)));
            InstrumentRange range = InstrumentRange.of(program, drums);
            Map<Integer, Integer> decided = pitchOf.getOrDefault(p, Map.of());
            List<ScoreNote> notes = new ArrayList<ScoreNote>();
            for (int n = 0; n < part.notes().size(); n++) {
                ScoreNote note = part.notes().get(n);
                Integer now = decided.get(n);
                if (now != null) {
                    if (now < 0) {
                        continue;
                    }
                    note = note.withPitch(now);
                }
                if (!drums && !range.contains(note.pitch())) {
                    int pitch = note.pitch();
                    while (pitch < range.low() && pitch + 12 <= 127) {
                        pitch += 12;
                    }
                    while (pitch > range.high() && pitch - 12 >= 0) {
                        pitch -= 12;
                    }
                    if (range.contains(pitch)) {
                        note = note.withPitch(pitch);
                        moved++;
                    } else {
                        unplayable++;
                        continue;
                    }
                }
                notes.add(note);
            }
            if (notes.isEmpty()) {
                continue;
            }
            parts.add(part.withNotes(notes));
            setups.add(new Setup(id, role, instrument, program, grand));
        }
        if (moved > 0) {
            remarks.add(moved + " Noten um Oktaven in den Umfang ihres Instruments verlegt.");
        }
        if (unplayable > 0) {
            remarks.add(unplayable + " Noten, die ihr Instrument in keiner Oktave spielen kann, weggelassen.");
        }
        Key key = score.key();
        if (key == null) {
            try {
                key = text(answer.get("key")).isEmpty() ? null : Key.parse(text(answer.get("key")));
            } catch (RuntimeException unknown) {
                key = null;
            }
            if (key == null) {
                key = TemplateKeys.estimate(score.parts());
            }
            remarks.add("Die Vorlage nennt keine Tonart; angenommen ist " + key.germanName() + ".");
        }
        String title = plain(text(answer.get("title")));
        if (title.isEmpty()) {
            title = plain(score.title());
        }
        TemplateScore reviewed = score.withParts(parts, List.of());
        return new Reviewed(reviewed, setups, title, plain(text(answer.get("mood"))), key, remarks);
    }

    /** The part that sounds highest, the drums left out: the melody where the AI says nothing. */
    private static int highestMelodicPart(TemplateScore score) {
        int best = -1;
        int pitch = -1;
        for (int p = 0; p < score.parts().size(); p++) {
            ScorePart part = score.parts().get(p);
            if (!part.drums() && part.medianPitch() > pitch) {
                pitch = part.medianPitch();
                best = p;
            }
        }
        return best;
    }

    /** A program for a part whose template names none: a piano for two staves, else strings by register. */
    static int guessProgram(ScorePart part) {
        if (part.staves() > 1) {
            return 0;
        }
        int median = part.medianPitch();
        return median >= 67 ? 40 : median >= 55 ? 41 : median >= 43 ? 42 : 43;
    }

    private static boolean keyboardLike(int program) {
        return program <= 7 || (program >= 16 && program <= 23) || program == 46;
    }

    static String uniqueId(String wish, Set<String> taken, int index) {
        String plain = Normalizer.normalize(wish == null ? "" : wish, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace("ß", "ss").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^[^a-z]+", "").replaceAll("-+$", "");
        if (plain.isEmpty()) {
            plain = "part" + (index + 1);
        }
        if (plain.length() > 24) {
            plain = plain.substring(0, 24).replaceAll("-+$", "");
        }
        String id = plain;
        for (int number = 2; !taken.add(id); number++) {
            id = plain + "-" + number;
        }
        return id;
    }

    /** One line of at most 100 characters, without tabs. */
    private static String plain(String text) {
        String line = text == null ? "" : text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return line.length() > 100 ? line.substring(0, 100).trim() : line;
    }

    private static String text(JsonElement element) {
        return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? "" : element.getAsString().trim();
    }

    private static int integer(JsonElement element, int otherwise) {
        try {
            return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? otherwise
                    : (int) Math.round(element.getAsDouble());
        } catch (RuntimeException notANumber) {
            return otherwise;
        }
    }

    private static boolean bool(JsonElement element) {
        try {
            return element != null && element.isJsonPrimitive() && element.getAsBoolean();
        } catch (RuntimeException notABoolean) {
            return false;
        }
    }
}
