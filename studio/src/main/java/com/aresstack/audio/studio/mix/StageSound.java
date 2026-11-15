package com.aresstack.audio.studio.mix;

import com.aresstack.audio.core.conv.ImpulseResponse;
import com.aresstack.audio.core.spatial.HrirSet;
import com.aresstack.audio.core.spatial.SphericalHeadModel;

/**
 * How the stage is heard (work package D4): on loudspeakers with the equal-power panorama, as
 * always, or on headphones with head-related filters ({@link HrirSet}); the room rings
 * algorithmically, as always, or by convolution with an impulse response. The default is
 * {@link #LOUDSPEAKERS}: loudspeakers and the algorithmic room, sound for sound the stage before D4.
 *
 * @param headphones      binaural for headphones instead of stereo for loudspeakers
 * @param convolution     the room rings by convolution instead of algorithmically
 * @param hrirs           the head-related responses; {@code null} for the built-in head model
 * @param impulseResponse the response of the room; {@code null} for one made from the room's numbers
 */
public record StageSound(boolean headphones, boolean convolution, HrirSet hrirs, ImpulseResponse impulseResponse) {
    /** Loudspeakers, algorithmic room: the stage as before work package D4. */
    public static final StageSound LOUDSPEAKERS = new StageSound(false, false, null, null);

    public StageSound {
        if (hrirs != null && hrirs.sampleRate() != SpatialStemMixer.RATE) {
            throw new IllegalArgumentException("Die Kopfdaten gelten für " + hrirs.sampleRate() + " Hz, der Mix läuft mit "
                    + SpatialStemMixer.RATE + " Hz.");
        }
    }

    /** Headphones with the built-in head model, algorithmic room. */
    public static StageSound binaural() {
        return new StageSound(true, false, null, null);
    }

    public StageSound onHeadphones(boolean binaural) {
        return new StageSound(binaural, convolution, hrirs, impulseResponse);
    }

    /** The room by convolution, with a response made from its numbers. */
    public StageSound withConvolution(boolean convolved) {
        return new StageSound(headphones, convolved, hrirs, impulseResponse);
    }

    /** The room by convolution with this measured response (e.g. read by {@code ImpulseResponseFile}). */
    public StageSound withImpulseResponse(ImpulseResponse response) {
        return new StageSound(headphones, response != null || convolution, hrirs, response);
    }

    /** Headphones with these head-related responses (e.g. MIT KEMAR); {@code null} for the head model. */
    public StageSound withHrirs(HrirSet set) {
        return new StageSound(headphones, convolution, set, impulseResponse);
    }

    /** The head-related responses that are used on headphones. */
    public HrirSet head() {
        return hrirs != null ? hrirs : Model.SET;
    }

    /** The built-in head model, made once when it is first needed. */
    private static final class Model {
        static final HrirSet SET = SphericalHeadModel.hrirs(SpatialStemMixer.RATE);
    }
}
