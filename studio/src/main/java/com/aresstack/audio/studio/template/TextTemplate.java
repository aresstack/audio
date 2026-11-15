package com.aresstack.audio.studio.template;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.ai.Backoff;
import com.aresstack.audio.studio.ai.ChatModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * A template written as text in a notation the studio does not read itself: ABC above all, also
 * LilyPond, Humdrum, MuseData, a hand-written list of notes, or a file with mistakes in it. The
 * AI writes it over into notes, portion by portion, and is told to keep to what is written: not
 * to compose, only to read, and to set right what is plainly a slip of the pen. Each portion is
 * a number of lines of the file (setting "Zeilen der Vorlage je Anfrage"); the beginning of the
 * file goes along with every portion, so that headers such as meter, key and unit note length
 * are known in each.
 */
public final class TextTemplate {
    /** How much of the beginning of the file goes along with every later portion, in characters. */
    public static final int HEAD_CHARACTERS = 2000;
    /** The fewest and the most lines of one portion. */
    public static final int MIN_LINES = 4;
    public static final int MAX_LINES = 400;
    /** The lines of one portion unless the setting says otherwise. */
    public static final int DEFAULT_LINES = 40;
    /** The folder in the piece where the answers of the AI on the template are kept. */
    public static final String CACHE_FOLDER = "template-ai";

    static final String INSTRUCTION = "You transcribe sheet music that is given as text (ABC notation, LilyPond, "
            + "Humdrum, MuseData, a list of notes or anything similar) into notes for a music program. You do NOT "
            + "compose: you read what is written and write exactly those notes. Only where the text is plainly "
            + "wrong (a bar that does not add up, a typo in a note name, a missing octave mark that makes a leap "
            + "absurd) you set it right in the most likely way. Answer with one JSON object only.";

    private TextTemplate() {
    }

