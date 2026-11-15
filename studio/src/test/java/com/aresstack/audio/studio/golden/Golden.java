package com.aresstack.audio.studio.golden;

import com.aresstack.audio.studio.score.Score;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The safety net of work package F0: what the studio does today with the shipped pieces is
 * written down once, and every later change is compared with it.
 *
 * <p>A golden file lives under {@code src/test/resources/golden}. If one is missing, the test
 * fails and prints what it would have written; with {@code -Dgolden.update=true} it writes it
 * instead. A golden file that exists is never rewritten silently: changing what the studio
 * produces is a decision, and it shows in the diff of the golden file.
 *
 * <p>This class needs nothing but the JDK and the studio itself, so that it can also be
 * compiled and run without the test libraries.
 */
public final class Golden {
    /** The pieces that every golden test is run with. */
    public static final List<String> FIXTURES = Arrays.asList(
            "demo-arrangement", // the hand-written demo of the music module, starts with a byte order mark
            "district-dawn",    // the piece the studio ships and composes from
            "edge-cases");      // comments, blank lines, roles, a shared channel, drums, tempo 96

    private static final Path ROOT = Paths.get("src", "test", "resources", "golden");

    private Golden() {
    }

    public static Path root() {
        return ROOT;
    }

    public static Path fixture(String name) {
        return ROOT.resolve("fixtures").resolve(name + ".tsv");
    }

    /** Compares a text with the golden file of that name, or writes it if asked to. */
    public static void assertMatches(String name, String actual) throws IOException {
        Path file = ROOT.resolve(name);
        String normalized = actual.replace("\r\n", "\n");
        if (!Files.isRegularFile(file)) {
            if (Boolean.getBoolean("golden.update")) {
                Files.createDirectories(file.getParent());
                Files.write(file, normalized.getBytes(StandardCharsets.UTF_8));
                return;
            }
            throw new AssertionError("Missing golden file " + file + " (run with -Dgolden.update=true to write it)."
                    + " It would contain:\n<<<\n" + normalized + ">>>");
        }
        String expected = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!expected.equals(normalized)) {
            throw new AssertionError("Output differs from golden file " + file + ".\nexpected:\n<<<\n" + expected
                    + ">>>\nactual:\n<<<\n" + normalized + ">>>");
        }
    }

    public static String sha256(Path file) throws IOException {
        return sha256(Files.readAllBytes(file));
    }

    public static String sha256(byte[] bytes) {
        try {
            StringBuilder text = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                text.append(String.format("%02x", b & 0xff));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** One line per file: its name and the hash of its bytes, sorted by name. */
    public static String hashes(Path directory, String... names) throws IOException {
        List<String> sorted = new ArrayList<String>(Arrays.asList(names));
        java.util.Collections.sort(sorted);
        StringBuilder text = new StringBuilder();
        for (String name : sorted) {
            text.append(name).append('\t').append(sha256(directory.resolve(name))).append('\n');
        }
        return text.toString();
    }

    /**
     * Runs the reference renderer of the music module (TSV to MIDI and Java-synthesis WAVs) as
     * the studio does, only in this JVM instead of a child process.
     */
    public static void renderReference(Path arrangement, Path output) throws Exception {
        try {
            Class.forName("MultiTrackMusicRenderer").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[]{arrangement.toString(), output.toString()});
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw failed;
        }
    }

    /** The whole structure of a score as text: staves, clefs, voices, and every event of every bar. */
    public static String describe(Score score) {
        if (score == null) {
            return "no score\n";
        }
        StringBuilder text = new StringBuilder();
        text.append("tempo ").append(score.tempo()).append(", bars ").append(score.bars()).append('\n');
        for (Score.Staff staff : score.staves()) {
            text.append("staff ").append(staff.trackId()).append(' ').append(staff.clef())
                    .append(", voices ").append(staff.voices()).append('\n');
            for (int voice = 0; voice < staff.voices(); voice++) {
                for (int bar = 0; bar < score.bars(); bar++) {
                    text.append("  v").append(voice).append(" bar ").append(bar + 1).append(':');
                    if (staff.silent(voice, bar)) {
                        text.append(" (silent)");
                    }
                    for (Score.Event event : staff.bar(voice, bar)) {
                        text.append(' ').append(event.start()).append('+').append(event.length());
                        if (event.rest()) {
                            text.append(" r");
                        } else {
                            int[] pitches = event.pitches();
                            for (int i = 0; i < pitches.length; i++) {
                                text.append(i == 0 ? " " : ",").append(pitches[i]);
                                if (event.tied(i)) {
                                    text.append('~');
                                }
                            }
                        }
                        text.append(';');
                    }
                    text.append('\n');
                }
            }
        }
        return text.toString();
    }
}
