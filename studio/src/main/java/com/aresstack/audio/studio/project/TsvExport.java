package com.aresstack.audio.studio.project;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.score.Voicing;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a composition as {@code arrangement.tsv}, the format every reader of the studio still
 * understands: a tempo line, then for each part its track line and its notes. A part is a track
 * of the file, under the id of the part.
 *
 * <p>The format can only say what the first stage of the model says: one whole-number tempo,
 * one meter and one key for the whole piece (meter, key and a length beyond the notes only
 * since F2, and only where they differ from what every older piece was). Anything more is
 * refused with {@link NotExpressible}; then {@code arrangement.mid} is the format to exchange
 * the piece in.
 *
 * <p>Since S1 a part on a grand staff gets a {@code staff} line after its track line, and the
 * notes of a part name their voice in a seventh column ({@link Voicing}) where the voices of the
 * model are not those the score would guess anyway. A piece whose voices are the guessed ones,
 * as every imported old piece, is therefore written exactly as before.
 */
public final class TsvExport {
    /** The composition says something the TSV format cannot. */
    public static final class NotExpressible extends IllegalArgumentException {
        NotExpressible(String message) {
            super(message);
        }
    }

    private TsvExport() {
    }

    /** The lines of the file, without line ends. */
    public static List<String> lines(Composition composition) {
        TimeMap time = composition.timeMap();
        if (time.tempos().size() != 1 || time.meters().size() != 1 || time.keys().size() != 1) {
            throw new NotExpressible("arrangement.tsv knows one tempo, one meter and one key only");
        }
        TimeMap.Meter meter = time.meters().get(0);
        if (meter.numerator() > 32 || meter.denominator() > 32) {
            throw new NotExpressible("arrangement.tsv knows meters up to 32/32 only");
        }
        Key key = time.keys().get(0).key();
        double bpm = time.tempos().get(0).bpm();
        if (bpm != Math.rint(bpm)) {
            throw new NotExpressible("arrangement.tsv knows whole-number tempos only: " + bpm);
        }
        Map<String, Integer> channels = channels(composition);
        List<String> lines = new ArrayList<>();
        lines.add("tempo\t" + (long) bpm);
        boolean fourFour = meter.numerator() == 4 && meter.denominator() == 4;
        if (!fourFour) {
            lines.add("meter\t" + meter.numerator() + "\t" + meter.denominator());
        }
        if (!key.equals(Key.C_MAJOR)) {
            lines.add("key\t" + key.tonicName() + "\t" + (key.mode() == Key.Mode.MAJOR ? "major" : "minor"));
        }
        long notesEnd = 0;
        for (Part part : composition.parts()) {
            for (Composition.PlacedNote placed : composition.notesOf(part.id())) {
                notesEnd = Math.max(notesEnd, placed.endTick());
            }
        }
        int bars = time.barsCovering(composition.endTick());
        if (ArrangementFile.needsBarsLine(bars, time.barsCovering(notesEnd), fourFour)) {
            lines.add("bars\t" + bars);
        }
        for (Part part : composition.parts()) {
            lines.add("track\t" + part.id() + "\t" + part.program() + "\t" + channels.get(part.id())
                    + (part.role() == null ? "" : "\t" + part.role()));
            boolean grand = part.staffLayout() == Part.StaffLayout.GRAND;
            if (grand) {
                lines.add("staff\t" + part.id() + "\tgrand");
            }
            List<Composition.PlacedNote> placedNotes = composition.notesOf(part.id());
            String[] voices = voices(part, placedNotes);
            for (int i = 0; i < placedNotes.size(); i++) {
                Composition.PlacedNote placed = placedNotes.get(i);
                lines.add("note\t" + part.id() + "\t" + placed.tick() + "\t" + placed.note().length() + "\t"
                        + placed.note().pitch() + "\t" + placed.note().velocity()
                        + (voices == null ? "" : "\t" + voices[i]));
            }
        }
        return lines;
    }

    /**
     * The voice of each note of a part in the words of {@link Voicing}, or {@code null} where
     * the file need not say them: for drums, and where they are the voices the score guesses.
     */
    static String[] voices(Part part, List<Composition.PlacedNote> placed) {
        boolean grand = part.staffLayout() == Part.StaffLayout.GRAND;
        if (Voicing.struck(part)) {
            return null;
        }
        Map<String, String> tokens = Voicing.tokens(part);
        String[] voices = new String[placed.size()];
        List<long[]> notes = new ArrayList<>();
        for (int i = 0; i < placed.size(); i++) {
            Composition.PlacedNote note = placed.get(i);
            String token = tokens.get(note.note().voiceId());
            voices[i] = token != null ? token : tokens.get(part.firstVoice().id());
            notes.add(new long[]{note.tick(), note.note().length(), note.note().pitch()});
        }
        return Arrays.equals(voices, Voicing.heuristic(notes, false, grand)) ? null : voices;
    }

