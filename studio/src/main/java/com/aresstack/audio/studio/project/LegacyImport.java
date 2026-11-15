package com.aresstack.audio.studio.project;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.CompositionValidator;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.Meta;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.Voice;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.LevelMode;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.Room;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.score.Score;
import com.aresstack.audio.studio.stage.Performer;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Makes a model of a piece from before the model (architecture review 5.2): the arrangement
 * gives the time map and, for every track, a part and a track of the same id (so that stage
 * and stems still fit) with one clip over the whole piece; {@code tracks.json},
 * {@code composition-plan.json} and {@code song.properties} add what they know; the stage becomes
 * the mix; rendered stems are taken over. Every file but the arrangement may be missing, and so
 * then four quarters to the bar and C major are assumed, which is all the old studio knew.
 *
 * <p>The import is lenient, as the readers of the old studio were: what it cannot take over is
 * left out and reported. The ids it gives are random but follow from the arrangement, so that
 * the same old piece always gives the same model; with an earlier model of the piece, objects
 * that are still there keep their ids.
 */
public final class LegacyImport {
    /** What the import made of the old files. */
    public record Result(Composition composition, MixScene mix, List<StemManifest.Entry> stems, List<String> warnings,
                         Set<String> namedSoundBanks) {
        /**
         * @param namedSoundBanks the parts whose sound bank {@code tracks.json} names itself, even
         *                        "builtin"; the others play the built-in sound only for want of one
         */
        public Result {
            warnings = List.copyOf(warnings);
            stems = List.copyOf(stems);
            namedSoundBanks = Set.copyOf(namedSoundBanks);
        }
    }

    /** The bars of a section that covers the whole of an old piece, at least. */
    private static final int MIN_BARS = 1;

    private final Path source;
    private final Path folder;
    private final Composition previous;
    private final MixScene previousMix;
    private final List<String> warnings = new ArrayList<>();
    private final Set<String> namedSoundBanks = new LinkedHashSet<>();
    private Ids ids;

    private LegacyImport(Path source, Path folder, Composition previous, MixScene previousMix) {
        this.source = source;
        this.folder = folder;
        this.previous = previous;
        this.previousMix = previousMix;
    }

    /**
     * Imports the old piece whose files lie in {@code source} (the folder itself or its
     * {@code legacy/}); rendered files are looked for in {@code folder}. Empty if there is no
     * arrangement, that is no piece yet.
     *
     * @param previous    an earlier model of the piece whose ids are kept where the objects are the same, or {@code null}
     * @param previousMix an earlier mix whose channel ids are kept, or {@code null}
     */
    public static Optional<Result> read(Path source, Path folder, Composition previous, MixScene previousMix)
            throws IOException {
        Path arrangement = source.resolve(ProjectFolder.ARRANGEMENT);
        if (!Files.isRegularFile(arrangement)) {
            return Optional.empty();
        }
        return Optional.of(new LegacyImport(source, folder, previous, previousMix).run(Files.readAllBytes(arrangement)));
    }

    /** Imports an old piece from its folder, or from its {@code legacy/} once that exists. */
    public static Optional<Result> read(Path folder) throws IOException {
        return read(ProjectFolder.source(folder), folder, null, null);
    }

