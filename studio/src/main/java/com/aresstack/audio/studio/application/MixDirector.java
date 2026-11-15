package com.aresstack.audio.studio.application;

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

    public MixDirector(ChatModel client, int maxTokens) {
        this.client = client;
        this.maxTokens = maxTokens;
    }

    /**
     * Asks for a mix of the written arrangement.
     *
     * @param current the stage as it stands; its locked placements are named as fixed
     * @return the proposed stage for exactly the tracks of the arrangement
     */
    public SpatialScene direct(String description, Path arrangement, SpatialScene current, Path directory,
                               Progress progress) throws IOException {
        progress.update("KI hört sich die Spuren an und schlägt eine Abmischung vor ...");
        List<MusicTrack> tracks = StageMixService.tracks(arrangement);
        Files.deleteIfExists(directory.resolve(FILE_NAME)); // asked anew each time; the answer is kept to be read
        JsonObject response = client.generate("Return valid JSON only, no Markdown.",
                prompt(description, tracks, arrangement, current), Math.min(maxTokens, 4096),
                directory.resolve(FILE_NAME));
        try {
            return SpatialSceneStore.read(response).fitting(tracks);
        } catch (RuntimeException invalid) {
            throw new IOException("The AI returned an invalid mix proposal; original JSON preserved.", invalid);
        }
    }

    static String prompt(String description, List<MusicTrack> tracks, Path arrangement, SpatialScene current)
            throws IOException {
        Map<String, Summary> played = summaries(arrangement);
        StringBuilder text = new StringBuilder("You are the mixing engineer of this instrumental piece: ")
                .append(description).append("\nTempo ").append(StageMixService.tempo(arrangement))
                .append(" BPM, ").append(meterAndKey(arrangement)).append(". The tracks and what they play:\n");
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
    private static String meterAndKey(Path arrangement) throws IOException {
        ArrangementFile file = ArrangementFile.read(arrangement);
        String meter = file.meter().map(m -> m.numerator() + "/" + m.denominator()).orElse("4/4");
        return file.key().map(key -> meter + ", " + key.name()).orElse(meter);
    }

    private static Map<String, Summary> summaries(Path arrangement) throws IOException {
        Map<String, Summary> played = new LinkedHashMap<String, Summary>();
        ArrangementFile file = ArrangementFile.read(arrangement);
        long barTicks = file.barTicks();
        for (ArrangementFile.Note note : file.notes()) {
            Summary summary = played.get(note.trackId());
            if (summary == null) {
                summary = new Summary();
                played.put(note.trackId(), summary);
            }
            int bar = (int) (note.tick() / barTicks);
            summary.notes++;
            summary.lowest = Math.min(summary.lowest, note.pitch());
            summary.highest = Math.max(summary.highest, note.pitch());
            summary.velocity += note.velocity();
            Integer before = summary.bars.get(bar);
            summary.bars.put(bar, before == null ? 1 : before + 1);
        }
        return played;
    }

    private static final class Summary {
        private int notes;
        private int lowest = 127;
        private int highest;
        private int velocity;
        private final TreeMap<Integer, Integer> bars = new TreeMap<Integer, Integer>();

        private String perBar() {
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
