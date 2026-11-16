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

    /** How many beats the tempo is read from. */
    private static final int BEATS = 8;
    private final double[] gaps = new double[BEATS];
    private int gapCount;
    private long seenBeats = -1;
    private double lastBeat = Double.NaN;
    private double bass = -1;
    private double loudness = -1;
    private Mood shown;
    private String readStyle = "";
    private Mood named;
    private Mood wanted;
    private double wantedSince;
    private double lastSeconds = Double.NaN;

    /** The beats per minute heard lately, 0 while too few beats were heard. */
    public double beatsPerMinute() {
        if (gapCount < 3) {
            return 0;
        }
        double[] sorted = java.util.Arrays.copyOf(gaps, Math.min(gapCount, BEATS));
        java.util.Arrays.sort(sorted);
        double middle = sorted[sorted.length / 2];
        return middle <= 0 ? 0 : 60 / middle;
    }

    /** The mood of the stage for this picture. */
    public Mood at(SoundPicture sound, VisualSettings settings) {
        Mood fixed = fixed(settings.atmosphere());
        listen(sound);
        if (fixed != null) {
            shown = fixed;
            wanted = fixed;
            return fixed;
        }
        if (!sound.style.equals(readStyle)) {
            readStyle = sound.style;
            named = fromWords(readStyle);
        }
        if (named != null) {
            // The piece says what it is: that is surer than anything heard in its sound.
            shown = named;
            wanted = named;
            return named;
        }
        Mood heard = heard(sound, settings);
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

    /** What the music asks for right now, before it has to hold. */
    private Mood heard(SoundPicture sound, VisualSettings settings) {
        if (!sound.sounding) {
            return shown == null ? Mood.CANDLE : shown; // silence changes nothing
        }
        if (beatsPerMinute() >= settings.laserBpm()) {
            return Mood.LASER;
        }
        if (bass >= settings.fireBass() && gapCount >= 3) {
            return Mood.FIRE;
        }
        return Mood.CANDLE;
    }

    private void listen(SoundPicture sound) {
        double seconds = sound.seconds;
        double step = Double.isNaN(lastSeconds) ? 0 : Math.max(0, Math.min(1, seconds - lastSeconds));
        lastSeconds = seconds;
        if (sound.sounding) {
            // A running average over about three seconds: the character of the passage, not of one note.
            double share = bass < 0 ? 1 : Math.min(1, step / 3);
            bass += bass < 0 ? sound.bass + 1 : (sound.bass - bass) * share;
            loudness += loudness < 0 ? sound.loudness() + 1 : (sound.loudness() - loudness) * share;
        }
        if (seenBeats < 0) {
            seenBeats = sound.beats;
            return;
        }
        if (sound.beats != seenBeats) {
            seenBeats = sound.beats;
            if (!Double.isNaN(lastBeat)) {
                double gap = seconds - lastBeat;
                if (gap > 0 && gap < 2) {
                    gaps[gapCount % BEATS] = gap;
                    gapCount++;
                } else {
                    gapCount = 0; // a long pause: the tempo is read anew
                }
            }
            lastBeat = seconds;
        } else if (!Double.isNaN(lastBeat) && seconds - lastBeat > 4) {
            gapCount = 0; // no beat for a long time: there is no tempo to speak of
        }
    }
}
