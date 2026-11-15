package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.studio.ai.IntensityCurve;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sets the intensity curve of the form (report 10.1, work package C4): the intensity of each
 * section, by hand or after a curve over some bars. It is a change of the form only, through the
 * edit core like every change: the scope allows {@link EditOp.Kind#SET_SECTIONS} and nothing else,
 * so no note, part or clip can change and nothing has to be rendered anew.
 *
 * <p>The intensity of a section is what the AI composes after: {@code APPEND} and {@code INSERT}
 * see it in the form of the context, and {@code REORCHESTRATE} takes it as the intensity of each
 * of its frames, which decides instrumentation and density ({@code Dramaturgy}). Setting the curve
 * and then re-orchestrating the bars is how a changed curve reaches the music.
 */
public final class IntensityEdit {
    private IntensityEdit() {
    }

    /** The scope of the change: the form, nothing else. */
    public static EditScope scope() {
        return new EditScope(false, Set.of(), Set.of(), Set.of(), 0, EditScope.END,
                EnumSet.of(EditOp.Kind.SET_SECTIONS), false, 0);
    }

    /**
     * The patch that gives sections a new intensity; {@code null} as value takes it away.
     *
     * @throws IllegalArgumentException if a section is unknown or a value is outside 0 to 1
     */
    public static Patch patch(Composition piece, Map<String, Double> intensities) {
        for (String id : intensities.keySet()) {
            if (piece.sections().stream().noneMatch(s -> s.id().equals(id))) {
                throw new IllegalArgumentException("Unbekannter Abschnitt: " + id);
            }
        }
        List<Section> form = new ArrayList<>();
        for (Section s : piece.sections()) {
            Double level = intensities.containsKey(s.id()) ? intensities.get(s.id()) : s.intensity();
            form.add(new Section(s.id(), s.name(), s.function(), s.startTick(), s.lengthTicks(), level, s.harmony(),
                    s.instruction()));
        }
        return new Patch("Intensität von " + intensities.size() + " Abschnitt(en) gesetzt",
                List.of(new EditOp.SetSections(form)));
    }

    /**
     * The patch that lays a curve over bars {@code firstBar..lastBar} (from 1): every section that
     * begins in those bars gets the value of the curve at its middle, relative to the bars.
     *
     * @throws IllegalArgumentException if the piece has no sections or no section begins there
     */
    public static Patch curve(Composition piece, int firstBar, int lastBar, IntensityCurve curve) {
        if (firstBar < 1 || lastBar < firstBar) {
            throw new IllegalArgumentException("Ungültiger Bereich: " + firstBar + "-" + lastBar);
        }
        TimeMap time = piece.timeMap();
        long from = time.tickOfBar(firstBar - 1);
        long to = time.tickOfBar(lastBar);
        Map<String, Double> levels = new java.util.LinkedHashMap<>();
        for (Section s : piece.sections()) {
            if (s.startTick() >= from && s.startTick() < to) {
                long middle = (s.startTick() + Math.min(s.endTick(), to)) / 2;
                levels.put(s.id(), curve.at((middle - from) / (double) (to - from)));
            }
        }
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("In den Takten " + firstBar + "-" + lastBar + " beginnt kein Abschnitt.");
        }
        return patch(piece, levels);
    }

    /** Applies the intensities at once, as an undoable step by the user. */
    public static ChangeSet apply(ProjectSession session, Map<String, Double> intensities)
            throws EditRefusedException, IOException {
        return session.apply(patch(session.composition(), intensities), scope());
    }

    /** Applies a curve over bars at once, as an undoable step by the user. */
    public static ChangeSet apply(ProjectSession session, int firstBar, int lastBar, IntensityCurve curve)
            throws EditRefusedException, IOException {
        return session.apply(curve(session.composition(), firstBar, lastBar, curve), scope());
    }
}
