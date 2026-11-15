package com.aresstack.audio.studio.template;

import com.aresstack.audio.composition.Key;
import java.util.ArrayList;
import java.util.List;

/**
 * A score given as a template, as it was read: its parts and notes and what it says about tempo,
 * meter and key. What the template does not say is unknown here ({@code 0} or {@code null});
 * the studio decides it later. {@code remarks} says in words what was noticed or set right on
 * the way, for the protocol.
 *
 * @param title       what the template calls the piece; may be empty
 * @param bpm         quarter notes per minute, {@code 0} if the template names no tempo
 * @param numerator   beats of the first bar, {@code 0} if the template names no meter
 * @param denominator note value of a beat, {@code 0} if the template names no meter
 * @param key         the first key the template names, {@code null} if none
 * @param format      what the template was read as, e.g. "MIDI" or "MusicXML"
 */
public record TemplateScore(String title, int bpm, int numerator, int denominator, Key key, List<ScorePart> parts,
                            List<String> remarks, String format) {
    /** Ticks per quarter note in every template once it is read, as in every arrangement. */
    public static final int PPQ = 480;

    public TemplateScore {
        title = title == null ? "" : title.trim();
        parts = List.copyOf(parts);
        remarks = List.copyOf(remarks);
        format = format == null ? "" : format;
    }

    public TemplateScore withParts(List<ScorePart> changed, List<String> moreRemarks) {
        List<String> all = new ArrayList<String>(remarks);
        all.addAll(moreRemarks);
        return new TemplateScore(title, bpm, numerator, denominator, key, changed, all, format);
    }

    public TemplateScore withKey(Key changed) {
        return new TemplateScore(title, bpm, numerator, denominator, changed, parts, remarks, format);
    }

    public int noteCount() {
        int count = 0;
        for (ScorePart part : parts) {
            count += part.notes().size();
        }
        return count;
    }

    public long end() {
        long end = 0;
        for (ScorePart part : parts) {
            end = Math.max(end, part.end());
        }
        return end;
    }

    /** The ticks of one bar of the first meter, 4/4 if the template names none. */
    public long barTicks() {
        int n = numerator > 0 ? numerator : 4;
        int d = denominator > 0 ? denominator : 4;
        return (long) n * PPQ * 4 / d;
    }

    /** How many bars the notes reach into, at least one. */
    public int bars() {
        long bar = barTicks();
        return (int) Math.max(1, (end() + bar - 1) / bar);
    }
}
