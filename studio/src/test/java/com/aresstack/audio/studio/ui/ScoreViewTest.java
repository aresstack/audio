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
}
