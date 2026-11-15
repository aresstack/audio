package com.aresstack.audio.studio.application;

import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
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
 */
public final class StageMixService {
    public static final String MIX_FILE = "arrangement-instruments.wav";
    /** The track that is listened to alone, as it stands on the stage. */
    public static final String SOLO_FILE = "stage-solo.wav";
    /** Which file holds the sound of each track, as the last rendering left it. */
    public static final String STEMS_FILE = "stems.tsv";

    private final SpatialSceneStore store = new SpatialSceneStore();
    private final SpatialStemMixer mixer = new SpatialStemMixer();

    /** The stage of a piece: the saved one, or the usual seating; always for exactly the tracks of the arrangement. */
    public SpatialScene scene(Path arrangement, Path directory) throws IOException {
        SpatialScene saved = store.load(directory);
        List<MusicTrack> tracks = tracks(arrangement);
        return saved == null ? SpatialScene.standard(tracks) : saved.fitting(tracks);
    }

    /** Mixes the rendered tracks of the piece on its stage and returns the file of the mix. */
    public Path mix(Path arrangement, Path directory, GemmaMusicComposer.Progress progress) throws Exception {
        return mix(arrangement, directory, scene(arrangement, directory), progress);
    }

    public Path mix(Path arrangement, Path directory, SpatialScene scene, GemmaMusicComposer.Progress progress)
            throws Exception {
        return mix(arrangement, directory, scene, null, directory.resolve(MIX_FILE), 16, progress);
    }

    /** One track alone, but as it stands on the stage: with its level, its place and the room. */
    public Path solo(Path arrangement, Path directory, SpatialScene scene, String trackId) throws Exception {
        return mix(arrangement, directory, scene, trackId, directory.resolve(SOLO_FILE), 16, message -> { });
    }

    /** Writes the mix on the stage to a file of the user's choice, with 16 or 24 bits. */
    public Path export(Path arrangement, Path directory, SpatialScene scene, Path target, int bits) throws Exception {
        return mix(arrangement, directory, scene, null, target, bits, message -> { });
    }

    private Path mix(Path arrangement, Path directory, SpatialScene scene, String only, Path target, int bits,
                     GemmaMusicComposer.Progress progress) throws Exception {
        List<MusicTrack> tracks = tracks(arrangement);
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
        Path fresh = target.resolveSibling("mix-" + System.nanoTime() + ".part");
        try {
            mixer.mix(files, placements, fitting.room(), 4 * 60.0 / tempo(arrangement), fitting.masterDb(), fresh, bits);
            Files.move(fresh, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(fresh);
        }
        return target;
    }

    /** Notes which file holds the sound of each track, for the mixes that follow without rendering. */
    public static void keepStems(Path directory, List<String> trackIds, Path[] files) throws IOException {
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < trackIds.size(); i++) {
            lines.add(trackIds.get(i) + "\t" + files[i].getFileName());
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
        if (named != null && named.matches("[A-Za-z0-9_.-]+") && Files.isRegularFile(directory.resolve(named))) {
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

    /** The tempo of a written arrangement, kept within 20..400; 108 if it names none that can be read. */
    public static int tempo(Path arrangement) throws IOException {
        return ArrangementFile.read(arrangement).tempoOrDefault();
    }
}
