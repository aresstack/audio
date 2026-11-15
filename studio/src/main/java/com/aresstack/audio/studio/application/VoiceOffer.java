package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.studio.ai.AddVoiceRequest;
import com.aresstack.audio.studio.ai.ComposedVoice;
import com.aresstack.audio.studio.ai.VoiceComposer;
import com.aresstack.audio.studio.ai.VoicePlan;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A new voice composed by the AI ({@link VoiceAddition}) as the window offers it. */
public final class VoiceOffer implements AiOffer {
    private final ProjectSession session;
    private final VoiceAddition addition;
    private Path preview;

    private VoiceOffer(ProjectSession session, VoiceAddition addition) {
        this.session = session;
        this.addition = addition;
    }

    /** Has the AI compose the voice and proposes it; the piece stays as it is. */
    public static VoiceOffer start(ProjectSession session, VoiceComposer composer, AddVoiceRequest request,
                                   Progress progress) throws IOException, EditRefusedException {
        return new VoiceOffer(session, VoiceAddition.start(session, composer, request, progress));
    }

    public VoiceAddition addition() {
        return addition;
    }

    @Override
    public String what() {
        ComposedVoice voice = addition.voice();
        return "Die KI hat die Stimme „" + voice.part().name() + "“ hinzukomponiert (" + voice.noteCount()
                + (voice.noteCount() == 1 ? " Note" : " Noten")
                + (addition.attempt() > 1 ? ", " + addition.attempt() + ". Versuch" : "") + ").";
    }

    @Override
    public List<String> notes() {
        ComposedVoice voice = addition.voice();
        List<String> notes = new ArrayList<String>();
        VoicePlan plan = voice.plan();
        if (plan != null) {
            if (plan.motifs() != null && !plan.motifs().isBlank()) {
                notes.add("Plan: " + plan.motifs());
            }
            int resting = 0;
            for (VoicePlan.SectionPlan section : plan.sections()) {
                resting += section.plays() ? 0 : 1;
            }
            if (resting > 0) {
                notes.add("Die Stimme pausiert in " + resting + (resting == 1 ? " Abschnitt." : " Abschnitten."));
            }
        }
        if (!voice.corrections().isEmpty()) {
            notes.add(voice.corrections().size() + (voice.corrections().size() == 1 ? " Note wurde" : " Noten wurden")
                    + " beim Prüfen korrigiert oder weggelassen.");
        }
        notes.add("Alle anderen Stimmen bleiben unverändert.");
        return notes;
    }

    @Override
    public Composition proposed() {
        return addition.proposal().result();
    }

    @Override
    public Path preview() throws IOException {
        preview = addition.preview();
        PreviewStems.carry(session.folder(), preview);
        return preview;
    }

    @Override
    public String soloPartId() {
        return addition.voice().part().id();
    }

    @Override
    public Map<String, Integer> newPrograms() {
        return Map.of(addition.voice().part().id(), addition.voice().part().program());
    }

    @Override
    public void again(Progress progress) throws Exception {
        preview = null;
        addition.regenerate(progress);
    }

    @Override
    public ChangeSet accept() throws IOException {
        if (preview != null) {
            PreviewStems.carry(preview, session.folder()); // what was heard need not be rendered again
        }
        return addition.accept();
    }

    @Override
    public void discard() throws IOException {
        if (addition.proposal() != null) {
            addition.discard();
        }
    }
}