    private Result run(byte[] tsv) throws IOException {
        ids = Ids.seeded(seed(tsv));
        reserve();
        ArrangementFile file = ArrangementFile.parse(new String(tsv, StandardCharsets.UTF_8).lines().toList());
        for (ArrangementFile.Problem problem : file.problems()) {
            warn("arrangement.tsv, Zeile " + problem.line() + " übergangen (" + problem.reason() + "): " + problem.text());
        }
        TimeMap time = timeMap(file);
        Map<String, JsonObject> listed = tracksJson();
        JsonObject plan = json(ProjectFolder.PLAN);
        Properties song = properties();

        // The tracks of the file, each once, with their notes, in the order of the file.
        Map<String, ArrangementFile.Track> lines = new LinkedHashMap<>();
        Map<String, List<ArrangementFile.Note>> notes = new LinkedHashMap<>();
        for (ArrangementFile.Track line : file.tracks()) {
            if (!Ids.valid(line.id())) {
                warn("arrangement.tsv, Zeile " + line.line() + ": Spur mit ungültiger Kennung übergangen: " + line.id());
            } else if (lines.containsKey(line.id())) {
                warn("arrangement.tsv, Zeile " + line.line() + ": Spur " + line.id() + " doppelt; es gilt die erste Zeile.");
            } else {
                lines.put(line.id(), line);
                notes.put(line.id(), new ArrayList<>());
            }
        }
        long end = 0;
        for (ArrangementFile.Note note : file.notes()) {
            List<ArrangementFile.Note> own = notes.get(note.trackId());
            if (own == null) {
                warn("arrangement.tsv, Zeile " + note.line() + ": Note einer unbekannten Spur übergangen: " + note.text());
            } else if (note.tick() < 0 || note.length() < 1 || note.pitch() < 0 || note.pitch() > 127
                    || note.velocity() < 1 || note.velocity() > 127) {
                warn("arrangement.tsv, Zeile " + note.line() + ": ungültige Note übergangen: " + note.text());
            } else {
                own.add(note);
                end = Math.max(end, note.tick() + note.length());
            }
        }

        int bars = Math.max(MIN_BARS, time.barsCovering(end));
        if (file.bars().isPresent()) {
            // Since F2 the file may say how long the piece is.
            if (file.bars().getAsInt() < bars) {
                warn("arrangement.tsv nennt " + file.bars().getAsInt() + " Takte, die Noten reichen bis Takt "
                        + bars + "; es gilt " + bars + ".");
            }
            bars = Math.max(bars, file.bars().getAsInt());
            planBars(plan, time); // only for its warning
        } else {
            int planned = planBars(plan, time);
            if (planned > bars) {
                bars = planned;
            }
            // Without a bars line a piece in four quarters and C major sounds at least eight bars long.
            bars = ArrangementFile.defaultBars(bars, time.meters().get(0).barTicks() == 1920
                    && time.meters().get(0).numerator() == 4 && time.keys().get(0).key().equals(Key.C_MAJOR));
        }
        long length = time.tickOfBar(bars);

        List<Part> parts = new ArrayList<>();
        List<Track> tracks = new ArrayList<>();
        Map<String, String> stageRoles = new LinkedHashMap<>();
        for (ArrangementFile.Track line : lines.values()) {
            JsonObject entry = listed.get(line.id());
            Part part = part(line, notes.get(line.id()), entry);
            parts.add(part);
            tracks.add(track(line.id(), part, notes.get(line.id()), length));
            stageRoles.put(line.id(), stageRole(line));
        }
        for (String id : listed.keySet()) {
            if (!lines.containsKey(id)) {
                warn("tracks.json nennt die Spur " + id + ", die im Arrangement fehlt; sie wird nicht übernommen.");
            }
        }
        parts = withLegacyChannels(parts, lines);

        Section whole = new Section(previousSectionId(), "A", null, 0, length, null, chords(plan), null);
        Composition composition = new Composition(meta(plan, song), time, List.of(whole), parts, tracks, List.of());
        for (CompositionValidator.Violation violation : CompositionValidator.validate(composition)) {
            warn("Modell: " + violation);
        }
        MixScene mix = mix(stageRoles, time);
        return new Result(composition, mix, stems(composition), warnings, namedSoundBanks);
    }

    private Part part(ArrangementFile.Track line, List<ArrangementFile.Note> notes, JsonObject listed) {
        String role = line.role() == null || line.role().trim().isEmpty() ? null : line.role().trim();
        if (role != null && !Role.ALL.contains(role)) {
            warn("Spur " + line.id() + ": unbekannte Rolle \"" + role + "\" übernommen, wie sie geschrieben ist.");
        }
        int program = line.programNumber().orElse(-1);
        if (program < 0 || program > 127) {
            warn("Spur " + line.id() + ": Programm \"" + line.program() + "\" ist kein General-MIDI-Programm; es gilt 0.");
            program = 0;
        }
        String instrument = text(listed, "instrument");
        String library = text(listed, "libraryId");
        if (library != null) {
            namedSoundBanks.add(line.id());
        }
        String listedRole = text(listed, "role");
        if (listedRole != null && !listedRole.equals(role) && !(role == null && listedRole.equals(line.id()))) {
            warn("Spur " + line.id() + ": tracks.json nennt die Rolle " + listedRole + ", das Arrangement "
                    + (role == null ? "keine" : role) + "; es gilt das Arrangement.");
        }
        // The voices: the heuristic of the score decides once, and the model keeps its answer.
        List<long[]> written = new ArrayList<>();
        for (ArrangementFile.Note note : notes) {
            written.add(new long[]{note.tick(), note.length(), note.pitch()});
        }
        boolean struck = line.onDrumChannel() || Role.DRUMS.equals(role);
        int[] voiceOf = Score.voicesOf(written, struck);
        boolean two = false;
        for (int voice : voiceOf) {
            two |= voice == 1;
        }
        Part before = previous == null ? null : previous.part(line.id());
        List<Voice> voices = new ArrayList<>();
        voices.add(new Voice(voiceId(before, 0), "Stimme 1", null, two ? Voice.Stem.UP : Voice.Stem.AUTO));
        if (two) {
            voices.add(new Voice(voiceId(before, 1), "Stimme 2", null, Voice.Stem.DOWN));
        }
        pendingVoices.put(line.id(), voiceOf);
        return new Part(line.id(), before == null ? line.id() : before.name(), role, instrument, program, library,
                Part.StaffLayout.SINGLE, voices, null);
    }

