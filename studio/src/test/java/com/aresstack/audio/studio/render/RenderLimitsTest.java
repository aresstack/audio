package com.aresstack.audio.studio.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.time.Duration;
import org.junit.Test;

public final class RenderLimitsTest {
    @Test
    public void twelveMinutesPassEveryLimit() throws IOException {
        RenderLimits.STANDARD.checkPiece(12 * 60, "A piece");
        RenderLimits.STANDARD.checkPiece(3 * 60 + 1, "A piece beyond the old three minutes");
    }

    @Test
    public void refusesWhatIsLongerThanTheLimit() {
        try {
            RenderLimits.STANDARD.checkPiece(61 * 60, "The piece");
            fail("an hour and a minute is beyond the limit");
        } catch (IOException expected) {
            assertEquals("The piece is 61.0 min long; the render limit is 60.0 min.", expected.getMessage());
        }
    }

    @Test
    public void givesASamplerTimeByTheLengthOfThePiece() {
        assertEquals(Duration.ofMinutes(5), RenderLimits.STANDARD.processTimeout(0));
        assertEquals(Duration.ofMinutes(29), RenderLimits.STANDARD.processTimeout(12 * 60));
    }
}
