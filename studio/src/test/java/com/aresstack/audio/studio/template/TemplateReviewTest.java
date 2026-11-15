package com.aresstack.audio.studio.template;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.ai.FakeChatModel;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The AI decides who plays and what is a slip of the pen, nothing else: the notes stay those of
 * the template. Without an answer the studio decides from the template alone.
 */
public final class TemplateReviewTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static TemplateScore score() {
        ScorePart violin = new ScorePart("Violino I", -1, false, 1, List.of(
                new ScoreNote(0, 240, 76, 80),
                new ScoreNote(240, 240, 93, 80),
                new ScoreNote(480, 240, 77, 80),
                new ScoreNote(720, 240, 79, 80)));
        ScorePart bass = new ScorePart("Basso", 42, false, 1, List.of(
                new ScoreNote(0, 960, 16, 80))); // below a cello: moved up by octaves
        ScorePart piano = new ScorePart("Cembalo", -1, false, 2, List.of(new ScoreNote(0, 960, 60, 80, "rh", "")));
        return new TemplateScore("Primavera", 0, 4, 4, null, List.of(violin, bass, piano), List.of(), "MIDI");
    }

    @Test
    public void theAiChoosesThePlayersAndSetsRightOnlyWhatItIsShown() throws Exception {
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> {
            assertTrue(prompt, prompt.contains("0: part 0, bar 1"));
            assertTrue(prompt, prompt.contains("Vier Jahreszeiten"));
            return "{\"title\":\"La primavera\",\"mood\":\"heiter\",\"key\":\"E major\","
                    + "\"parts\":[{\"index\":0,\"id\":\"Violine 1\",\"role\":\"melody\",\"instrument\":\"Solo violin\","
                    + "\"program\":40},{\"index\":1,\"id\":\"violine-1\",\"role\":\"bass\",\"instrument\":\"Cello\","
                    + "\"program\":0},{\"index\":2,\"role\":\"nonsense\",\"program\":6,\"grand\":true}],"
                    + "\"corrections\":[{\"flag\":0,\"action\":\"octave_down\"},{\"flag\":9,\"action\":\"drop\"}]}";
        });
        Path folder = temporary.newFolder("piece").toPath();
        TemplateReview.Reviewed reviewed = TemplateReview.review(score(), "Vier Jahreszeiten, Frühling", model, 4096,
                folder, null);

        assertEquals("La primavera", reviewed.title());
        assertEquals("heiter", reviewed.mood());
        assertEquals("the template names no key: the AI's is taken", new Key(4, Key.Mode.MAJOR), reviewed.key());
        assertEquals(new TemplateReview.Setup("violine-1", "melody", "Solo violin", 40, false), reviewed.setups().get(0));
        assertEquals("ids stay unique; a program the file names stays when the AI's is the same or invalid",
                "violine-1-2", reviewed.setups().get(1).id());
        assertEquals(0, reviewed.setups().get(1).program());
        assertEquals(new TemplateReview.Setup("cembalo", "harmony", "Cembalo", 6, true), reviewed.setups().get(2));
        assertEquals("the flagged note went an octave down", 81,
                reviewed.score().parts().get(0).notes().get(1).pitch());
        assertEquals(4, reviewed.score().parts().get(0).notes().size());
        assertEquals("a bass note no piano can play is moved into its range", 28,
                reviewed.score().parts().get(1).notes().get(0).pitch());
    }

    @Test
    public void withoutAnAnswerTheTemplateDecides() {
        TemplateReview.Reviewed reviewed = TemplateReview.asWritten(score());
        assertEquals("Primavera", reviewed.title());
        assertEquals(List.of("violino-i", "basso", "cembalo"),
                reviewed.setups().stream().map(TemplateReview.Setup::id).toList());
        assertEquals("the highest part leads", "melody", reviewed.setups().get(0).role());
        assertEquals("a violin by its register", 40, reviewed.setups().get(0).program());
        assertEquals(42, reviewed.setups().get(1).program());
        assertEquals("bass", reviewed.setups().get(1).role());
        assertEquals("two staves are a piano", 0, reviewed.setups().get(2).program());
        assertTrue(reviewed.setups().get(2).grand());
        assertEquals("the outlier is kept unless the AI says otherwise", 93,
                reviewed.score().parts().get(0).notes().get(1).pitch());
        assertEquals("below a cello it goes up by octaves", 40, reviewed.score().parts().get(1).notes().get(0).pitch());
        assertFalse(reviewed.remarks().isEmpty());
    }
}
