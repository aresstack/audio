package com.aresstack.audio.core.spatial;

/**
 * Head-related responses computed from a model instead of measured: the spherical head with
 * outer-ear echoes of C. P. Brown and R. O. Duda, "A structural model for binaural sound
 * synthesis", IEEE Transactions on Speech and Audio Processing 6 (5), 1998. A published
 * algorithm, no data set, so no licence to observe; it is the set the core brings along.
 *
 * <ul>
 *   <li><b>Head shadow</b> per ear: the one-pole, one-zero filter
 *       {@code H(s) = (α s + β) / (s + β)}, {@code β = 2c/a}, with
 *       {@code α(θ) = 1 + αmin/2 + (1 − αmin/2) cos(θ/θmin · 180°)}, θ the angle between the
 *       ear and the source: up to +6 dB in the heights at the ear facing the source, a dull
 *       shadow on the far side.</li>
 *   <li><b>Arrival time</b> per ear (Woodworth): {@code a/c · (1 − cos θ)} up to 90°,
 *       {@code a/c · (1 + θ − π/2)} beyond: up to 0.66 ms between the ears.</li>
 *   <li><b>Outer ear</b>: five echoes {@code ρk} after
 *       {@code τk = Ak cos(θ/2) sin(Dk (90° − φ)) + Bk} samples at 44.1 kHz; the notch they cut
 *       moves with the elevation φ, the strongest cue for height.</li>
 * </ul>
 *
 * The result is plausible rather than personal: it places sources left and right well and
 * gives height and front/back only roughly. A measured set ({@link HrirMeasurements}) is better
 * at both.
 */
public final class SphericalHeadModel {
    /** Radius of an average head, in metres. */
    public static final double HEAD_RADIUS = 0.0875;
    private static final double SPEED = 343;
    private static final double ALPHA_MIN = 0.1;
    private static final double THETA_MIN = 150;
    private static final double[] RHO = {0.5, -1, 0.5, -0.25, 0.25};
    private static final double[] A = {1, 5, 5, 5, 5};
    private static final double[] B = {2, 4, 7, 11, 13};
    private static final double[] D = {1, 0.5, 0.5, 0.5, 0.5};

    private SphericalHeadModel() {
    }

    /** The model at this rate with 64 taps, on a grid of 5° in azimuth and 10° in elevation from −40° to 90°. */
    public static HrirSet hrirs(int sampleRate) {
        return hrirs(sampleRate, 64);
    }

    public static HrirSet hrirs(int sampleRate, int taps) {
        if (sampleRate <= 0 || taps < 16) {
            throw new IllegalArgumentException("The head model needs a rate and at least 16 taps.");
        }
        int azimuths = 72;
        double lowest = -40;
        double step = 10;
        int elevations = 14;
        int points = azimuths * elevations;
        float[][] left = new float[points][];
        float[][] right = new float[points][];
        float[] delayLeft = new float[points];
        float[] delayRight = new float[points];
        for (int e = 0; e < elevations; e++) {
            for (int a = 0; a < azimuths; a++) {
                int p = e * azimuths + a;
                double azimuth = a * 360.0 / azimuths;
                double elevation = lowest + e * step;
                left[p] = new float[taps];
                right[p] = new float[taps];
                delayLeft[p] = (float) ear(azimuth, elevation, -1, sampleRate, left[p]);
                delayRight[p] = (float) ear(azimuth, elevation, 1, sampleRate, right[p]);
            }
        }
        return new HrirSet(sampleRate, taps, azimuths, lowest, step, elevations, left, right, delayLeft, delayRight,
                "Kugelkopfmodell nach Brown & Duda (1998), ohne Datensatz");
    }

    /**
     * Writes the filter of one ear ({@code side} −1 left, +1 right) for a direction and returns its
     * arrival time in frames.
     */
    static double ear(double azimuth, double elevation, int side, int sampleRate, float[] filter) {
        double az = Math.toRadians(azimuth);
        double el = Math.toRadians(elevation);
        // x to the right, y ahead, z up.
        double x = Math.sin(az) * Math.cos(el);
        double theta = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, x * side))));
        double alpha = 1 + ALPHA_MIN / 2 + (1 - ALPHA_MIN / 2) * Math.cos(Math.toRadians(theta / THETA_MIN * 180));
        if (theta > THETA_MIN) {
            alpha = ALPHA_MIN;
        }
        double radians = Math.toRadians(theta);
        double seconds = radians < Math.PI / 2
                ? HEAD_RADIUS / SPEED * (1 - Math.cos(radians))
                : HEAD_RADIUS / SPEED * (1 + radians - Math.PI / 2);
        // Outer ear: the direct path and five echoes, at fractional delays (linear interpolation).
        int taps = filter.length;
        double[] pinna = new double[taps];
        pinna[0] = 1;
        double scale = sampleRate / 44100.0;
        // Azimuth as seen from this ear's side of the head, 0 ahead, ±180 behind.
        double front = Math.toDegrees(Math.atan2(Math.sin(az) * side, Math.cos(az)));
        double halfCos = Math.cos(Math.toRadians(Math.abs(front) / 2));
        for (int k = 0; k < RHO.length; k++) {
            double tau = (A[k] * halfCos * Math.sin(Math.toRadians(D[k] * (90 - elevation))) + B[k]) * scale;
            tau = Math.max(0, tau);
            int i = (int) tau;
            double f = tau - i;
            if (i + 1 < taps) {
                pinna[i] += RHO[k] * (1 - f);
                pinna[i + 1] += RHO[k] * f;
            }
        }
        // Head shadow by the bilinear transform of (α s + β) / (s + β).
        double beta = 2 * SPEED / HEAD_RADIUS;
        double k2 = 2.0 * sampleRate;
        double b0 = (alpha * k2 + beta) / (k2 + beta);
        double b1 = (beta - alpha * k2) / (k2 + beta);
        double a1 = (beta - k2) / (k2 + beta);
        double previousIn = 0;
        double previousOut = 0;
        int fade = taps / 4;
        for (int n = 0; n < taps; n++) {
            double in = pinna[n];
            double out = b0 * in + b1 * previousIn - a1 * previousOut;
            previousIn = in;
            previousOut = out;
            double window = n < taps - fade ? 1 : 0.5 + 0.5 * Math.cos(Math.PI * (n - (taps - fade) + 1) / (fade + 1));
            filter[n] = (float) (out * window);
        }
        return seconds * sampleRate;
    }
}
