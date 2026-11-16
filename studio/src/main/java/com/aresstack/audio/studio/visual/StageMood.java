package com.aresstack.audio.studio.visual;

import java.util.Locale;

/**
 * The atmosphere of the stage. Where the piece says in words what it is (its description, the style
 * of its album), those words choose: rock, techno, jazz, country, pop, classical. Where it does not,
 * the music itself is read, and only for what can be heard in it: fast beats call for lasers, a
 * heavy low end with a beat for fire, everything else for candle light; jazz, country and pop
 * cannot be told apart from the spectrum and are never guessed. A heard mood must hold for a while
 * before the stage changes, so that one loud bar does not turn a quiet piece into a rock concert. One
 * instance belongs to one view and is asked once per picture.
 */
public final class StageMood {
    /** What the stage shows around its lights. */
    public enum Mood {
        /** Jets of fire on the beats of a heavy low end. */
        FIRE("fire", "Feuer"),
        /** Beams sweeping through the haze, for fast music. */
        LASER("laser", "Laser"),
        /** Warm light from above, candles at the edge and drifting haze, for quiet music. */
        CANDLE("candle", "Kerzen"),
        /** A smoky club: blue haze and one amber light, for jazz and blues. */
        CLUB("club", "Jazzclub"),
        /** A string of warm lanterns before an evening sky, for country and folk. */
        LANTERN("lantern", "Laternen"),
        /** Coloured spotlights that wander and change with the beat, for pop. */
        SPOTS("spots", "Scheinwerfer"),
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
            Mood.CANDLE.key(), Mood.CLUB.key(), Mood.LANTERN.key(), Mood.SPOTS.key(), Mood.OFF.key());

    /**
     * The words that name a style, in German and English, in the order they are looked for: a
     * piece called "Pop-Rock" is rock. Whole words only, so that "Barock" is not rock.
     */
    private static final java.util.Map<Mood, java.util.regex.Pattern> WORDS = words();

    private static java.util.Map<Mood, java.util.regex.Pattern> words() {
        java.util.Map<Mood, java.util.regex.Pattern> words = new java.util.LinkedHashMap<Mood, java.util.regex.Pattern>();
        words.put(Mood.LASER, word("techno|trance|house|edm|electro\\w*|elektro\\w*|dance|rave|dubstep|drum ?(and|&|'?n'?) ?bass|eurodance|hardstyle"));
        words.put(Mood.FIRE, word("rock\\w*|hardrock|metal\\w*|punk\\w*|grunge"));
        words.put(Mood.CLUB, word("jazz\\w*|swing|bebop|blues|bossa( nova)?|lounge|soul"));
        words.put(Mood.LANTERN, word("country|folk\\w*|bluegrass|western|americana"));
        // "Klassische Popmusik" is pop: the noun before the adjective.
        words.put(Mood.SPOTS, word("pop|popmusik|popsong|pop-?ballade|poppig|disco|funk\\w*|schlager|synthpop|r ?& ?b"));
        words.put(Mood.CANDLE, word("klassik|klassisch\\w*|classical|barock\\w*|baroque|romantik|romantisch\\w*|sinfon\\w*|symphon\\w*"
                + "|kammermusik|chamber|streichquartett|string quartet|orchest\\w*|sonate|sonata|fuge|fugue|choral\\w*|menuett|nocturne"));
        // Instruments that give a style away count as its name.
        words.put(Mood.CLUB, both(words.get(Mood.CLUB), word("sax|saxophon\\w*|saxophone|vibraphon\\w*|vibraphone")));
        words.put(Mood.LANTERN, both(words.get(Mood.LANTERN), word("banjo|steel guitar|steelgitarre|mundharmonika|harmonica|mandolin\\w*")));
        return words;
    }

    private static java.util.regex.Pattern both(java.util.regex.Pattern one, java.util.regex.Pattern two) {
        return java.util.regex.Pattern.compile(one.pattern() + "|" + two.pattern(), one.flags());
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

    private final SoundCharacter character;
    private SoundCharacter.Heard heard = SoundCharacter.Heard.NOTHING;
    private Mood shown;
    private String readStyle = "";
    private Mood named;
    private Mood wanted;
    private double wantedSince;

    public StageMood() {
        this(new SimpleCharacter());
    }

    /** @param character who reads the music where the piece does not say what it is */
    public StageMood(SoundCharacter character) {
        this.character = character;
    }

    /** What was heard in the music at the last picture, whatever the piece says of itself. */
    public SoundCharacter.Heard heard() {
        return heard;
    }

    /** The style the words about the piece name, as a word for the player; empty if they name none. */
    public String named() {
        return named == null ? "" : switch (named) {
            case FIRE -> "Rock";
            case LASER -> "Techno";
            case CANDLE -> "Klassik";
            case CLUB -> "Jazz";
            case LANTERN -> "Country";
            case SPOTS -> "Pop";
            default -> "";
        };
    }

    /** The mood of the stage for this picture. */
    public Mood at(SoundPicture sound, VisualSettings settings) {
        Mood fixed = fixed(settings.atmosphere());
        heard = character.hear(sound, settings);
        if (!sound.style.equals(readStyle)) {
            readStyle = sound.style;
            named = fromWords(readStyle);
        }
        if (fixed != null) {
            shown = fixed;
            wanted = fixed;
            return fixed;
        }
        if (named != null) {
            // The piece says what it is: that is surer than anything heard in its sound.
            shown = named;
            wanted = named;
            return named;
        }
        Mood heard = this.heard.mood() == null ? (shown == null ? Mood.CANDLE : shown) : this.heard.mood();
        if (shown == null) {
            shown = heard;
            wanted = heard;
            wantedSince = sound.seconds;
        } else if (heard != wanted) {
            wanted = heard;
            wantedSince = sound.seconds;
        } else if (wanted != shown && sound.seconds - wantedSince >= settings.moodSeconds()) {
            shown = wanted;
        }
        return shown;
    }
}
