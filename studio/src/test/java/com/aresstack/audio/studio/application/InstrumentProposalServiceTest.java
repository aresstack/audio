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
                "http://127.0.0.1:1/v1/chat/completions", "", "local", "", 8080)
                .legacyModel("local"); // the kept answers below were written for this model

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

    /** Answers with six instruments and remembers what it was asked. */
    private static final class SixInstruments implements com.aresstack.audio.studio.ai.ChatModel {
        String asked;

        @Override
        public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) {
            asked = prompt;
            JsonObject proposal = new JsonObject();
            JsonArray entries = new JsonArray();
            String[] roles = {"melody", "harmony", "bass", "drums", "texture", "melody"};
            for (int i = 0; i < roles.length; i++) {
                entries.add(instrument("voice" + i, roles[i], "Voice " + i, "drums".equals(roles[i]) ? 0 : 40 + i));
            }
            proposal.add("tracks", entries);
            return proposal;
        }

        @Override
        public void cancel() {
        }
    }

    @Test
    public void theUserSaysHowManyInstrumentsTheAiMayChoose() throws Exception {
        SixInstruments model = new SixInstruments();
        java.util.List<String> said = new java.util.ArrayList<String>();

        TrackArrangement free = new InstrumentProposalService(model, 4096)
                .propose("Country", folder.newFolder("free").toPath(), said::add);
        Assert.assertTrue("without a range the AI decides", model.asked.contains("as many complementary tracks as this music needs"));
        Assert.assertEquals(6, free.tracks().size());

        TrackArrangement ranged = new InstrumentProposalService(model, 4096).voices(3, 4)
                .propose("Country", folder.newFolder("ranged").toPath(), said::add);
        Assert.assertTrue(model.asked.contains("between 3 and 4 complementary tracks"));
        Assert.assertEquals("more than allowed are cut to the most", 4, ranged.tracks().size());
        Assert.assertEquals("voice0", ranged.tracks().get(0).id());
        Assert.assertTrue(said.stream().anyMatch(line -> line.contains("höchstens 4")));

        new InstrumentProposalService(model, 4096).voices(6, 6)
                .propose("Country", folder.newFolder("exact").toPath(), said::add);
        Assert.assertTrue("the same number twice is an exact number", model.asked.contains("exactly 6 complementary tracks"));

        try {
            new InstrumentProposalService(model, 4096).voices(5, 3);
            Assert.fail("the most is not below the fewest");
        } catch (IllegalArgumentException expected) {
            // said
        }
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
