package com.aresstack.audio.core.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Random;
import org.junit.Test;

/** Work package D3: one kind of curve for every parameter that moves. */
public final class AutomationCurveTest {
    @Test
    public void framesAreTurnedIntoTheUnitOfTheCurve() {
        // Points in seconds, 1000 frames a second.
        AutomationCurve curve = AutomationCurve.of(new double[] {1, 3}, new double[] {-12, 0}, 1000);
        assertEquals(-12, curve.valueAt(0), 0);
        assertEquals(-12, curve.valueAt(1000), 0);
        assertEquals(-6, curve.valueAt(2000), 1e-12);
        assertEquals(0, curve.valueAt(3000), 0);
        assertEquals(0, curve.valueAt(1_000_000), 0);
    }

    @Test
    public void twoPointsAtOneTimeMakeAJump() {
        AutomationCurve curve = AutomationCurve.of(new double[] {0, 2, 2, 4}, new double[] {-1, -1, 1, 1}, 1);
        assertEquals(-1, curve.valueAtTime(1.999), 0);
        assertEquals(1, curve.valueAtTime(2.001), 0);
    }

    /** The search by halving finds what a walk from the first point finds, to the last bit. */
    @Test
    public void halvingGivesWhatAWalkThroughThePointsGives() {
        Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            int size = 1 + random.nextInt(40);
            double[] times = new double[size];
            double[] values = new double[size];
            double time = random.nextDouble();
            for (int i = 0; i < size; i++) {
                time += random.nextInt(4) == 0 ? 0 : random.nextDouble() * 3;
                times[i] = time;
                values[i] = random.nextGaussian() * 10;
            }
            AutomationCurve curve = AutomationCurve.of(times, values, 1);
            for (int probe = 0; probe < 200; probe++) {
                double at = random.nextDouble() * (time + 2) - 1;
                assertEquals(walk(times, values, at), curve.valueAtTime(at), 0);
            }
            for (double at : times) {
                assertEquals(walk(times, values, at), curve.valueAtTime(at), 0);
            }
        }
    }

    /** The way the studio has always read its level curves (SoundSourcePlacement.dynamicsAt). */
    private static double walk(double[] times, double[] values, double at) {
        double beforeTime = times[0];
        double beforeValue = values[0];
        if (at <= beforeTime) {
            return beforeValue;
        }
        for (int i = 0; i < times.length; i++) {
            if (at <= times[i]) {
                double span = times[i] - beforeTime;
                return span <= 0 ? values[i] : beforeValue + (values[i] - beforeValue) * (at - beforeTime) / span;
            }
            beforeTime = times[i];
            beforeValue = values[i];
        }
        return beforeValue;
    }

    @Test
    public void aCurveIsCheckedWhenItIsMade() {
        for (Runnable wrong : new Runnable[] {
                () -> AutomationCurve.of(new double[] {1, 0}, new double[] {0, 0}, 1),
                () -> AutomationCurve.of(new double[] {}, new double[] {}, 1),
                () -> AutomationCurve.of(new double[] {0}, new double[] {Double.NaN}, 1),
                () -> AutomationCurve.of(new double[] {0}, new double[] {0, 1}, 1),
                () -> AutomationCurve.of(new double[] {0}, new double[] {0}, 0)}) {
            try {
                wrong.run();
                fail("refused");
            } catch (IllegalArgumentException expected) {
                // checked outside the audio thread
            }
        }
    }

    @Test
    public void aCurveKeepsItsOwnPoints() {
        double[] times = {0, 1};
        double[] values = {0, 1};
        AutomationCurve curve = AutomationCurve.of(times, values, 1);
        values[1] = 5;
        assertEquals(1, curve.valueAtTime(1), 0);
        assertFalse(curve.constant());
        assertTrue(AutomationCurve.constant(0.3).constant());
        assertEquals(2, curve.size());
    }
}
