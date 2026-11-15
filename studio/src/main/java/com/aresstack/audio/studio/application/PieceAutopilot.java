package com.aresstack.audio.studio.application;

import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.ai.ModelLineup;
import com.aresstack.audio.studio.ai.ModelRoles;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A piece from its music wish to its rendered sound without a question in between: the AI
 * proposes the players and their stage, composes for them, and the tracks are rendered. This is
 * what "the AI decides everything" does in the window, as a service without a window, so that
 * the production of an album runs the very same steps.
 *
 * <p>The models are those of one {@link ModelLineup}, handed over once: the proposal asks the
 * model of the role {@link ModelRoles#INSTRUMENTS}, composing those of planning and composition.
 */
public final class PieceAutopilot {
    /**
     * How tracks with a sound bank are rendered.
     *
     * @param sf2Engine  what plays SoundFonts
     * @param fluidSynth the FluidSynth executable, may be empty
     * @param sfizz      the sfizz executable, may be empty
     */
    public record Rendering(Sf2Engine sf2Engine, String fluidSynth, String sfizz) {
        /** The built-in way: no external program. */
        public static final Rendering BUILT_IN = new Rendering(Sf2Engine.GERVILL, "", "");
    }

    /** Told while a piece comes into being. */
    public interface Listener {
        /**
         * The players and their stage are chosen and saved, composing begins next. The window
         * shows them here; it may wait until it has.
         */
        void staged(TrackArrangement tracks, SpatialScene scene) throws Exception;
    }

    /**
     * What came of it.
     *
     * @param arrangement the written arrangement of the piece
     * @param rendered    what the renderer returned: the mixed file of the rendered tracks
     */
    public record Result(Path arrangement, Path rendered, TrackArrangement tracks, SpatialScene scene) {
    }

    private final InstrumentRegistry registry;
    private final ModelLineup models;
    private final int maxTokens;
    private final int maxParallel;
    private final Rendering rendering;
    private final SpatialSceneStore scenes = new SpatialSceneStore();

    public PieceAutopilot(InstrumentRegistry registry, ModelLineup models, int maxTokens, int maxParallel,
                          Rendering rendering) {
        this.registry = registry;
        this.models = models;
        this.maxTokens = maxTokens;
        this.maxParallel = maxParallel;
        this.rendering = rendering == null ? Rendering.BUILT_IN : rendering;
    }

    /**
     * Makes the piece in its folder. Everything composing writes becomes part of the model of the
     * piece ({@link ComposingIntoPiece}). Called again for the same folder, the answers the models
     * gave before are used and rendered tracks are kept, so only what is missing is made.
     *
     * @param spec  bars, tempo, key and meter, or {@code null} for what the folder was planned
     *              with (the legacy spec for a new piece)
     * @param stage the stage the piece has so far, or {@code null} to read the one saved with it;
     *              the stage the AI proposes is taken into it, but for what is protected there
     */
    public Result run(String description, Path folder, CompositionSpec spec, SpatialScene stage, SongLibrary songs,
                      PieceModel model, Progress progress, Listener listener) throws Exception {
        InstrumentProposalService.Staged staged = new InstrumentProposalService(
                models.model(ModelRoles.INSTRUMENTS), maxTokens).proposeStaged(description, folder, progress);
        TrackArrangement tracks = withPreferredSoundbanks(staged.tracks(), registry);
        SpatialScene before = stage != null ? stage : scenes.load(folder);
        SpatialScene scene = (before == null ? SpatialScene.standard(tracks.tracks()) : before.fitting(tracks.tracks()))
                .taking(staged.scene());
        scenes.save(folder, scene);
        Map<String, InstrumentLibrary> assignments = assignments(tracks, registry);
        Map<String, Integer> programs = new HashMap<String, Integer>();
        for (MusicTrack track : tracks.tracks()) {
            programs.put(track.id(), track.program());
        }
        if (listener != null) {
            listener.staged(tracks, scene);
        }
        new TrackArrangementStore().save(folder, tracks);
        GemmaMusicComposer composer = new GemmaMusicComposer(models, maxTokens, maxParallel);
        Path arrangement = ComposingIntoPiece.run(
                () -> spec == null ? composer.compose(description, folder, tracks, progress)
                        : composer.compose(description, folder, tracks, spec, progress),
                folder, description, songs, model);
        Path rendered = new StudioMusicRenderer(registry).render(arrangement, folder, assignments, programs,
                rendering.sf2Engine(), rendering.fluidSynth(), rendering.sfizz(), progress);
        return new Result(arrangement, rendered, tracks, scene);
    }

    /** Stops the requests to the models that are running. */
    public void cancel() {
        models.cancel();
    }

    /** Every track on the General-User sound bank if it is installed, else on the built-in synthesis. */
    public static TrackArrangement withPreferredSoundbanks(TrackArrangement original, InstrumentRegistry registry) {
        String preferred = "builtin";
        for (InstrumentLibrary library : registry.installedInstruments()) {
            if (library.id().startsWith("generaluser::") || library.id().equals("generaluser")) {
                preferred = library.id();
                break;
            }
        }
        List<MusicTrack> selected = new ArrayList<MusicTrack>();
        for (MusicTrack track : original.tracks()) {
            selected.add(track.withLibrary(preferred));
        }
        return new TrackArrangement(selected);
    }

    /**
     * The installed sound bank of every track that is not on the built-in synthesis.
     *
     * @throws IllegalArgumentException if a track names a sound bank that is not installed
     */
    public static Map<String, InstrumentLibrary> assignments(TrackArrangement tracks, InstrumentRegistry registry) {
        Map<String, InstrumentLibrary> assignments = new HashMap<String, InstrumentLibrary>();
        for (MusicTrack track : tracks.tracks()) {
            if (!"builtin".equals(track.libraryId())) {
                InstrumentLibrary installed = null;
                for (InstrumentLibrary library : registry.installedInstruments()) {
                    if (library.id().equals(track.libraryId())) {
                        installed = library;
                    }
                }
                if (installed == null) {
                    throw new IllegalArgumentException("Soundbank nicht installiert: "
                            + track.libraryId() + " (Spur " + track.id() + ")");
                }
                assignments.put(track.id(), installed);
            }
        }
        return assignments;
    }
}
