package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.studio.score.Score;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** The drawn score in the meter and key of the piece (work package F2, the window's part). */
public final class ScoreViewTest {
    /** The notes begin late enough to leave the first bar empty in every meter here, so that the head of the staff stands alone. */
    private static final int FIRST = 7680;

    private static Score score(String... head) {
        List<String> lines = new ArrayList<String>(Arrays.asList(head));
        lines.add("track\tmelody\t73\t0\tmelody");
        lines.add("track\tbass\t32\t1\tbass");
        int[] pitches = {62, 65, 69, 70, 73, 76, 61, 63, 66, 68};
        for (int i = 0; i < 40; i++) {
            lines.add("note\tmelody\t" + (FIRST + i * 240) + "\t" + (i % 5 == 0 ? 120 : 240) + "\t" + pitches[i % pitches.length] + "\t90");
        }
        lines.add("note\tbass\t" + FIRST + "\t2880\t38\t90");
        lines.add("note\tbass\t" + (FIRST + 4320) + "\t1440\t45\t90");
        return Score.of(lines);
    }

    private static BufferedImage drawn(Score score) {
        ScoreView view = new ScoreView();
        view.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12));
        view.setScore(score, Arrays.asList("melody", "bass"), "");
        BufferedImage picture = view.picture(1400);
        assertNotNull(picture);
        return picture;
    }

    /** How much of the head of the first staff, between clef and first bar, is inked. */
    private static int inkAtTheHead(BufferedImage picture) {
        int dark = 0;
        for (int x = 165; x < 300; x++) {
            for (int y = 30; y < 110; y++) {
                if ((picture.getRGB(x, y) & 0xFF) < 100) {
                    dark++;
                }
            }
        }
        return dark;
    }

    @Test
    public void everyMeterAndKeyTheStudioOffersIsDrawn() {
        String[][] meters = {{"4", "4"}, {"3", "4"}, {"6", "8"}, {"12", "8"}, {"7", "8"}, {"5", "4"}, {"2", "2"},
                {"9", "16"}, {"1", "1"}};
        String[] keys = {"C\tmajor", "D\tminor", "F#\tmajor", "Gb\tmajor", "Eb\tminor", "B\tmajor", "Ab\tminor",
                "C#\tmajor"};
        for (String[] meter : meters) {
            for (String key : keys) {
                Score score = score("tempo\t96", "meter\t" + meter[0] + "\t" + meter[1], "key\t" + key);
                assertEquals(Integer.parseInt(meter[0]), score.meterNumerator());
                drawn(score);
            }
        }
    }

    @Test
    public void aKeySignatureAndItsMeterTakeTheirRoomAtTheHeadOfTheStaff() {
        int plain = inkAtTheHead(drawn(score("tempo\t96")));
        int flats = inkAtTheHead(drawn(score("tempo\t96", "key\tEb\tminor")));
        int sharps = inkAtTheHead(drawn(score("tempo\t96", "key\tB\tmajor")));
        assertTrue("six flats are drawn: " + plain + " < " + flats, flats > plain + 150);
        assertTrue("five sharps are drawn: " + plain + " < " + sharps, sharps > plain + 150);
    }

    @Test
    public void theOutlinesHoldEverySignTheScoreCanAskFor() throws Exception {
        Set<String> names = new HashSet<String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                ScoreView.class.getResourceAsStream("/score-glyphs.txt"), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.startsWith("#")) {
                    assertEquals(line.substring(0, Math.min(20, line.length())), 3, line.split("\\t").length);
                    names.add(line.split("\\t")[0]);
                }
            }
        }
        for (int digit = 0; digit <= 9; digit++) {
            assertTrue("time" + digit, names.contains("time" + digit));
        }
        assertTrue(names.containsAll(Arrays.asList("sharp", "flat", "natural", "doubleSharp", "doubleFlat")));
    }

    /** Flute, then a part on two staves (a grand staff if asked), every note a whole note on a middle line: nothing leaves its staff. */
    private static Score threeStaves(boolean grand) {
        List<String> lines = new ArrayList<String>(Arrays.asList("tempo\t96", "track\tflute\t73\t0\tmelody"));
        if (grand) {
            lines.add("track\tpiano\t0\t1\tharmony");
            lines.add("staff\tpiano\tgrand");
        } else {
            lines.add("track\tviolin\t40\t1\tharmony");
            lines.add("track\tcello\t42\t2\tbass");
        }
        for (int bar = 0; bar < 4; bar++) {
            lines.add("note\tflute\t" + bar * 1920 + "\t1920\t71\t90");
            lines.add("note\t" + (grand ? "piano" : "violin") + "\t" + bar * 1920 + "\t1920\t71\t90" + (grand ? "\trh" : ""));
            lines.add("note\t" + (grand ? "piano" : "cello") + "\t" + bar * 1920 + "\t1920\t50\t90" + (grand ? "\tlh" : ""));
        }
        return Score.of(lines);
    }

    private static int ink(BufferedImage picture, int fromX, int toX, int fromY, int toY) {
        int dark = 0;
        for (int x = fromX; x < toX; x++) {
            for (int y = fromY; y < toY; y++) {
                if ((picture.getRGB(x, y) & 0xFF) < 120) {
                    dark++;
                }
            }
        }
        return dark;
    }

    @Test
    public void aGrandStaffIsBracedItsBarLinesGoThroughBothSystemsAndTheyStandCloser() {
        Score grand = threeStaves(true);
        assertEquals(3, grand.staves().size());
        assertTrue(grand.staves().get(1).grand() && grand.staves().get(2).grand());
        ScoreView view = new ScoreView();
        view.setScore(grand, Arrays.asList("flute", "piano"), "");
        BufferedImage braced = view.picture(1400);
        ScoreView plain = new ScoreView();
        plain.setScore(threeStaves(false), Arrays.asList("flute", "violin", "cello"), "");
        BufferedImage single = plain.picture(1400);
        assertTrue("the two systems stand closer than two parts", braced.getHeight() < single.getHeight());

        // The second staff begins 86 below the first (54); a staff is 36 high. Below it: the gap to the third.
        int gapTop = 54 + 86 + 36 + 4;
        assertTrue("bar lines cross the gap between the systems of the grand staff",
                ink(braced, 300, 1390, gapTop, gapTop + 14) > 4 * 10);
        assertEquals("but not the gap between two parts", 0, ink(single, 300, 1390, gapTop, gapTop + 14));
        assertEquals("nor the gap between the flute and the grand staff", 0, ink(braced, 300, 1390, 54 + 36 + 6, 54 + 86 - 6));
        assertTrue("a brace stands before the grand staff",
                ink(braced, 118, 131, 54 + 86, 54 + 86 + 62 + 36) > ink(single, 118, 131, 54 + 86, 54 + 86 + 62 + 36) + 60);
    }

    @Test
    public void threeVoicesOnAStaffAreDrawnAndNamedUnderTheMouse() {
        List<String> lines = new ArrayList<String>(Arrays.asList("tempo\t96", "meter\t3\t4", "key\tF\tmajor",
                "track\tpiano\t0\t1\tharmony", "staff\tpiano\tgrand"));
        String[][] notes = {{"0", "1440", "72", "rh"}, {"0", "480", "65", "rh2"}, {"480", "480", "69", "rh2"},
                {"0", "240", "64", "rh3"}, {"240", "240", "66", "rh3"}, {"480", "960", "64", "rh3"},
                {"0", "1440", "41", "lh"}, {"0", "480", "53", "lh2"}, {"1440", "1440", "46", "lh"}};
        for (String[] note : notes) {
            lines.add("note\tpiano\t" + note[0] + "\t" + note[1] + "\t" + note[2] + "\t80\t" + note[3]);
        }
        Score score = Score.of(lines);
        assertEquals(3, score.staves().get(0).voices());
        ScoreView view = new ScoreView();
        view.setSize(1400, 400);
        view.setScore(score, Arrays.asList("piano"), "");
        assertNotNull(view.picture(1400));
        String upper = view.getToolTipText(new java.awt.event.MouseEvent(view, java.awt.event.MouseEvent.MOUSE_MOVED, 0,
                0, 400, 54 + 18, 0, false));
        assertNotNull(upper);
        assertTrue(upper, upper.startsWith("piano · ") && upper.contains(score.staves().get(0).voiceName(2)));
        String lower = view.getToolTipText(new java.awt.event.MouseEvent(view, java.awt.event.MouseEvent.MOUSE_MOVED, 0,
                0, 400, 54 + 62 + 18, 0, false));
        assertTrue(lower, lower.contains(score.staves().get(1).voiceName(0)));
    }
}
