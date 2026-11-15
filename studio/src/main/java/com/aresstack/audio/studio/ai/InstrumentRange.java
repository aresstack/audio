package com.aresstack.audio.studio.ai;

/**
 * The pitches an instrument can play, by General MIDI program: what the normalizer limits new
 * notes to (architecture review E4: pitches are only limited to the range of the instrument, not
 * pulled onto a scale). The ranges are the usual written-to-sounding compass of the orchestral
 * instrument, generous for synthesizers.
 */
public record InstrumentRange(int low, int high) {
    /** The kit of General MIDI channel 10: the sounds from high Q to open triangle. */
    public static final InstrumentRange DRUM_KIT = new InstrumentRange(27, 87);

    public InstrumentRange {
        if (low < 0 || high > 127 || high <= low) {
            throw new IllegalArgumentException("Invalid range " + low + ".." + high);
        }
    }

    public static InstrumentRange of(int program, boolean drums) {
        if (drums) {
            return DRUM_KIT;
        }
        return switch (program) {
            case 0, 1, 2, 3, 4, 5, 6, 7 -> new InstrumentRange(21, 108); // pianos
            case 8, 9, 10, 11, 12, 13, 14, 15 -> new InstrumentRange(48, 108); // chromatic percussion
            case 16, 17, 18, 19, 20, 21, 22, 23 -> new InstrumentRange(36, 96); // organs
            case 24, 25, 26, 27, 28, 29, 30, 31 -> new InstrumentRange(40, 88); // guitars
            case 32, 33, 34, 35, 36, 37, 38, 39 -> new InstrumentRange(28, 67); // basses
            case 40 -> new InstrumentRange(55, 103); // violin
            case 41 -> new InstrumentRange(48, 91); // viola
            case 42 -> new InstrumentRange(36, 76); // cello
            case 43 -> new InstrumentRange(28, 67); // contrabass
            case 44, 45, 48, 49, 50, 51 -> new InstrumentRange(28, 103); // string sections
            case 46 -> new InstrumentRange(24, 103); // harp
            case 47 -> new InstrumentRange(36, 57); // timpani
            case 52, 53, 54, 55 -> new InstrumentRange(40, 84); // choir, voice, orchestra hit
            case 56, 59 -> new InstrumentRange(54, 86); // trumpet, muted trumpet
            case 57 -> new InstrumentRange(40, 77); // trombone
            case 58 -> new InstrumentRange(28, 58); // tuba
            case 60 -> new InstrumentRange(34, 77); // French horn
            case 61, 62, 63 -> new InstrumentRange(34, 86); // brass sections
            case 64 -> new InstrumentRange(56, 88); // soprano sax
            case 65 -> new InstrumentRange(49, 81); // alto sax
            case 66 -> new InstrumentRange(44, 76); // tenor sax
            case 67 -> new InstrumentRange(36, 69); // baritone sax
            case 68 -> new InstrumentRange(58, 91); // oboe
            case 69 -> new InstrumentRange(52, 81); // English horn
            case 70 -> new InstrumentRange(34, 75); // bassoon
            case 71 -> new InstrumentRange(50, 94); // clarinet
            case 72 -> new InstrumentRange(74, 108); // piccolo
            case 73, 75, 76, 77, 78, 79 -> new InstrumentRange(60, 96); // flute and pipes
            case 74 -> new InstrumentRange(72, 98); // recorder
            default -> program < 104 ? new InstrumentRange(36, 96) : new InstrumentRange(48, 84); // synths, ethnic, effects
        };
    }

    public boolean contains(int pitch) {
        return pitch >= low && pitch <= high;
    }

    public int clamp(int pitch) {
        return Math.max(low, Math.min(high, pitch));
    }
}
