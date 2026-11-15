package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.studio.ai.ContinuationPlan;
import com.aresstack.audio.studio.ai.OrchestrationComposer;
import com.aresstack.audio.studio.ai.OrchestrationPlan;
import com.aresstack.audio.studio.ai.ReorchestratedRegion;
import com.aresstack.audio.studio.ai.ReorchestrationRequest;
import com.aresstack.audio.studio.ai.VoiceFrame;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A passage the AI orchestrated anew ({@link Reorchestration}, work package C4) as the window
 * offers it: the notes say who takes over what in which bars.
 */
public final class ReorchestrationOffer implements AiOffer {
    private final ProjectSession session;
    private final Reorchestration reorchestration;
    private Path preview;

    private ReorchestrationOffer(ProjectSession session, Reorchestration reorchestration) {
        this.session = session;
        this.reorchestration = reorchestration;
    }

    /** Has the AI plan and write the passage anew and proposes it; the piece stays as it is. */
    public static ReorchestrationOffer start(ProjectSession session, OrchestrationComposer composer,
                                             ReorchestrationRequest request, Progress progress)
            throws IOException, EditRefusedException {
        return new ReorchestrationOffer(session, Reorchestration.start(session, composer, request, progress));
    }

    public Reorchestration reorchestration() {
        return reorchestration;
    }

    @Override
    public String what() {
        ReorchestrationRequest request = reorchestration.request();
        ReorchestratedRegion region = reorchestration.region();
        int notes = region.noteCount();
        int added = region.newParts().size();
        return "Die KI hat " + (request.firstBar() == request.lastBar() ? "Takt " + request.firstBar()
                : "Takt " + request.firstBar() + " bis " + request.lastBar()) + " umorchestriert ("
                + request.preset().label() + ", " + notes + (notes == 1 ? " Note" : " Noten")
                + (added > 0 ? ", " + added + (added == 1 ? " neues Instrument" : " neue Instrumente") : "")
                + (reorchestration.attempt() > 1 ? ", " + reorchestration.attempt() + ". Versuch" : "") + ").";
    }

    /** Who plays what, frame by frame, the new instruments, and what had to be repaired. */
    @Override
    public List<String> notes() {
        ReorchestratedRegion region = reorchestration.region();
        OrchestrationPlan plan = region.plan();
        Composition piece = session.composition();
        List<String> notes = new ArrayList<String>();
        if (plan.idea() != null && !plan.idea().isBlank()) {
            notes.add("Idee: " + plan.idea());
        }
        for (VoiceFrame frame : region.task().frames()) {
            OrchestrationPlan.FramePlan planned = plan.frame(frame.sectionId());
            if (planned == null) {
                continue;
            }
            List<String> who = new ArrayList<String>();
            for (OrchestrationPlan.Assignment assignment : planned.assignments()) {
                who.add(name(piece, plan, assignment.partKey()) + ": " + assignment.job()
                        + (assignment.source() == null || assignment.source().equals(assignment.partKey()) ? ""
                        : " (von " + name(piece, plan, assignment.source()) + ")"));
            }
            int first = frame.firstBar() + 1; // frames count their bars from 0
            notes.add((frame.bars() == 1 ? "Takt " + first : "Takt " + first + " bis " + (first + frame.bars() - 1))
                    + (frame.intensity() == null ? "" : " · Intensität " + Math.round(frame.intensity() * 100) + " %")
                    + " · " + (who.isEmpty() ? "alle pausieren" : String.join("; ", who)));
        }
        for (Part part : region.newParts()) {
            notes.add("Neues Instrument: " + part.name());
        }
        for (String repair : plan.repairs()) {
            notes.add("Am Plan korrigiert: " + repair);
        }
        int corrected = region.corrections().size();
        if (corrected > 0) {
            notes.add(corrected + (corrected == 1 ? " Note wurde" : " Noten wurden")
                    + " beim Prüfen korrigiert, ausgedünnt oder weggelassen.");
        }
        notes.add("Außerhalb der Passage und in den nicht gewählten Stimmen bleibt alles unverändert.");
        return notes;
    }

    private static String name(Composition piece, OrchestrationPlan plan, String key) {
        Part part = piece.part(key);
        if (part != null) {
            return part.name() == null || part.name().isBlank() ? key : part.name();
        }
        ContinuationPlan.NewPart added = plan.newPart(key);
        return added == null ? key : added.name();
    }

    @Override
    public Composition proposed() {
        return reorchestration.proposal().result();
    }

    @Override
    public Path preview() throws IOException {
        preview = reorchestration.preview();
        PreviewStems.carry(session.folder(), preview);
        return preview;
    }

    @Override
    public String soloPartId() {
        return null;
    }

    @Override
    public Map<String, Integer> newPrograms() {
        Map<String, Integer> programs = new LinkedHashMap<String, Integer>();
        for (Part part : reorchestration.region().newParts()) {
            programs.put(part.id(), part.program());
        }
        return programs;
    }

    /** Two bars before the passage, so that the way into it is heard. */
    @Override
    public double listenFromSecond() {
        Composition piece = proposed();
        int bar = Math.max(0, reorchestration.request().firstBar() - 1 - ExtensionOffer.BARS_BEFORE);
        return piece.timeMap().seconds(piece.timeMap().tickOfBar(bar));
    }

    @Override
    public void again(Progress progress) throws Exception {
        preview = null;
        reorchestration.regenerate(progress);
    }

    @Override
    public ChangeSet accept() throws IOException {
        if (preview != null) {
            PreviewStems.carry(preview, session.folder()); // what was heard need not be rendered again
        }
        return reorchestration.accept();
    }

    @Override
    public void discard() throws IOException {
        if (reorchestration.proposal() != null) {
            reorchestration.discard();
        }
    }
}