    private final Map<String, int[]> pendingVoices = new HashMap<>();

    private Track track(String id, Part part, List<ArrangementFile.Note> written, long length) {
        Track before = previous == null ? null : previous.track(id);
        MidiClip oldClip = before == null ? null : before.clips().stream()
                .filter(MidiClip.class::isInstance).map(MidiClip.class::cast).findFirst().orElse(null);
        Map<String, Deque<String>> oldNotes = new HashMap<>();
        if (oldClip != null) {
            for (Note note : oldClip.notes()) {
                oldNotes.computeIfAbsent(key(note.tick(), note.length(), note.pitch(), note.velocity()),
                        k -> new ArrayDeque<>()).add(note.id());
            }
        }
        int[] voiceOf = pendingVoices.remove(id);
        List<Note> notes = new ArrayList<>();
        for (int i = 0; i < written.size(); i++) {
            ArrangementFile.Note line = written.get(i);
            Deque<String> reuse = oldNotes.get(key(line.tick(), line.length(), line.pitch(), line.velocity()));
            String noteId = reuse != null && !reuse.isEmpty() ? reuse.poll() : fresh();
            notes.add(new Note(noteId, line.tick(), line.length(), line.pitch(), line.velocity(),
                    part.voices().get(voiceOf[i]).id()));
        }
        String clipId = oldClip != null ? oldClip.id() : fresh();
        return new Track(id, before == null ? id : before.name(), Track.Kind.MIDI, part.id(),
                List.of(new MidiClip(clipId, 0, length, null, notes)));
    }

    /** Keeps the channel an old track was written with where the studio would now choose another one. */
    private List<Part> withLegacyChannels(List<Part> parts, Map<String, ArrangementFile.Track> lines) {
        Map<String, Integer> derived = TsvExport.derivedChannels(parts);
        List<Part> kept = new ArrayList<>();
        for (Part part : parts) {
            ArrangementFile.Track line = lines.get(part.id());
            int written = line.channelNumber().orElse(-1);
            Integer legacy = null;
            if (written < 0 || written > 15) {
                warn("Spur " + part.id() + ": Kanal \"" + line.channel() + "\" ist kein MIDI-Kanal; es gilt "
                        + derived.get(part.id()) + ".");
            } else if (written != derived.get(part.id())) {
                legacy = written;
            }
            kept.add(new Part(part.id(), part.name(), part.role(), part.instrument(), part.program(), part.libraryId(),
                    part.staffLayout(), part.voices(), legacy));
        }
        return kept;
    }

    /** The role the stage seats a track by, as the old studio decided it. */
    private static String stageRole(ArrangementFile.Track line) {
        String role = line.role() == null ? null : line.role().trim();
        return role != null && Role.ALL.contains(role) ? role
                : line.onDrumChannel() ? Role.DRUMS
                : Role.ALL.contains(line.id().trim()) ? line.id().trim() : Role.HARMONY;
    }

    private record Seat(String id, String role) implements Performer {
    }

