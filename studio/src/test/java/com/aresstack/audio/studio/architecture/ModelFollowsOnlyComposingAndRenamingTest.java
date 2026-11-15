package com.aresstack.audio.studio.architecture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The window lets the model of a piece take over its old files in exactly two places: after the
 * composer wrote them, and after a track was renamed. Rendering, playing, mixing and proposals
 * never do, so that none of them can change the saved piece (acceptance finding on the window's
 * link to the model: a click on "render" took a stale arrangement into the model).
 */
public final class ModelFollowsOnlyComposingAndRenamingTest {
    private static final Path WINDOW = Paths.get("src", "main", "java", "com", "aresstack", "audio", "studio",
            "ui", "AudioStudioFrame.java");

    @Test
    public void theWindowSynchronizesTheModelOnlyAfterComposingAndRenaming() throws Exception {
        String source = new String(Files.readAllBytes(WINDOW), StandardCharsets.UTF_8);
        List<String> callers = new ArrayList<String>();
        Matcher call = Pattern.compile("pieceModel\\.synchronize\\(\\)").matcher(source);
        while (call.find()) {
            callers.add(methodAround(source, call.start()));
        }
        assertEquals("only composing and renaming bring the model in step with the old files",
                List.of("renamed", "composeIntoPiece"), callers);
    }

    @Test
    public void everyComposingOfThePieceGoesThroughTheOnePlaceThatSynchronizes() throws Exception {
        String source = new String(Files.readAllBytes(WINDOW), StandardCharsets.UTF_8);
        Matcher compose = Pattern.compile("\\.compose\\(").matcher(source);
        List<String> callers = new ArrayList<String>();
        while (compose.find()) {
            callers.add(methodAround(source, compose.start()));
        }
        assertEquals(List.of("composeIntoPiece"), callers);
        assertTrue("the worker's end, which every operation passes, does not touch the model",
                !bodyOf(source, "protected void done()").contains("pieceModel"));
    }

    /** The name of the method whose body holds this place: the last method header before it. */
    private static String methodAround(String source, int place) {
        Matcher header = Pattern.compile("(?m)^ {4,12}(?:public |private |protected )?(?:static )?(?:final )?"
                + "[\\w.<>\\[\\], ]+ (\\w+)\\([^;{}]*\\)[^;{}]*\\{").matcher(source);
        String name = "";
        while (header.find() && header.start() < place) {
            name = header.group(1);
        }
        return name;
    }

    private static String bodyOf(String source, String header) {
        int start = source.indexOf(header);
        assertTrue(header, start >= 0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i);
            }
        }
        return source.substring(open);
    }
}
