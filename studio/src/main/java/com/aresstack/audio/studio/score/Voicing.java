package com.aresstack.audio.studio.score;

import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Voice;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which voice of a part each note belongs to, written as the short words of
 * {@code arrangement.tsv} and of the answers of the composer (work package S1): {@code 1},
 * {@code 2}, {@code 3}, ... on a single staff, from the top voice down; {@code rh}, {@code rh2},
 * ... on the upper system of a grand staff (right hand) and {@code lh}, {@code lh2}, ... on the
 * lower one (left hand).
 *
 * <p>Where the notes of a part name their voices, those are the voices. Where none does, the
 * heuristic of the score decides, as for every piece from before S1: on a single staff one voice
 * or two ({@link Score#voicesOf}); on a grand staff the notes from middle C up go to the right
 * hand, the others to the left hand, and each hand gets one voice or two by the same heuristic.
 * The model keeps the answer once, as the voice of each note; the score then reads it.
 */
public final class Voicing {
    /** The lowest note a grand staff writes in the upper system when the notes name no voice. */
    public static final int SPLIT = 60;
    /** The most voices one staff (or one system of a grand staff) takes. */
    public static final int MOST_PER_STAFF = 8;

    /** Upper system first, then the lower one; within each, the top voice first. */
    public static final Comparator<String> ORDER = Comparator.comparingInt(Voicing::rank);

    private Voicing() {
    }

    /** The staff layout a part of this General MIDI program is written in: piano, organ and harp on a grand staff. */
    public static Part.StaffLayout layoutFor(int program, boolean drums) {
        boolean grand = !drums && (program >= 0 && program <= 7       // pianos, harpsichord, clavinet
                || program >= 16 && program <= 20                      // organs
                || program == 46);                                     // harp
        return grand ? Part.StaffLayout.GRAND : Part.StaffLayout.SINGLE;
    }

    /**
     * Whether a part of the model is struck, not held, so that it has one voice and a percussion
     * staff: drums by their role, by the drum channel an old piece kept, or, without a role, by
     * the id the old format takes for the role.
     */
    public static boolean struck(Part part) {
        return part.drums() || Integer.valueOf(9).equals(part.legacyChannel())
                || part.role() == null && "drums".equals(part.id());
    }

    /**
     * The voice a note names in the words of this class, or {@code null} if it names none that
     * fits the staff: on a grand staff only hands ({@code rh}, {@code lh}, also "right", "left",
     * "upper", "lower", "treble", "bass", with a number for a further voice of the hand), on a
     * single staff only numbers ({@code 1} to {@code 8}, also "v2", "voice 2").
     */
    public static String normalize(String raw, boolean grand) {
        if (raw == null) {
            return null;
        }
        String word = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-.]", "");
        if (word.isEmpty()) {
            return null;
        }
        String digits = word.replaceAll("^\\D*", "");
        String letters = word.substring(0, word.length() - digits.length());
        if (!digits.matches("\\d{0,2}")) {
            return null;
        }
        int number = digits.isEmpty() ? 1 : Integer.parseInt(digits);
        if (number < 1 || number > MOST_PER_STAFF) {
            return null;
        }
        String hand;
        switch (letters) {
            case "rh": case "r": case "right": case "righthand": case "upper": case "treble":
            case "rechts": case "rechtehand": case "oben":
                hand = "rh";
                break;
            case "lh": case "l": case "left": case "lefthand": case "lower": case "bass":
            case "links": case "linkehand": case "unten":
                hand = "lh";
                break;
            case "": case "v": case "voice": case "stimme":
                hand = null;
                break;
            default:
                return null;
        }
        if (grand) {
            return hand == null ? null : hand + (number == 1 ? "" : String.valueOf(number));
        }
        return hand == null && !digits.isEmpty() ? String.valueOf(number) : null;
    }

    /** The system of a grand staff a voice is written on; {@code null} for a voice of a single staff. */
    public static Voice.Staff staffOf(String token) {
        return token.startsWith("rh") ? Voice.Staff.UPPER : token.startsWith("lh") ? Voice.Staff.LOWER : null;
    }

    /** The place of a voice on its staff or system, from 1 for the top voice. */
    public static int numberOf(String token) {
        String digits = token.replaceAll("^\\D*", "");
        return digits.isEmpty() ? 1 : Integer.parseInt(digits);
    }

    /** The word for the voice at this place of a staff ({@code null}) or of a system of a grand staff. */
    public static String token(Voice.Staff staff, int number) {
        if (staff == null) {
            return String.valueOf(number);
        }
        return (staff == Voice.Staff.UPPER ? "rh" : "lh") + (number == 1 ? "" : String.valueOf(number));
    }

    private static int rank(String token) {
        Voice.Staff staff = staffOf(token);
        return (staff == Voice.Staff.LOWER ? 100 : 0) + numberOf(token);
    }

    /** The German name of a voice, as a voice of the model is called: "Rechte Hand", "Stimme 2". */
    public static String name(String token) {
        Voice.Staff staff = staffOf(token);
        int number = numberOf(token);
        if (staff == null) {
            return "Stimme " + number;
        }
        return (staff == Voice.Staff.UPPER ? "Rechte Hand" : "Linke Hand") + (number == 1 ? "" : " " + number);
    }

    /**
     * The stem direction of a voice among the voices of its staff (or system), as printed music
     * sets it: alone it is free; with others the first, third, ... voice stems up, the second,
     * fourth, ... down.
     */
    public static Voice.Stem stem(String token, List<String> onTheSameStaff) {
        if (onTheSameStaff.size() < 2) {
            return Voice.Stem.AUTO;
        }
        int place = onTheSameStaff.indexOf(token);
        return place % 2 == 0 ? Voice.Stem.UP : Voice.Stem.DOWN;
    }

    /**
     * The voice of each note of a part.
     *
     * @param notes   tick, length and pitch of each note, in the order of the file
     * @param written the voice each note names, as written (any spelling {@link #normalize} reads),
     *                or {@code null}; {@code null} for no column at all
     * @param struck  whether the part is struck (drums): it has one voice
     * @param grand   whether the part is written on a grand staff
     * @return the voice of each note, in the order given, in the words of this class
     */
    public static String[] assign(List<long[]> notes, String[] written, boolean struck, boolean grand) {
        String[] voices = new String[notes.size()];
        if (struck) {
            java.util.Arrays.fill(voices, "1");
            return voices;
        }
        boolean any = false;
        for (int i = 0; written != null && i < voices.length; i++) {
            voices[i] = normalize(written[i], grand);
            any |= voices[i] != null;
        }
        if (!any) {
            return heuristic(notes, false, grand);
        }
        for (int i = 0; i < voices.length; i++) {
            if (voices[i] == null) { // a note the answer gave no voice: the hand its pitch suggests, else the top voice
                voices[i] = grand ? (notes.get(i)[2] >= SPLIT ? "rh" : "lh") : "1";
            }
        }
        return voices;
    }

    /** Whether any note names a voice that fits the staff, so that the voices are the written ones. */
    public static boolean named(String[] written, boolean grand) {
        for (int i = 0; written != null && i < written.length; i++) {
            if (normalize(written[i], grand) != null) {
                return true;
            }
        }
        return false;
    }

    /** The voice of each note as the score has always guessed it; see the class comment. */
    public static String[] heuristic(List<long[]> notes, boolean struck, boolean grand) {
        String[] voices = new String[notes.size()];
        if (!grand || struck) {
            int[] guessed = Score.voicesOf(notes, struck);
            for (int i = 0; i < voices.length; i++) {
                voices[i] = String.valueOf(guessed[i] + 1);
            }
            return voices;
        }
        for (Voice.Staff staff : Voice.Staff.values()) {
            List<long[]> own = new ArrayList<>();
            List<Integer> places = new ArrayList<>();
            for (int i = 0; i < notes.size(); i++) {
                if ((notes.get(i)[2] >= SPLIT) == (staff == Voice.Staff.UPPER)) {
                    own.add(notes.get(i));
                    places.add(i);
                }
            }
            int[] guessed = Score.voicesOf(own, false);
            for (int i = 0; i < guessed.length; i++) {
                voices[places.get(i)] = token(staff, guessed[i] + 1);
            }
        }
        return voices;
    }

    /**
     * The word of each voice of a part of the model: the voices of a single staff are counted
     * from the top in the order of the part, those of a grand staff for each system (a voice of a
     * grand staff that names no system counts to the upper one).
     */
    public static Map<String, String> tokens(Part part) {
        Map<String, String> tokens = new LinkedHashMap<>();
        boolean grand = part.staffLayout() == Part.StaffLayout.GRAND;
        int upper = 0;
        int lower = 0;
        int single = 0;
        for (Voice voice : part.voices()) {
            if (!grand) {
                tokens.put(voice.id(), token(null, Math.min(MOST_PER_STAFF, ++single)));
            } else if (voice.staff() == Voice.Staff.LOWER) {
                tokens.put(voice.id(), token(Voice.Staff.LOWER, Math.min(MOST_PER_STAFF, ++lower)));
            } else {
                tokens.put(voice.id(), token(Voice.Staff.UPPER, Math.min(MOST_PER_STAFF, ++upper)));
            }
        }
        return tokens;
    }
}
