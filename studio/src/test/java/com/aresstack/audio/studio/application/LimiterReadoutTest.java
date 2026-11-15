package com.aresstack.audio.studio.application;

import org.junit.Assert;
import org.junit.Test;

public class LimiterReadoutTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    public void aRestingLimiterSaysNothing() {
        LimiterReadout readout = new LimiterReadout();
        Assert.assertEquals("", readout.read(1f, 0));
        Assert.assertEquals("less than a tenth of a dB is not worth a word", "", readout.read(0.995f, SECOND));
    }

    @Test
    public void theReductionIsSaidInDbAndTheStrongestStaysReadableForAMoment() {
        LimiterReadout readout = new LimiterReadout();
        float down = (float) Math.pow(10, -2.3 / 20);
        Assert.assertEquals("Limiter −2,3 dB", readout.read(down, 0));
        Assert.assertEquals("a short peak is still shown", "Limiter −2,3 dB", readout.read(1f, SECOND));
        Assert.assertEquals("a stronger one replaces it at once", "Limiter −6,0 dB",
                readout.read((float) Math.pow(10, -6.0 / 20), SECOND + 1));
        Assert.assertEquals("then the line goes quiet again", "", readout.read(1f, 3 * SECOND));
    }

    @Test
    public void afterStoppingNothingIsHeld() {
        LimiterReadout readout = new LimiterReadout();
        readout.read(0.5f, 0);
        readout.clear();
        Assert.assertEquals("", readout.read(1f, 1));
    }
}
