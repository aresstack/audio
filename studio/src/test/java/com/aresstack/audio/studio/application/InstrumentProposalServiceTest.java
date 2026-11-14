package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Let the user inspect and modify a cached AI instrument suggestion without using tokens. */
public final class InstrumentProposalServiceTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void loadsAiProposalWithoutGeneratingAnyNotesAndAllowsManualChanges() throws Exception {
        Path song = folder.newFolder("proposal").toPath();
        JsonObject proposal = new JsonObject();
        JsonArray entries = new JsonArray();
        entries.add(instrument("clarinet", "melody", "Warm clarinet", 71));
        entries.add(instrument("harp", "harmony", "Concert harp", 46));
        entries.add(instrument("pizzicato", "texture", "Pizzicato strings", 45));
        proposal.add("tracks", entries);
        Files.write(song.resolve("instrument-proposal.json"),
                proposal.toString().getBytes(StandardCharsets.UTF_8));
        KipitzChatClient offline = new KipitzChatClient(
                "http://127.0.0.1:1/v1/chat/completions", "", "local", "", 8080);

        TrackArrangement proposalOnly = new InstrumentProposalService(offline, 4096)
                .propose("Mysterious forest at dusk", song, message -> { });

        Assert.assertEquals(3, proposalOnly.tracks().size());
        Assert.assertEquals("clarinet", proposalOnly.tracks().get(0).id());
        Assert.assertFalse(Files.exists(song.resolve("arrangement.tsv")));
        Assert.assertFalse(Files.exists(song.resolve("composition-plan.json")));

        java.util.List<MusicTrack> edited = new java.util.ArrayList<MusicTrack>(
                proposalOnly.tracks());
        edited.remove(1);
        edited.add(new MusicTrack("bassoon", MusicTrack.BASS, "Bassoon", 70, "builtin"));
        TrackArrangement manualChange = new TrackArrangement(edited);
        new TrackArrangementStore().save(song, manualChange);
        Assert.assertEquals("bassoon", new TrackArrangementStore().load(song)
                .tracks().get(2).id());
    }

    private static JsonObject instrument(String id, String role, String name, int program) {
        JsonObject instrument = new JsonObject();
        instrument.addProperty("id", id);
        instrument.addProperty("role", role);
        instrument.addProperty("instrument", name);
        instrument.addProperty("program", program);
        return instrument;
    }
}
