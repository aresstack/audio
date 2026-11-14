package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Offer a user-reviewable instrumentation proposal before generating any musical notes. */
public final class InstrumentProposalService {
    private final KipitzChatClient client;
    private final int maxTokens;

    public InstrumentProposalService(KipitzChatClient client, int maxTokens) {
        this.client = client;
        this.maxTokens = maxTokens;
    }

    public TrackArrangement propose(String description, Path directory,
                                    GemmaMusicComposer.Progress progress) throws IOException {
        progress.update("KI entwirft eine Instrumentenbesetzung ...");
        String prompt = "Suggest an ORIGINAL instrumental arrangement for this music description: "
                + description
                + "\nReturn one JSON object with an array 'tracks'. Each track must contain "
                + "id (lowercase ASCII a-z, digits, _ or -; start with letter; unique), "
                + "role (one of melody, harmony, bass, drums, texture), "
                + "instrument (short human-readable name), program (zero-based General MIDI 0..127). "
                + "Create 2 to 8 complementary tracks, at most one drums role. "
                + "Choose actual instruments and GM programs suitable for the mood; "
                + "do not repeat the same orchestral role unnecessarily. "
                + "Use the GM drum kit on channel 10 for drums, with program=0. "
                + "Do not include libraryId, channel numbers, notes or commentary. Return only JSON.";
        JsonObject response = client.generate("Return valid JSON only, no Markdown.",
                prompt, Math.min(maxTokens, 4096),
                directory.resolve("instrument-proposal.json"));
        try {
            JsonArray options = response.getAsJsonArray("tracks");
            if (options.size() < 1 || options.size() > 16) {
                throw new IOException("The proposed arrangement must have 1 to 16 instruments.");
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
            return proposal;
        } catch (RuntimeException invalid) {
            throw new IOException("The AI returned an invalid instrument proposal; original JSON preserved.",
                    invalid);
        }
    }
}
