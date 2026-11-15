package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.studio.application.ScoreTemplates;
import com.aresstack.audio.studio.assist.AssistTool;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The AI help of a score template writes the notes as the template of the piece, and takes them back. */
public class AssistantScoreTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static AssistTool tool(List<AssistTool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    public void theNotesBecomeTheTemplateAndCanBeTakenBack() throws Exception {
        Path piece = folder.newFolder("piece").toPath();
        int[] written = {0};
        Assistant.Field field = Assistant.scoreField("Notenvorlage", "piece-x-score", () -> piece,
                () -> "Ein Wiegenlied", () -> written[0]++);
        List<AssistTool> tools = field.tools();
        assertTrue(tool(tools, "read_score").action().run(new com.google.gson.JsonObject()).contains("noch keine"));

        Runnable before = field.snapshot();
        String abc = "X:1\nT:Wiegenlied\nM:3/4\nL:1/4\nK:G\nV:1 name=\"Flute\"\nG A B|d2 B|]\n";
        String said = tool(tools, "write_score").action().run(JsonParser.parseString(
                "{\"text\":" + new com.google.gson.Gson().toJson(abc) + ",\"name\":\"wiegenlied\"}").getAsJsonObject());
        assertTrue(said, said.contains("wiegenlied.abc"));
        Path kept = ScoreTemplates.of(piece).orElseThrow();
        assertEquals("wiegenlied.abc", kept.getFileName().toString());
        assertEquals(abc, Files.readString(kept, StandardCharsets.UTF_8));
        assertEquals(1, written[0]);
        assertTrue(tool(tools, "read_score").action().run(new com.google.gson.JsonObject()).contains("T:Wiegenlied"));

        javax.swing.SwingUtilities.invokeAndWait(before);
        assertFalse("taken back: no template as before", ScoreTemplates.of(piece).isPresent());
    }
}
