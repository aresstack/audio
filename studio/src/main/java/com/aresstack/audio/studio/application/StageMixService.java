package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.mix.StageAutomation;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.MixJson;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mixes the tracks that were already rendered, as they stand on the stage of the
 * piece. It needs neither the AI nor a synthesizer: after moving an instrument or a
 * fader, only this runs again.
 *
 * <p>The level curves come from the stage as the window keeps it ({@code spatial-scene.json});
 * a movement of the place or of the room share, which only the mix document can hold, comes from
 * {@code mix.json} of the piece, read through its time map (work package D3). A piece without
 * such a lane mixes exactly as before.
 */
public final class StageMixService {
    public static final String MIX_FILE = "arrangement-instruments.wav";
    /** The track that is listened to alone, as it stands on the stage. */
    public static final String SOLO_FILE = "stage-solo.wav";
    /** Which file holds the sound of each track, as the last rendering left it. */
    public static final String STEMS_FILE = "stems.tsv";

    private final SpatialSceneStore store = new SpatialSceneStore();
    private final SpatialStemMixer mixer = new SpatialStemMixer();

    /**
     * What the mix has to know of a piece: its tracks, and where its bars lie in time. A piece
     * with a model is mixed {@linkplain #of(Composition) from the model}; the written
     * {@code arrangement.tsv} is only read for a piece that has none, or for an arrangement the
     * user chose from elsewhere ({@link #written(Path)}).
     */
    public static final class Piece {
        private final List<MusicTrack> tracks;
        /** The time of the model; {@code null} for a written arrangement, which has one tempo and one meter. */
        private final TimeMap time;
        private final int tempo;
        private final double secondsPerBar;

        private Piece(List<MusicTrack> tracks, TimeMap time, int tempo, double secondsPerBar) {
            this.tracks = List.copyOf(tracks);
            this.time = time;
            this.tempo = tempo;
            this.secondsPerBar = secondsPerBar;
        }

        /** The piece as its model has it: the parts as tracks, in their order, and the time map with all its changes. */
        public static Piece of(Composition model) {
            List<MusicTrack> tracks = new ArrayList<MusicTrack>();
            for (Part part : model.parts()) {
                String role = part.role() != null && MusicTrack.ROLES.contains(part.role()) ? part.role()
                        : part.drums() ? MusicTrack.DRUMS
                        : MusicTrack.ROLES.contains(part.id().trim()) ? part.id().trim() : MusicTrack.HARMONY;
                tracks.add(new MusicTrack(part.id(), role, part.id(), Math.max(0, Math.min(127, part.program())),
                        "builtin"));
            }
            if (tracks.isEmpty()) {
                throw new IllegalArgumentException("Das Werk hat keine Stimme, die gemischt werden könnte.");
            }
            TimeMap time = model.timeMap();
            double bpm = time.tempoAt(0);
            return new Piece(tracks, time, (int) Math.round(bpm),
                    time.meterAt(0).barTicks() / (double) TimeMap.PPQ * 60.0 / bpm);
        }

        /** A written arrangement, for a piece without a model or an arrangement chosen from elsewhere. */
        public static Piece written(Path arrangement) throws IOException {
            return new Piece(StageMixService.tracks(arrangement), null, StageMixService.tempo(arrangement),
                    StageMixService.secondsPerBar(arrangement));
        }

        public List<MusicTrack> tracks() {
            return tracks;
        }

        /** The tempo at the start of the piece, in whole beats per minute. */
        public int tempo() {
            return tempo;
        }

        /** How long the first bar lasts; every bar, while the piece keeps one tempo and one meter. */
        public double secondsPerBar() {
            return secondsPerBar;
        }

        /** The level curves of the stage, whose points are in bars: laid along the time of the piece. */
        StageAutomation levels(List<SoundSourcePlacement> placements) {
            return time == null || time.constant() || (time.tempos().size() == 1 && time.meters().size() == 1)
                    ? StageAutomation.of(placements, secondsPerBar) : StageAutomation.of(placements, time);
        }