    /**
     * The lines of one part as {@link #lines} writes them: its track line, its staff line and
     * its notes. They say all that sounds of the part and, since S1, how it is written.
     */
    public static List<String> linesOf(Composition composition, String partId) {
        List<String> all = lines(composition);
        List<String> own = new ArrayList<>();
        for (String line : all) {
            String[] fields = line.split("\t", 3);
            if (fields.length >= 2 && fields[1].equals(partId)) {
                own.add(line);
            }
        }
        return own;
    }

    /**
     * The MIDI channel of each part: the channel an old piece wrote where it is kept, else as the
     * studio has always chosen it, drums on channel 9, the others in turn on the 15 other
     * channels of a port. A part without a role counts as the role its id names, as the old
     * format has it.
     */
    public static Map<String, Integer> channels(Composition composition) {
        Map<String, Integer> channels = new LinkedHashMap<>();
        int melodic = 0;
        for (Part part : composition.parts()) {
            int channel;
            if (drums(part)) {
                channel = 9;
            } else {
                int next = melodic++ % 15;
                channel = next >= 9 ? next + 1 : next;
            }
            channels.put(part.id(), part.legacyChannel() != null ? part.legacyChannel() : channel);
        }
        return channels;
    }

    /** The channel {@link #channels} gives a part when the part keeps none of its own. */
    static Map<String, Integer> derivedChannels(List<Part> parts) {
        List<Part> plain = new ArrayList<>();
        for (Part part : parts) {
            plain.add(new Part(part.id(), part.name(), part.role(), part.instrument(), part.program(),
                    part.libraryId(), part.staffLayout(), part.voices(), null));
        }
        return channels(new Composition(null, TimeMap.of(120), null, plain, null, null));
    }

    /** Whether the old format plays a part as drums: by its role, or by its id where it has none. */
    static boolean drums(Part part) {
        return Role.DRUMS.equals(part.role() != null ? part.role() : part.id());
    }

    /**
     * An old arrangement as this class would write it: without byte order marks, comments and
     * blank lines, the last tempo line first, then each track with its notes in their written
     * order. Two files with the same canonical lines play the same.
     */
    public static List<String> canonical(ArrangementFile arrangement) {
        List<String> lines = new ArrayList<>();
        String tempo = null;
        for (ArrangementFile.Entry entry : arrangement.entries()) {
            if (entry instanceof ArrangementFile.Tempo t) {
                tempo = t.text();
            }
        }
        if (tempo != null) {
            lines.add(tempo);
        }
        // Since F2: meter, key and length, as the last line of each says them.
        arrangement.meter().ifPresent(meter -> lines.add(meter.text()));
        arrangement.key().ifPresent(key -> lines.add(key.text()));
        String bars = null;
        for (ArrangementFile.Entry entry : arrangement.entries()) {
            if (entry instanceof ArrangementFile.Bars line) {
                bars = line.text();
            }
        }
        if (bars != null) {
            lines.add(bars);
        }
        Map<String, List<String>> tracks = new LinkedHashMap<>();
        for (ArrangementFile.Entry entry : arrangement.entries()) {
            if (entry instanceof ArrangementFile.Track track && !tracks.containsKey(track.id())) {
                List<String> own = new ArrayList<>();
                own.add(track.text());
                tracks.put(track.id(), own);
            }
        }
        // Since S1: a grand staff, as the last staff line of a track says it.
        for (ArrangementFile.Track track : arrangement.tracks()) {
            List<String> own = tracks.get(track.id());
            ArrangementFile.Staff staff = null;
            for (ArrangementFile.Entry entry : arrangement.entries()) {
                if (entry instanceof ArrangementFile.Staff line && line.trackId().equals(track.id())) {
                    staff = line;
                }
            }
            if (own != null && own.size() == 1 && staff != null && staff.grand()) {
                own.add(staff.text());
            }
        }
        for (ArrangementFile.Note note : arrangement.notes()) {
            List<String> own = tracks.get(note.trackId());
            if (own != null) {
                own.add(note.text());
            }
        }
        for (List<String> own : tracks.values()) {
            lines.addAll(own);
        }
        return lines;
    }
}
