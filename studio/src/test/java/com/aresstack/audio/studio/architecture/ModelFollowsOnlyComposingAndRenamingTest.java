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
        // Beside these, the stage page shows the movements the model holds and keeps a curve the user
        // drew there (work package D3): an edit of the mix on purpose, which takes over no old file.
        // Likewise movements the AI proposed and the user took with "Übernehmen" (askDirector only
        // reads which tracks have a channel; the proposal itself changes nothing until it is taken).
        assertEquals("opening loads the model, composing and renaming bring it in step, a drawn movement is kept,"
                        + " the switch of the level is noted, rendering reads it, movements of the AI are kept"
                        + " once taken; nothing else reaches it",
                new java.util.TreeSet<String>(List.of("askDirector", "composeIntoPiece", "levelSwitched",
                        "loadArrangementForCurrentDirectory", "loadDemo", "movementChanged", "movementsTaken",
                        "pieceToEdit", "renamed", "renderArrangement", "savedPiece", "showMovements")), users);
        assertTrue("asking the AI for a mix only reads the model",
                bodyOf(source, "private void askDirector()").replace("pieceModel.movements()", "")
                        .indexOf("pieceModel") < 0);
        assertEquals("movements of the AI are written only when the user took them", List.of("movementsTaken"),
                callers(source, "pieceModel\\.takeMovements\\("));
        assertTrue("taking movements brings no old files into the model",
                bodyOf(source, "private void movementsTaken(").indexOf("synchronize") < 0);
        assertEquals("only a curve the user drew is written into the mix of the model", List.of("movementChanged"),
                callers(source, "pieceModel\\.move\\("));
        assertTrue("showing the movements only reads the model",
                bodyOf(source, "private void showMovements()").replace("pieceModel.movements()", "")
                        .indexOf("pieceModel") < 0);
        assertTrue("a curve is kept only once it has settled, and never brings old files into the model",
                bodyOf(source, "private void movementChanged(").indexOf("synchronize") < 0
                        && bodyOf(source, "private void movementChanged(").contains("if (!settled) {"));
        assertEquals("the switch of the level writes only the level mode", List.of("levelSwitched"),
                callers(source, "pieceModel\\.levelFixed\\("));
        assertTrue("loading the demo replaces the piece on the user's word, through the one place that"
                        + " protects a model the demo cannot replace",
                bodyOf(source, "private void loadDemo() {").contains("DemoIntoPiece.load(input, folder, pieceModel)"));
        assertEquals("rendering only reads the saved model; the changes the user asks for by name"
                        + " (a proposal taken, undo, redo, revisions) get it from one place",
                List.of("renderArrangement", "savedPiece"), callers(source, "pieceModel\\.of\\("));
        assertEquals("only what the user asks for by name changes the saved piece",
                new java.util.TreeSet<String>(List.of("composeAgain", "pieceChanged", "showRevisions", "undoPiece",
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
