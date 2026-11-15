package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The AI proposes how the instruments move on the stage over the bars: their side, depth, share
 * of the room and height. This is a request of its own, asked only when the user wants it (the
 * switch "Mit Bewegungen" under "KI mischt ab", off by default) and after the mix proposal of the
 * {@link MixDirector}, whose request stays exactly as it was. The level curve is not here: that one
 * the mix proposal brings ({@code dynamics}).
 *
 * <p>What the AI says is checked and kept within bounds ({@link #read}): only tracks that may move
 * (not protected by the user, with a channel in the mix document), only the four parameters, values
 * within their range, bars within the piece, at most {@link #MAX_POINTS} points a curve. What it
 * proposes is a proposal like the mix: the studio shows it and the user takes it or drops it.
 */
public final class MovementDirector {
    public static final String FILE_NAME = "movement-proposal.json";
    /** The user's switch in the settings of the studio; off unless set. */
    public static final String SETTING = "aiStageMovements";
    /** The most points the AI may give one curve; more are dropped from the end. */
    public static final int MAX_POINTS = 8;

    private final ChatModel client;
    private final int maxTokens;

    public MovementDirector(ChatModel client, int maxTokens) {
        this.client = client;
        this.maxTokens = maxTokens;
    }

    /** Whether the user wants the AI to propose movements along with the mix; off unless set. */
    public static boolean wanted(Properties settings) {
        return "true".equals(settings.getProperty(SETTING));
    }

    /** Keeps the user's switch in the settings of the studio. */
    public static void into(Properties settings, boolean wanted) {
        settings.setProperty(SETTING, String.valueOf(wanted));
    }

    /**
     * What the AI proposes: for some tracks the curves of some parameters, points in bars counted
     * from 0. A track or a parameter that is not here stays as it is.
     */
    public record Proposal(Map<String, Map<String, List<StageMovements.Point>>> curves) {
        public static final Proposal NONE = new Proposal(Map.of());

        public Proposal {
            Map<String, Map<String, List<StageMovements.Point>>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, List<StageMovements.Point>>> track : curves.entrySet()) {
                Map<String, List<StageMovements.Point>> kept = new LinkedHashMap<>();
                for (Map.Entry<String, List<StageMovements.Point>> curve : track.getValue().entrySet()) {
                    kept.put(curve.getKey(), List.copyOf(curve.getValue()));
                }
                if (!kept.isEmpty()) {
                    copy.put(track.getKey(), Collections.unmodifiableMap(kept));
                }
            }
            curves = Collections.unmodifiableMap(copy);
        }

        public boolean empty() {
            return curves.isEmpty();
        }

        /** How many tracks move. */
        public int tracks() {
            return curves.size();
        }

        /** The proposed curve of a parameter of a track; empty if the AI proposes none. */
        public List<StageMovements.Point> of(String trackId, String parameter) {
            Map<String, List<StageMovements.Point>> track = curves.get(trackId);
            List<StageMovements.Point> points = track == null ? null : track.get(parameter);
            return points == null ? List.of() : points;
        }

        /** Whether the AI proposes a curve for this parameter of this track. */
        public boolean has(String trackId, String parameter) {
            return !of(trackId, parameter).isEmpty();
        }
    }

    /**
     * What the AI is told of the piece: tempo, meter and key, length, and for each track its role
     * and how many notes it plays in each bar. Made from the written arrangement ({@link #of(Path)});
     * or from the model of the piece ({@link #of(com.aresstack.audio.composition.Composition)}), so
     * that the request does not depend on the file.
     *
     * @param meterAndKey as {@code "4/4"} or {@code "3/4, D minor"}
     */
    public record Piece(int tempo, String meterAndKey, int bars, List<Track> tracks) {
        public Piece {
            bars = Math.max(1, bars);
            tracks = List.copyOf(tracks);
        }
    }

    /**
     * A track as the AI is told of it.
     *
     * @param perBar the notes in each bar, as {@code "[1, 0, 2]"}, or {@code null} if it plays none
     */
    public record Track(String id, String role, String perBar) {
    }

    /** The piece as its written arrangement says. */
    public static Piece of(Path arrangement) throws IOException {
        Map<String, MixDirector.Summary> played = MixDirector.summaries(arrangement);
        List<Track> tracks = new ArrayList<>();
        for (MusicTrack track : StageMixService.tracks(arrangement)) {
            MixDirector.Summary summary = played.get(track.id());
            tracks.add(new Track(track.id(), track.role(),
                    summary == null || summary.notes == 0 ? null : summary.perBar()));
        }
        return new Piece(StageMixService.tempo(arrangement), MixDirector.meterAndKey(arrangement),
                bars(arrangement), tracks);
    }

    /**
     * The piece as its model says, with its time map; no written arrangement is needed. Tempo,
     * meter and key are those at its start, as the mix proposal tells them.
     */
    public static Piece of(com.aresstack.audio.composition.Composition model) {
        List<MusicTrack> parts = StageMixService.Piece.of(model).tracks();
        Map<String, MixDirector.Summary> played = MixDirector.summaries(parts, model);
        List<Track> tracks = new ArrayList<>();
        for (MusicTrack track : parts) {
            MixDirector.Summary summary = played.get(track.id());
            tracks.add(new Track(track.id(), track.role(),
                    summary == null || summary.notes == 0 ? null : summary.perBar()));
        }
        com.aresstack.audio.composition.TimeMap time = model.timeMap();
        return new Piece((int) Math.round(time.tempoAt(0)), MixDirector.meterAndKey(model),
                time.barsCovering(model.endTick()), tracks);
    }

    /**
     * Asks for the movements of a piece.
     *
     * @param stage   where the tracks stand when nothing moves (the mix as proposed, protected tracks
     *                as the user set them); the curves start from there
     * @param movable the tracks that may move: not protected, with a channel in the mix document
     * @return the checked proposal, perhaps empty: the AI may well let everything stand
     */
    public Proposal propose(String description, Piece piece, SpatialScene stage, Set<String> movable,
                            Path directory, Progress progress) throws IOException {
        if (movable.isEmpty()) {
            return Proposal.NONE;
        }
        progress.update("KI schlägt Bewegungen auf der Bühne vor ...");
        Files.deleteIfExists(directory.resolve(FILE_NAME)); // asked anew each time; the answer is kept to be read
        JsonObject response = client.generate("Return valid JSON only, no Markdown.",
                prompt(description, piece, stage, movable), Math.min(maxTokens, 4096),
                directory.resolve(FILE_NAME));
        try {
            return read(response, movable, piece.bars());
        } catch (RuntimeException invalid) {
            throw new IOException("The AI returned an invalid movement proposal; original JSON preserved.", invalid);
        }
    }

    static String prompt(String description, Piece piece, SpatialScene stage, Set<String> movable) {
        int bars = piece.bars();
        StringBuilder text = new StringBuilder("You are the mixing engineer of this instrumental piece: ")
                .append(description).append("\nTempo ").append(piece.tempo())
                .append(" BPM, ").append(piece.meterAndKey()).append(", ").append(bars)
                .append(" bars (counted from 0 to ").append(bars).append("). ")
                .append("The mix is set; now say how instruments move on the stage over the bars. ")
                .append("The tracks, what they play and where they stand when nothing moves:\n");
        for (Track track : piece.tracks()) {
            text.append("- ").append(track.id()).append(" (").append(track.role()).append("): ");
            text.append(track.perBar() == null ? "no notes" : "notes per bar " + track.perBar());
            SoundSourcePlacement placement = stage == null ? null : stage.placement(track.id());
            if (placement != null) {
                text.append("; x=").append(number(placement.x())).append(", depth=")
                        .append(number(placement.depth())).append(", reverb=").append(number(placement.reverb()))
                        .append(", height=").append(number(placement.height()));
            }
            if (!movable.contains(track.id())) {
                text.append(" — FIXED by the user: it does not move, leave it out");
            }
            text.append('\n');
        }
        text.append("Return one JSON object {\"movements\": [...]} with one entry per curve: track (the track id), "
                + "parameter (one of x, depth, reverb, height) and points, an array of {bar, value} in order "
                + "(bar from 0 to ").append(bars).append(", fractions allowed), between which the value moves in a "
                + "straight line; before the first point it holds the first value, after the last the last. "
                + "Ranges: x -1 far left to 1 far right, depth 0 front edge to 1 back wall, reverb 0 dry to 1 "
                + "very wet, height 0 ear height to 1 high above. At most ").append(MAX_POINTS)
                .append(" points per curve. Start each curve where the track stands. Movement is the exception: "
                        + "most pieces want one or two tracks moving, slowly and where the music gives a reason "
                        + "(a solo stepping forward, a pad drifting across, a rise towards the climax, a fade into "
                        + "the room at the end). Keep the bass and the drums still. Leave out every track and "
                        + "parameter that should stay where it stands; an empty array is a fine answer. "
                        + "No commentary. Return only JSON.");
        return text.toString();
    }

    /**
     * What is kept of an answer: entries for tracks that may move and for the four parameters,
     * points with numbers, bars within the piece, values within the range of their parameter,
     * in order, at most {@link #MAX_POINTS}; a curve needs two points to move. The first entry of
     * a track and parameter counts. Anything else is dropped without a word.
     *
     * @throws IllegalArgumentException if the answer has no array {@code movements}
     */
    static Proposal read(JsonObject response, Set<String> movable, int bars) {
        JsonElement listed = response == null ? null : response.get("movements");
        if (listed == null || !listed.isJsonArray()) {
            throw new IllegalArgumentException("No array 'movements' in the answer.");
        }
        Map<String, Map<String, List<StageMovements.Point>>> curves = new LinkedHashMap<>();
        for (JsonElement element : listed.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String track = text(entry, "track");
            String parameter = parameter(text(entry, "parameter"));
            if (track == null || parameter == null || !movable.contains(track)
                    || (curves.containsKey(track) && curves.get(track).containsKey(parameter))) {
                continue;
            }
            List<StageMovements.Point> points = points(entry.get("points"), parameter, bars);
            if (points.size() >= 2) {
                curves.computeIfAbsent(track, any -> new LinkedHashMap<>()).put(parameter, points);
            }
        }
        return new Proposal(curves);
    }

    /** The number of bars of the piece: its bars line, else the bars its notes fill. */
    static int bars(Path arrangement) throws IOException {
        ArrangementFile file = ArrangementFile.read(arrangement);
        return Math.max(1, file.bars().orElse(file.barsCovering()));
    }

    private static List<StageMovements.Point> points(JsonElement listed, String parameter, int bars) {
        List<StageMovements.Point> points = new ArrayList<>();
        if (listed == null || !listed.isJsonArray()) {
            return points;
        }
        double least = AutomationLane.least(parameter);
        double most = AutomationLane.most(parameter);
        for (JsonElement element : listed.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            Double bar = number(element.getAsJsonObject(), "bar");
            Double value = number(element.getAsJsonObject(), "value");
            if (bar == null || value == null) {
                continue;
            }
            points.add(new StageMovements.Point(Math.max(0, Math.min(bars, bar)),
                    Math.max(least, Math.min(most, value))));
        }
        points.sort((first, second) -> Double.compare(first.bar(), second.bar()));
        return points.size() > MAX_POINTS ? new ArrayList<>(points.subList(0, MAX_POINTS)) : points;
    }

    /** The parameter as the studio names it; also a few words a model might use for them. */
    private static String parameter(String said) {
        if (said == null) {
            return null;
        }
        return switch (said.trim().toLowerCase(Locale.ROOT)) {
            case "x", "side", "pan" -> AutomationLane.X;
            case "depth", "distance" -> AutomationLane.DEPTH;
            case "reverb", "room" -> AutomationLane.REVERB;
            case "height", "elevation" -> AutomationLane.HEIGHT;
            default -> null;
        };
    }

    private static String text(JsonObject entry, String name) {
        JsonElement element = entry.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static Double number(JsonObject entry, String name) {
        JsonElement element = entry.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double value = element.getAsDouble();
        return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * The tracks of a stage that may move: not protected by the user, and with a channel in the mix
     * document of the piece, so that their curves can be kept.
     */
    public static Set<String> movable(SpatialScene current, StageMovements movements, Piece piece) {
        Set<String> movable = new java.util.LinkedHashSet<>();
        for (Track track : piece.tracks()) {
            SoundSourcePlacement placement = current == null ? null : current.placement(track.id());
            if ((placement == null || !placement.locked()) && movements.editable(track.id())) {
                movable.add(track.id());
            }
        }
        return movable;
    }
}
