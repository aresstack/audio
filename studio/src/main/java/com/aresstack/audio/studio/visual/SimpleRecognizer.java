package com.aresstack.audio.studio.visual;

import java.util.Locale;

/**
 * The character of the music from three things that are easy to hear: how fast the beats follow
 * each other, how heavy the low end is over a few seconds, and whether there is a beat at all.
 * Fast beats call for lasers, a heavy low end with a beat for fire, everything else for candle
 * light. It names no genre: jazz, country and pop cannot be told apart this way.
 */
public final class SimpleRecognizer implements GenreRecognizer {
    /** How many beats the tempo is read from. */
    private static final int BEATS = 8;
    private final double[] gaps = new double[BEATS];
    private int gapCount;
    private long seenBeats = -1;
    private double lastBeat = Double.NaN;
    private double bass = -1;
    private double lastSeconds = Double.NaN;
    private Heard last = Heard.NOTHING;

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

    @Override
    public Heard hear(SoundPicture sound, VisualSettings settings) {
        listen(sound);
        if (!sound.sounding) {
            return last; // silence changes nothing
        }
        double tempo = beatsPerMinute();
        StageMood.Mood mood;
        String character;
        if (tempo >= settings.laserBpm()) {
            mood = StageMood.Mood.LASER;
            character = "schnell";
        } else if (bass >= settings.fireBass() && gapCount >= 3) {
            mood = StageMood.Mood.FIRE;
            character = "basslastig";
        } else {
            mood = StageMood.Mood.CANDLE;
            character = "ruhig";
        }
        String words = tempo > 0 ? String.format(Locale.ROOT, "%.0f BPM · %s", tempo, character) : character;
        last = new Heard(Genre.UNKNOWN, 0, tempo, mood, words);
        return last;
    }

    private void listen(SoundPicture sound) {
        double seconds = sound.seconds;
        double step = Double.isNaN(lastSeconds) ? 0 : Math.max(0, Math.min(1, seconds - lastSeconds));
        lastSeconds = seconds;
        if (sound.sounding) {
            // A running average over about three seconds: the character of the passage, not of one note.
            bass = bass < 0 ? sound.bass : bass + (sound.bass - bass) * Math.min(1, step / 3);
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