        /** The movements of the mix document, read through the time of the piece. */
        StageAutomation movements(Path directory, Progress progress) {
            return time == null ? StageMixService.movements(directory, tempo, progress)
                    : StageMixService.movements(directory, time, progress);
        }
    }

    /** The stage of a piece: the saved one, or the usual seating; always for exactly the tracks of the arrangement. */
    public SpatialScene scene(Path arrangement, Path directory) throws IOException {
        return scene(Piece.written(arrangement), directory);
    }

    /** The stage of a piece: the saved one, or the usual seating; always for exactly the tracks of the piece. */
    public SpatialScene scene(Piece piece, Path directory) throws IOException {
        SpatialScene saved = store.load(directory);
        return saved == null ? SpatialScene.standard(piece.tracks()) : saved.fitting(piece.tracks());
    }

    /** Mixes the rendered tracks of the piece on its stage and returns the file of the mix. */
    public Path mix(Path arrangement, Path directory, Progress progress) throws Exception {
        return mix(arrangement, directory, scene(arrangement, directory), progress);
    }

    public Path mix(Path arrangement, Path directory, SpatialScene scene, Progress progress)
            throws Exception {
        return mix(Piece.written(arrangement), directory, scene, progress);
    }

    /** One track alone, but as it stands on the stage: with its level, its place and the room. */
    public Path solo(Path arrangement, Path directory, SpatialScene scene, String trackId) throws Exception {
        return solo(Piece.written(arrangement), directory, scene, trackId);
    }

    /** Writes the mix on the stage to a file of the user's choice, with 16 or 24 bits. */
    public Path export(Path arrangement, Path directory, SpatialScene scene, Path target, int bits) throws Exception {
        return export(Piece.written(arrangement), directory, scene, target, bits);
    }

    /** Mixes the rendered tracks of a piece on its saved stage; no written arrangement is read for a model. */
    public Path mix(Piece piece, Path directory, Progress progress) throws Exception {
        return mix(piece, directory, scene(piece, directory), progress);
    }

    public Path mix(Piece piece, Path directory, SpatialScene scene, Progress progress) throws Exception {
        return mix(piece, directory, scene, null, directory.resolve(MIX_FILE), 16, progress);
    }

    /** One track alone, but as it stands on the stage: with its level, its place and the room. */
    public Path solo(Piece piece, Path directory, SpatialScene scene, String trackId) throws Exception {
        return mix(piece, directory, scene, trackId, directory.resolve(SOLO_FILE), 16, message -> { });
    }

    /** Writes the mix on the stage to a file of the user's choice, with 16 or 24 bits. */
    public Path export(Piece piece, Path directory, SpatialScene scene, Path target, int bits) throws Exception {
        return mix(piece, directory, scene, null, target, bits, message -> { });
    }

