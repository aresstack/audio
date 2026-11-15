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
 * link to the model: a click on "render" took a stale arrangement into the model). The note editor
 * (B1) reads the open session and changes it only by patches through the edit core, never by
 * taking over files.
 */
public final class ModelFollowsOnlyComposingAndRenamingTest {
    private static final Path WINDOW = Paths.get("src", "main", "java", "com", "aresstack", "audio", "studio",
            "ui", "AudioStudioFrame.java");

    @Test
    public void theWindowTouchesTheModelOnlyWhereAPieceIsOpenedComposedOrRenamed() throws Exception {
        String source = new String(Files.readAllBytes(WINDOW), StandardCharsets.UTF_8);
        java.util.Set<String> users = new java.util.TreeSet<String>();
        Matcher use = Pattern.compile("pieceModel\\b(?! =)").matcher(source);
        while (use.find()) {
            users.add(methodAround(source, use.start()));
        }
        assertEquals("opening loads the model, composing and renaming bring it in step, rendering reads it;"
                        + " nothing else reaches it",
                new java.util.TreeSet<String>(List.of("composeIntoPiece", "loadArrangementForCurrentDirectory",
                        "pieceToEdit", "renamed", "renderArrangement", "savedPiece")), users);
        assertEquals("rendering only reads the saved model; the changes the user asks for by name"
                        + " (a proposal taken, undo, redo, revisions) get it from one place",
                List.of("renderArrangement", "savedPiece"), callers(source, "pieceModel\\.of\\("));
        assertEquals("only what the user asks for by name changes the saved piece",
                new java.util.TreeSet<String>(List.of("addVoice", "composeAgain", "pieceChanged", "reworkRegion", "showRevisions",
                        "undoPiece",
                        "popupMenuWillBecomeVisible", "savedPiece" /* its own header */)),
                new java.util.TreeSet<String>(callers(source, "savedPiece\\(\\)")));
        assertEquals(1, callers(source, "pieceModel\\.").stream().filter("renderArrangement"::equals).count());
        assertEquals("the note editor only gets the open session; it changes it through the edit core alone",
                "return pieceModel.session();",
                bodyOf(source, "pieceToEdit() {").replace("{", "").trim());
        assertEquals("renaming synchronizes itself, right after its files were written", List.of("renamed"),
                callers(source, "pieceModel\\.synchronize\\(\\)"));
        assertEquals("opening only opens", List.of("loadArrangementForCurrentDirectory"),
                callers(source, "pieceModel\\.open\\("));
    }

    @Test
    public void everyComposingOfThePieceGoesThroughTheOnePlaceThatSynchronizes() throws Exception {
        String source = new String(Files.readAllBytes(WINDOW), StandardCharsets.UTF_8);
        assertEquals("the piece itself is composed in one place; a proposal is composed aside, into its own folder",
                List.of("composeIntoPiece", "composeAside"), callers(source, "\\.compose\\("));
        assertTrue(!bodyOf(source, "private Path composeAside(").contains("pieceModel"));
        assertTrue("composing hands the model to the one order that keeps it whole",
                bodyOf(source, "private Path composeIntoPiece(").contains("ComposingIntoPiece.run("));
        assertTrue("the worker's end, which every operation passes, does not touch the model",
                !bodyOf(source, "protected void done()").contains("pieceModel"));
    }

    private static List<String> callers(String source, String pattern) {
        List<String> callers = new ArrayList<String>();
        Matcher call = Pattern.compile(pattern).matcher(source);
        while (call.find()) {
            callers.add(methodAround(source, call.start()));
        }
        return callers;
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
