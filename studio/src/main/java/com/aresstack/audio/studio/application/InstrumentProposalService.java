package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.progress.Progress;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;

/** Offer a user-reviewable instrumentation proposal before generating any musical notes. */
public final class InstrumentProposalService {
    private final ChatModel client;
    private final int maxTokens;

    public InstrumentProposalService(ChatModel client, int maxTokens) {
        this.client = client;
        this.maxTokens = maxTokens;
    }

    /** The instruments the AI proposes, and where it would put them on the stage. */
    public static final class Staged {
        private final TrackArrangement tracks;
        private final SpatialScene scene;

        Staged(TrackArrangement tracks, SpatialScene scene) {
            this.tracks = tracks;
            this.scene = scene;
        }

        public TrackArrangement tracks() { return tracks; }

        /** The proposed stage; a proposal without one gives the usual seating. Nothing of it is taken over by itself. */
        public SpatialScene scene() { return scene; }
    }

    /** How the stage is described to the AI; the mix director asks with the same words. */
    static final String STAGE_FORMAT = "Place the instruments on a stage seen from a listener in front of it. "
            + "Add 'room' (one of studio, chamber, hall, cathedral) and an array 'stage' with one entry per "
            + "track: track (the track id), x (-1 far left to 1 far right), depth (0 front edge to 1 back wall), "
            + "level (dB: the lead around 0, accompaniment -3 to -8, background -8 to -14), "
            + "width (0 a point to 1 wide), reverb (0 dry to 1 very wet). "
            + "Put the bass and the drums near the middle, spread the others to both sides so that the "
            + "stage is balanced, and put what leads in front.";

    public TrackArrangement propose(String description, Path directory,
                                    Progress progress) throws IOException {
        return proposeStaged(description, directory, progress).tracks();
    }

    public Staged proposeStaged(String description, Path directory,
                                Progress progress) throws IOException {
        progress.update("KI entwirft eine Instrumentenbesetzung ...");
        String prompt = "Suggest an ORIGINAL instrumental arrangement for this music description: "
                + description
                + "\nReturn one JSON object with an array 'tracks'. Each track must contain "
                + "id (lowercase ASCII a-z, digits, _ or -; start with letter; unique), "
                + "role (one of melody, harmony, bass, drums, texture), "
                + "instrument (short human-readable name), program (zero-based General MIDI 0..127). "
                + "Create as many complementary tracks as this music needs, a small ensemble or a full "
                + "orchestra, at least 2. "
                + "Choose actual instruments and GM programs suitable for the mood; "
                + "do not repeat the same orchestral role unnecessarily. "
                + "Use the GM drum kit on channel 10 for drums, with program=0. "
                + STAGE_FORMAT
                + " Do not include libraryId, channel numbers, notes or commentary. Return only JSON.";
        final com.aresstack.audio.studio.progress.SketchProgress watched =
                progress instanceof com.aresstack.audio.studio.progress.SketchProgress
                        ? (com.aresstack.audio.studio.progress.SketchProgress) progress : null;
        final String step = "Die KI wählt die Instrumente";
        if (watched != null) {
            watched.preparing(step, 0);
        }
        JsonObject response = client.generate("Return valid JSON only, no Markdown.",
                prompt, Math.min(maxTokens, 4096),
                directory.resolve("instrument-proposal.json"),
                watched == null ? null : text -> watched.preparing(step, text.length()));
        try {
            JsonArray options = response.getAsJsonArray("tracks");
            if (options.size() < 1) {
                throw new IOException("The proposed arrangement has no instruments.");
            }
            List<MusicTrack> tracks = new ArrayList<MusicTrack>();
            for (JsonElement entry : options) {
                JsonObject item = entry.getAsJsonObject();
                tracks.add(new MusicTrack(item.get("id").getAsString(),
                        item.get("role").getAsString(),
                        item.get("instrument").getAsString(),
                        item.get("program").getAsInt(), "builtin"));
            }
            TrackArrangement proposal = new TrackArrangement(tracks);
            progress.update("KI-Vorschlag: " + tracks.size()
                    + " Spuren. Du kannst die Tabelle vor der Komposition bearbeiten.");
            // What the AI says about the stage is read leniently: anything missing or out of range
            // takes its usual value, and a track it forgot gets the usual seat of its role.
            SpatialScene scene;
            try {
                scene = SpatialSceneStore.read(response).fitting(tracks);
            } catch (RuntimeException odd) {
                scene = SpatialScene.standard(tracks);
            }
            return new Staged(proposal, scene);
        } catch (RuntimeException invalid) {
            throw new IOException("The AI returned an invalid instrument proposal; original JSON preserved.",
                    invalid);
        }
    }
}
