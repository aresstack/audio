package com.aresstack.audio.studio.visual;

/**
 * Reads the character of the music from what is heard. The studio's own reader
 * ({@link SimpleCharacter}) keeps to what is easy to hear: the tempo, a heavy low end, quiet. A
 * reader that knows more (a trained model that tells genres apart) can take its place behind this
 * interface. One instance belongs to one listener and is asked once per picture, in order.
 */
public interface SoundCharacter {
    /**
     * What was heard.
     *
     * @param beatsPerMinute the tempo heard lately, 0 while it is not known
     * @param mood           the atmosphere the sound asks for, {@code null} while nothing is heard
     * @param words          the character in a few words for the player, empty while nothing is heard
     */
    record Heard(double beatsPerMinute, StageMood.Mood mood, String words) {
        public static final Heard NOTHING = new Heard(0, null, "");
    }

    /** Listens to one picture of the sound and says what the music is like by now. */
    Heard hear(SoundPicture sound, VisualSettings settings);
}
