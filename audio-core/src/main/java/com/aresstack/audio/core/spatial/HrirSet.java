package com.aresstack.audio.core.spatial;

/**
 * Head-related impulse responses on a regular grid of directions: for every direction one short
 * filter per ear and the time the sound needs to reach that ear (work package D4). The filters
 * hold the colour of head and outer ear, the delays the difference in arrival time between the
 * ears; kept apart, both can be interpolated between neighbouring directions without the comb
 * filtering that mixing two measured responses would give.
 *
 * <p>Directions: azimuth in degrees, 0 straight ahead, 90 to the right, 180 behind, 270 to the
 * left; elevation in degrees, 0 at ear height, 90 straight up, negative below. The set is made
 * off the audio thread ({@link SphericalHeadModel}, {@link HrirMeasurements}); {@link #lookup}
 * interpolates bilinearly and allocates nothing, so a moving source may ask every few frames.
 */
public final class HrirSet {
    private final int sampleRate;
    private final int taps;
    private final double azimuthStep;
    private final int azimuths;
    private final double lowestElevation;
    private final double elevationStep;
    private final int elevations;
    private final float[][] left;
    private final float[][] right;
    private final float[] delayLeft;
    private final float[] delayRight;
    private final String source;

    /**
     * @param azimuths   how many azimuths per ring, evenly from 0 (so the step is 360 / azimuths)
     * @param elevations how many rings, from {@code lowestElevation} up in {@code elevationStep}
     * @param left       filters, index {@code ring * azimuths + azimuth}
     * @param delayLeft  arrival times in frames, same index
     * @param source     where the responses come from, for licences and the user interface
     */
    HrirSet(int sampleRate, int taps, int azimuths, double lowestElevation, double elevationStep, int elevations,
            float[][] left, float[][] right, float[] delayLeft, float[] delayRight, String source) {
        if (sampleRate <= 0 || taps <= 0 || azimuths < 4 || elevations < 1 || !(elevationStep > 0)) {
            throw new IllegalArgumentException("A grid of responses needs a rate, taps and at least one ring.");
        }
        int points = azimuths * elevations;
        if (left.length != points || right.length != points || delayLeft.length != points
                || delayRight.length != points) {
            throw new IllegalArgumentException("Every direction of the grid needs both filters and both delays.");
        }
        for (int i = 0; i < points; i++) {
            if (left[i].length != taps || right[i].length != taps || !(delayLeft[i] >= 0)
                    || !(delayRight[i] >= 0)) {
                throw new IllegalArgumentException("Every filter has " + taps + " taps and a delay of at least 0.");
            }
        }
        this.sampleRate = sampleRate;
        this.taps = taps;
        this.azimuths = azimuths;
        this.azimuthStep = 360.0 / azimuths;
        this.lowestElevation = lowestElevation;
        this.elevationStep = elevationStep;
        this.elevations = elevations;
        this.left = left;
        this.right = right;
        this.delayLeft = delayLeft;
        this.delayRight = delayRight;
        this.source = source;
    }

    public int sampleRate() {
        return sampleRate;
    }

    /** The length of every filter, in frames. */
    public int taps() {
        return taps;
    }

    /** The longest arrival time of any direction, in frames. */
    public float longestDelay() {
        float most = 0;
        for (int i = 0; i < delayLeft.length; i++) {
            most = Math.max(most, Math.max(delayLeft[i], delayRight[i]));
        }
        return most;
    }

    public double lowestElevation() {
        return lowestElevation;
    }

    public double highestElevation() {
        return lowestElevation + elevationStep * (elevations - 1);
    }

    /** Where the responses come from (model or measured set, with its licence). */
    public String source() {
        return source;
    }

    /**
     * Writes the filters of a direction into {@code toLeft} and {@code toRight} (at least
     * {@link #taps()} long) and the arrival times in frames into {@code delays[0]} (left) and
     * {@code delays[1]} (right). Elevations beyond the grid take its outermost ring.
     */
    public void lookup(double azimuth, double elevation, float[] toLeft, float[] toRight, float[] delays) {
        double a = azimuth % 360;
        if (a < 0) {
            a += 360;
        }
        double ap = a / azimuthStep;
        int a0 = (int) ap;
        double fa = ap - a0;
        if (a0 >= azimuths) {
            a0 = 0;
            fa = 0;
        }
        int a1 = a0 + 1 == azimuths ? 0 : a0 + 1;
        double ep = (elevation - lowestElevation) / elevationStep;
        int e0;
        double fe;
        if (!(ep > 0)) {
            e0 = 0;
            fe = 0;
        } else if (ep >= elevations - 1) {
            e0 = elevations - 1;
            fe = 0;
        } else {
            e0 = (int) ep;
            fe = ep - e0;
        }
        int e1 = Math.min(e0 + 1, elevations - 1);
        int p00 = e0 * azimuths + a0;
        int p01 = e0 * azimuths + a1;
        int p10 = e1 * azimuths + a0;
        int p11 = e1 * azimuths + a1;
        float w00 = (float) ((1 - fa) * (1 - fe));
        float w01 = (float) (fa * (1 - fe));
        float w10 = (float) ((1 - fa) * fe);
        float w11 = (float) (fa * fe);
        float[] l00 = left[p00];
        float[] l01 = left[p01];
        float[] l10 = left[p10];
        float[] l11 = left[p11];
        float[] r00 = right[p00];
        float[] r01 = right[p01];
        float[] r10 = right[p10];
        float[] r11 = right[p11];
        for (int k = 0; k < taps; k++) {
            toLeft[k] = l00[k] * w00 + l01[k] * w01 + l10[k] * w10 + l11[k] * w11;
            toRight[k] = r00[k] * w00 + r01[k] * w01 + r10[k] * w10 + r11[k] * w11;
        }
        delays[0] = delayLeft[p00] * w00 + delayLeft[p01] * w01 + delayLeft[p10] * w10 + delayLeft[p11] * w11;
        delays[1] = delayRight[p00] * w00 + delayRight[p01] * w01 + delayRight[p10] * w10 + delayRight[p11] * w11;
    }
}
