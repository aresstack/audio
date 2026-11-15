package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Voice;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.golden.Golden;
import com.aresstack.audio.studio.project.LegacyImport;
import com.aresstack.audio.studio.project.ProjectFolder;
import com.aresstack.audio.studio.project.TsvExport;
import com.aresstack.audio.studio.score.Score;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * S1 acceptance (architecture review 7.2): an answer of the composer that names the voices is
 * written so in the score; without voices the heuristic still decides; a piano part is written on
 * a grand staff; a staff takes more than two voices. The voices travel from the answer through
 * {@code arrangement.tsv} into the model and from the model back into the file and the score.
 */
public final class ExplicitVoicesTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final MusicTrack PIANO = new MusicTrack("piano", MusicTrack.HARMONY, "Konzertflügel", 0, "builtin");
    private static final MusicTrack FLUTE = new MusicTrack("flute", MusicTrack.MELODY, "Flöte", 73, "builtin");

    /** The answer names right hand, second line of the right hand and left hand: the score shows exactly that. */
    @Test
    public void theAnswerOfTheComposerNamesTheVoicesAndThePianoGetsAGrandStaff() throws Exception {
        CompositionSpec spec = CompositionSpec.of(8, 100, Key.parse("G-Dur"), 4, 4);
        Path dir = folder.newFolder("lied").toPath();
        FakeChatModel model = new FakeChatModel("G", "C", "D", "G");
        Path tsv = new GemmaMusicComposer(model).compose("Lied", dir,
                new TrackArrangement(List.of(FLUTE, PIANO)), spec, message -> { });

        String pianoPrompt = model.prompts.stream().filter(p -> p.contains("Set part=\"piano\"")).findFirst().orElseThrow();
        assertTrue(pianoPrompt, pianoPrompt.contains("grand staff") && pianoPrompt.contains("\"rh\"")
                && pianoPrompt.contains("\"lh\""));
        String flutePrompt = model.prompts.stream().filter(p -> p.contains("Set part=\"flute\"")).findFirst().orElseThrow();
        assertFalse("a melody is asked for no voices", flutePrompt.contains("field voice"));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        int piano = lines.indexOf(lines.stream().filter(l -> l.startsWith("track\tpiano\t")).findFirst().orElseThrow());
        assertEquals("staff\tpiano\tgrand", lines.get(piano + 1));
        assertTrue(lines.stream().filter(l -> l.startsWith("note\tpiano\t"))
                .allMatch(l -> l.matches(".*\t(rh|rh2|lh)")));
        assertTrue("the flute names no voices",
                lines.stream().filter(l -> l.startsWith("note\tflute\t")).allMatch(l -> l.split("\t").length == 6));

        assertEquals("no note of the hands needed a correction",
                List.of("part\tnote\tfield\toriginal\tcorrected\treason"),
                Files.readAllLines(dir.resolve("note-corrections.tsv"), StandardCharsets.UTF_8));

        // The reference renderer plays the file as before: neither line nor column sounds.
        Golden.renderReference(tsv, folder.newFolder("lied-render").toPath());

        Score score = Score.read(tsv);
        assertGrandPiano(score);

        // Into the model and back: the model keeps the voices, and the score of the model is the same.
        Composition composition = LegacyImport.read(dir).orElseThrow().composition();
        Part part = composition.part("piano");
        assertEquals(Part.StaffLayout.GRAND, part.staffLayout());
        assertEquals(List.of("Rechte Hand", "Rechte Hand 2", "Linke Hand"),
                part.voices().stream().map(Voice::name).toList());
        assertEquals(List.of(Voice.Staff.UPPER, Voice.Staff.UPPER, Voice.Staff.LOWER),
                part.voices().stream().map(Voice::staff).toList());
        assertEquals(List.of(Voice.Stem.UP, Voice.Stem.DOWN, Voice.Stem.AUTO),
                part.voices().stream().map(Voice::stem).toList());
        assertEquals(Part.StaffLayout.SINGLE, composition.part("flute").staffLayout());
        assertGrandPiano(Score.of(composition));
        assertEquals(Golden.describe(score), Golden.describe(Score.of(composition)));
        assertEquals("the file says what the model says",
                TsvExport.canonical(ArrangementFile.read(tsv)), TsvExport.lines(composition));
    }

    private static void assertGrandPiano(Score score) {
        List<Score.Staff> piano = score.staves().stream().filter(s -> s.trackId().equals("piano")).toList();
        assertEquals("a piano part is a grand staff", 2, piano.size());
        Score.Staff upper = piano.get(0);
        Score.Staff lower = piano.get(1);
        assertEquals(Score.Placement.UPPER, upper.system());
        assertEquals(Score.Clef.TREBLE, upper.clef());
        assertEquals(Score.Placement.LOWER, lower.system());
        assertEquals(Score.Clef.BASS, lower.clef());
        // Two lines that begin and end together: the heuristic would write one chord, the answer says two voices.
        assertEquals(2, upper.voices());
        assertEquals("rh", upper.voice(0));
        assertEquals("rh2", upper.voice(1));
        assertEquals("Rechte Hand 2", upper.voiceName(1));
        assertEquals(Voice.Stem.UP, upper.stem(0));
        assertEquals(Voice.Stem.DOWN, upper.stem(1));
        assertArrayEquals(new int[]{74}, upper.bar(0, 0).get(0).pitches()); // G: D above, B below
        assertArrayEquals(new int[]{71}, upper.bar(1, 0).get(0).pitches());
        assertEquals(16, upper.bar(0, 0).get(0).length());
        assertEquals(16, upper.bar(1, 0).get(0).length());
        assertEquals(1, lower.voices());
        assertEquals(Voice.Stem.AUTO, lower.stem(0));
        assertArrayEquals("the left hand keeps its low G", new int[]{43}, lower.bar(0, 0).get(0).pitches());
        assertEquals("the flute stays one staff", 1,
                score.staves().stream().filter(s -> s.trackId().equals("flute")).count());
        assertFalse(score.staves().stream().filter(s -> s.trackId().equals("flute")).findFirst().orElseThrow().grand());
    }

    /** Without voices the heuristic decides as before S1; with them the written voices count. */
    @Test
    public void withoutVoicesTheHeuristicDecides() {
        List<String> together = List.of("tempo\t108", "track\tstrings\t48\t0\tharmony",
                "note\tstrings\t0\t1920\t72\t80", "note\tstrings\t0\t1920\t64\t80");
        Score guessed = Score.of(together);
        assertEquals("notes that begin and end together are one chord", 1, guessed.staves().get(0).voices());
        assertArrayEquals(new int[]{64, 72}, guessed.staves().get(0).bar(0).get(0).pitches());

        Score named = Score.of(List.of("tempo\t108", "track\tstrings\t48\t0\tharmony",
                "note\tstrings\t0\t1920\t72\t80\t1", "note\tstrings\t0\t1920\t64\t80\t2"));
        Score.Staff staff = named.staves().get(0);
        assertEquals("two voices, as written", 2, staff.voices());
        assertArrayEquals(new int[]{72}, staff.bar(0, 0).get(0).pitches());
        assertArrayEquals(new int[]{64}, staff.bar(1, 0).get(0).pitches());
        assertEquals(Voice.Stem.UP, staff.stem(0));
        assertEquals(Voice.Stem.DOWN, staff.stem(1));
    }

    /** A staff takes as many voices as are written, with stems up, down, up. */
    @Test
    public void aStaffTakesMoreThanTwoVoices() {
        Score score = Score.of(List.of("tempo\t108", "track\tchoir\t52\t0\tharmony",
                "note\tchoir\t0\t1920\t76\t80\t1", "note\tchoir\t0\t960\t72\t80\t2", "note\tchoir\t960\t960\t71\t80\t2",
                "note\tchoir\t0\t480\t67\t80\t3", "note\tchoir\t480\t1440\t65\t80\t3"));
        Score.Staff staff = score.staves().get(0);
        assertEquals(3, staff.voices());
        assertEquals(List.of("Stimme 1", "Stimme 2", "Stimme 3"),
                List.of(staff.voiceName(0), staff.voiceName(1), staff.voiceName(2)));
        assertEquals(List.of(Voice.Stem.UP, Voice.Stem.DOWN, Voice.Stem.UP),
                List.of(staff.stem(0), staff.stem(1), staff.stem(2)));
        assertEquals(2, staff.bar(1, 0).size());
        assertEquals(8, staff.bar(1, 0).get(0).length());
        assertEquals(4, staff.bar(2, 0).get(0).length());
        assertEquals(Score.Clef.TREBLE, staff.clef());
    }

    /** A grand staff without voices: from middle C up the right hand, below it the left hand. */
    @Test
    public void aGrandStaffWithoutVoicesSplitsAtMiddleC() {
        Score score = Score.of(List.of("tempo\t108", "track\tpiano\t0\t0\tharmony", "staff\tpiano\tgrand",
                "note\tpiano\t0\t1920\t72\t80", "note\tpiano\t0\t1920\t60\t80", "note\tpiano\t0\t1920\t48\t80"));
        assertEquals(2, score.staves().size());
        assertArrayEquals(new int[]{60, 72}, score.staves().get(0).bar(0).get(0).pitches());
        assertArrayEquals(new int[]{48}, score.staves().get(1).bar(0).get(0).pitches());
        // A grand staff without notes in a hand still has that system, with a whole rest.
        Score right = Score.of(List.of("tempo\t108", "track\tharp\t46\t0\tharmony", "staff\tharp\tgrand",
                "note\tharp\t0\t480\t79\t80"));
        assertEquals(2, right.staves().size());
        assertTrue(right.staves().get(1).bar(0).get(0).rest());
        // An old piano piece without a staff line stays on one staff, as before.
        assertEquals(1, Score.of(List.of("tempo\t108", "track\tpiano\t0\t0\tharmony",
                "note\tpiano\t0\t1920\t72\t80", "note\tpiano\t0\t1920\t48\t80")).staves().size());
    }

    /**
     * The model is the truth: a voice changed in the model reaches file and score, and voices that
     * are the guessed ones are not written, so that an old piece is written as it was.
     */
    @Test
    public void theModelWritesOnlyVoicesTheScoreWouldNotGuess() throws Exception {
        Path piece = folder.newFolder("alt").toPath();
        List<String> old = List.of("tempo\t90", "track\tstrings\t48\t0\tharmony",
                "note\tstrings\t0\t1920\t72\t80", "note\tstrings\t0\t1920\t64\t80");
        Files.write(piece.resolve(ProjectFolder.ARRANGEMENT), old, StandardCharsets.UTF_8);
        Composition composition = LegacyImport.read(piece).orElseThrow().composition();
        assertEquals(old, TsvExport.lines(composition));
        assertEquals(1, composition.part("strings").voices().size());

        // Give the lower note a second voice of its own, as an editor would.
        Part part = composition.part("strings");
        Voice second = new Voice("v-unten", "Stimme 2", null, Voice.Stem.DOWN);
        Part twoVoices = new Part(part.id(), part.name(), part.role(), part.instrument(), part.program(),
                part.libraryId(), part.staffLayout(), List.of(part.firstVoice(), second), part.legacyChannel());
        List<com.aresstack.audio.composition.Track> tracks = new ArrayList<>();
        com.aresstack.audio.composition.Track track = composition.track("strings");
        com.aresstack.audio.composition.MidiClip clip = (com.aresstack.audio.composition.MidiClip) track.clips().get(0);
        List<Note> notes = new ArrayList<>();
        for (Note note : clip.notes()) {
            notes.add(note.pitch() == 64 ? new Note(note.id(), note.tick(), note.length(), note.pitch(),
                    note.velocity(), second.id()) : note);
        }
        tracks.add(new com.aresstack.audio.composition.Track(track.id(), track.name(), track.kind(), track.defaultPartId(),
                List.of(new com.aresstack.audio.composition.MidiClip(clip.id(), clip.startTick(), clip.lengthTicks(),
                        clip.partId(), notes))));
        Composition edited = composition.withParts(List.of(twoVoices)).withTracks(tracks);

        List<String> written = TsvExport.lines(edited);
        assertEquals(List.of("tempo\t90", "track\tstrings\t48\t0\tharmony",
                "note\tstrings\t0\t1920\t72\t80\t1", "note\tstrings\t0\t1920\t64\t80\t2"), written);
        assertNotEquals(TsvExport.linesOf(composition, "strings"), TsvExport.linesOf(edited, "strings"));
        assertEquals(2, Score.of(edited).staves().get(0).voices());
        assertEquals(1, Score.of(composition).staves().get(0).voices());

        // And back: the file with the voices gives the model with the voices, ids kept.
        Files.write(piece.resolve(ProjectFolder.ARRANGEMENT), written, StandardCharsets.UTF_8);
        Composition again = LegacyImport.read(piece, piece, edited, null).orElseThrow().composition();
        assertEquals(edited.part("strings").voices().stream().map(Voice::id).toList(),
                again.part("strings").voices().stream().map(Voice::id).toList());
        assertEquals(written, TsvExport.lines(again));
    }

    /** Drums never get voices or a grand staff, whatever the answer says. */
    @Test
    public void drumsHaveOneVoice() {
        Score score = Score.of(List.of("tempo\t108", "track\tkit\t0\t9\tdrums", "staff\tkit\tgrand",
                "note\tkit\t0\t240\t36\t90\t2", "note\tkit\t0\t240\t42\t90\t1"));
        assertEquals(1, score.staves().size());
        assertEquals(Score.Clef.PERCUSSION, score.staves().get(0).clef());
        assertEquals(1, score.staves().get(0).voices());
    }
}
