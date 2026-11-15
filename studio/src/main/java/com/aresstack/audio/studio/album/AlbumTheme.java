package com.aresstack.audio.studio.album;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The main theme of an album (its leitmotif): a short melody, written out as notes, that comes
 * back in several pieces of the album, each time in the variant its title says. It is what makes
 * the pieces of a soundtrack belong together instead of being separate pieces.
 *
 * @param name        what it stands for, e.g. "Aurora theme"
 * @param description how it sounds and what it means, in words
 * @param key         the key the notes are written in, e.g. "D minor"
 * @param notes       the melody: beats in quarters from the start of the theme, MIDI pitches
 */
public record AlbumTheme(String name, String description, String key, List<Note> notes) {
    /** No theme: the pieces of the album are composed each for itself, as before. */
    public static final AlbumTheme NONE = new AlbumTheme("", "", "", List.of());
    /** The most notes a theme keeps; a theme is short. */
    public static final int MAX_NOTES = 64;

    /** One note of the theme. */
    public record Note(double beat, double duration, int pitch) {
        public Note {
            beat = Math.max(0, beat);
            duration = duration > 0 ? duration : 1;
            pitch = Math.max(0, Math.min(127, pitch));
        }
    }

    public AlbumTheme {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        key = key == null ? "" : key.trim();
        notes = notes == null ? List.of() : List.copyOf(notes.size() > MAX_NOTES ? notes.subList(0, MAX_NOTES) : notes);
    }

    /** Whether the album has a theme: one with notes. */
    public boolean isEmpty() {
        return notes.isEmpty();
    }

    public AlbumTheme withNotes(List<Note> changed) {
        return new AlbumTheme(name, description, key, changed);
    }

    /** The notes as the composer reads notes: {@code [{beat:0,duration:1,pitch:67},...]}. */
    public String notesText() {
        List<String> each = new ArrayList<String>();
        for (Note note : notes) {
            each.add(String.format(Locale.ROOT, "{beat:%s,duration:%s,pitch:%d}", number(note.beat()),
                    number(note.duration()), note.pitch()));
        }
        return "[" + String.join(",", each) + "]";
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