    private Path mix(Piece piece, Path directory, SpatialScene scene, String only, Path target, int bits,
                     Progress progress) throws Exception {
        List<MusicTrack> tracks = piece.tracks();
        SpatialScene fitting = scene.fitting(tracks);
        Map<String, String> stems = stems(directory);
        List<Path> files = new ArrayList<Path>();
        List<SoundSourcePlacement> placements = new ArrayList<SoundSourcePlacement>();
        boolean heard = false;
        for (MusicTrack track : tracks) {
            Path file = stem(directory, track.id(), stems);
            SoundSourcePlacement placement = fitting.placement(track.id());
            if (only != null) {
                // Alone means: the others are silent, and the one is heard even if it is muted in the mix.
                placement = placement.withMuted(!only.equals(track.id()));
            }
            if (!placement.muted() && !Files.isRegularFile(file)) {
                throw new IOException("Die Spur " + track.id() + " ist noch nicht gerendert: " + file.getFileName());
            }
            heard |= !placement.muted();
            files.add(file);
            placements.add(placement);
        }
        progress.update("Mische " + tracks.size() + " Spuren auf der Bühne · " + fitting.room().name()
                + (heard ? "" : " · alle Spuren sind stumm") + " ...");
        double secondsPerBar = piece.secondsPerBar();
        StageAutomation automation = piece.levels(placements).movingAs(piece.movements(directory, progress));
        Path fresh = target.resolveSibling("mix-" + System.nanoTime() + ".part");
        try {
            mixer.withAutomation(automation).mix(files, placements, fitting.room(), secondsPerBar, fitting.masterDb(),
                    fresh, bits);
            Files.move(fresh, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(fresh);
        }
        return target;
    }

    /** How the tracks move according to the mix document of the piece, along the time map of its model. */
    static StageAutomation movements(Path directory, TimeMap time, Progress progress) {
        MixScene document;
        try {
            document = MixJson.load(directory);
        } catch (IOException | RuntimeException unreadable) {
            progress.update("mix.json ist nicht lesbar; die Bühne wird ohne ihre Bewegungen gemischt.");
            return StageAutomation.NONE;
        }
        return document == null || document.automation().isEmpty() ? StageAutomation.NONE
                : StageAutomation.of(document, time);
    }

    /**
     * How the tracks move according to the mix document of the piece: place and room share over
     * time, for the tracks named as the owners of their channels. The time map of the model is
     * taken while it has the tempo of the arrangement the stems were rendered from; otherwise one
     * tempo. A piece without {@code mix.json}, or with one that cannot be read, does not move.
     */
    static StageAutomation movements(Path directory, int tempo, Progress progress) {
        MixScene document;
        try {
            document = MixJson.load(directory);
        } catch (IOException | RuntimeException unreadable) {
            progress.update("mix.json ist nicht lesbar; die Bühne wird ohne ihre Bewegungen gemischt.");
            return StageAutomation.NONE;
        }
        if (document == null || document.automation().isEmpty()) {
            return StageAutomation.NONE;
        }
        // Seconds depend on the tempo alone, so one tempo is enough when the model does not fit.
        TimeMap time = TimeMap.of(tempo);
        if (Files.isRegularFile(directory.resolve(CompositionJson.FILE_NAME))) {
            try {
                Composition model = CompositionJson.load(directory);
                if (model.timeMap().tempoAt(0) == tempo) {
                    time = model.timeMap();
                }
            } catch (IOException | RuntimeException unreadable) {
                // then the tempo of the arrangement, as the stems were rendered
            }
        }
        return StageAutomation.of(document, time);
    }

    /** Notes which file holds the sound of each track, for the mixes that follow without rendering. */
    public static void keepStems(Path directory, List<String> trackIds, Path[] files) throws IOException {
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < trackIds.size(); i++) {
            lines.add(trackIds.get(i) + "\t" + files[i].getFileName());
        }
        Files.write(directory.resolve(STEMS_FILE), lines, StandardCharsets.UTF_8);
    }