    /**
     * @param heard told the portion being written ("Teil 2 von 7") and the characters of its
     *              answer so far; may be {@code null}
     */
    public static TemplateScore read(String text, String title, ChatModel model, int maxTokens, int linesPerRequest,
                                     Path folder, BiConsumer<String, Long> heard) throws IOException {
        List<String> lines = new ArrayList<String>();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw new IOException("Die Vorlage ist leer.");
        }
        int per = Math.max(MIN_LINES, Math.min(MAX_LINES, linesPerRequest));
        int portions = (lines.size() + per - 1) / per;
        String head = String.join("\n", lines.subList(0, Math.min(per, lines.size())));
        if (head.length() > HEAD_CHARACTERS) {
            head = head.substring(0, HEAD_CHARACTERS);
        }
        List<String[]> parts = new ArrayList<String[]>(); // name, program, drums
        List<List<ScoreNote>> notes = new ArrayList<List<ScoreNote>>();
        String named = "";
        int bpm = 0;
        int numerator = 0;
        int denominator = 0;
        Key key = null;
        double at = 0; // quarters from the beginning of the piece where the portion begins
        String before = "";
        List<String> remarks = new ArrayList<String>();
        remarks.add("Die Vorlage ist Text; die KI hat sie in " + portions + (portions == 1 ? " Teil" : " Teilen")
                + " in Noten übertragen.");
        for (int p = 0; p < portions; p++) {
            String portion = String.join("\n", lines.subList(p * per, Math.min(lines.size(), (p + 1) * per)));
            String label = "Teil " + (p + 1) + " von " + portions;
            String prompt = prompt(title, head, before, portion, p, portions, parts, at, numerator, denominator, key);
            Path cache = folder.resolve(CACHE_FOLDER).resolve("transcription-" + (p + 1) + "-" + hash(prompt) + ".json");
            JsonObject answer = null;
            String problem = null;
            for (int asked = 1; answer == null; asked++) {
                Files.createDirectories(cache.getParent());
                JsonObject given = model.generate(INSTRUCTION, prompt + again(problem), maxTokens, cache,
                        heard == null ? null : soFar -> heard.accept(label, (long) soFar.length()));
                problem = problem(given);
                if (problem == null) {
                    answer = given;
                } else {
                    Files.deleteIfExists(cache);
                    if (Backoff.givesUp(asked)) {
                        throw new IOException("Die KI hat " + label + " der Vorlage nicht lesbar übertragen: " + problem);
                    }
                    Backoff.tell("Unbrauchbare KI-Antwort für " + label + " der Vorlage: " + problem + " · Versuch "
                            + (asked + 1) + " in " + Backoff.words(Backoff.delay(asked)) + ".");
                    Backoff.pause(asked, null);
                }
            }
            if (named.isEmpty() && answer.has("title") && answer.get("title").isJsonPrimitive()) {
                named = answer.get("title").getAsString().trim();
            }
            if (bpm == 0) {
                bpm = Math.max(0, integer(answer.get("tempoBpm")));
            }
            if (numerator == 0 && answer.has("meter") && answer.get("meter").isJsonPrimitive()) {
                String[] meter = answer.get("meter").getAsString().trim().split("/");
                if (meter.length == 2) {
                    numerator = Math.max(0, MusicXmlTemplate.number(meter[0], 0));
                    denominator = Math.max(0, MusicXmlTemplate.number(meter[1], 0));
                    if (numerator <= 0 || denominator <= 0 || Integer.bitCount(denominator) != 1) {
                        numerator = 0;
                        denominator = 0;
                    }
                }
            }
            if (key == null && answer.has("key") && answer.get("key").isJsonPrimitive()) {
                try {
                    key = Key.parse(answer.get("key").getAsString());
                } catch (RuntimeException unknown) {
                    key = null;
                }
            }
            JsonArray named0 = answer.has("parts") && answer.get("parts").isJsonArray()
                    ? answer.getAsJsonArray("parts") : new JsonArray();
            for (int i = parts.size(); i < named0.size(); i++) {
                JsonElement part = named0.get(i);
                String name = "Stimme " + (i + 1);
                int program = -1;
                boolean drums = false;
                if (part.isJsonObject()) {
                    JsonObject object = part.getAsJsonObject();
                    if (object.has("name") && object.get("name").isJsonPrimitive()
                            && !object.get("name").getAsString().isBlank()) {
                        name = object.get("name").getAsString().trim();
                    }
                    program = object.has("program") ? integer(object.get("program")) : -1;
                    drums = object.has("drums") && object.get("drums").isJsonPrimitive()
                            && object.get("drums").getAsBoolean();
                }
                parts.add(new String[]{name, Integer.toString(program), Boolean.toString(drums)});
                notes.add(new ArrayList<ScoreNote>());
            }
            double furthest = 0;
            int dropped = 0;
            for (JsonElement element : answer.getAsJsonArray("notes")) {
                double[] note = note(element);
                if (note == null) {
                    dropped++;
                    continue;
                }
                int part = (int) note[0];
                while (part >= parts.size()) {
                    parts.add(new String[]{"Stimme " + (parts.size() + 1), "-1", "false"});
                    notes.add(new ArrayList<ScoreNote>());
                }
                long tick = Math.round((at + note[1]) * TemplateScore.PPQ);
                if (tick < 0) {
                    dropped++;
                    continue;
                }
                long length = Math.round(note[2] * TemplateScore.PPQ);
                notes.get(part).add(new ScoreNote(tick, length, (int) note[3], (int) note[4]));
                furthest = Math.max(furthest, note[1] + note[2]);
            }
            if (dropped > 0) {
                remarks.add(label + ": " + dropped + " Einträge der KI waren keine Noten und sind weggelassen.");
            }
            double covered = answer.has("portionQuarters") ? number(answer.get("portionQuarters")) : 0;
            if (!(covered > 0)) {
                double bar = (numerator > 0 ? numerator * 4.0 / denominator : 4.0);
                covered = Math.ceil(furthest / bar) * bar;
            }
            at += covered;
            before = portion;
        }
        List<ScorePart> read = new ArrayList<ScorePart>();
        for (int i = 0; i < parts.size(); i++) {
            List<ScoreNote> list = notes.get(i);
            if (list.isEmpty()) {
                continue;
            }
            list.sort(Comparator.comparingLong(ScoreNote::tick).thenComparingInt(ScoreNote::pitch));
            boolean drums = Boolean.parseBoolean(parts.get(i)[2]);
            read.add(new ScorePart(parts.get(i)[0], drums ? 0 : Integer.parseInt(parts.get(i)[1]), drums, 1, list));
        }
        return new TemplateScore(named.isEmpty() ? title : named, bpm, numerator, denominator, key, read, remarks,
                "Text (von der KI übertragen)");
    }

    static String prompt(String title, String head, String before, String portion, int index, int portions,
                         List<String[]> parts, double at, int numerator, int denominator, Key key) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Transcribe portion ").append(index + 1).append(" of ").append(portions)
                .append(" of the sheet music file \"").append(title).append("\".\n");
        if (index > 0) {
            prompt.append("\nThe beginning of the file, for its headers (meter, key, unit note length, voices):\n```\n")
                    .append(head).append("\n```\n");
            prompt.append("\nThe portion before this one, for context only (already transcribed):\n```\n")
                    .append(before).append("\n```\n");
            prompt.append("\nKnown so far: meter ")
                    .append(numerator > 0 ? numerator + "/" + denominator : "unknown").append(", key ")
                    .append(key == null ? "unknown" : key.name()).append(".\n");
        }
        if (!parts.isEmpty()) {
            prompt.append("Parts so far, by index (keep these indexes; append new parts at the end):\n");
            for (int i = 0; i < parts.size(); i++) {
                prompt.append("  ").append(i).append(": ").append(parts.get(i)[0]).append('\n');
            }
        }
        prompt.append("\nTRANSCRIBE THIS PORTION:\n```\n").append(portion).append("\n```\n");
        prompt.append("\nAnswer with this JSON object:\n")
                .append("{\"title\": \"the title the file names, or empty\",\n")
                .append(" \"tempoBpm\": quarter notes per minute the file names, or 0,\n")
                .append(" \"meter\": \"e.g. 3/4\", \"key\": \"e.g. E major or D minor\",\n")
                .append(" \"parts\": [{\"name\": \"Violin I\", \"program\": General MIDI program 0-127 or -1, ")
                .append("\"drums\": false}, ... every part from index 0, including those listed above],\n")
                .append(" \"notes\": [[part, start, length, pitch, velocity], ...],\n")
                .append(" \"portionQuarters\": how many quarter notes this portion lasts}\n")
                .append("start and length are in quarter notes (decimals allowed, a triplet eighth is 0.3333), ")
                .append("start counted from the beginning of THIS portion, which begins ").append(fmt(at))
                .append(" quarter notes into the piece; a voice written in a block of its own that begins earlier ")
                .append("(together with a voice of an earlier portion) gets a negative start. ")
                .append("portionQuarters is how far the NEXT portion begins after this one's beginning. pitch is the MIDI number (middle C = 60). ")
                .append("velocity 1-127 from the dynamics, 80 if none are written. A tie is one longer note. ")
                .append("Rests are no notes but take their time. Leave nothing out and add nothing.");
        return prompt.toString();
    }

    private static String fmt(double quarters) {
        return quarters == Math.rint(quarters) ? Long.toString((long) quarters)
                : String.format(Locale.ROOT, "%.4f", quarters);
    }

    private static String again(String problem) {
        return problem == null ? "" : "\nYour previous answer could not be used: " + problem
                + " Answer again with the JSON object described above.";
    }

    /** Why an answer cannot be used, or {@code null} if it can. */
    static String problem(JsonObject answer) {
        if (answer == null || !answer.has("notes") || !answer.get("notes").isJsonArray()) {
            return "it has no list \"notes\".";
        }
        return null;
    }

    /** part, start, length, pitch, velocity of one entry; {@code null} if it is no note. */
    static double[] note(JsonElement element) {
        double[] values = new double[5];
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() < 4) {
                return null;
            }
            for (int i = 0; i < 5; i++) {
                values[i] = i < array.size() ? number(array.get(i)) : Double.NaN;
            }
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String[] names = {"part", "start", "length", "pitch", "velocity"};
            for (int i = 0; i < 5; i++) {
                values[i] = object.has(names[i]) ? number(object.get(names[i])) : Double.NaN;
            }
        } else {
            return null;
        }
        if (Double.isNaN(values[4])) {
            values[4] = 80;
        }
        if (Double.isNaN(values[0])) {
            values[0] = 0;
        }
        for (double value : values) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return null;
            }
        }
        // A start may be negative: a voice written in a block of its own begins before the portion does.
        if (values[0] < 0 || values[3] < 0 || values[3] > 127) {
            return null;
        }
        values[0] = Math.floor(values[0]);
        values[3] = Math.round(values[3]);
        values[4] = Math.max(1, Math.min(127, Math.round(values[4])));
        return values;
    }

    private static double number(JsonElement element) {
        try {
            return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? Double.NaN
                    : element.getAsDouble();
        } catch (RuntimeException notANumber) {
            return Double.NaN;
        }
    }

    private static int integer(JsonElement element) {
        double value = number(element);
        return Double.isNaN(value) ? -1 : (int) Math.round(value);
    }

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
