package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Articulation;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.studio.project.PlanShape;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.ModelLineup;
import com.aresstack.audio.studio.ai.ModelRoles;
import com.aresstack.audio.studio.ai.PartialNotes;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.progress.SketchProgress;
import com.aresstack.audio.studio.progress.TrackProgress;
import com.aresstack.audio.studio.score.Voicing;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Plan a song once, then compose any validated arrangement of voices concurrently.
 *
 * <p>The piece follows a {@link CompositionSpec}: bars, tempo, key and meter. A piece of more
 * than twice the chosen section length ({@link #sectionBars(int)}, 8 bars unless the user says otherwise) is composed in sections (architecture
 * review E4): the tracks run side by side, the sections of a track one after another, and each
 * request after the first is told how the previous section of its track ended. Beats in a
 * request count from the start of its section; the application turns them into ticks.
 *
 * <p>A piece of {@link CompositionSpec#legacy() the legacy spec} is asked for with exactly the
 * prompts of before, so that its cached answers stay valid and its arrangement stays the same.
 */
public final class GemmaMusicComposer {
    /** The spec of a piece, kept next to it when it is not the legacy one. */
    public static final String SPEC_FILE = "composition-spec.json";

    private static final String[] CHORDS = {"C", "G", "Am", "F", "C", "G", "F", "G"};
    /** The bars of the previous section a request is told about. */
    private static final int CONTEXT_BARS = 2;
    /** Asks for the plan: form, harmonies, tempo (role {@link ModelRoles#PLANNING}). */
    private final ChatModel planner;
    /** Asks for the notes of each track (role {@link ModelRoles#COMPOSITION}). */
    private final ChatModel client;
    private final int maxTokens;
    private final int maxParallel;
    private int sectionBars = CompositionSpec.SECTION_BARS;
    /** Whether the plan names tempo changes, dynamics and hairpins, and the notes articulations. */
    private boolean shapePerformance = true;

    /** The velocity a plain note is written with when the player applies the dynamics: that of the reference level. */
    static final int ACCENT_REFERENCE = Dynamics.Scale.STANDARD.velocity(Dynamics.Scale.STANDARD.reference());
    /** The articulations a note may name, as the AI is to write them. */
    static final String ARTICULATIONS = articulationWords();

    /**
     * Whether the planning model plans the course of tempo (accelerando, ritardando, sudden
     * changes) and of loudness (dynamic marks, crescendo, decrescendo), and the notes may name
     * articulations. On by default; a piece of the legacy spec is always asked for as before.
     */
    public GemmaMusicComposer shapePerformance(boolean shape) {
        this.shapePerformance = shape;
        return this;
    }

    /**
     * How many bars one request for notes covers; a piece of up to twice as many bars is asked
     * for in one. The studio's setting "Takte je Abschnitt".
     */
    public GemmaMusicComposer sectionBars(int bars) {
        if (bars < CompositionSpec.MIN_CHOSEN_SECTION_BARS || bars > CompositionSpec.MAX_CHOSEN_SECTION_BARS) {
            throw new IllegalArgumentException("Ein Abschnitt hat " + CompositionSpec.MIN_CHOSEN_SECTION_BARS
                    + " bis " + CompositionSpec.MAX_CHOSEN_SECTION_BARS + " Takte: " + bars);
        }
        this.sectionBars = bars;
        return this;
    }

    public GemmaMusicComposer(ChatModel client) {
        this(client, 16384, 32);
    }

    public GemmaMusicComposer(ChatModel client, int maxTokens) {
        this(client, maxTokens, 32);
    }

    public GemmaMusicComposer(ChatModel client, int maxTokens, int maxParallel) {
        this(client, client, maxTokens, maxParallel);
    }

    /** Plans with the lineup's planning model and writes the notes with its composition model. */
    public GemmaMusicComposer(ModelLineup models, int maxTokens, int maxParallel) {
        this(models.model(ModelRoles.PLANNING), models.model(ModelRoles.COMPOSITION), maxTokens, maxParallel);
    }

    /**
     * @param planner  the model that plans the piece
     * @param composer the model that writes the notes; may be the same as the planner
     */
    public GemmaMusicComposer(ChatModel planner, ChatModel composer, int maxTokens, int maxParallel) {
        if (planner == null || composer == null) {
            throw new IllegalArgumentException("A planning and a composition model are needed.");
        }
        if (maxTokens < 256 || maxParallel < 1 || maxParallel > 1024) {
            throw new IllegalArgumentException("Use at least 256 tokens and 1–1024 parallel requests.");
        }
        this.planner = planner;
        this.client = composer;
        this.maxTokens = maxTokens;
        this.maxParallel = maxParallel;
    }

    public Path compose(String description, Path directory, Progress progress) throws IOException {
        return compose(description, directory, TrackArrangement.defaults(), progress);
    }

    /** Composes with the spec the piece was planned with, or the legacy spec for a new piece. */
    public Path compose(String description, Path directory, TrackArrangement tracks,
                        Progress progress) throws IOException {
        CompositionSpec known = specOf(directory);
        return compose(description, directory, tracks, known != null ? known : CompositionSpec.legacy(), progress);
    }

    public Path compose(String description, Path directory, TrackArrangement tracks, CompositionSpec spec,
                        Progress progress) throws IOException {
        Files.createDirectories(directory);
        CompositionSpec planned = specOf(directory);
        if (planned != null && !planned.equals(spec)) {
            throw new IOException("Dieses Stück ist als " + planned.label() + " geplant, verlangt ist "
                    + spec.label() + ". Für andere Vorgaben ein neues Stück beginnen.");
        }
        if (!spec.isLegacy()) {
            Files.write(directory.resolve(SPEC_FILE), new GsonBuilder().setPrettyPrinting().create()
                    .toJson(toJson(spec)).getBytes(StandardCharsets.UTF_8));
        }
        progress.update("Composition plan ...");
        JsonObject plan = plan(description, tracks, spec, directory, progress);
        Map<String, List<JsonObject>> responses = generateTracks(tracks, plan, spec, directory, progress);
        return assemble(tracks, responses, spec, chords(plan, spec), directory, progress);
    }

    /**
     * The spec of a piece in this folder: the one kept with it, else the one its plan names, else
     * {@code null} for a folder without a plan.
     */
    public static CompositionSpec specOf(Path directory) {
        Path file = directory.resolve(SPEC_FILE);
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json = com.google.gson.JsonParser.parseString(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
                CompositionSpec spec = CompositionSpec.ofPlan(json);
                if (spec != null) {
                    return spec;
                }
            } catch (IOException | RuntimeException unreadable) {
                // fall back to the plan
            }
        }
        return CompositionSpec.ofPlan(directory);
    }

    private static JsonObject toJson(CompositionSpec spec) {
        JsonObject json = new JsonObject();
        json.addProperty("bars", spec.bars());
        json.addProperty("tempoBpm", spec.bpm());
        json.addProperty("key", spec.key().name());
        json.addProperty("timeSignature", spec.meter());
        json.addProperty("snapToHarmony", spec.snapToHarmony());
        return json;
    }

    private JsonObject plan(String description, TrackArrangement tracks, CompositionSpec spec, Path directory,
                            Progress progress) throws IOException {
        StringBuilder instruments = new StringBuilder();
        for (MusicTrack track : tracks.tracks()) {
            instruments.append(track.id()).append(": ").append(track.instrument())
                    .append(" (").append(track.role()).append("); ");
        }
        String prompt;
        if (spec.isLegacy()) {
            prompt = "Compose ORIGINAL short instrumental game music for: " + description
                    + "\nInstrumentation: " + instruments
                    + "\nReturn JSON with title, mood, tempoBpm=108, key=\"C major\", "
                    + "timeSignature=\"4/4\", bars=8, "
                    + "chords=[\"C\",\"G\",\"Am\",\"F\",\"C\",\"G\",\"F\",\"G\"], arrangementNotes."
                    + "\nKeep these exact musical constraints. No notes yet. Return only JSON.";
        } else {
            prompt = "Compose ORIGINAL instrumental game music for: " + description
                    + "\nInstrumentation: " + instruments
                    + "\nReturn JSON with title, mood, tempoBpm=" + spec.bpm() + ", key=\"" + spec.key().name()
                    + "\", timeSignature=\"" + spec.meter() + "\", bars=" + spec.bars()
                    + ", chords (an array of exactly " + spec.bars() + " chord symbols, one per bar, that suit "
                    + spec.key().name() + ", for example \"" + exampleChords(spec.key()) + "\"; give the piece a "
                    + "form with contrasting sections and a cadence at the end), arrangementNotes (how the "
                    + "instruments share the work, section by section)."
                    + (shapePerformance ? performancePlan(tracks, spec) : "")
                    + "\nKeep tempoBpm, key, timeSignature and bars exactly as given. No notes yet. Return only JSON.";
        }
        final SketchProgress watched = progress instanceof SketchProgress ? (SketchProgress) progress : null;
        final String step = "Die KI plant Struktur und Harmonien";
        if (watched != null) {
            watched.preparing(step, 0);
        }
        JsonObject plan = planner.generate("Return valid JSON only, no Markdown.", prompt,
                Math.min(spec.isLegacy() ? 2048 : 4096, maxTokens), directory.resolve("composition-plan.json"),
                watched == null ? null : text -> watched.preparing(step, text.length()));
        validatePlan(plan, spec, progress);
        if (watched != null) {
            watched.planned(chords(plan, spec));
        }
        return plan;
    }

    /** What the plan is to say about tempo and loudness, in the words of {@link PlanShape}. */
    static String performancePlan(TrackArrangement tracks, CompositionSpec spec) {
        StringBuilder ids = new StringBuilder();
        for (MusicTrack track : tracks.tracks()) {
            ids.append(ids.length() == 0 ? "" : ", ").append(track.id());
        }
        StringBuilder levels = new StringBuilder();
        for (Dynamics.Level level : Dynamics.Level.values()) {
            levels.append(levels.length() == 0 ? "" : " ").append(level.symbol());
        }
        return "\nPlan the performance as a score marks it, as the music asks for it:"
                + " tempoChanges (an array, may be empty, of points {bar, bpm, glide}: bar counts from 1; from that"
                + " bar the tempo is bpm; glide=true makes the tempo change evenly from this point to the next"
                + " point, an accelerando or ritardando, glide=false makes it jump; bar 1 keeps tempoBpm; keep"
                + " bpm between " + PlanShape.MIN_BPM + " and " + PlanShape.MAX_BPM + "),"
                + " dynamics (an array of marks {bar, beat, level, part}: beat is optional, quarter notes from 1;"
                + " level is one of " + levels + "; part is optional, one of " + ids + ", omitted for all"
                + " instruments; begin with a mark in bar 1),"
                + " hairpins (an array, may be empty, of crescendos and decrescendos {fromBar, toBar, to, part}:"
                + " the level changes evenly from the start of fromBar to the start of toBar and ends on level to;"
                + " part as for dynamics; hairpins of the same part do not overlap).";
    }

    /**
     * The course of tempo and loudness in the bars of one request, for one track, in words; or
     * {@code null} if the plan names none.
     */
    static String performance(JsonObject plan, CompositionSpec spec, String trackId, int[] section) {
        if (plan == null || !(plan.has("tempoChanges") || plan.has("dynamics") || plan.has("hairpins"))) {
            return null;
        }
        TimeMap time = spec.timeMap();
        PlanShape.Shape shape = PlanShape.read(plan, time, java.util.Set.of(trackId), PlanShape.MIN_BPM,
                PlanShape.MAX_BPM, spec.ticks(), warning -> { });
        long from = time.tickOfBar(section[0]);
        long to = time.tickOfBar(section[0] + section[1]);
        List<String> said = new ArrayList<String>();
        Dynamics dynamics = shape.dynamics();
        Dynamics.Level opening = null;
        for (Dynamics.Mark mark : dynamics.marks()) {
            if (mark.holdsFor(trackId) && mark.tick() <= from) {
                opening = mark.level();
            }
        }
        if (opening != null) {
            said.add("starts " + opening.symbol());
        }
        for (Dynamics.Mark mark : dynamics.marks()) {
            if (mark.holdsFor(trackId) && mark.tick() > from && mark.tick() < to) {
                said.add(at(time, mark.tick()) + " " + mark.level().symbol());
            }
        }
        for (Dynamics.Hairpin pin : dynamics.hairpins()) {
            if (pin.holdsFor(trackId) && pin.startTick() < to && pin.endTick() > from) {
                said.add(at(time, pin.startTick()) + " to " + at(time, pin.endTick()) + " "
                        + (dynamics.swells(pin) ? "crescendo" : "decrescendo") + " to " + pin.target().symbol());
            }
        }
        List<TimeMap.Tempo> tempos = shape.timeMap().tempos();
        for (int i = 0; i < tempos.size(); i++) {
            TimeMap.Tempo tempo = tempos.get(i);
            boolean inside = tempo.tick() >= from && tempo.tick() < to && tempo.tick() > 0;
            if (tempo.glide() && tempo.tick() < to && tempos.get(i + 1).tick() > from) {
                TimeMap.Tempo next = tempos.get(i + 1);
                said.add(at(time, tempo.tick()) + " to " + at(time, next.tick()) + " "
                        + (next.bpm() > tempo.bpm() ? "accelerando" : "ritardando") + " to " + number(next.bpm()) + " BPM");
            } else if (inside) {
                said.add(at(time, tempo.tick()) + " " + number(tempo.bpm()) + " BPM");
            }
        }
        return said.isEmpty() ? null : String.join(", ", said);
    }

    private static String at(TimeMap time, long tick) {
        int bar = time.barOf(tick);
        long into = tick - time.tickOfBar(bar);
        return "bar " + (bar + 1) + (into == 0 ? "" : " beat " + beats(1 + into / (double) TimeMap.PPQ));
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** The articulation a note of an answer names, or {@code null}. */
    private static Articulation articulationOf(JsonObject note) {
        try {
            return note.has("articulation") && !note.get("articulation").isJsonNull()
                    ? Articulation.parse(note.get("articulation").getAsString()) : null;
        } catch (RuntimeException odd) {
            return null;
        }
    }

    private static String articulationWords() {
        StringBuilder words = new StringBuilder();
        for (Articulation articulation : Articulation.values()) {
            words.append(words.length() == 0 ? "" : ", ").append(articulation.token());
        }
        return words.toString();
    }

    /** Chord symbols on the first, fourth, fifth and sixth degree of a key, to show the format. */
    private static String exampleChords(Key key) {
        int[] scale = key.scale();
        boolean minor = key.mode() == Key.Mode.MINOR;
        String tonic = name(key, scale[0]) + (minor ? "m" : "");
        String four = name(key, scale[3]) + (minor ? "m" : "");
        String five = name(key, scale[4]) + (minor ? "7" : "");
        String six = name(key, scale[5]) + (minor ? "" : "m");
        return tonic + "\", \"" + four + "\", \"" + five + "\", \"" + six;
    }

    private static String name(Key key, int pitchClass) {
        Key.Spelling spelling = key.spell(60 + pitchClass);
        return spelling.letterName() + (spelling.alteration() > 0 ? "#" : spelling.alteration() < 0 ? "b" : "");
    }

    private Map<String, List<JsonObject>> generateTracks(TrackArrangement tracks, JsonObject plan,
                                                          CompositionSpec spec, Path directory,
                                                          Progress progress) throws IOException {
        int threads = Math.min(maxParallel, tracks.tracks().size());
        List<int[]> sections = spec.sections(sectionBars);
        List<String> chords = chords(plan, spec);
        final Sketch sketch = progress instanceof SketchProgress
                ? new Sketch(tracks, spec, sections, chords, (SketchProgress) progress) : null;
        final Arriving arriving = new Arriving(progress);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CompletionService<TrackResponse> completion =
                new ExecutorCompletionService<TrackResponse>(executor);
        List<Future<TrackResponse>> pending = new ArrayList<Future<TrackResponse>>();
        progress.update("Composing " + tracks.tracks().size() + " tracks, " + threads + " at the same time"
                + (sections.size() > 1 ? ", " + sections.size() + " sections each" : "") + " ...");
        try {
            for (MusicTrack track : tracks.tracks()) {
                TrackProgress.tell(progress, track.id(), TrackProgress.Stage.COMPOSE, TrackProgress.State.WAITING, "");
            }
            for (MusicTrack track : tracks.tracks()) {
                final MusicTrack target = track;
                pending.add(completion.submit(new Callable<TrackResponse>() {
                    @Override
                    public TrackResponse call() throws IOException {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("Composition cancelled.");
                        }
                        progress.update("Composing " + target.id() + " ("
                                + target.instrument() + ") ...");
                        TrackProgress.tell(progress, target.id(), TrackProgress.Stage.COMPOSE, TrackProgress.State.WORKING, "");
                        try {
                            List<JsonObject> answers = new ArrayList<JsonObject>();
                            int notes = 0;
                            boolean allKnown = true;
                            for (int s = 0; s < sections.size(); s++) {
                                if (Thread.currentThread().isInterrupted()) {
                                    throw new IOException("Composition cancelled.");
                                }
                                int[] section = sections.get(s);
                                String prompt = spec.isLegacy() ? promptFor(target, plan)
                                        : promptFor(target, plan, spec, chords, section,
                                        s == 0 ? null : context(answers.get(s - 1), sections.get(s - 1), spec));
                                Path cached = cachedResponse(directory, target, prompt, spec);
                                String instruction = "Compose original instrumental music. Return valid JSON only.";
                                boolean known = client.hasAnswer(instruction, prompt, maxTokens, cached);
                                allKnown &= known;
                                arriving.asked(known);
                                final int at = s;
                                JsonObject answer = client.generate(instruction, prompt, maxTokens, cached,
                                        text -> {
                                            arriving.heard(target.id() + "/" + at, text.length());
                                            if (sketch != null) {
                                                sketch.heard(target.id(), at, text);
                                            }
                                        });
                                validateResponse(target, answer);
                                answers.add(answer);
                                if (sketch != null) {
                                    sketch.written(target.id(), at, answer);
                                }
                                notes += answer.getAsJsonArray("notes").size();
                                if (sections.size() > 1) {
                                    TrackProgress.tell(progress, target.id(), TrackProgress.Stage.COMPOSE,
                                            TrackProgress.State.WORKING, "Abschnitt " + (s + 1) + "/" + sections.size());
                                }
                            }
                            TrackProgress.tell(progress, target.id(), TrackProgress.Stage.COMPOSE, TrackProgress.State.DONE,
                                    notes + (allKnown ? " notes, cached" : " notes"));
                            return new TrackResponse(target.id(), answers, notes);
                        } catch (IOException failed) {
                            TrackProgress.tell(progress, target.id(), TrackProgress.Stage.COMPOSE, TrackProgress.State.FAILED, String.valueOf(failed.getMessage()));
                            throw failed;
                        } catch (RuntimeException failed) {
                            TrackProgress.tell(progress, target.id(), TrackProgress.Stage.COMPOSE, TrackProgress.State.FAILED, String.valueOf(failed));
                            throw failed;
                        }
                    }
                }));
            }
            Map<String, List<JsonObject>> responses = new HashMap<String, List<JsonObject>>();
            for (int i = 0; i < pending.size(); i++) {
                TrackResponse generated = completion.take().get();
                responses.put(generated.id, generated.responses);
                progress.update("Finished " + generated.id + " (" + generated.notes + " notes)");
            }
            progress.update(arriving.summary());
            return responses;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            cancel(pending);
            client.cancel();
            throw new IOException("Composition cancelled.", cancelled);
        } catch (ExecutionException failed) {
            cancel(pending);
            client.cancel();
            Throwable cause = failed.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("One instrument generation failed: " + cause, cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void cancel(List<Future<TrackResponse>> tasks) {
        for (Future<TrackResponse> task : tasks) {
            task.cancel(true);
        }
    }

    /** The prompt of every piece of the legacy spec, word for word as before F2. */
    private static String promptFor(MusicTrack track, JsonObject plan) {
        return "Compose the independent " + track.id() + " track (" + track.instrument()
                + "; role=" + track.role() + "; zero-based General MIDI program="
                + track.program() + ") for an ORIGINAL 8-bar instrumental song. "
                + "4/4, 108 BPM, C major, 32 quarter-note beats, chords by bar: "
                + "C | G | Am | F | C | G | F | G. "
                + "Arrangement: " + plan.get("arrangementNotes").getAsString()
                + "\n" + rules(track.role())
                + "\nReturn exactly one JSON object with keys part, program, notes. "
                + "Set part=\"" + track.id() + "\" and program=" + track.program() + ". "
                + "Every note needs numeric beat,duration,pitch,velocity; "
                + "0 <= beat < 32, 0.25 <= duration <= 4, beat+duration <= 32. "
                + "Align to multiples of 0.25. Use absolute song beats and explicitly list "
                + "the notes. Compose this track as part of the shared arrangement; no Markdown."
                + voiceRule(track, true);
    }

    /**
     * The prompt for one section of one track.
     *
     * @param section {@code {first bar, bars}}
     * @param context how the previous section of this track ended, or {@code null}
     */
    static String promptFor(MusicTrack track, JsonObject plan, CompositionSpec spec, List<String> chords,
                            int[] section, String context) {
        int first = section[0];
        int bars = section[1];
        String beats = beats(bars * spec.quartersPerBar());
        boolean whole = bars == spec.bars();
        StringBuilder text = new StringBuilder("Compose the independent ").append(track.id()).append(" track (")
                .append(track.instrument()).append("; role=").append(track.role())
                .append("; zero-based General MIDI program=").append(track.program()).append(") for an ORIGINAL ")
                .append(spec.bars()).append("-bar instrumental song in ").append(spec.key().name()).append(", ")
                .append(spec.meter()).append(", ").append(spec.bpm()).append(" BPM. ");
        if (whole) {
            text.append(beats).append(" quarter-note beats, chords by bar: ");
        } else {
            text.append("This request is bars ").append(first + 1).append("–").append(first + bars)
                    .append(" of the song: ").append(bars).append(" bars, ").append(beats)
                    .append(" quarter-note beats counted from the start of these bars, chords by bar: ");
        }
        text.append(String.join(" | ", chords.subList(first, first + bars))).append(". ");
        if (!spec.fourFour()) {
            text.append("A bar has ").append(beats(spec.quartersPerBar())).append(" quarter-note beats. ");
        }
        text.append("Arrangement: ").append(plan.has("arrangementNotes") ? plan.get("arrangementNotes").getAsString() : "");
        String performance = performance(plan, spec, track.id(), section);
        if (performance != null) {
            text.append("\nPerformance in these bars (the player applies it): ").append(performance)
                    .append(". Write velocity as the accent of a note within the marked level: about ")
                    .append(ACCENT_REFERENCE).append(" for a plain note, more for a stressed one; do not fade or swell "
                            + "by velocity yourself.");
        }
        if (context != null) {
            text.append("\nThe previous bars of this track ended like this (beat counted from the start of "
                    + "these bars, so negative): ").append(context).append(". Continue from there.");
        }
        if (!whole && first + bars == spec.bars()) {
            text.append("\nThese are the last bars: bring the song to an end.");
        }
        text.append("\n").append(rules(track.role(), spec.key(), bars))
                .append("\nReturn exactly one JSON object with keys part, program, notes. ")
                .append("Set part=\"").append(track.id()).append("\" and program=").append(track.program()).append(". ")
                .append("Every note needs numeric beat,duration,pitch,velocity; ")
                .append("0 <= beat < ").append(beats).append(", 0.25 <= duration <= 4, beat+duration <= ")
                .append(beats).append(". Align to multiples of 0.25. ")
                .append(whole ? "Use absolute song beats" : "Count beats from the start of these bars")
                .append(" and explicitly list the notes. Compose this track as part of the shared arrangement; "
                        + "no Markdown.")
                .append(performance != null ? " A note may also have articulation, one of " + ARTICULATIONS
                        + ", where the music asks for it (short notes, stresses, bound lines)." : "")
                .append(voiceRule(track, false));
        return text.toString();
    }

    private static String beats(double beats) {
        return beats == Math.rint(beats) ? String.valueOf((long) beats) : String.valueOf(beats);
    }

    /** The notes of the last bars of a section's answer, relative to the start of the next section. */
    private static String context(JsonObject answer, int[] section, CompositionSpec spec) {
        double length = section[1] * spec.quartersPerBar();
        double from = length - CONTEXT_BARS * spec.quartersPerBar();
        List<String> notes = new ArrayList<String>();
        for (JsonElement element : answer.getAsJsonArray("notes")) {
            try {
                JsonObject note = element.getAsJsonObject();
                double beat = note.get("beat").getAsDouble();
                if (beat >= from && beat < length) {
                    notes.add(String.format(Locale.ROOT, "{beat:%s,duration:%s,pitch:%d}",
                            beats(beat - length), beats(note.get("duration").getAsDouble()), note.get("pitch").getAsInt()));
                }
            } catch (RuntimeException odd) {
                // the normalizer reports it; here it is only left out of the context
            }
        }
        return notes.isEmpty() ? "[rest]" : "[" + String.join(",", notes) + "]";
    }

    private static String rules(String role) {
        if (MusicTrack.MELODY.equals(role)) {
            return "Create a memorable, expressive motif with 8–55 notes, pitches 60..88.";
        }
        if (MusicTrack.HARMONY.equals(role)) {
            return "Write chord tones with elegant voice leading, pitches 48..76, 8–60 notes.";
        }
        if (MusicTrack.BASS.equals(role)) {
            return "Write roots, fifths and bass movements, pitches 36..60, 8–48 notes.";
        }
        if (MusicTrack.DRUMS.equals(role)) {
            return "Write 16–90 GM drum hits using pitches 36 kick, 38 snare, 42 closed "
                    + "hi-hat and 46 open hi-hat; do not use pitched notes.";
        }
        return "Write 8–64 contrasting decorative notes suited to this instrument, "
                + "using C-major pitches 48..88 and the shared harmonic progression.";
    }

    /** The rules of a role for a request of this many bars: the counts of before, scaled to the bars. */
    private static String rules(String role, Key key, int bars) {
        if (MusicTrack.MELODY.equals(role)) {
            return "Create a memorable, expressive melody in " + key.name() + " with " + count(1, 7, bars)
                    + " notes, pitches 60..88.";
        }
        if (MusicTrack.HARMONY.equals(role)) {
            return "Write chord tones with elegant voice leading, pitches 48..76, " + count(1, 8, bars) + " notes.";
        }
        if (MusicTrack.BASS.equals(role)) {
            return "Write roots, fifths and bass movements, pitches 36..60, " + count(1, 6, bars) + " notes.";
        }
        if (MusicTrack.DRUMS.equals(role)) {
            return "Write " + count(2, 11, bars) + " GM drum hits using pitches 36 kick, 38 snare, 42 closed "
                    + "hi-hat and 46 open hi-hat; do not use pitched notes.";
        }
        return "Write " + count(1, 8, bars) + " contrasting decorative notes suited to this instrument, "
                + "using " + key.name() + " pitches 48..88 and the shared harmonic progression.";
    }

    /** For example {@code "8–56"}: so many notes per bar, at most 256 in all. */
    private static String count(int perBarLow, int perBarHigh, int bars) {
        return Math.min(perBarLow * bars, 200) + "–" + Math.min(perBarHigh * bars, 250);
    }

    private static void validateResponse(MusicTrack track, JsonObject response) throws IOException {
        try {
            if (!track.id().equals(response.get("part").getAsString())
                    || track.program() != response.get("program").getAsInt()) {
                throw new IOException("Model returned an unexpected ID or program for " + track.id()
                        + "; keep cached JSON for diagnostics.");
            }
            JsonArray notes = response.getAsJsonArray("notes");
            if (notes == null || notes.size() < 1 || notes.size() > 256) {
                throw new IOException("Unsupported note count for " + track.id());
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Malformed notes for " + track.id(), invalid);
        }
    }

    private static Path cachedResponse(Path directory, MusicTrack track, String prompt, CompositionSpec spec)
            throws IOException {
        if (track.hasLegacyCacheIdentity() && spec.isLegacy()) {
            return directory.resolve(track.id() + ".json");
        }
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] digest = hash.digest(prompt.getBytes(StandardCharsets.UTF_8));
            StringBuilder shortHash = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                shortHash.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
            }
            return directory.resolve(track.id() + "-" + shortHash + ".json");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable.", impossible);
        }
    }

    private Path assemble(TrackArrangement tracks, Map<String, List<JsonObject>> responses,
                                 CompositionSpec spec, List<String> chords, Path directory,
                                 Progress progress) throws IOException {
        CompositionNoteNormalizer normalizer = new CompositionNoteNormalizer(progress, spec, chords);
        List<String> arrangement = lines(tracks, responses, spec, spec.sections(sectionBars), normalizer, progress, false);
        Path output = directory.resolve("arrangement.tsv");
        Files.write(output, arrangement, StandardCharsets.UTF_8);
        Files.write(directory.resolve("note-corrections.tsv"),
                normalizer.corrections(), StandardCharsets.UTF_8);
        progress.update("Created " + tracks.tracks().size() + " MIDI tracks; "
                + normalizer.correctionCount() + " note corrections. Cached JSON preserved.");
        return output;
    }

    /**
     * The lines of the arrangement made of the answers.
     *
     * @param responses the answers of each track, one per section in the order of the sections
     * @param unfinished whether the piece is still being composed: a track without an answer or
     *                   without a playable note is then left out instead of being an error
     */
    private static List<String> lines(TrackArrangement tracks, Map<String, List<JsonObject>> responses,
                                      CompositionSpec spec, List<int[]> sections,
                                      CompositionNoteNormalizer normalizer,
                                      Progress progress, boolean unfinished) throws IOException {
        List<String> body = new ArrayList<String>();
        long end = 0;
        for (MusicTrack track : tracks.tracks()) {
            List<JsonObject> parts = responses.get(track.id());
            if (parts == null) {
                if (unfinished) {
                    continue;
                }
                throw new IOException("Missing generated instrument: " + track.id());
            }
            int channel = tracks.channelFor(track.id());
            List<String> head = new ArrayList<String>();
            head.add("track\t" + track.id() + "\t" + track.program() + "\t"
                    + channel + "\t" + track.role());
            boolean grand = grand(track);
            if (grand) {
                head.add("staff\t" + track.id() + "\tgrand");
            }
            // The voices the answer names (since S1); written only if it names any.
            List<String> lines = new ArrayList<String>();
            List<long[]> written = new ArrayList<long[]>();
            List<String> named = new ArrayList<String>();
            List<Articulation> articulations = new ArrayList<Articulation>();
            int playable = 0;
            int index = 0;
            for (int s = 0; s < parts.size() && s < sections.size(); s++) {
                int[] section = sections.get(s);
                JsonArray notes = parts.get(s) == null ? null : parts.get(s).getAsJsonArray("notes");
                for (int n = 0; notes != null && n < notes.size(); n++) {
                    index++;
                    JsonElement element = notes.get(n);
                    if (!element.isJsonObject()) {
                        progress.update("WARN: Ignoring non-object note in " + track.id());
                        continue;
                    }
                    String hand = grand ? Voicing.normalize(voiceOf(element.getAsJsonObject()), true) : null;
                    CompositionNoteNormalizer.NormalizedNote note = normalizer.normalize(
                            element.getAsJsonObject(), track.id(), track.role(), index, section[0], section[1],
                            CompositionNoteNormalizer.range(track.role(), grand, hand));
                    if (note == null) {
                        continue;
                    }
                    lines.add("note\t" + track.id() + "\t" + note.startTick()
                            + "\t" + note.durationTicks() + "\t" + note.pitch()
                            + "\t" + note.velocity());
                    articulations.add(articulationOf(element.getAsJsonObject()));
                    written.add(new long[]{note.startTick(), note.durationTicks(), note.pitch()});
                    named.add(voiceOf(element.getAsJsonObject()));
                    end = Math.max(end, note.startTick() + note.durationTicks());
                    playable++;
                }
            }
            if (playable == 0) {
                if (unfinished) {
                    continue;
                }
                throw new IOException("No playable notes for " + track.id()
                        + ". The cached model response was preserved.");
            }
            String[] answered = named.toArray(new String[0]);
            boolean struck = MusicTrack.DRUMS.equals(track.role());
            boolean voiced = !struck && Voicing.named(answered, grand);
            if (voiced) {
                String[] voices = Voicing.assign(written, answered, false, grand);
                for (int i = 0; i < lines.size(); i++) {
                    lines.set(i, lines.get(i) + "\t" + voices[i]);
                }
            }
            // The articulation in the eighth column, after a blank voice column where none is named.
            for (int i = 0; i < lines.size(); i++) {
                if (articulations.get(i) != null) {
                    lines.set(i, lines.get(i) + (voiced ? "" : "\t") + "\t" + articulations.get(i).token());
                }
            }
            body.addAll(head);
            body.addAll(lines);
        }
        // A piece that is not yet as long as planned is given its length in the header, so the
        // paper has the bars of the plan from the first note on.
        List<String> arrangement = new ArrayList<String>(
                spec.headerLines(ArrangementFile.barsCovering(end, spec.barTicks())));
        arrangement.addAll(body);
        return arrangement;
    }

    /**
     * How much text of the answers has arrived, so that waiting for a service that lets requests
     * queue can be told from work that stands still. A listener that watches the piece is told
     * every time; any other one reads a line about it now and then.
     */
    private static final class Arriving {
        /** The time between two lines for a listener that only reads messages, in milliseconds. */
        private static final long EVERY = 15000;
        private final Progress progress;
        private final Map<String, Integer> lengths = new HashMap<String, Integer>();
        private final long began = System.currentTimeMillis();
        private long last = began;
        private long first = -1;
        private int deliveries;
        private int kept;
        private int fresh;

        private Arriving(Progress progress) {
            this.progress = progress;
        }

        /** The answer to one request has this many characters by now. */
        synchronized void heard(String request, int characters) {
            lengths.put(request, characters);
            deliveries++;
            if (first < 0) {
                first = System.currentTimeMillis() - began;
            }
            long sum = 0;
            for (int length : lengths.values()) {
                sum += length;
            }
            if (progress instanceof SketchProgress) {
                ((SketchProgress) progress).arriving(sum);
                return;
            }
            long time = System.currentTimeMillis();
            if (time - last >= EVERY) {
                last = time;
                progress.update("Die KI schreibt: " + sum + " Zeichen bisher");
            }
        }

        /** A request is about to be made: answered from what was kept, or by the model. */
        synchronized void asked(boolean fromKept) {
            if (fromKept) {
                kept++;
            } else {
                fresh++;
            }
        }

        /** One line for the protocol when all tracks are composed: how the answers came. */
        synchronized String summary() {
            if (fresh == 0) {
                return "KI-Antworten: alle " + kept + " aus dem Speicher dieses Stücks, die KI wurde nicht neu gefragt "
                        + "(deshalb liefen keine Noten mit).";
            }
            if (deliveries == 0) {
                return "KI-Antworten: " + fresh + " neu gefragt, " + kept + " aus dem Speicher. Der Dienst hat keine "
                        + "Antwort stückweise geliefert, jede kam erst am Ende als Ganzes (deshalb liefen keine Noten mit).";
            }
            long sum = 0;
            for (int length : lengths.values()) {
                sum += length;
            }
            return "KI-Antworten: " + fresh + " neu gefragt, " + kept + " aus dem Speicher. Stückweise angekommen: "
                    + sum + " Zeichen in " + deliveries + " Lieferungen, die erste nach "
                    + String.format(Locale.GERMANY, "%.1f", first / 1000.0) + " s.";
        }
    }

    /**
     * The piece while it is composed: what every track has so far, the sections that are whole
     * and the notes already written of the section a model is at. Shown a few times a second at
     * most; nothing of it is kept, the piece is made of the whole answers at the end.
     */
    private static final class Sketch {
        /** The time between two pictures of the piece, in milliseconds. */
        private static final long EVERY = 250;
        private final TrackArrangement tracks;
        private final CompositionSpec spec;
        private final List<int[]> sections;
        private final List<String> chords;
        private final SketchProgress shown;
        private final Map<String, List<JsonObject>> soFar = new HashMap<String, List<JsonObject>>();
        private long last;

        private Sketch(TrackArrangement tracks, CompositionSpec spec, List<int[]> sections, List<String> chords,
                       SketchProgress shown) {
            this.tracks = tracks;
            this.spec = spec;
            this.sections = sections;
            this.chords = chords;
            this.shown = shown;
        }

        /** The text a model has written so far for a section of a track. */
        void heard(String track, int section, String text) {
            JsonObject partial = new JsonObject();
            partial.add("notes", PartialNotes.read(text));
            put(track, section, partial, false);
        }

        /** The whole answer for a section of a track. */
        void written(String track, int section, JsonObject answer) {
            put(track, section, answer, true);
        }

        private synchronized void put(String track, int section, JsonObject answer, boolean now) {
            List<JsonObject> parts = soFar.get(track);
            if (parts == null) {
                parts = new ArrayList<JsonObject>();
                soFar.put(track, parts);
            }
            while (parts.size() <= section) {
                parts.add(null);
            }
            parts.set(section, answer);
            long time = System.currentTimeMillis();
            if (!now && time - last < EVERY) {
                return;
            }
            last = time;
            try {
                Progress silent = message -> { };
                shown.sketch(lines(tracks, soFar, spec, sections, new CompositionNoteNormalizer(silent, spec, chords),
                        silent, true));
            } catch (IOException | RuntimeException unshown) {
                // a picture of the piece on its way; the piece itself does not depend on it
            }
        }
    }

    /** Whether a track is written on a grand staff: piano, organ and harp (since S1). */
    static boolean grand(MusicTrack track) {
        return Voicing.layoutFor(track.program(), MusicTrack.DRUMS.equals(track.role())) == Part.StaffLayout.GRAND;
    }

    /** The voice a note of an answer names, as written; {@code null} if it names none. */
    private static String voiceOf(JsonObject note) {
        JsonElement voice = note.get("voice");
        if (voice == null || voice.isJsonNull() || !voice.isJsonPrimitive()) {
            return null;
        }
        return voice.getAsString();
    }

    /**
     * What the prompt of a track asks about voices (since S1): on a grand staff the hand of every
     * note; on a harmony or texture track, which may play several lines at once, the line of each
     * note if it has several. Empty for every other track, whose prompt stays as it was.
     */
    static String voiceRule(MusicTrack track, boolean legacy) {
        if (grand(track)) {
            return "\nThis instrument is written on a grand staff: give every note a field voice, \"rh\" for the "
                    + "right hand (treble staff) or \"lh\" for the left hand (bass staff); a hand that plays two "
                    + "independent lines at once uses \"rh2\" or \"lh2\" for its second line.";
        }
        if (!legacy && (MusicTrack.HARMONY.equals(track.role()) || MusicTrack.TEXTURE.equals(track.role()))) {
            return "\nIf the track plays independent lines at the same time, give every note a field voice: "
                    + "\"1\" for the top line, \"2\" for the next one, and so on.";
        }
        return "";
    }

    /** The chord of each bar: the locked ones of the legacy spec, else those of the plan. */
    private static List<String> chords(JsonObject plan, CompositionSpec spec) {
        if (spec.isLegacy()) {
            return CompositionSpec.LEGACY_CHORDS;
        }
        List<String> chords = new ArrayList<String>();
        JsonArray written = plan.getAsJsonArray("chords");
        for (int bar = 0; bar < spec.bars(); bar++) {
            chords.add(bar < written.size() ? written.get(bar).getAsString().trim() : "");
        }
        return chords;
    }

    private static void validatePlan(JsonObject plan, CompositionSpec spec, Progress progress) throws IOException {
        if (spec.isLegacy()) {
            validatePlan(plan);
            return;
        }
        try {
            CompositionSpec named = CompositionSpec.ofPlan(plan);
            if (named == null || !named.withSnapToHarmony(spec.snapToHarmony()).equals(spec)) {
                throw new IOException("Model changed locked tempo, key, meter or duration: " + named);
            }
            JsonArray chords = plan.getAsJsonArray("chords");
            if (chords == null || chords.size() != spec.bars()) {
                throw new IOException("Expected " + spec.bars() + " chords, one per bar.");
            }
            for (int bar = 0; bar < chords.size(); bar++) {
                String symbol = chords.get(bar).getAsString();
                if (com.aresstack.audio.composition.Chord.tryParse(symbol) == null) {
                    progress.update("WARN: Bar " + (bar + 1) + " has no chord symbol I can read (\"" + symbol
                            + "\"); notes there are checked against the key only.");
                }
            }
            if (!plan.has("arrangementNotes")) {
                throw new IOException("The plan has no arrangementNotes.");
            }
        } catch (RuntimeException invalid) {
            throw new IOException("The cached or generated composition plan is invalid.", invalid);
        }
    }

    private static void validatePlan(JsonObject plan) throws IOException {
        try {
            if (plan.get("tempoBpm").getAsInt() != 108 || plan.get("bars").getAsInt() != 8
                    || !"C major".equals(plan.get("key").getAsString())
                    || !"4/4".equals(plan.get("timeSignature").getAsString())) {
                throw new IOException("Model changed locked tempo, key or duration.");
            }
            JsonArray chords = plan.getAsJsonArray("chords");
            if (chords.size() != CHORDS.length) {
                throw new IOException("Expected eight chords.");
            }
            for (int i = 0; i < CHORDS.length; i++) {
                if (!CHORDS[i].equals(chords.get(i).getAsString())) {
                    throw new IOException("Unexpected chord at bar " + (i + 1));
                }
            }
        } catch (RuntimeException invalid) {
            throw new IOException("The cached or generated composition plan is invalid.", invalid);
        }
    }

    private static final class TrackResponse {
        private final String id;
        private final List<JsonObject> responses;
        private final int notes;

        private TrackResponse(String id, List<JsonObject> responses, int notes) {
            this.id = id;
            this.responses = responses;
            this.notes = notes;
        }
    }
}
