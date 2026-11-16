package com.aresstack.audio.studio.visual;

import java.util.Locale;

/**
 * The atmosphere of the stage: a surprise for the wildest moments, not a decoration. Fire belongs
 * to rock and lasers to techno, and to nothing else. Whether a piece is one of them is taken from
 * what it says of itself (its description, the style of its album) or from a
 * {@link GenreRecognizer} that names the genre and is sure; the studio's own recognizer names
 * none, so a piece that says nothing gets nothing. One instance belongs to one view.
 */
public final class StageMood {
    /** What the stage shows around its lights. */
    public enum Mood {
        /** Jets of fire on the hard beats of a rock piece. */
        FIRE("fire", "Feuer"),
        /** Beams sweeping through the haze, for techno. */
        LASER("laser", "Laser"),
        /** Nothing but the lights of the tracks. */
        OFF("off", "Aus");

        private final String key;
        private final String label;

        Mood(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }
    }

    /** The setting that lets the music choose. */
    public static final String AUTO = "auto";
    /** The settings in the order a key steps through them. */
    public static final java.util.List<String> CHOICES = java.util.List.of(AUTO, Mood.FIRE.key(), Mood.LASER.key(),
            Mood.OFF.key());

    /**
     * The words that name a style, in German and English, in the order they are looked for: a
     * piece called "Pop-Rock" is rock. Whole words only, so that "Barock" is not rock.
     */
    private static final java.util.Map<Mood, java.util.regex.Pattern> WORDS = words();

    private static java.util.Map<Mood, java.util.regex.Pattern> words() {
        java.util.Map<Mood, java.util.regex.Pattern> words = new java.util.LinkedHashMap<Mood, java.util.regex.Pattern>();
        words.put(Mood.LASER, word("techno|trance|house|edm|electro\\w*|elektro\\w*|dance|rave|dubstep|drum ?(and|&|'?n'?) ?bass|eurodance|hardstyle"));
        words.put(Mood.FIRE, word("rock\\w*|hardrock|metal\\w*|punk\\w*|grunge"));
        return words;
    }

    private static java.util.regex.Pattern word(String any) {
        return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])(" + any + ")(?![\\p{L}\\p{N}])",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    }

    /** The atmosphere the words about a piece ask for, or {@code null} if they name no style the stage knows. */
    public static Mood fromWords(String style) {
        if (style == null || style.isBlank()) {
            return null;
        }
        for (java.util.Map.Entry<Mood, java.util.regex.Pattern> each : WORDS.entrySet()) {
            if (each.getValue().matcher(style).find()) {
                return each.getKey();
            }
        }
        return null;
    }

    /** The name of a setting on its key. */
    public static String label(String choice) {
        Mood fixed = fixed(choice);
        return fixed == null ? "Auto" : fixed.label();
    }

    /** The setting after this one. */
    public static String next(String choice) {
        int at = CHOICES.indexOf(normal(choice));
        return CHOICES.get((at + 1) % CHOICES.size());
    }

    /** A setting as it is kept: one of {@link #CHOICES}, {@link #AUTO} for anything else. */
    public static String normal(String choice) {
        String key = choice == null ? "" : choice.trim().toLowerCase(Locale.ROOT);
        return CHOICES.contains(key) ? key : AUTO;
    }

    /** The mood a setting fixes, or {@code null} if the music chooses. */
    static Mood fixed(String choice) {
        String key = normal(choice);
        for (Mood mood : Mood.values()) {
            if (mood.key().equals(key)) {
                return mood;
            }
        }
        return null;
    }

    private final GenreRecognizer recognizer;
    private GenreRecognizer.Heard heard = GenreRecognizer.Heard.NOTHING;
    private String readStyle = "";
    private Mood named;

    public StageMood() {
        this(new SimpleRecognizer());
    }

    /** @param recognizer who reads the music where the piece does not say what it is */
    public StageMood(GenreRecognizer recognizer) {
        this.recognizer = recognizer;
    }

    /** What was heard in the music at the last picture, whatever the piece says of itself. */
    public GenreRecognizer.Heard heard() {
        return heard;
    }

    /** The style the words about the piece name, as a word for the player; empty if they name none. */
    public String named() {
        return GenreRecognizer.Genre.of(named).label();
    }

    /**
     * The mood of the stage for this picture. Left to itself the stage shows fire only for a piece
     * that is rock and lasers only for one that is techno, by what the piece says of itself or by a
     * recognizer that names the genre and is sure of it; for everything else, and whenever in doubt,
     * nothing. Never both: there is one mood at a time.
     */
    public Mood at(SoundPicture sound, VisualSettings settings) {
        Mood fixed = fixed(settings.atmosphere());
        heard = recognizer.hear(sound, settings);
        if (!sound.style.equals(readStyle)) {
            readStyle = sound.style;
            named = fromWords(readStyle);
        }
        if (fixed != null) {
            return fixed;
        }
        if (named != null) {
            return named;
        }
        Mood sure = heard.confidence() >= SURE ? heard.genre().mood() : null;
        return sure == null ? Mood.OFF : sure;
    }

    /** Whether the mood was set by hand: then it shows all the time, not only in the wildest moments. */
    public static boolean byHand(VisualSettings settings) {
        return fixed(settings.atmosphere()) != null;
    }

    /** How sure a recognizer must be of a genre before the stage follows it. */
    static final double SURE = 0.8;
}