    /**
     * Notes which file holds the sound of each track, as a path relative to the folder of the
     * piece; the stem cache keeps its files under {@code render/stems/}.
     */
    public static void keepStems(Path directory, Map<String, Path> files) throws IOException {
        Path base = directory.toAbsolutePath().normalize();
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<String, Path> file : files.entrySet()) {
            lines.add(file.getKey() + "\t"
                    + base.relativize(file.getValue().toAbsolutePath().normalize()).toString().replace('\\', '/'));
        }
        Files.write(directory.resolve(STEMS_FILE), lines, StandardCharsets.UTF_8);
    }

    /** The file with the sound of a track: the one the last rendering named, else the sampled one, else the Java sound. */
    public static Path stem(Path directory, String trackId) {
        Map<String, String> stems;
        try {
            stems = stems(directory);
        } catch (IOException unreadable) {
            stems = new LinkedHashMap<String, String>();
        }
        return stem(directory, trackId, stems);
    }

    private static Path stem(Path directory, String trackId, Map<String, String> stems) {
        String named = stems.get(trackId);
        // A name, or a path below the folder of the piece (the stem cache), never one that leaves it.
        if (named != null && named.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")
                && !named.matches("(.*/)?\\.\\.(/.*)?") && Files.isRegularFile(directory.resolve(named))) {
            return directory.resolve(named);
        }
        Path sampled = directory.resolve(trackId + "-sampled.wav");
        return Files.isRegularFile(sampled) ? sampled : directory.resolve(trackId + ".wav");
    }

    private static Map<String, String> stems(Path directory) throws IOException {
        Map<String, String> stems = new LinkedHashMap<String, String>();
        Path file = directory.resolve(STEMS_FILE);
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] fields = line.split("\\t");
                if (fields.length == 2) {
                    stems.put(fields[0], fields[1]);
                }
            }
        }
        return stems;
    }

    /**
     * A track got another name: what is written and rendered of it goes with it, so that its
     * notes and its sound stay. The written arrangement, the note of which file holds its sound,
     * and its sound files are renamed; the kept answers of the AI stay as they are.
     */
    public static void rename(Path directory, String from, String to) throws IOException {
        Path arrangement = directory.resolve("arrangement.tsv");
        if (Files.isRegularFile(arrangement)) {
            Files.write(arrangement, ArrangementFile.renameTrack(
                    Files.readAllLines(arrangement, StandardCharsets.UTF_8), from, to), StandardCharsets.UTF_8);
        }
        for (String ending : new String[]{".wav", "-sampled.wav", "-isolated.mid"}) {
            Path old = directory.resolve(from + ending);
            if (Files.isRegularFile(old)) {
                Files.move(old, directory.resolve(to + ending), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path stems = directory.resolve(STEMS_FILE);
        if (Files.isRegularFile(stems)) {
            List<String> lines = new ArrayList<String>();
            for (String line : Files.readAllLines(stems, StandardCharsets.UTF_8)) {
                String[] fields = line.split("\\t");
                if (fields.length == 2 && fields[0].equals(from)) {
                    String file = fields[1].equals(from + ".wav") ? to + ".wav"
                            : fields[1].equals(from + "-sampled.wav") ? to + "-sampled.wav" : fields[1];
                    line = to + "\t" + file;
                }
                lines.add(line);
            }
            Files.write(stems, lines, StandardCharsets.UTF_8);
        }
    }

    /** The tracks of a written arrangement with their roles; an arrangement without roles gets them from its names. */
    public static List<MusicTrack> tracks(Path arrangement) throws IOException {
        List<MusicTrack> tracks = new ArrayList<MusicTrack>();
        for (ArrangementFile.Track track : ArrangementFile.read(arrangement).tracks()) {
            String role = track.role() != null && MusicTrack.ROLES.contains(track.role().trim()) ? track.role().trim()
                    : track.onDrumChannel() ? MusicTrack.DRUMS
                    : MusicTrack.ROLES.contains(track.id().trim()) ? track.id().trim() : MusicTrack.HARMONY;
            int program = Math.max(0, Math.min(127, track.programNumber().orElse(0)));
            tracks.add(new MusicTrack(track.id(), role, track.id(), program, "builtin"));
        }
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("Arrangement must contain at least one track.");
        }
        return tracks;
    }

    /** How long one bar of a written arrangement lasts: its meter at its tempo (4/4 for pieces from before F2). */
    public static double secondsPerBar(Path arrangement) throws IOException {
        ArrangementFile file = ArrangementFile.read(arrangement);
        return file.quartersPerBar() * 60.0 / file.tempoOrDefault();
    }

    /** The tempo of a written arrangement, kept within 20..400; 108 if it names none that can be read. */
    public static int tempo(Path arrangement) throws IOException {
        return ArrangementFile.read(arrangement).tempoOrDefault();
    }
}
