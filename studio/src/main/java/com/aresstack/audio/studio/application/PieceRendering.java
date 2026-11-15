package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Part;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.progress.TrackProgress;
import com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine;
import com.aresstack.audio.studio.render.RenderLimits;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Makes a piece audible from its model, as the studio's render key asks for it: the stems of the
 * parts that changed are rendered (the others come from the stem cache), the piece is written as
 * a MIDI file beside them, and the stage is mixed from the stems. No part is synthesized a second
 * time for a mix without the stage; what a part sounds like alone is its stem.
 */
public final class PieceRendering {
    /** The file of the piece as MIDI, where the studio has always left it. */
    public static final String MIDI_FILE = "arrangement.mid";

    /** @param mix the mix on the stage; @param stems which parts were rendered and which were kept */
    public record Outcome(Path mix, RenderService.Result stems) {
    }

    private final InstrumentRegistry registry;

    public PieceRendering(InstrumentRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param session   the model of the piece; its folder is the folder of the piece. It is only
     *                  read: rendering never changes the saved piece.
     * @param libraries the sound bank the user chose for a track in the studio's table, by track
     *                  ("builtin" is the Java sound); a track that is not named plays the bank its
     *                  part has in the model. The table's choice is not part of the model until
     *                  the piece is composed again, but it is what the user asks to hear.
     * @param programs  the General MIDI program the user chose for a track, by track
     * @param progress hears every step; a {@link TrackProgress} also hears how each track gets on
     */
    public Outcome render(ProjectSession session, Map<String, String> libraries, Sf2Engine sf2Engine,
                          String fluidSynthExecutable, String sfizzExecutable, Map<String, Integer> programs,
                          Progress progress) throws Exception {
        return render(session.folder(), session.composition(), libraries, sf2Engine, fluidSynthExecutable,
                sfizzExecutable, programs, progress);
    }

    /**
     * The same for a piece that is not the saved one: the preview of a proposal, in its own
     * folder, with its own stage and its own stem cache.
     */
    public Outcome render(Path folder, com.aresstack.audio.composition.Composition composition,
                          Map<String, String> libraries, Sf2Engine sf2Engine, String fluidSynthExecutable,
                          String sfizzExecutable, Map<String, Integer> programs, Progress progress)
            throws Exception {
        Set<String> open = new LinkedHashSet<String>();
        for (Part part : composition.parts()) {
            open.add(part.id());
            TrackProgress.tell(progress, part.id(), TrackProgress.Stage.RENDER, TrackProgress.State.WAITING, "");
        }
        RenderService.Result stems;
        try {
            RenderService.Instrumentation usual = RenderService.instrumentation(registry, sf2Engine,
                    fluidSynthExecutable, sfizzExecutable, programs);
            RenderService.Instrumentation chosen = part -> usual.of(withLibrary(part,
                    libraries == null ? null : libraries.get(part.id())));
            stems = new RenderService(chosen, RenderLimits.STANDARD).render(folder, composition, message -> {
                String part = partBeingRendered(message, open);
                if (part != null) {
                    TrackProgress.tell(progress, part, TrackProgress.Stage.RENDER, TrackProgress.State.WORKING, "");
                }
                progress.update(message);
            });
        } catch (Exception failed) {
            for (String part : open) {
                TrackProgress.tell(progress, part, TrackProgress.Stage.RENDER, TrackProgress.State.FAILED, "");
            }
            throw failed;
        }
        for (String part : open) {
            TrackProgress.tell(progress, part, TrackProgress.Stage.RENDER, TrackProgress.State.DONE,
                    stems.reused().contains(part) ? "unverändert" : "");
        }
        if (!stems.reused().isEmpty()) {
            progress.update(stems.rendered().size() + " Spuren neu gerendert, " + stems.reused().size()
                    + " unverändert übernommen.");
        }
        com.aresstack.audio.studio.project.MidiExport.write(composition, folder.resolve(MIDI_FILE));
        // The tracks and the time of the mix are those of the model: arrangement.tsv is not read.
        Path mix = new StageMixService().mix(StageMixService.Piece.of(composition), folder, progress);
        return new Outcome(mix, stems);
    }

    /** The part as it sounds with the sound bank chosen for it; the part itself if none is chosen. */
    private static Part withLibrary(Part part, String libraryId) {
        if (libraryId == null || libraryId.equals(part.libraryId())) {
            return part;
        }
        return new Part(part.id(), part.name(), part.role(), part.instrument(), part.program(), libraryId,
                part.staffLayout(), part.voices(), part.legacyChannel());
    }

    /**
     * The part a message of the render service is about, if it says that a part is being rendered.
     * The service speaks in sentences only; this reads the one sentence it has for a part.
     */
    private static String partBeingRendered(String message, Set<String> parts) {
        if (message == null || !message.startsWith("Rendere ")) {
            return null;
        }
        for (String part : parts) {
            if (message.startsWith("Rendere " + part + " mit ")) {
                return part;
            }
        }
        return null;
    }
}
