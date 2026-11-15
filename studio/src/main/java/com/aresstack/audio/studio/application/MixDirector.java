package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The last step before the tracks are mixed: the AI is told what each track plays
 * and says how loud each should be, also over the course of the piece, where it
 * should stand and in what room. What it says is a proposal; the studio decides
 * what is taken over.
 */
public final class MixDirector {
    public static final String FILE_NAME = "mix-proposal.json";
    private final ChatModel client;
    private final int maxTokens;
    private java.util.function.Consumer<SpatialScene> watching;

    public MixDirector(ChatModel client, int maxTokens) {
        this.client = client;
        this.maxTokens = maxTokens;
    }

    /**
     * Who sees the mix while the answer arrives: the stage as it stands with every placement the
     * AI has written so far. Only for looking; the proposal is the one {@code direct} returns.
     */
    public MixDirector watching(java.util.function.Consumer<SpatialScene> seen) {
        this.watching = seen;
        return this;
    }

    /**
     * The stage as far as a mix proposal has arrived: {@code current} with the placements that are
     * whole already (the last one may still be written and is left out), the room and the master
     * once they are named; {@code null} if nothing new can be read yet.
     */
    static SpatialScene seenMix(String text, SpatialScene current, List<MusicTrack> tracks) {
        try {
            JsonObject seen = com.aresstack.audio.studio.ai.PartialJson.read(text);
            if (seen == null) {
                return null;
            }
            boolean whole = text.trim().endsWith("}") && seen.equals(com.google.gson.JsonParser.parseString(
                    text.substring(text.indexOf('{'))));
            SpatialScene read = SpatialSceneStore.read(seen);
            SpatialScene stage = current.fitting(tracks);
            List<SoundSourcePlacement> placed = read.placements();
            int count = whole ? placed.size() : placed.size() - 1;
            for (int i = 0; i < count; i++) {
                if (stage.placement(placed.get(i).trackId()) != null) {
                    stage = stage.with(placed.get(i));
                }
            }
            if (seen.has("room") && (count > 0 || whole)) {
                stage = stage.inRoom(read.room());
            }
            if (seen.has("master")) {
                stage = stage.withMaster(read.masterDb());
            }
            return stage;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Asks for a mix of the written arrangement.
     *
     * @param current the stage as it stands; its locked placements are named as fixed
     * @return the proposed stage for exactly the tracks of the arrangement
     */
    public SpatialScene direct(String description, Path arrangement, SpatialScene current, Path directory,
                               Progress progress) throws IOException {
        List<MusicTrack> tracks = StageMixService.tracks(arrangement);
        return direct(tracks, prompt(description, tracks, arrangement, current), current, directory, progress);
    }

    /**
     * Asks for a mix of a piece with a model: what each part plays is read from the model, with
     * its time map, and no written arrangement is needed.
     */
    public SpatialScene direct(String description, Composition model, SpatialScene current, Path directory,
                               Progress progress) throws IOException {
        List<MusicTrack> tracks = StageMixService.Piece.of(model).tracks();
        return direct(tracks, prompt(description, tracks, model, current), current, directory, progress);
    }

    private SpatialScene direct(final List<MusicTrack> tracks, String prompt, final SpatialScene current,
                                Path directory, Progress progress) throws IOException {
        final java.util.function.Consumer<SpatialScene> watcher = watching;
        progress.update("KI hört sich die Spuren an und schlägt eine Abmischung vor ...");
        Files.deleteIfExists(directory.resolve(FILE_NAME)); // asked anew each time; the answer is kept to be read
        JsonObject response = client.generate("Return valid JSON only, no Markdown.", prompt,
                Math.min(maxTokens, 4096), directory.resolve(FILE_NAME),
                watcher == null || current == null ? null : new java.util.function.Consumer<String>() {
                    private long told;

                    @Override
                    public void accept(String text) {
                        long now = System.currentTimeMillis();
                        if (now - told < 200) {
                            return; // a few pictures a second are enough
                        }
                        SpatialScene stage = seenMix(text, current, tracks);
                        if (stage != null) {
                            told = now;
                            watcher.accept(stage);
                        }
                    }
                });
        try {
            return SpatialSceneStore.read(response).fitting(tracks);
        } catch (RuntimeException invalid) {
            throw new IOException("The AI returned an invalid mix proposal; original JSON preserved.", invalid);
        }
    }

    static String prompt(String description, List<MusicTrack> tracks, Path arrangement, SpatialScene current)
            throws IOException {
        return prompt(description, tracks, StageMixService.tempo(arrangement), meterAndKey(arrangement),
                summaries(arrangement), current);
    }

    /** The prompt for a piece with a model: tempo, meter and key at its start, and the notes of every part. */
    static String prompt(String description, List<MusicTrack> tracks, Composition model, SpatialScene current) {
        TimeMap time = model.timeMap();
        return prompt(description, tracks, (int) Math.round(time.tempoAt(0)), meterAndKey(model),
                summaries(tracks, model), current);
    }

    /** What each part of a model plays, by track id; also told to the AI that proposes movements. */
    static Map<String, Summary> summaries(List<MusicTrack> tracks, Composition model) {
        TimeMap time = model.timeMap();
        Map<String, Summary> played = new LinkedHashMap<String, Summary>();
        for (MusicTrack track : tracks) {
            Summary summary = new Summary();
            for (Composition.PlacedNote placed : model.notesOf(track.id())) {
                summary.add(time.barOf(placed.tick()), placed.note().pitch(), placed.note().velocity());
            }
            played.put(track.id(), summary);
        }
        return played;
    }

    /** Meter and key at the start of a model, as {@code "3/4, D minor"}. */
    static String meterAndKey(Composition model) {
        TimeMap time = model.timeMap();
        TimeMap.Meter meter = time.meterAt(0);
        return meter.numerator() + "/" + meter.denominator() + ", " + time.keyAt(0).name();
    }

    private static String prompt(String description, List<MusicTrack> tracks, int tempo, String meterAndKey,
                                 Map<String, Summary> played, SpatialScene current) {
        StringBuilder text = new StringBuilder("You are the mixing engineer of this instrumental piece: ")
                .append(description).append("\nTempo ").append(tempo)
                .append(" BPM, ").append(meterAndKey).append(". The tracks and what they play:\n");
        for (MusicTrack track : tracks) {
            Summary summary = played.get(track.id());
            text.append("- ").append(track.id()).append(" (").append(track.role()).append(", General MIDI program ")
                    .append(track.program()).append("): ");
            if (summary == null || summary.notes == 0) {
                text.append("no notes");
            } else {
                text.append(summary.notes).append(" notes, pitches ").append(summary.lowest).append("-")
                        .append(summary.highest).append(", mean velocity ").append(summary.velocity / summary.notes)
                        .append(", notes per bar ").append(summary.perBar());
            }
            SoundSourcePlacement placement = current == null ? null : current.placement(track.id());
            if (placement != null && placement.locked()) {
                text.append(" — FIXED by the user at x=").append(placement.x()).append(", depth=")
                        .append(placement.depth()).append(", level=").append(placement.levelDb())
                        .append("; mix the others around it");
            }
            text.append('\n');
        }
        text.append("Return one JSON object. ").append(InstrumentProposalService.STAGE_FORMAT)
                .append(" Each stage entry may also have 'dynamics': an array of points {bar, db} (bar counted "
                        + "from 0, db between -12 and 4 relative to the track's level) between which the level "
                        + "moves in a straight line: let the piece breathe — bring the lead forward where it "
                        + "plays, take the accompaniment back under it, build towards the climax and relax at "
                        + "the end. Leave 'dynamics' out for a track that should stay as it is. "
                        + "Use the whole range of levels: a mix in which everything is equally loud is flat. "
                        + "No commentary. Return only JSON.");
        return text.toString();
    }

    /** {@code "4/4"} for a piece from before F2; since then also the key, as {@code "3/4, D minor"}. */
    static String meterAndKey(Path arrangement) throws IOException {
        ArrangementFile file = ArrangementFile.read(arrangement);
        String meter = file.meter().map(m -> m.numerator() + "/" + m.denominator()).orElse("4/4");
        return file.key().map(key -> meter + ", " + key.name()).orElse(meter);
    }

    /** What each track of a written arrangement plays, by track id; also told to the AI that proposes movements. */
    static Map<String, Summary> summaries(Path arrangement) throws IOException {
        Map<String, Summary> played = new LinkedHashMap<String, Summary>();
        ArrangementFile file = ArrangementFile.read(arrangement);
        long barTicks = file.barTicks();
        for (ArrangementFile.Note note : file.notes()) {
            Summary summary = played.get(note.trackId());
            if (summary == null) {
                summary = new Summary();
                played.put(note.trackId(), summary);
            }
            summary.add((int) (note.tick() / barTicks), note.pitch(), note.velocity());
        }
        return played;
    }

    static final class Summary {
        int notes;
        int lowest = 127;
        int highest;
        int velocity;
        final TreeMap<Integer, Integer> bars = new TreeMap<Integer, Integer>();

        private void add(int bar, int pitch, int loudness) {
            notes++;
            lowest = Math.min(lowest, pitch);
            highest = Math.max(highest, pitch);
            velocity += loudness;
            Integer before = bars.get(bar);
            bars.put(bar, before == null ? 1 : before + 1);
        }

        String perBar() {
            List<String> counts = new ArrayList<String>();
            int last = Math.min(63, bars.isEmpty() ? 0 : bars.lastKey());
            for (int bar = 0; bar <= last; bar++) {
                Integer count = bars.get(bar);
                counts.add(String.valueOf(count == null ? 0 : count));
            }
            return counts.toString();
        }
    }
}
