package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.studio.ai.RegionComposer;
import com.aresstack.audio.studio.ai.RegionRequest;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A passage the AI wrote anew or varied ({@link RegionRework}) as the window offers it. */
public final class RegionOffer implements AiOffer {
    private final ProjectSession session;
    private final RegionRework rework;
    private final List<String> partIds;
    private final int firstBar;
    private final int lastBar;
    private Path preview;

    private RegionOffer(ProjectSession session, RegionRework rework, List<String> partIds, int firstBar, int lastBar) {
        this.session = session;
        this.rework = rework;
        this.partIds = List.copyOf(partIds);
        this.firstBar = firstBar;
        this.lastBar = lastBar;
    }

    /**
     * Has the AI write the passage and proposes it; the piece stays as it is.
     *
     * @param partIds  the parts of the selection, for what the offer says of itself
     * @param firstBar the first bar of the selection, counted from 1
     * @param lastBar  the last bar of the selection, counted from 1
     */
    public static RegionOffer start(ProjectSession session, RegionComposer composer, RegionRequest request,
                                    List<String> partIds, int firstBar, int lastBar, Progress progress)
            throws IOException, EditRefusedException {
        return new RegionOffer(session, RegionRework.start(session, composer, request, progress), partIds, firstBar,
                lastBar);
    }

    public RegionRework rework() {
        return rework;
    }

    private String where() {
        Composition piece = session.composition();
        List<String> names = new ArrayList<String>();
        for (String id : partIds) {
            Part part = piece.part(id);
            names.add(part == null || part.name() == null || part.name().isBlank() ? id : part.name());
        }
        return (firstBar == lastBar ? "Takt " + firstBar : "Takt " + firstBar + " bis " + lastBar) + " in "
                + String.join(", ", names);
    }

    @Override
    public String what() {
        int notes = rework.region().noteCount();
        return "Die KI hat " + where() + (rework.request().mode() == RegionRequest.Mode.VARIATE ? " variiert"
                : " neu geschrieben") + " (" + notes + (notes == 1 ? " Note" : " Noten")
                + (rework.attempt() > 1 ? ", " + rework.attempt() + ". Versuch" : "") + ").";
    }

    @Override
    public List<String> notes() {
        List<String> notes = new ArrayList<String>();
        int corrected = rework.corrections().size();
        if (corrected > 0) {
            notes.add(corrected + (corrected == 1 ? " Note wurde" : " Noten wurden")
                    + " beim Prüfen korrigiert, gekürzt oder weggelassen.");
        }
        notes.add("Außerhalb der markierten Passage bleibt alles unverändert.");
        return notes;
    }

    @Override
    public Composition proposed() {
        return rework.proposal().result();
    }

    @Override
    public Path preview() throws IOException {
        preview = rework.preview();
        PreviewStems.carry(session.folder(), preview);
        return preview;
    }

    @Override
    public String soloPartId() {
        return partIds.size() == 1 ? partIds.get(0) : null;
    }

    @Override
    public String soloName() {
        return "Nur diese Stimme anhören";
    }

    @Override
    public Map<String, Integer> newPrograms() {
        return Map.of();
    }

    @Override
    public void again(Progress progress) throws Exception {
        preview = null;
        rework.regenerate(progress);
    }

    @Override
    public ChangeSet accept() throws IOException {
        if (preview != null) {
            PreviewStems.carry(preview, session.folder()); // what was heard need not be rendered again
        }
        return rework.accept();
    }

    @Override
    public void discard() throws IOException {
        if (rework.proposal() != null) {
            rework.discard();
        }
    }
}
