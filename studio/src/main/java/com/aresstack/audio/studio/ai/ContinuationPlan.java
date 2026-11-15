package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Chord;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.Section;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The shared plan of a continuation (report 5.2, architecture review 2.4 "EditPlan"): which
 * sections follow the original, how long and how intense each is, its chords bar by bar, what
 * happens in it, which parts play there, and which new instruments come in. Every generator call
 * follows the same plan, so that parts written in parallel do not contradict one another.
 *
 * <p>A plan always covers exactly the new bars: {@link #complete} repairs a planning answer that
 * does not, and records what it repaired in {@link #repairs()}.
 *
 * @param motifs  how the continuation relates to the motifs of the piece, in words; may be empty
 * @param arc     the overall dramaturgy in words; may be empty
 * @param repairs what was repaired in the planning answer, one line each, in German
 */
public record ContinuationPlan(List<PlannedSection> sections, List<NewPart> newParts, String motifs, String arc,
                               List<String> repairs) {
    /** The longest section one generator call writes (architecture review E4: 8 to 16 bars). */
    public static final int MAX_SECTION_BARS = 16;
    private static final String[] NAMES = {"C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"};

    /**
     * A new section.
     *
     * @param key       the name of the section within the plan, for example "s1"; the model's
     *                  section id is given when the plan becomes part of the piece
     * @param intensity 0 to 1
     * @param chords    one chord per bar, or empty if the plan says none
     * @param parts     the keys of the parts that play: ids of existing parts, keys of new ones
     */
    public record PlannedSection(String key, String name, String function, int bars, double intensity,
                                 List<String> chords, String idea, List<String> parts) {
        public PlannedSection {
            if (key == null || key.isBlank() || bars < 1 || !(intensity >= 0 && intensity <= 1)) {
                throw new IllegalArgumentException("Invalid planned section " + key);
            }
            name = name == null || name.isBlank() ? key : name.strip();
            function = function == null || function.isBlank() ? null : function.strip();
            chords = List.copyOf(chords == null ? List.of() : chords);
            if (!chords.isEmpty() && chords.size() != bars) {
                throw new IllegalArgumentException("A planned section has one chord per bar: " + key);
            }
            idea = idea == null ? "" : idea.strip();
            parts = List.copyOf(parts == null ? List.of() : parts);
        }

        public boolean plays(String partKey) {
            return parts.contains(partKey);
        }
    }

    /**
     * A new instrument the plan brings in.
     *
     * @param key  the name of the part within the plan, "new1", "new2", ...
     * @param role one of {@link Role#ALL}
     */
    public record NewPart(String key, String name, String instrument, int program, String role, String idea) {
        public NewPart {
            if (key == null || name == null || name.isBlank() || program < 0 || program > 127
                    || !Role.ALL.contains(role)) {
                throw new IllegalArgumentException("Invalid new part " + key);
            }
            name = name.strip();
            instrument = instrument == null || instrument.isBlank() ? name : instrument.strip();
            idea = idea == null ? "" : idea.strip();
        }
    }

    public ContinuationPlan {
        sections = List.copyOf(sections);
        newParts = List.copyOf(newParts);
        motifs = motifs == null ? "" : motifs.strip();
        arc = arc == null ? "" : arc.strip();
        repairs = List.copyOf(repairs == null ? List.of() : repairs);
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("A plan has at least one section.");
        }
    }

    /** How many bars the plan covers. */
    public int bars() {
        return sections.stream().mapToInt(PlannedSection::bars).sum();
    }

    public NewPart newPart(String key) {
        for (NewPart part : newParts) {
            if (part.key().equals(key)) {
                return part;
            }
        }
        return null;
    }

    /** The bar of the continuation a section begins with, counted from 0 at the first new bar. */
    public int firstBarOf(PlannedSection section) {
        int bar = 0;
        for (PlannedSection s : sections) {
            if (s == section) {
                return bar;
            }
            bar += s.bars();
        }
        throw new IllegalArgumentException("Not a section of this plan: " + section.key());
    }

    /**
     * The plan without a planning answer: sections of eight bars with names, functions and an
     * intensity curve after the preset (or the curve of the request), the chords of the original
     * again bar by bar, in each section the parts its intensity calls for ({@link Dramaturgy#ensemble}),
     * no new instrument. Deterministic: the same task gives the same plan.
     */
    public static ContinuationPlan fallback(ContinuationTask task, Composition piece) {
        return fallback(task, piece, List.of("Kein Plan der KI; Plan nach der Vorgabe " + task.request().preset().label()
                + " erstellt."));
    }

    static ContinuationPlan fallback(ContinuationTask task, Composition piece, List<String> repairs) {
        ContinuationRequest request = task.request();
        int bars = request.bars();
        List<Integer> sizes = new ArrayList<>();
        int left = bars;
        while (left > 0) {
            int size = left <= 8 ? left : left < 12 ? left : 8;
            sizes.add(size);
            left -= size;
        }
        List<String> harmony = originalHarmony(piece);
        List<Double> curve = request.curve() == null ? null : request.curve().forSections(sizes);
        Key key = task.time().keyAt(task.newStart());
        List<PlannedSection> sections = new ArrayList<>();
        int count = sizes.size();
        int bar = 0;
        for (int i = 0; i < count; i++) {
            String[] named = name(request, i, count);
            List<String> chords = new ArrayList<>();
            if (!harmony.isEmpty()) {
                for (int b = 0; b < sizes.get(i); b++) {
                    chords.add(harmony.get((bar + b) % harmony.size()));
                }
            }
            double level = curve == null ? intensity(request, i, count) : curve.get(i);
            List<String> parts = Dramaturgy.ensemble(level, task.parts(), Part::role).stream().map(Part::id).toList();
            sections.add(new PlannedSection("s" + (i + 1), named[0], named[1], sizes.get(i), level, chords, named[2],
                    parts));
            bar += sizes.get(i);
        }
        sections = closing(sections, request.ending(), key, new ArrayList<>());
        return new ContinuationPlan(sections, List.of(), "", request.preset().brief(), repairs);
    }

    /**
     * Makes a planning answer a plan that covers exactly the new bars and names only parts that
     * exist: sections beyond the new bars are dropped, the last one shortened or lengthened,
     * sections longer than {@value #MAX_SECTION_BARS} bars split, chord lists brought to one chord
     * per bar, unknown parts left out, new parts beyond the allowed number or playing nowhere
     * dropped. Every repair is recorded.
     */
    static ContinuationPlan complete(List<PlannedSection> given, List<NewPart> newParts, String motifs, String arc,
                                     ContinuationTask task, Composition piece, List<String> repairs) {
        int bars = task.request().bars();
        List<String> existing = task.parts().stream().map(Part::id).toList();
        List<NewPart> allowed = new ArrayList<>();
        for (NewPart part : newParts) {
            if (allowed.size() >= task.request().newParts()) {
                repairs.add("Neues Instrument " + part.name() + " verworfen: höchstens " + task.request().newParts()
                        + " erlaubt.");
            } else {
                allowed.add(part);
            }
        }
        Set<String> known = new LinkedHashSet<>(existing);
        allowed.forEach(part -> known.add(part.key()));

        List<PlannedSection> fitted = new ArrayList<>();
        int covered = 0;
        for (PlannedSection section : given) {
            if (covered >= bars) {
                repairs.add("Abschnitt " + section.name() + " liegt hinter den neuen Takten und entfällt.");
                continue;
            }
            int length = Math.min(section.bars(), bars - covered);
            if (length != section.bars()) {
                repairs.add("Abschnitt " + section.name() + " auf " + length + " Takte gekürzt.");
            }
            fitted.add(resized(section, length));
            covered += length;
        }
        if (fitted.isEmpty()) {
            return fallback(task, piece, append(repairs, "Der Plan der KI enthielt keinen brauchbaren Abschnitt; "
                    + "Plan nach der Vorgabe " + task.request().preset().label() + " erstellt."));
        }
        if (covered < bars) {
            PlannedSection last = fitted.remove(fitted.size() - 1);
            int length = last.bars() + bars - covered;
            repairs.add("Abschnitt " + last.name() + " auf " + length + " Takte verlängert, damit der Plan alle "
                    + bars + " neuen Takte abdeckt.");
            fitted.add(resized(last, length));
        }

        List<PlannedSection> sections = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Set<String> playing = new HashSet<>();
        for (PlannedSection section : fitted) {
            List<String> parts = new ArrayList<>();
            for (String part : section.parts()) {
                if (known.contains(part) && !parts.contains(part)) {
                    parts.add(part);
                } else if (!known.contains(part)) {
                    repairs.add("Abschnitt " + section.name() + ": unbekannte Stimme " + part + " entfällt.");
                }
            }
            if (parts.isEmpty()) {
                parts.addAll(existing);
                repairs.add("Abschnitt " + section.name() + " nennt keine Stimme; alle bisherigen Stimmen spielen.");
            }
            playing.addAll(parts);
            for (PlannedSection chunk : split(section)) {
                String key = chunk.key();
                int n = 2;
                while (!keys.add(key)) {
                    key = chunk.key() + "-" + n++;
                }
                sections.add(new PlannedSection(key, chunk.name(), chunk.function(), chunk.bars(), chunk.intensity(),
                        chunk.chords(), chunk.idea(), parts));
            }
            if (section.bars() > MAX_SECTION_BARS) {
                repairs.add("Abschnitt " + section.name() + " in Stücke zu höchstens " + MAX_SECTION_BARS
                        + " Takten geteilt.");
            }
        }
        List<NewPart> used = new ArrayList<>();
        for (NewPart part : allowed) {
            if (playing.contains(part.key())) {
                used.add(part);
            } else {
                repairs.add("Neues Instrument " + part.name() + " spielt in keinem Abschnitt und entfällt.");
            }
        }
        sections = following(sections, task.request().curve(), repairs);
        sections = closing(sections, task.request().ending(), task.time().keyAt(task.newStart()), repairs);
        return new ContinuationPlan(sections, used, motifs, arc, repairs);
    }

    /** One chord per bar: a shorter list is continued with its last chord, a longer one cut, unknown chords dropped. */
    static List<String> chords(List<String> given, int bars, String where, List<String> repairs) {
        List<String> valid = new ArrayList<>();
        for (String chord : given) {
            if (chord != null && Chord.tryParse(chord) != null) {
                valid.add(chord.strip());
            }
        }
        if (valid.size() != given.size()) {
            repairs.add(where + ": " + (given.size() - valid.size()) + " unlesbare Akkorde entfernt.");
        }
        if (valid.isEmpty()) {
            return List.of();
        }
        if (valid.size() != bars) {
            repairs.add(where + ": " + valid.size() + " Akkorde für " + bars + " Takte angeglichen.");
        }
        List<String> fitted = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            fitted.add(valid.get(Math.min(i, valid.size() - 1)));
        }
        return fitted;
    }

    /** The sections of the plan as sections of the piece, from {@code fromTick} on, with the given ids. */
    public List<Section> asSections(ContinuationTask task, List<String> ids) {
        List<Section> made = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            PlannedSection planned = sections.get(i);
            int first = task.firstNewBar() + firstBarOf(planned);
            long start = task.time().tickOfBar(first);
            long end = task.time().tickOfBar(first + planned.bars());
            made.add(new Section(ids.get(i), planned.name(), planned.function(), start, end - start,
                    planned.intensity(), planned.chords(), planned.idea().isEmpty() ? null : planned.idea()));
        }
        return made;
    }

    private static PlannedSection resized(PlannedSection section, int bars) {
        List<String> chords = section.chords().isEmpty() ? List.of() : new ArrayList<>();
        for (int i = 0; i < bars && !section.chords().isEmpty(); i++) {
            chords.add(section.chords().get(Math.min(i, section.chords().size() - 1)));
        }
        return new PlannedSection(section.key(), section.name(), section.function(), bars, section.intensity(), chords,
                section.idea(), section.parts());
    }

    private static List<PlannedSection> split(PlannedSection section) {
        if (section.bars() <= MAX_SECTION_BARS) {
            return List.of(section);
        }
        List<PlannedSection> pieces = new ArrayList<>();
        int pieceCount = (section.bars() + MAX_SECTION_BARS - 1) / MAX_SECTION_BARS;
        int from = 0;
        for (int i = 0; i < pieceCount; i++) {
            int length = (section.bars() - from) / (pieceCount - i);
            List<String> chords = section.chords().isEmpty() ? List.of()
                    : section.chords().subList(from, from + length);
            pieces.add(new PlannedSection(i == 0 ? section.key() : section.key() + "-" + (i + 1),
                    i == 0 ? section.name() : section.name() + " " + (i + 1), section.function(), length,
                    section.intensity(), chords, section.idea(), section.parts()));
            from += length;
        }
        return pieces;
    }

    /**
     * With an intensity curve of the user, every section takes the intensity of the curve at its
     * middle; a planned intensity that differs is replaced and recorded.
     */
    private static List<PlannedSection> following(List<PlannedSection> sections, IntensityCurve curve,
                                                  List<String> repairs) {
        if (curve == null) {
            return sections;
        }
        List<Double> levels = curve.forSections(sections.stream().map(PlannedSection::bars).toList());
        List<PlannedSection> changed = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            PlannedSection s = sections.get(i);
            double level = levels.get(i);
            if (Double.compare(level, s.intensity()) != 0) {
                repairs.add("Abschnitt " + s.name() + ": Intensität " + String.format(Locale.ROOT, "%.2f", s.intensity())
                        + " nach der vorgegebenen Kurve auf " + String.format(Locale.ROOT, "%.2f", level) + " gesetzt.");
            }
            changed.add(new PlannedSection(s.key(), s.name(), s.function(), s.bars(), level, s.chords(), s.idea(),
                    s.parts()));
        }
        return changed;
    }

    /** A closed ending ends on the tonic: a last chord that is not is set to it, and recorded. */
    private static List<PlannedSection> closing(List<PlannedSection> sections, ContinuationEnding ending, Key key,
                                                List<String> repairs) {
        PlannedSection last = sections.get(sections.size() - 1);
        if (ending != ContinuationEnding.CLOSED || last.chords().isEmpty()) {
            return sections;
        }
        String tonic = NAMES[key.tonic()] + (key.mode() == Key.Mode.MINOR ? "m" : "");
        String lastChord = last.chords().get(last.chords().size() - 1);
        Chord chord = Chord.tryParse(lastChord);
        if (chord != null && chord.root() == key.tonic()) {
            return sections;
        }
        List<String> chords = new ArrayList<>(last.chords());
        chords.set(chords.size() - 1, tonic);
        repairs.add("Schlussakkord " + lastChord + " durch die Tonika " + tonic + " ersetzt (Abschluss).");
        List<PlannedSection> changed = new ArrayList<>(sections);
        changed.set(changed.size() - 1, new PlannedSection(last.key(), last.name(), last.function(), last.bars(),
                last.intensity(), chords, last.idea(), last.parts()));
        return changed;
    }

    /** The chords of the original, bar by bar as its sections give them. */
    private static List<String> originalHarmony(Composition piece) {
        List<String> chords = new ArrayList<>();
        for (Section section : piece.sections()) {
            for (String chord : section.harmony()) {
                if (Chord.tryParse(chord) != null) {
                    chords.add(chord);
                }
            }
        }
        return chords;
    }

    /** Name, function and idea of section {@code i} of {@code count} of a plan without planning answer. */
    private static String[] name(ContinuationRequest request, int i, int count) {
        boolean last = i == count - 1;
        boolean resolves = last && closes(request, count);
        return switch (request.preset()) {
            case UNDERSCORE -> resolves ? new String[]{"Ausklang", "outro", "the texture thins out and comes to rest"}
                    : new String[]{"Fläche " + (i + 1), "underscore", "restrained texture, slow changes of colour"};
            case STANDALONE -> resolves ? new String[]{"Reprise", "reprise", "the main theme returns varied and closes"}
                    : i == 0 ? new String[]{"B-Teil", "contrast", "a contrasting section with a new idea"}
                    : new String[]{"Durchführung " + i, "development", "develop the themes heard so far"};
            case DRAMATIC_BUILD -> resolves ? new String[]{"Auflösung", "outro", "the climax resolves"}
                    : (last || (i == count - 2 && closes(request, count)))
                    ? new String[]{"Höhepunkt", "climax", "full instrumentation, the highest tension"}
                    : new String[]{"Aufbau " + (i + 1), "build", "tension and instrumentation grow"};
            case VARIATION_REPRISE -> resolves || (last && count == 1)
                    ? new String[]{"Reprise", "reprise", "the main motif returns recognisably"}
                    : new String[]{"Variation " + (i + 1), "variation", "vary the main motif"};
            case FREE -> new String[]{"Fortsetzung " + (i + 1), null, request.instruction()};
        };
    }

    /** The intensity of section {@code i} of {@code count} after the preset. */
    private static double intensity(ContinuationRequest request, int i, int count) {
        ContinuationPreset preset = request.preset();
        double start = preset.startIntensity();
        double peak = preset.peakIntensity();
        boolean resolves = i == count - 1 && closes(request, count);
        double value;
        if (resolves) {
            value = Math.min(start, 0.5);
        } else {
            int climb = count - (closes(request, count) ? 2 : 1);
            value = climb <= 0 ? peak : start + (peak - start) * Math.min(i, climb) / climb;
        }
        return Math.round(value * 100) / 100.0;
    }

    /**
     * Whether a plan without planning answer ends with a section of its own that resolves: with a
     * closed ending and more than one section; a dramatic build keeps its climax for the last
     * section unless there are at least three.
     */
    private static boolean closes(ContinuationRequest request, int count) {
        return request.ending() == ContinuationEnding.CLOSED
                && count > (request.preset() == ContinuationPreset.DRAMATIC_BUILD ? 2 : 1);
    }

    private static List<String> append(List<String> list, String line) {
        list.add(line);
        return list;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (PlannedSection s : sections) {
            text.append(s.key()).append(' ').append(s.name()).append(' ').append(s.bars()).append(" bars ")
                    .append(String.format(Locale.ROOT, "%.2f", s.intensity())).append(' ').append(s.parts()).append('\n');
        }
        return text.toString();
    }
}