    /** The stage as the old studio mixed it: the saved one fitted to the tracks, else the usual seating. */
    private MixScene mix(Map<String, String> stageRoles, TimeMap time) {
        List<Seat> seats = new ArrayList<>();
        stageRoles.forEach((id, role) -> seats.add(new Seat(id, role)));
        SpatialScene scene = null;
        JsonObject saved = json(ProjectFolder.SCENE);
        if (saved != null) {
            scene = SpatialSceneStore.read(saved);
            for (SoundSourcePlacement placement : scene.placements()) {
                if (!stageRoles.containsKey(placement.trackId())) {
                    warn("spatial-scene.json: Platz der Spur " + placement.trackId()
                            + ", die es nicht mehr gibt, nicht übernommen.");
                }
            }
        }
        SpatialScene fitted = scene == null ? SpatialScene.standard(seats) : scene.fitting(seats);
        long barTicks = time.meters().get(0).barTicks();
        List<MixChannel> channels = new ArrayList<>();
        List<AutomationLane> automation = new ArrayList<>();
        for (SoundSourcePlacement placement : fitted.placements()) {
            MixChannel.Owner owner = MixChannel.Owner.part(placement.trackId());
            MixChannel before = previousMix == null ? null : previousMix.channelOf(owner);
            MixChannel channel = new MixChannel(before == null ? fresh() : before.id(), owner, placement.x(),
                    placement.depth(), placement.levelDb(), placement.width(), placement.reverb(), placement.muted(),
                    placement.locked());
            channels.add(channel);
            if (!placement.dynamics().isEmpty()) {
                List<AutomationLane.Point> points = new ArrayList<>();
                for (SoundSourcePlacement.LevelPoint point : placement.dynamics()) {
                    points.add(new AutomationLane.Point(Math.round(point.bar() * barTicks), point.db()));
                }
                String laneId = null;
                if (previousMix != null) {
                    for (AutomationLane lane : previousMix.automation()) {
                        if (channel.id().equals(lane.channelId()) && AutomationLane.LEVEL.equals(lane.parameter())) {
                            laneId = lane.id();
                        }
                    }
                }
                automation.add(new AutomationLane(laneId == null ? fresh() : laneId, channel.id(),
                        AutomationLane.LEVEL, points));
            }
        }
        Room room = new Room(fitted.room().kind(), fitted.room().reverbSeconds(), fitted.room().amount());
        // A piece is mixed the old way until its user switched it; the stage file says so.
        return new MixScene(room, fitted.masterDb(), fitted.fixedLevel() ? LevelMode.FIXED : LevelMode.LEGACY_PEAK,
                channels, automation);
    }

