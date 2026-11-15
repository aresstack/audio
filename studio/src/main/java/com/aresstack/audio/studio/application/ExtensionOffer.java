package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.studio.ai.ComposedContinuation;
import com.aresstack.audio.studio.ai.ContinuationComposer;
import com.aresstack.audio.studio.ai.ContinuationPlan;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A longer piece, or new bars within it ({@link PieceExtension}), as the window offers it: what
 * the AI planned stands in the notes, and listening starts shortly before the first new bar.
 */
public final class ExtensionOffer implements AiOffer {
    /** How many bars of the original are heard before the new ones, to judge the transition. */
    public static final int BARS_BEFORE = 2;

    private final ProjectSession session;
    private final PieceExtension extension;
    private Path preview;
    /** Whether the user agreed to the notes of the original that this attempt shortens. */
    private boolean confirmed;

    private ExtensionOffer(ProjectSession session, PieceExtension extension) {
        this.session = session;
        this.extension = extension;
    }

    /** Has the AI plan and write the new bars and proposes them; the piece stays as it is. */
    public static ExtensionOffer start(ProjectSession session, ContinuationComposer composer,
                                       ContinuationRequest request, Progress progress)
            throws IOException, EditRefusedException {
        return new ExtensionOffer(session, PieceExtension.start(session, composer, request, progress));
    }

    public PieceExtension extension() {
        return extension;
    }

    @Override
    public String what() {
        ComposedContinuation written = extension.continuation();
        ContinuationRequest request = extension.request();
        int bars = written.plan().bars();
        int sections = written.sections().size();
        int notes = written.noteCount();
        int added = written.newParts().size();
        String did = request.inserts()
                ? "Die KI hat vor Takt " + request.insertBeforeBar() + " " + bars + (bars == 1 ? " Takt" : " Takte")
                + " eingefügt"
                : "Die KI hat das Stück um " + bars + (bars == 1 ? " Takt" : " Takte") + " verlängert";
        return did + " (" + sections + (sections == 1 ? " Abschnitt, " : " Abschnitte, ") + notes
                + (notes == 1 ? " Note" : " Noten")
                + (added > 0 ? ", " + added + (added == 1 ? " neues Instrument" : " neue Instrumente") : "")
                + (extension.attempt() > 1 ? ", " + extension.attempt() + ". Versuch" : "") + ")"
                + (written.shortened().isEmpty() ? "." : "; " + written.shortened().size()
                + (written.shortened().size() == 1 ? " Note des Originals wird gekürzt."
                : " Noten des Originals werden gekürzt."));
    }

    /** The plan of the AI section by section, its new instruments, and what had to be repaired. */
    @Override
    public List<String> notes() {
        ComposedContinuation written = extension.continuation();
        ContinuationPlan plan = written.plan();
        List<String> notes = new ArrayList<String>();
        if (!plan.arc().isBlank()) {
            notes.add("Verlauf: " + plan.arc());
        }
        if (!plan.motifs().isBlank()) {
            notes.add("Motive: " + plan.motifs());
        }
        for (ContinuationPlan.PlannedSection section : plan.sections()) {
            notes.add(sectionLine(plan, section));
        }
        for (ContinuationPlan.NewPart part : plan.newParts()) {
            boolean plays = false;
            for (Part added : written.newParts()) {
                plays |= added.name() != null && added.name().equals(part.name());
            }
            if (plays) {
                notes.add("Neues Instrument: " + part.name()
                        + (part.instrument() == null || part.instrument().isBlank()
                        || part.instrument().equals(part.name()) ? "" : " (" + part.instrument() + ")")
                        + (part.idea() == null || part.idea().isBlank() ? "" : " · " + part.idea()));
            }
        }
        for (String repair : plan.repairs()) {
            notes.add("Am Plan korrigiert: " + repair);
        }
        int corrected = written.corrections().size();
        if (corrected > 0) {
            notes.add(corrected + (corrected == 1 ? " Note wurde" : " Noten wurden")
                    + " beim Prüfen korrigiert, gekürzt oder weggelassen.");
        }
        ContinuationRequest request = extension.request();
        if (written.tail().isEmpty()) {
            notes.add(request.inserts() ? "Die Takte davor und danach bleiben Note für Note, wie sie sind;"
                    + " alles ab der Einfügestelle rückt nach hinten."
                    : "Das bisherige Stück bleibt Note für Note unverändert.");
        } else {
            notes.add((request.tailBars() == 1 ? "Der letzte Takt" : "Die letzten " + request.tailBars() + " Takte")
                    + (request.inserts() ? " vor der Einfügestelle" : " des bisherigen Stücks")
                    + (request.tailBars() == 1 ? " wurde" : " wurden")
                    + " für den Übergang überarbeitet; alles davor bleibt unverändert.");
        }
        return notes;
    }

