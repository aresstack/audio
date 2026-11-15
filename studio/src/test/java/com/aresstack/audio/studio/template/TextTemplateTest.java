package com.aresstack.audio.studio.template;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.ai.Backoff;
import com.aresstack.audio.studio.ai.FakeChatModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Notes written as text (here ABC) are written over by the AI, portion by portion: every portion
 * goes on where the one before ended, the beginning of the file goes along for its headers, an
 * answer without notes is asked again, and the answers are kept.
 */
public final class TextTemplateTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @After
    public void normalBackoff() {
        Backoff.forTests(0, 0);
    }

    static final String ABC = String.join("\n", "X:1", "T:Frühling", "M:4/4", "L:1/4", "Q:1/4=100", "K:E",
            "e g b e' |", "d' c' b2 |");

    @Test
    public void portionsFollowEachOtherAndTheHeadGoesAlong() throws Exception {
        Backoff.forTests(3, 1);
        AtomicInteger asked = new AtomicInteger();
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> {
            int number = asked.incrementAndGet();
            if (number == 1) {
                return "{\"title\":\"Frühling\",\"tempoBpm\":100,\"meter\":\"4/4\",\"key\":\"E major\","
                        + "\"parts\":[{\"name\":\"Violino\",\"program\":40}],"
                        + "\"notes\":[[0,0,1,64,80],[0,1,1,68,80],[0,2,1,71],[0,3,1,76,80]],\"portionQuarters\":4}";
            }
            if (number == 2) {
                return "{\"what\":\"no notes here\"}"; // not usable: asked again
            }
            assertTrue("the head goes along with a later portion", prompt.contains("K:E"));
            assertTrue(prompt.contains("0: Violino"));
            return "{\"parts\":[{\"name\":\"Violino\",\"program\":40}],"
                    + "\"notes\":[{\"part\":0,\"start\":0,\"length\":1,\"pitch\":75},[0,1,1,73,80],[0,2,2,71,80],"
                    + "\"rubbish\"]}";
        });
        Path folder = temporary.newFolder("piece").toPath();
        TemplateScore score = TextTemplate.read(ABC, "fruehling", model, 4096, 4, folder, null);

        assertEquals("Frühling", score.title());
        assertEquals(100, score.bpm());
        assertEquals(new Key(4, Key.Mode.MAJOR), score.key());
        assertEquals(1, score.parts().size());
        assertEquals(40, score.parts().get(0).program());
        List<ScoreNote> notes = score.parts().get(0).notes();
        assertEquals(7, notes.size());
        assertEquals(new ScoreNote(960, 480, 71, 80), notes.get(2));
        assertEquals("the second portion begins after the four quarters of the first",
                new ScoreNote(1920, 480, 75, 80), notes.get(4));
        assertEquals(new ScoreNote(2880, 960, 71, 80), notes.get(6));
        assertTrue(score.remarks().toString(), score.remarks().stream().anyMatch(r -> r.contains("keine Noten")));
        assertEquals(3, asked.get());

        // Read again: the kept answers are used, the AI is not asked.
        try (var kept = Files.list(folder.resolve(TextTemplate.CACHE_FOLDER))) {
            assertEquals(2, kept.count());
        }
        TemplateScore again = TextTemplate.read(ABC, "fruehling", model, 4096, 4, folder, null);
        assertEquals(score, again);
        assertEquals(3, asked.get());
    }

    @Test
    public void theReaderSendsOnlyOtherNotationsToTheAi() throws Exception {
        Path folder = temporary.newFolder("piece2").toPath();
        Path abc = folder.resolve("fruehling.abc");
        Files.write(abc, ABC.getBytes(StandardCharsets.UTF_8));
        try {
            TemplateReader.read(abc, null, 4096, 40, folder, null);
            throw new AssertionError("text needs the AI");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("KI"));
        }
        Path pdf = folder.resolve("noten.pdf");
        Files.write(pdf, "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
        try {
            TemplateReader.read(pdf, null, 4096, 40, folder, null);
            throw new AssertionError("a PDF is refused");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("MusicXML"));
        }
        Path midi = folder.resolve("noten.bin");
        Files.write(midi, MidiTemplateTest.vivaldiLike());
        assertEquals("MIDI is known by its bytes, whatever the file is called", "MIDI",
                TemplateReader.read(midi, null, 4096, 40, folder, null).format());
    }
}
