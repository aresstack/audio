package com.aresstack.audio.core.fft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.core.Allocation;
import java.util.Random;
import org.junit.Test;

public class RadixTwoFourierTransformTest {
    @Test
    public void forwardMatchesTheDefinitionOfTheDft() {
        int n = 64;
        Random random = new Random(3);
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) {
            re[i] = random.nextGaussian();
            im[i] = random.nextGaussian();
        }
        double[] wantRe = new double[n];
        double[] wantIm = new double[n];
        for (int k = 0; k < n; k++) {
            for (int t = 0; t < n; t++) {
                double a = -2 * Math.PI * k * t / n;
                wantRe[k] += re[t] * Math.cos(a) - im[t] * Math.sin(a);
                wantIm[k] += re[t] * Math.sin(a) + im[t] * Math.cos(a);
            }
        }
        new RadixTwoFourierTransform(n).forward(re, im);
        for (int k = 0; k < n; k++) {
            assertEquals(wantRe[k], re[k], 1e-9);
            assertEquals(wantIm[k], im[k], 1e-9);
        }
    }

    @Test
    public void inverseUndoesForwardFarBelowTheResolutionOfFloat() {
        for (int n = 2; n <= 16384; n <<= 1) {
            Random random = new Random(n);
            double[] re = new double[n];
            double[] im = new double[n];
            double[] keep = new double[n];
            for (int i = 0; i < n; i++) {
                re[i] = keep[i] = random.nextDouble() * 2 - 1;
            }
            RadixTwoFourierTransform fft = new RadixTwoFourierTransform(n);
            fft.forward(re, im);
            fft.inverse(re, im);
            for (int i = 0; i < n; i++) {
                assertEquals(keep[i], re[i], 1e-12);
                assertEquals(0, im[i], 1e-12);
            }
        }
    }

    @Test
    public void aDiracHasAFlatSpectrum() {
        double[] re = new double[8];
        double[] im = new double[8];
        re[0] = 1;
        new RadixTwoFourierTransform(8).forward(re, im);
        for (int k = 0; k < 8; k++) {
            assertEquals(1, re[k], 1e-15);
            assertEquals(0, im[k], 1e-15);
        }
    }

    @Test
    public void allocatesNothing() throws Exception {
        RadixTwoFourierTransform fft = new RadixTwoFourierTransform(1024);
        double[] re = new double[1024];
        double[] im = new double[1024];
        re[3] = 1;
        long bytes = Allocation.of(() -> {
            for (int i = 0; i < 100; i++) {
                fft.forward(re, im);
                fft.inverse(re, im);
            }
        });
        assertTrue("allocated " + bytes + " bytes", bytes < 256);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesLengthsThatAreNoPowerOfTwo() {
        new RadixTwoFourierTransform(48);
    }
}
