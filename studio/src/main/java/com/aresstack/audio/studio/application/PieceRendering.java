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
     * @param session  the model of the piece; its folder is the folder of the piece
     * @param programs the General MIDI program the user chose for a track, by track
     * @param progress hears every step; a {@link TrackProgress} also hears how each track gets on
     */
    public Outcome render(ProjectSession session, Sf2Engine sf2Engine, String fluidSynthExecutable,
                          String sfizzExecutable, Map<String, Integer> programs, Progress progress) throws Exception {
        Path folder = session.folder();
        Set<String> open = new LinkedHashSet<String>();
        for (Part part : session.composition().parts()) {
            open.add(part.id());
            TrackProgress.tell(progress, part.id(), TrackProgress.Stage.RENDER, TrackProgress.State.WAITING, "");
        }
        RenderService.Result stems;
        try {
            stems = new RenderService(RenderService.instrumentation(registry, sf2Engine, fluidSynthExecutable,
                    sfizzExecutable, programs), RenderLimits.STANDARD).render(session, message -> {
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
        session.exportMidi(folder.resolve(MIDI_FILE));
        Path mix = new StageMixService().mix(folder.resolve("arrangement.tsv"), folder, progress);
        return new Outcome(mix, stems);
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
