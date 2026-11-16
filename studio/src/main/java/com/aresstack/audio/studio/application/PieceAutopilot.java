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

    /**
     * A long piece is not composed in one go: it begins with {@link #growBars} bars, and the
     * continuation (C3) adds the rest in steps of as many bars, each step planned with the piece so
     * far in view. One plan for minutes of music would need an answer of the model longer than any
     * limit of tokens. The number is the setting "Takte je Planungsschritt"; this is its default.
     */
    public static final int GROW_BARS = com.aresstack.audio.studio.settings.AiDefaults.PIECE_BARS;
    public static final int MIN_GROW_BARS = 4;
    public static final int MAX_GROW_BARS = 512;

    private final InstrumentRegistry registry;
    private final ModelLineup models;
    private final int maxTokens;
    private final int maxParallel;
    private final Rendering rendering;
    private int sectionBars = CompositionSpec.SECTION_BARS;
    private int growBars = GROW_BARS;
    private final SpatialSceneStore scenes = new SpatialSceneStore();

    public PieceAutopilot(InstrumentRegistry registry, ModelLineup models, int maxTokens, int maxParallel,
                          Rendering rendering) {
        this.registry = registry;
        this.models = models;
        this.maxTokens = maxTokens;
        this.maxParallel = maxParallel;
        this.rendering = rendering == null ? Rendering.BUILT_IN : rendering;
    }

    /** How many bars a long piece begins with and grows by in each step (setting "Takte je Wachstumsschritt"). */
    public PieceAutopilot growBars(int bars) {
        if (bars < MIN_GROW_BARS || bars > MAX_GROW_BARS) {
            throw new IllegalArgumentException("Ein Wachstumsschritt hat " + MIN_GROW_BARS + " bis " + MAX_GROW_BARS
                    + " Takte: " + bars);
        }
        this.growBars = bars;
        return this;
    }

    /** How many bars one request for notes covers, see {@link GemmaMusicComposer#sectionBars(int)}. */
    private int fewestVoices;
    private int mostVoices;

    /** How many instruments the AI may choose, see {@link InstrumentProposalService#voices(int, int)}. */
    public PieceAutopilot voices(int fewest, int most) {
        this.fewestVoices = fewest;
        this.mostVoices = most;
        return this;
    }

    public PieceAutopilot sectionBars(int bars) {
        this.sectionBars = bars;
        return this;
    }

    private int templateLines = com.aresstack.audio.studio.template.TextTemplate.DEFAULT_LINES;

    /** How many lines of a template written as text the AI writes over in one request (setting "Zeilen der Vorlage je Anfrage"). */
    public PieceAutopilot templateLines(int lines) {
        this.templateLines = lines;
        return this;
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
        java.util.Optional<Path> template = ScoreTemplates.of(folder);
        if (template.isPresent()) {
            return fromTemplate(template.get(), description, folder, stage, songs, model, progress, listener);
        }
        InstrumentProposalService proposals = new InstrumentProposalService(
                models.model(ModelRoles.INSTRUMENTS), maxTokens);
        if (mostVoices > 0) {
            proposals.voices(fewestVoices, mostVoices);
        }
        InstrumentProposalService.Staged staged = proposals.proposeStaged(description, folder, progress);
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
        GemmaMusicComposer composer = new GemmaMusicComposer(models, maxTokens, maxParallel).sectionBars(sectionBars);
        CompositionSpec first = spec == null || spec.bars() <= growBars ? spec
                : new CompositionSpec(growBars, spec.bpm(), spec.key(), spec.numerator(), spec.denominator(),
                spec.snapToHarmony());
        // The beginning of a long piece is told that it goes on: it must not end yet.
        String opening = first == spec ? description : description + "\nCompose only the opening " + growBars
                + " bars of a piece of " + spec.bars() + " bars: introduce the themes and do not end;"
                + " the piece is continued afterwards.";
        // A piece that was interrupted on its way goes on from the bars it has, in portions of the
        // size set now: no portion size is kept with the piece, only its whole length (its spec)
        // and the bars it has. Composing its opening again would write over what it has.
        java.util.Optional<ProjectSession> saved = spec == null || model == null ? java.util.Optional.empty()
                : model.of(folder);
        int has = saved.isPresent() ? bars(saved.get()) : 0;
        boolean begun = has > 0 && has < spec.bars();
        Path arrangement = begun ? folder.resolve("arrangement.tsv") : ComposingIntoPiece.run(
                () -> first == null ? composer.compose(opening, folder, tracks, progress)
                        // the piece keeps its whole length as its spec; only the notes come in portions
                        : composer.compose(opening, folder, tracks, first, spec, progress),
                folder, description, songs, model);
        if (begun || first != spec) {
            arrangement = grow(folder, model, description, spec.bars(), progress);
        }
        return new Result(arrangement, render(arrangement, folder, model, assignments, programs, progress), tracks,
                scene);
    }

    /**
     * The piece follows notes the user gave: the players come from the template, not from a
     * proposal, and its notes are those of the template, set right where they are plainly wrong
     * ({@link TemplatePiece}). Tempo, meter, key and length are the template's. The stage the
     * piece has is kept for the tracks it fits; new tracks stand where a new stage puts them.
     */
    private Result fromTemplate(Path template, String description, Path folder, SpatialScene stage, SongLibrary songs,
                                PieceModel model, Progress progress, Listener listener) throws Exception {
        TemplatePiece.Made made = new TemplatePiece(models, maxTokens, templateLines)
                .read(template, description, folder, progress);
        TrackArrangement tracks = withPreferredSoundbanks(made.tracks(), registry);
        SpatialScene before = stage != null ? stage : scenes.load(folder);
        SpatialScene scene = before == null ? SpatialScene.standard(tracks.tracks()) : before.fitting(tracks.tracks());
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
        Path arrangement = ComposingIntoPiece.run(() -> TemplatePiece.write(made, tracks, folder, template), folder,
                description, songs, model);
        progress.update("Das Stück folgt der Notenvorlage „" + template.getFileName() + "“: " + made.spec().label()
                + ".");
        return new Result(arrangement, render(arrangement, folder, model, assignments, programs, progress), tracks,
                scene);
    }

    /**
     * Makes the composed piece audible the way the studio's render key does: from the model of
     * the piece ({@link PieceRendering}), so that stems that are still right come from the stem
     * cache. A production that was interrupted therefore renders only what is missing. A piece
     * whose model cannot be used is rendered from its file as before.
     */
    private Path render(Path arrangement, Path folder, PieceModel model, Map<String, InstrumentLibrary> assignments,
                        Map<String, Integer> programs, Progress progress) throws Exception {
        java.util.Optional<ProjectSession> piece = model == null ? java.util.Optional.empty() : model.of(folder);
        if (piece.isPresent()) {
            Map<String, String> libraries = new HashMap<String, String>();
            for (String track : programs.keySet()) {
                InstrumentLibrary bank = assignments.get(track);
                libraries.put(track, bank == null ? "builtin" : bank.id());
            }
            return new PieceRendering(registry).render(piece.get(), libraries, rendering.sf2Engine(),
                    rendering.fluidSynth(), rendering.sfizz(), programs, progress).mix();
        }
        progress.update("Dieses Stück wird aus seiner Datei gerendert (kein Werkmodell).");
        return new StudioMusicRenderer(registry).render(arrangement, folder, assignments, programs,
                rendering.sf2Engine(), rendering.fluidSynth(), rendering.sfizz(), progress);
    }

    /**
     * Lets the piece grow to its length with the continuation, step by step; every step is taken
     * into the piece at once, so an interrupted production goes on from the bars it has.
     */
    private Path grow(Path folder, PieceModel model, String description, int target, Progress progress)
            throws Exception {
        ProjectSession session = (model == null ? java.util.Optional.<ProjectSession>empty() : model.of(folder))
                .orElseThrow(() -> new java.io.IOException("Das Stück hat kein Werkmodell; es lässt sich nicht"
                        + " schrittweise verlängern."));
        String words = description == null ? "" : description.strip();
        if (words.length() > 600) {
            words = words.substring(0, 600);
        }
        PieceGrowth.grow(session, () -> com.aresstack.audio.studio.ai.ContinuationComposer.with(models, maxParallel),
                com.aresstack.audio.studio.ai.ContinuationRequest.of(1,
                        com.aresstack.audio.studio.ai.ContinuationPreset.STANDALONE,
                        com.aresstack.audio.studio.ai.ContinuationEnding.CLOSED, words),
                target, growBars, progress);
        return folder.resolve("arrangement.tsv");
    }

    private static int bars(ProjectSession session) {
        return PieceGrowth.bars(session);
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