    private static String sectionLine(ContinuationPlan plan, ContinuationPlan.PlannedSection section) {
        int first = plan.firstBarOf(section) + 1;
        StringBuilder line = new StringBuilder("Abschnitt „" + section.name() + "“");
        if (section.function() != null && !section.function().equalsIgnoreCase(section.name())) {
            line.append(" (").append(section.function()).append(")");
        }
        line.append(" · ").append(section.bars() == 1 ? "neuer Takt " + first
                : "neue Takte " + first + " bis " + (first + section.bars() - 1));
        line.append(" · Intensität ").append(Math.round(section.intensity() * 100)).append(" %");
        if (!section.chords().isEmpty()) {
            line.append(" · ").append(String.join(" | ", section.chords()));
        }
        if (!section.idea().isBlank()) {
            line.append(" · ").append(section.idea());
        }
        return line.toString();
    }

    @Override
    public Composition proposed() {
        return extension.proposal().result();
    }

    @Override
    public Path preview() throws IOException {
        preview = extension.preview();
        PreviewStems.carry(session.folder(), preview);
        return preview;
    }

    /** New bars are heard in the whole piece: the parts that go on are no voice of their own. */
    @Override
    public String soloPartId() {
        return null;
    }

    @Override
    public Map<String, Integer> newPrograms() {
        Map<String, Integer> programs = new LinkedHashMap<String, Integer>();
        for (Part part : extension.continuation().newParts()) {
            programs.put(part.id(), part.program());
        }
        return programs;
    }

    /** {@value #BARS_BEFORE} bars before the first new bar, so that the transition is heard. */
    @Override
    public double listenFromSecond() {
        TimeMap time = proposed().timeMap();
        int bar = Math.max(0, time.barOf(extension.continuation().fromTick()) - BARS_BEFORE);
        return time.seconds(time.tickOfBar(bar));
    }

    /**
     * The notes of the original that an insertion cannot keep in full (they sound across the
     * insertion point and beyond the end of the new bars): each with its voice, where it begins,
     * its length in the original and the length it keeps.
     */
    @Override
    public List<String> toConfirm() {
        ComposedContinuation written = extension.continuation();
        Composition piece = session.composition();
        TimeMap time = piece.timeMap();
        List<String> lines = new ArrayList<String>();
        for (ComposedContinuation.Hold hold : written.shortened()) {
            Part part = piece.part(hold.partId());
            String voice = part == null || part.name() == null || part.name().isBlank() ? hold.partId() : part.name();
            int bar = time.barOf(hold.tick());
            lines.add(voice + ": " + pitchOf(piece, hold) + "ab Takt " + (bar + 1) + ", "
                    + beat(hold.tick() - time.tickOfBar(bar)) + " · im Original " + quarters(hold.length())
                    + " lang, bleibt " + quarters(written.keptLength(hold)));
        }
        return lines;
    }

    private static String pitchOf(Composition piece, ComposedContinuation.Hold hold) {
        for (Composition.PlacedNote placed : piece.notesOf(hold.partId())) {
            if (placed.note().id().equals(hold.noteId())) {
                int pitch = placed.note().pitch();
                String[] names = {"C", "Cis", "D", "Es", "E", "F", "Fis", "G", "As", "A", "B", "H"};
                return "Note " + names[pitch % 12] + (pitch / 12 - 1) + " ";
            }
        }
        return "Note ";
    }

    private static String beat(long ticksIntoBar) {
        return "Schlag " + number(1 + ticksIntoBar / (double) TimeMap.PPQ);
    }

    /** A length in quarter notes, the unit a reader counts in: "6 Viertel", "1,5 Viertel". */
    private static String quarters(long ticks) {
        return number(ticks / (double) TimeMap.PPQ) + " Viertel";
    }

    private static String number(double value) {
        double rounded = Math.round(value * 100) / 100.0;
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded)
                : Double.toString(rounded).replace('.', ',');
    }

    @Override
    public void confirm() {
        confirmed = true;
    }

    @Override
    public void again(Progress progress) throws Exception {
        preview = null;
        confirmed = false; // another attempt may shorten other notes
        extension.regenerate(progress);
    }

    @Override
    public ChangeSet accept() throws IOException {
        if (!confirmed && !extension.continuation().shortened().isEmpty()) {
            throw new IllegalStateException("Der Vorschlag kürzt Noten des Originals; er wird erst übernommen,"
                    + " wenn die gekürzten Noten gesehen und bestätigt sind.");
        }
        if (preview != null) {
            PreviewStems.carry(preview, session.folder()); // what was heard need not be rendered again
        }
        return extension.accept();
    }

    @Override
    public void discard() throws IOException {
        if (extension.proposal() != null) {
            extension.discard();
        }
    }
}
