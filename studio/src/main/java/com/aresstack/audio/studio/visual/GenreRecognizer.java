package com.aresstack.audio.studio.visual;

/**
 * Recognizes what kind of music plays from what is heard. The studio's own recognizer
 * ({@link SimpleRecognizer}) keeps to what is easy to hear (the tempo, a heavy low end, quiet)
 * and names no genre. A recognizer that tells genres apart, such as a trained model, can take its
 * place behind this interface without the stage or the player changing. One instance belongs to
 * one listener and is asked once per picture, in the order of the pictures.
 */
public interface GenreRecognizer {
    /** The genres the studio tells apart, each with its name for the player and its atmosphere on the stage. */
    enum Genre {
        ROCK("Rock", StageMood.Mood.FIRE),
        ELECTRONIC("Techno", StageMood.Mood.LASER),
        CLASSICAL("Klassik", StageMood.Mood.CANDLE),
        JAZZ("Jazz", StageMood.Mood.CLUB),
        COUNTRY("Country", StageMood.Mood.LANTERN),
        POP("Pop", StageMood.Mood.SPOTS),
        /** Not recognized: the recognizer names no genre. */
        UNKNOWN("", null);

        private final String label;
        private final StageMood.Mood mood;

        Genre(String label, StageMood.Mood mood) {
            this.label = label;
            this.mood = mood;
        }

        /** Its name for the player; empty for {@link #UNKNOWN}. */
        public String label() {
            return label;
        }

        /** The atmosphere it asks for; {@code null} for {@link #UNKNOWN}. */
        public StageMood.Mood mood() {
            return mood;
        }

        /** The genre an atmosphere stands for, {@link #UNKNOWN} if none does. */
        public static Genre of(StageMood.Mood mood) {
            for (Genre genre : values()) {
                if (genre.mood == mood && mood != null) {
                    return genre;
                }
            }
            return UNKNOWN;
        }
    }

    /**
     * What was heard.
     *
     * @param genre          the genre, {@link Genre#UNKNOWN} if the recognizer names none
     * @param confidence     how sure the genre is, 0 to 1; 0 for {@link Genre#UNKNOWN}
     * @param beatsPerMinute the tempo heard lately, 0 while it is not known
     * @param mood           the atmosphere the sound asks for where no genre is named, {@code null} while nothing is heard
     * @param words          the character in a few words for the player, empty while nothing is heard
     */
    record Heard(Genre genre, double confidence, double beatsPerMinute, StageMood.Mood mood, String words) {
        public static final Heard NOTHING = new Heard(Genre.UNKNOWN, 0, 0, null, "");

        public Heard {
            genre = genre == null ? Genre.UNKNOWN : genre;
            confidence = genre == Genre.UNKNOWN || !Double.isFinite(confidence) ? 0 : Math.max(0, Math.min(1, confidence));
            words = words == null ? "" : words;
        }

        /** The atmosphere for the stage: that of the genre if one is named, else the one read from the sound. */
        public StageMood.Mood atmosphere() {
            return genre == Genre.UNKNOWN ? mood : genre.mood();
        }
    }

    /** Listens to one picture of the sound and says what the music is by now. */
    Heard hear(SoundPicture sound, VisualSettings settings);
}
