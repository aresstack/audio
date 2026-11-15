package com.aresstack.audio.studio.template;

import com.aresstack.audio.composition.Key;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads MusicXML (partwise, plain {@code .musicxml}/{@code .xml} or compressed {@code .mxl}) as a
 * template: pitches, durations, chords, ties, voices across {@code backup} and {@code forward},
 * the staves of a piano, dynamics as loudness and the usual articulations. A short first bar (an
 * upbeat) is moved to the end of a full first bar, so that the bar lines of the piece stay where
 * the template has them. Grace notes are left out. No file is fetched over the network: the
 * document type that MusicXML files name is not loaded.
 */
public final class MusicXmlTemplate {
    private static final String[] STEPS = {"C", "D", "E", "F", "G", "A", "B"};
    private static final int[] STEP_PITCH = {0, 2, 4, 5, 7, 9, 11};
    /** How loud each dynamic mark plays, as MIDI velocity (the studio's own reading of the marks). */
    private static final Map<String, Integer> DYNAMICS = Map.ofEntries(Map.entry("pppp", 20), Map.entry("ppp", 30),
            Map.entry("pp", 42), Map.entry("p", 55), Map.entry("mp", 68), Map.entry("mf", 80), Map.entry("f", 96),
            Map.entry("ff", 110), Map.entry("fff", 120), Map.entry("ffff", 127), Map.entry("sf", 105),
            Map.entry("sfz", 110), Map.entry("fz", 105));
    private static final int UNMARKED_VELOCITY = 80;
    private static final int DRUM_MIDI_CHANNEL = 10;

    private MusicXmlTemplate() {
    }