    /** The rendered files that are there, each taken over for the part as it sounds now. */
    private List<StemManifest.Entry> stems(Composition composition) {
        Map<String, String> named = new HashMap<>();
        Path list = folder.resolve(ProjectFolder.STEMS);
        if (Files.isRegularFile(list)) {
            try {
                for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                    String[] fields = line.split("\t");
                    if (fields.length >= 2) {
                        named.put(fields[0], fields[1]);
                    }
                }
            } catch (IOException unreadable) {
                warn("stems.tsv nicht lesbar: " + unreadable.getMessage());
            }
        }
        List<StemManifest.Entry> entries = new ArrayList<>();
        for (Part part : composition.parts()) {
            String digest = StemManifest.digest(composition, part.id());
            Map<String, String> files = new LinkedHashMap<>();
            if (named.containsKey(part.id())) {
                files.put(named.get(part.id()), "stems.tsv");
            }
            files.putIfAbsent(part.id() + "-sampled.wav", "sampled");
            files.putIfAbsent(part.id() + ".wav", "reference");
            files.forEach((name, origin) -> {
                if (!name.contains("/") && !name.contains("\\") && Files.isRegularFile(folder.resolve(name))) {
                    entries.add(new StemManifest.Entry(part.id(), name, origin, StemManifest.ADOPTED, digest));
                }
            });
        }
        return entries;
    }

    private Meta meta(JsonObject plan, Properties song) {
        String name = blankToNull(song.getProperty("name"));
        String title = name != null ? name : text(plan, "title");
        return new Meta(title, null, text(plan, "mood"), blankToNull(song.getProperty("description")),
                song.getProperty("plannedFrom"), text(plan, "arrangementNotes"));
    }

    private double tempo(ArrangementFile file) {
        if (file.tempo().isPresent() && file.tempo().getAsInt() > 0) {
            return file.tempo().getAsInt();
        }
        warn("arrangement.tsv nennt kein lesbares Tempo; es gilt " + ArrangementFile.DEFAULT_TEMPO + ".");
        return ArrangementFile.DEFAULT_TEMPO;
    }

    /**
     * The time map the file says: its tempo and, since F2, its meter and key; four quarters and
     * C major where it names none, as every piece from before.
     */
    private TimeMap timeMap(ArrangementFile file) {
        double tempo = tempo(file);
        int numerator = 4;
        int denominator = 4;
        if (file.meter().isPresent()) {
            ArrangementFile.Meter meter = file.meter().get();
            if (meter.numerator() <= 64 && meter.denominator() <= 64) {
                numerator = meter.numerator();
                denominator = meter.denominator();
            } else {
                warn("arrangement.tsv, Zeile " + meter.line() + ": Taktart " + meter.text() + " übergangen; es gilt 4/4.");
            }
        }
        Key key = Key.C_MAJOR;
        if (file.key().isPresent()) {
            try {
                key = Key.parse(file.key().get().name());
            } catch (IllegalArgumentException odd) {
                warn("arrangement.tsv, Zeile " + file.key().get().line() + ": Tonart übergangen; es gilt C-Dur.");
            }
        }
        return new TimeMap(List.of(new TimeMap.Tempo(0, tempo)), List.of(new TimeMap.Meter(0, numerator, denominator)),
                List.of(new TimeMap.KeyChange(0, key)));
    }

    private int planBars(JsonObject plan, TimeMap time) {
        if (plan == null) {
            return 0;
        }
        String key = text(plan, "key");
        String meter = text(plan, "timeSignature");
        TimeMap.Meter written = time.meters().get(0);
        Key keyOfFile = time.keys().get(0).key();
        boolean sameKey;
        try {
            sameKey = key == null || Key.parse(key).equals(keyOfFile);
        } catch (IllegalArgumentException odd) {
            sameKey = false;
        }
        if (!sameKey || (meter != null && !(written.numerator() + "/" + written.denominator()).equals(meter.trim()))) {
            warn("composition-plan.json nennt " + key + " " + meter + "; arrangement.tsv sagt "
                    + keyOfFile.name() + " " + written.numerator() + "/" + written.denominator() + "; es gilt arrangement.tsv.");
        }
        try {
            return plan.has("bars") ? Math.max(0, Math.min(4096, plan.get("bars").getAsInt())) : 0;
        } catch (RuntimeException odd) {
            return 0;
        }
    }

    private static List<String> chords(JsonObject plan) {
        List<String> chords = new ArrayList<>();
        if (plan != null && plan.has("chords") && plan.get("chords").isJsonArray()) {
            for (JsonElement chord : plan.getAsJsonArray("chords")) {
                if (chord.isJsonPrimitive()) {
                    chords.add(chord.getAsString());
                }
            }
        }
        return chords;
    }

    private Map<String, JsonObject> tracksJson() {
        Map<String, JsonObject> listed = new LinkedHashMap<>();
        JsonObject root = json(ProjectFolder.TRACKS);
        if (root != null && root.has("tracks") && root.get("tracks").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("tracks")) {
                if (element.isJsonObject() && text(element.getAsJsonObject(), "id") != null) {
                    listed.put(text(element.getAsJsonObject(), "id").trim(), element.getAsJsonObject());
                }
            }
        }
        return listed;
    }

    private JsonObject json(String name) {
        Path file = source.resolve(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JsonText.read(file);
        } catch (IOException unreadable) {
            warn(name + " nicht lesbar und übergangen: " + unreadable.getMessage());
            return null;
        }
    }

    private Properties properties() {
        Properties properties = new Properties();
        Path file = source.resolve(ProjectFolder.SONG);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException unreadable) {
                warn("song.properties nicht lesbar und übergangen: " + unreadable.getMessage());
            }
        }
        return properties;
    }

    private String voiceId(Part before, int index) {
        return before != null && before.voices().size() > index ? before.voices().get(index).id() : fresh();
    }

    private String previousSectionId() {
        return previous != null && !previous.sections().isEmpty() ? previous.sections().get(0).id() : fresh();
    }

    /**
     * A new id that no object of the earlier model has: the seeded sequence starts again from the
     * beginning on every import, so an id kept from before could otherwise come round a second time.
     */
    private String fresh() {
        String id = ids.next();
        while (!taken.add(id)) {
            id = ids.next();
        }
        return id;
    }

    /** Every id of the earlier model and the earlier mix, and every id given out since. */
    private final java.util.Set<String> taken = new java.util.HashSet<>();

    private void reserve() {
        if (previous != null) {
            previous.sections().forEach(section -> taken.add(section.id()));
            for (Part part : previous.parts()) {
                part.voices().forEach(voice -> taken.add(voice.id()));
            }
            for (Track track : previous.tracks()) {
                for (com.aresstack.audio.composition.Clip clip : track.clips()) {
                    taken.add(clip.id());
                    if (clip instanceof MidiClip midi) {
                        midi.notes().forEach(note -> taken.add(note.id()));
                    }
                }
            }
        }
        if (previousMix != null) {
            previousMix.channels().forEach(channel -> taken.add(channel.id()));
            previousMix.automation().forEach(lane -> taken.add(lane.id()));
        }
    }

    private void warn(String warning) {
        warnings.add(warning);
    }

    private static String key(long tick, long length, int pitch, int velocity) {
        return tick + "/" + length + "/" + pitch + "/" + velocity;
    }

    private static String text(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) {
            return null;
        }
        return blankToNull(object.get(name).getAsString());
    }

    private static String blankToNull(String text) {
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    private static long seed(byte[] bytes) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes)).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
