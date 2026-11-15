package com.aresstack.audio.composition;

import java.util.Arrays;
import java.util.Locale;

/**
 * A chord symbol as a harmony plan writes it, for example {@code "Dm"}, {@code "A7"},
 * {@code "Bbmaj7"}, {@code "F#dim"}, {@code "Gsus4"}, {@code "C/E"}: a root and the pitch classes
 * it is made of. Only what a repair of notes needs: which pitch classes belong to the chord.
 *
 * @param symbol the symbol as written
 * @param root   the pitch class of the root, 0 = C
 * @param tones  the pitch classes of the chord, root first, without repetition
 */
public record Chord(String symbol, int root, int[] tones) {
    public Chord {
        tones = tones.clone();
    }

    @Override
    public int[] tones() {
        return tones.clone();
    }

    /** Whether a pitch (any octave) is one of the tones of the chord. */
    public boolean contains(int pitch) {
        int pc = Math.floorMod(pitch, 12);
        for (int tone : tones) {
            if (tone == pc) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads a chord symbol; root letters A to G, then {@code #} or {@code b}, then the kind:
     * nothing or {@code maj} (major), {@code m}/{@code min}/{@code -} (minor), {@code dim}/{@code °},
     * {@code aug}/{@code +}, {@code sus2}, {@code sus4}/{@code sus}, {@code 5}, and an optional
     * {@code 6}, {@code 7}, {@code maj7}, {@code 9}; a bass after {@code /} is added as a tone.
     *
     * @throws IllegalArgumentException if the text is no chord symbol
     */
    public static Chord parse(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            throw new IllegalArgumentException("No chord given.");
        }
        String text = symbol.trim();
        String bass = null;
        int slash = text.indexOf('/');
        if (slash > 0) {
            bass = text.substring(slash + 1).trim();
            text = text.substring(0, slash).trim();
        }
        int[] parsed = root(text, symbol);
        int root = parsed[0];
        String kind = text.substring(parsed[1]).trim();
        String lower = kind.toLowerCase(Locale.ROOT);
        int third = 4;
        int fifth = 7;
        int added = -1;
        boolean noThird = false;
        if (lower.startsWith("ø") || lower.startsWith("m7b5") || lower.startsWith("min7b5")) {
            third = 3;
            fifth = 6;
            added = 10;
            lower = "";
        } else if (lower.startsWith("maj7") || lower.startsWith("ma7") || lower.startsWith("Δ")) {
            added = 11;
            lower = "";
        } else if (lower.startsWith("maj") || lower.startsWith("ma")) {
            lower = lower.substring(lower.startsWith("maj") ? 3 : 2);
        } else if (lower.startsWith("min") || (kind.startsWith("m") && !kind.startsWith("maj")) || lower.startsWith("-")) {
            third = 3;
            lower = lower.startsWith("min") ? lower.substring(3) : lower.substring(1);
            if (lower.startsWith("maj7")) {
                added = 11;
                lower = "";
            }
        } else if (lower.startsWith("dim") || lower.startsWith("°") || lower.startsWith("o")) {
            third = 3;
            fifth = 6;
            lower = lower.startsWith("dim") ? lower.substring(3) : lower.substring(1);
            if (lower.startsWith("7")) {
                added = 9;
                lower = lower.substring(1);
            }
        } else if (lower.startsWith("aug") || lower.startsWith("+")) {
            fifth = 8;
            lower = lower.startsWith("aug") ? lower.substring(3) : lower.substring(1);
        }
        if (lower.startsWith("sus2")) {
            noThird = true;
            third = 2;
            lower = lower.substring(4);
        } else if (lower.startsWith("sus")) {
            noThird = true;
            third = 5;
            lower = lower.substring(lower.startsWith("sus4") ? 4 : 3);
        }
        if (lower.equals("5")) {
            third = -1;
            lower = "";
        } else if (lower.startsWith("m7b5")) {
            fifth = 6;
            added = 10;
            lower = "";
        } else if (lower.startsWith("maj7")) {
            added = 11;
            lower = "";
        } else if (lower.startsWith("7") || lower.startsWith("9") || lower.startsWith("11") || lower.startsWith("13")) {
            added = added < 0 ? 10 : added;
            lower = "";
        } else if (lower.startsWith("6")) {
            added = 9;
            lower = "";
        } else if (lower.startsWith("add9") || lower.startsWith("add2")) {
            added = 2;
            lower = "";
        }
        if (!lower.isEmpty() && !lower.matches("[0-9b#()+\\-]*")) {
            throw new IllegalArgumentException("Not a chord: \"" + symbol + "\".");
        }
        int[] tones = new int[]{root, third < 0 ? -1 : (root + third) % 12, (root + fifth) % 12,
                added < 0 ? -1 : (root + added) % 12, bass == null ? -1 : root(bass, symbol)[0]};
        int[] distinct = Arrays.stream(tones).filter(t -> t >= 0).distinct().toArray();
        if (noThird && distinct.length < 2) {
            throw new IllegalArgumentException("Not a chord: \"" + symbol + "\".");
        }
        return new Chord(symbol.trim(), root, distinct);
    }

    /** The chord of a symbol, or {@code null} if it is none. */
    public static Chord tryParse(String symbol) {
        try {
            return parse(symbol);
        } catch (IllegalArgumentException notAChord) {
            return null;
        }
    }

    /** Root pitch class and the number of characters it took. */
    private static int[] root(String text, String symbol) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Not a chord: \"" + symbol + "\".");
        }
        int letter = "CDEFGAB".indexOf(Character.toUpperCase(text.charAt(0)));
        if (letter < 0) {
            throw new IllegalArgumentException("Not a chord: \"" + symbol + "\".");
        }
        int pc = new int[]{0, 2, 4, 5, 7, 9, 11}[letter];
        int used = 1;
        while (used < text.length() && (text.charAt(used) == '#' || text.charAt(used) == '♯'
                || text.charAt(used) == 'b' || text.charAt(used) == '♭')) {
            char c = text.charAt(used);
            pc += c == '#' || c == '♯' ? 1 : -1;
            used++;
        }
        return new int[]{Math.floorMod(pc, 12), used};
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Chord c && c.symbol.equals(symbol) && c.root == root && Arrays.equals(c.tones, tones);
    }

    @Override
    public int hashCode() {
        return symbol.hashCode() * 31 + Arrays.hashCode(tones);
    }

    @Override
    public String toString() {
        return symbol;
    }
}
