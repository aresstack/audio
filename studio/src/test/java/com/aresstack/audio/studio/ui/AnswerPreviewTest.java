package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** A long piece's saved plan is shown in part instead of not at all. */
public class AnswerPreviewTest {

    @Test
    public void aShortAnswerIsShownWhole() throws Exception {
        Path file = Files.createTempFile("plan", ".json");
        Files.write(file, "{\"bars\":925}".getBytes(StandardCharsets.UTF_8));
        assertEquals("{\"bars\":925}", AudioStudioFrame.previewOf(file));
    }

    @Test
    public void aHugeAnswerShowsItsBeginningAndWhereTheRestIs() throws Exception {
        Path file = Files.createTempFile("composition", ".json");
        StringBuilder big = new StringBuilder();
        while (big.length() <= AudioStudioFrame.PREVIEW_CHARS_PER_FILE * 2) {
            big.append("{\"note\":60},");
        }
        Files.write(file, big.toString().getBytes(StandardCharsets.UTF_8));
        String shown = AudioStudioFrame.previewOf(file);
        assertTrue(shown.startsWith(big.substring(0, AudioStudioFrame.PREVIEW_CHARS_PER_FILE)));
        assertTrue(shown.contains("gekürzt"));
        assertTrue(shown.contains(file.toAbsolutePath().toString()));
    }
}