    /** Whether these bytes are a compressed MusicXML file (a zip archive). */
    public static boolean looksCompressed(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    /** Whether this text is a MusicXML document. */
    public static boolean looksLikeMusicXml(String text) {
        String head = text.length() > 4096 ? text.substring(0, 4096) : text;
        return head.contains("<score-partwise") || head.contains("<score-timewise");
    }

    public static TemplateScore read(byte[] bytes, String title) throws IOException {
        byte[] xml = looksCompressed(bytes) ? unzip(bytes) : bytes;
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(
                    new java.io.StringReader("")));
            document = builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception unreadable) {
            throw new IOException("Die MusicXML-Datei lässt sich nicht lesen: " + unreadable.getMessage(), unreadable);
        }
        Element root = document.getDocumentElement();
        if ("score-timewise".equals(root.getTagName())) {
            throw new IOException("MusicXML „timewise“ wird nicht gelesen; bitte als „partwise“ speichern"
                    + " (das ist die übliche Form, z. B. aus MuseScore).");
        }
        if (!"score-partwise".equals(root.getTagName())) {
            throw new IOException("Keine MusicXML-Partitur (score-partwise fehlt).");
        }
        return new Reading(root, title).read();
    }

    private static byte[] unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), readAll(zip));
                }
            }
        }
        byte[] container = entries.get("META-INF/container.xml");
        if (container != null) {
            java.util.regex.Matcher path = java.util.regex.Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"")
                    .matcher(new String(container, StandardCharsets.UTF_8));
            if (path.find() && entries.containsKey(path.group(1))) {
                return entries.get(path.group(1));
            }
        }
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (!name.startsWith("meta-inf/") && (name.endsWith(".xml") || name.endsWith(".musicxml"))) {
                return entry.getValue();
            }
        }
        throw new IOException("Im MXL-Archiv steckt keine MusicXML-Partitur.");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    /** One pass over a document. */
    private static final class Reading {
        private final Element root;
        private final String title;
        private final List<String> remarks = new ArrayList<String>();
        private int bpm;
        private int numerator;
        private int denominator;
        private Key key;
        private int tempi;
        private int meters;
        private int graces;

        Reading(Element root, String title) {
            this.root = root;
            this.title = title;
        }

        TemplateScore read() {
            String named = text(first(first(root, "work"), "work-title"));
            if (named.isEmpty()) {
                named = text(first(root, "movement-title"));
            }
            Map<String, String> names = new LinkedHashMap<String, String>();
            Map<String, Integer> programs = new HashMap<String, Integer>();
            Map<String, Boolean> drums = new HashMap<String, Boolean>();
            Element partList = first(root, "part-list");
            for (Element scorePart : children(partList, "score-part")) {
                String id = scorePart.getAttribute("id");
                names.put(id, text(first(scorePart, "part-name")));
                Element instrument = first(scorePart, "midi-instrument");
                if (instrument != null) {
                    String program = text(first(instrument, "midi-program"));
                    if (!program.isEmpty()) {
                        programs.put(id, number(program, 1) - 1);
                    }
                    drums.put(id, number(text(first(instrument, "midi-channel")), 0) == DRUM_MIDI_CHANNEL);
                }
            }
            List<ScorePart> parts = new ArrayList<ScorePart>();
            long shift = -1;
            List<List<ScoreNote>> notesOfParts = new ArrayList<List<ScoreNote>>();
            List<Element> partElements = children(root, "part");
            List<int[]> staves = new ArrayList<int[]>();
            for (Element part : partElements) {
                int[] staffCount = {1};
                long[] upbeat = {-1};
                notesOfParts.add(notes(part, staffCount, upbeat));
                staves.add(staffCount);
                // The first part decides whether the piece begins with an upbeat; all parts move alike.
                if (notesOfParts.size() == 1) {
                    shift = upbeat[0];
                }
            }
            if (shift > 0) {
                remarks.add("Der Auftakt der Vorlage beginnt im ersten Takt so, dass er auf die Eins des zweiten führt.");
            }
            for (int i = 0; i < partElements.size(); i++) {
                String id = partElements.get(i).getAttribute("id");
                List<ScoreNote> notes = notesOfParts.get(i);
                if (notes.isEmpty()) {
                    continue;
                }
                if (shift > 0) {
                    List<ScoreNote> moved = new ArrayList<ScoreNote>();
                    for (ScoreNote note : notes) {
                        moved.add(note.shifted(shift));
                    }
                    notes = moved;
                }
                notes.sort(Comparator.comparingLong(ScoreNote::tick).thenComparingInt(ScoreNote::pitch));
                String name = names.getOrDefault(id, "");
                parts.add(new ScorePart(name.isEmpty() ? "Stimme " + (i + 1) : name, programs.getOrDefault(id, -1),
                        drums.getOrDefault(id, false), staves.get(i)[0], notes));
            }
            if (tempi > 1) {
                remarks.add("Die Vorlage wechselt das Tempo " + (tempi - 1) + "-mal; übernommen ist das erste ("
                        + bpm + " BPM).");
            }
            if (meters > 1) {
                remarks.add("Die Vorlage nennt " + meters + " Taktarten; übernommen ist die erste.");
            }
            if (graces > 0) {
                remarks.add(graces + " Vorschlagsnoten sind weggelassen.");
            }
            return new TemplateScore(named.isEmpty() ? title : named, bpm, numerator, denominator, key, parts,
                    remarks, "MusicXML");
        }

        /**
         * The notes of one part.
         *
         * @param staves told the most staves the part names
         * @param upbeat told how far a short first bar must be moved to end where a full bar ends,
         *               if it is short
         */
        private List<ScoreNote> notes(Element part, int[] staves, long[] upbeat) {
            List<ScoreNote> notes = new ArrayList<ScoreNote>();
            // Tied notes waiting for their continuation: pitch and voice to the index in notes.
            Map<String, Integer> tied = new HashMap<String, Integer>();
            int divisions = 1;
            int velocity = UNMARKED_VELOCITY;
            long measureStart = 0;
            // A part that names no meter of its own is in the meter an earlier part named.
            int partNumerator = numerator > 0 ? numerator : 4;
            int partDenominator = denominator > 0 ? denominator : 4;
            boolean firstMeasure = true;
            for (Element measure : children(part, "measure")) {
                long cursor = measureStart;
                long furthest = measureStart;
                long lastStart = measureStart;
                for (Element element : children(measure, null)) {
                    switch (element.getTagName()) {
                        case "attributes" -> {
                            String d = text(first(element, "divisions"));
                            if (!d.isEmpty()) {
                                divisions = Math.max(1, number(d, 1));
                            }
                            Element k = first(element, "key");
                            if (k != null && key == null && first(k, "fifths") != null) {
                                key = TemplateKeys.ofFifths(number(text(first(k, "fifths")), 0),
                                        "minor".equals(text(first(k, "mode"))));
                            }
                            Element time = first(element, "time");
                            if (time != null && first(time, "beats") != null) {
                                int beats = number(text(first(time, "beats")).split("\\+")[0], 4);
                                int type = number(text(first(time, "beat-type")), 4);
                                partNumerator = Math.max(1, beats);
                                partDenominator = Math.max(1, type);
                                if (numerator == 0) {
                                    numerator = partNumerator;
                                    denominator = partDenominator;
                                    meters = 1;
                                } else if (partNumerator != numerator || partDenominator != denominator) {
                                    meters++;
                                }
                            }
                            String count = text(first(element, "staves"));
                            if (!count.isEmpty()) {
                                staves[0] = Math.max(staves[0], number(count, 1));
                            }
                        }
                        case "direction" -> {
                            for (Element dynamics : descendants(element, "dynamics")) {
                                for (Element mark : children(dynamics, null)) {
                                    Integer loud = DYNAMICS.get(mark.getTagName());
                                    if (loud != null) {
                                        velocity = loud;
                                    }
                                }
                            }
                            tempo(first(element, "sound"));
                            if (bpm == 0) {
                                for (Element metronome : descendants(element, "metronome")) {
                                    int perMinute = number(text(first(metronome, "per-minute")), 0);
                                    if (perMinute > 0) {
                                        bpm = quarters(perMinute, text(first(metronome, "beat-unit")),
                                                first(metronome, "beat-unit-dot") != null);
                                        tempi = 1;
                                    }
                                }
                            }
                        }
                        case "sound" -> tempo(element);
                        case "backup" -> cursor -= ticks(element, divisions);
                        case "forward" -> {
                            cursor += ticks(element, divisions);
                            furthest = Math.max(furthest, cursor);
                        }
                        case "note" -> {
                            if (first(element, "grace") != null) {
                                graces++;
                                continue;
                            }
                            long length = ticks(element, divisions);
                            boolean chord = first(element, "chord") != null;
                            long start = chord ? lastStart : cursor;
                            if (!chord) {
                                lastStart = cursor;
                                cursor += length;
                                furthest = Math.max(furthest, cursor);
                            }
                            if (first(element, "rest") != null) {
                                continue;
                            }
                            int pitch = pitch(element);
                            if (pitch < 0) {
                                continue;
                            }
                            String voice = text(first(element, "voice"));
                            int staff = number(text(first(element, "staff")), 1);
                            String written = staves[0] >= 2 ? (staff >= 2 ? "lh" : "rh") : "";
                            boolean stops = false;
                            boolean starts = false;
                            for (Element tie : children(element, "tie")) {
                                stops |= "stop".equals(tie.getAttribute("type"));
                                starts |= "start".equals(tie.getAttribute("type"));
                            }
                            String tieKey = pitch + "/" + voice + "/" + staff;
                            Integer open = tied.get(tieKey);
                            if (stops && open != null) {
                                ScoreNote before = notes.get(open);
                                notes.set(open, before.withLength(start + length - before.tick()));
                                if (!starts) {
                                    tied.remove(tieKey);
                                }
                                continue;
                            }
                            notes.add(new ScoreNote(start, length, pitch, velocity, written, articulation(element)));
                            if (starts) {
                                tied.put(tieKey, notes.size() - 1);
                            }
                        }
                        default -> {
                        }
                    }
                }
                long full = (long) partNumerator * TemplateScore.PPQ * 4 / partDenominator;
                long length = furthest - measureStart;
                if (firstMeasure && length > 0 && length < full) {
                    upbeat[0] = full - length;
                }
                if (length <= 0) {
                    length = full; // an empty measure still takes its time
                }
                measureStart += length;
                firstMeasure = false;
            }
            return notes;
        }

        private void tempo(Element sound) {
            if (sound == null || sound.getAttribute("tempo").isEmpty()) {
                return;
            }
            try {
                int value = (int) Math.round(Double.parseDouble(sound.getAttribute("tempo").trim()));
                if (value > 0) {
                    if (bpm == 0) {
                        bpm = value;
                    } else if (value != bpm) {
                        tempi++;
                    }
                    tempi = Math.max(tempi, 1);
                }
            } catch (NumberFormatException odd) {
                // no tempo then
            }
        }

        /** A metronome mark as quarter notes per minute. */
        private static int quarters(int perMinute, String unit, boolean dotted) {
            double quartersPerUnit = switch (unit) {
                case "whole" -> 4;
                case "half" -> 2;
                case "eighth" -> 0.5;
                case "16th" -> 0.25;
                default -> 1;
            };
            return (int) Math.round(perMinute * quartersPerUnit * (dotted ? 1.5 : 1));
        }

        private static long ticks(Element element, int divisions) {
            String duration = text(first(element, "duration"));
            if (duration.isEmpty()) {
                return 0;
            }
            try {
                return Math.round(Double.parseDouble(duration) * TemplateScore.PPQ / divisions);
            } catch (NumberFormatException odd) {
                return 0;
            }
        }

        private static int pitch(Element note) {
            Element pitch = first(note, "pitch");
            if (pitch == null) {
                pitch = first(note, "unpitched");
                if (pitch == null) {
                    return -1;
                }
                return midi(text(first(pitch, "display-step")), 0, text(first(pitch, "display-octave")));
            }
            double alter = 0;
            try {
                String a = text(first(pitch, "alter"));
                alter = a.isEmpty() ? 0 : Double.parseDouble(a);
            } catch (NumberFormatException odd) {
                alter = 0;
            }
            return midi(text(first(pitch, "step")), (int) Math.round(alter), text(first(pitch, "octave")));
        }

        private static int midi(String step, int alter, String octave) {
            for (int i = 0; i < STEPS.length; i++) {
                if (STEPS[i].equalsIgnoreCase(step)) {
                    int value = (number(octave, 4) + 1) * 12 + STEP_PITCH[i] + alter;
                    return value < 0 || value > 127 ? -1 : value;
                }
            }
            return -1;
        }

        private static String articulation(Element note) {
            for (Element notations : children(note, "notations")) {
                for (Element articulations : children(notations, "articulations")) {
                    for (Element mark : children(articulations, null)) {
                        switch (mark.getTagName()) {
                            case "staccatissimo", "spiccato":
                                return "staccatissimo";
                            case "staccato":
                                return "staccato";
                            case "tenuto":
                                return "tenuto";
                            case "strong-accent":
                                return "marcato";
                            case "accent":
                                return "accent";
                            default:
                                break;
                        }
                    }
                }
            }
            return "";
        }
    }

    // ---- small DOM helpers ----

    static Element first(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals(tag)) {
                return element;
            }
        }
        return null;
    }

    /** The child elements with this tag, or all of them for {@code null}. */
    static List<Element> children(Element parent, String tag) {
        List<Element> found = new ArrayList<Element>();
        if (parent == null) {
            return found;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && (tag == null || element.getTagName().equals(tag))) {
                found.add(element);
            }
        }
        return found;
    }

    static List<Element> descendants(Element parent, String tag) {
        List<Element> found = new ArrayList<Element>();
        NodeList all = parent.getElementsByTagName(tag);
        for (int i = 0; i < all.getLength(); i++) {
            found.add((Element) all.item(i));
        }
        return found;
    }

    static String text(Element element) {
        return element == null ? "" : element.getTextContent().trim();
    }

    static int number(String text, int otherwise) {
        try {
            return text == null || text.isBlank() ? otherwise : (int) Math.round(Double.parseDouble(text.trim()));
        } catch (NumberFormatException odd) {
            return otherwise;
        }
    }
}
