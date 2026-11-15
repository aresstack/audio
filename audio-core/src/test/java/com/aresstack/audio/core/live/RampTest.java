package com.aresstack.audio.core.live;

import org.junit.Assert;
import org.junit.Test;

/** A ramp reaches its target exactly, in the given frames, and never jumps. */
public final class RampTest {
    @Test
    public void glidesInAStraightLineAndEndsExactlyOnTheTarget() {
        Ramp ramp = new Ramp(0.3f);
        ramp.glideTo(1.7f, 100);
        float last = ramp.value();
        float most = 0;
        for (int i = 0; i < 100; i++) {
            float now = ramp.next();
            most = Math.max(most, Math.abs(now - last));
            last = now;
        }
        Assert.assertEquals(1.7f, ramp.value(), 0f);
        Assert.assertTrue(ramp.settled());
        Assert.assertEquals((1.7f - 0.3f) / 100, most, 1e-6f);
        Assert.assertEquals(1.7f, ramp.next(), 0f);
    }

    @Test
    public void aNewTargetInTheMiddleStartsFromWhereTheValueIs() {
        Ramp ramp = new Ramp(0);
        ramp.glideTo(1, 10);
        for (int i = 0; i < 5; i++) {
            ramp.next();
        }
        float middle = ramp.value();
        ramp.glideTo(0, 10);
        Assert.assertEquals(middle - middle / 10, ramp.next(), 1e-6f);
        for (int i = 0; i < 9; i++) {
            ramp.next();
        }
        Assert.assertEquals(0f, ramp.value(), 0f);
    }

    @Test
    public void noGlideJumpsAtOnce() {
        Ramp ramp = new Ramp(1);
        ramp.glideTo(2, 0);
        Assert.assertTrue(ramp.settled());
        Assert.assertEquals(2f, ramp.next(), 0f);
    }
}
