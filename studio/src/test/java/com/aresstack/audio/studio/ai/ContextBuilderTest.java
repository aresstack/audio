package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.Voice;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.LegacyImport;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** C1: the context is a pure function of piece and window (7.2: byte-equal for the same piece and scope). */
public final class ContextBuilderTest {
    private static final long BAR = 1920;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    static Composition studioPiece() throws Exception {
        Path folder = Paths.get("src", "test", "resources", "legacy", "studio-piece");
        return LegacyImport.read(folder, folder, null, null).orElseThrow().composition();
    }

    @Test
    public void theSamePieceAndWindowGiveTheSameBytes() throws Exception {
        Composition piece = studioPiece();
        MusicalContext first = ContextBuilder.build(piece, 0, 8 * BAR, 2, null);
        // the same piece once more, through its file: equal model, equal context
        Path folder = temporary.newFolder().toPath();
        CompositionJson.save(folder, piece);
        MusicalContext again = ContextBuilder.build(CompositionJson.load(folder), 0, 8 * BAR, 2, null);
        assertEquals(first.text(), again.text());
        assertEquals(first.digest(), again.digest());
        assertEquals(ContextBuilder.sha256(first.text()), first.digest());
        assertNotEquals(first.digest(), ContextBuilder.build(piece, 4 * BAR, 8 * BAR, 0, null).digest());
        assertNotEquals(first.digest(), ContextBuilder.build(piece, 0, 8 * BAR, 2, "melody").digest());
    }

    @Test
    public void itHasFourLayersAndTheRealNotesOfEveryPart() throws Exception {
        Composition piece = studioPiece();
        String text = ContextBuilder.build(piece, 0, 8 * BAR, 2, "melody").text();
        int profile = text.indexOf("# PIECE");
        int form = text.indexOf("# FORM");
        int parts = text.indexOf("# PARTS");
        int notes = text.indexOf("# NOTES");
        assertTrue(profile >= 0 && profile < form && form < parts && parts < notes);
        assertTrue(text.contains("title: Morgen im Viertel"));
        assertTrue(text.contains("tempo: bar 1 108 BPM"));
        assertTrue(text.contains("key: bar 1 C major"));
        assertTrue(text.contains("chords by bar: C | G | Am | F | C | G | F | G"));
        assertTrue(text.contains("part melody MOTIF-SOURCE"));
        for (Part part : piece.parts()) {
            assertTrue("summary of " + part.id(), text.contains("part " + part.id() + " \""));
            assertTrue("notes of " + part.id(), text.contains("\npart " + part.id() + (part.id().equals("melody")
                    ? " MOTIF-SOURCE" : "") + "\n  "));
        }
        // the first melody note of the fixture: tick 0, 240 ticks, pitch 60, velocity 80
        assertTrue(text.contains("\npart melody MOTIF-SOURCE\n  1 0 0.5 C4/60 80\n"));
        long notesInPiece = piece.parts().stream().mapToLong(p -> piece.notesOf(p.id()).size()).sum();
        long noteLines = text.substring(notes).lines().filter(l -> l.startsWith("  ") && !l.contains("rests")).count();
        assertEquals("every note of the window, once", notesInPiece, noteLines);
    }

    @Test
    public void notesOutsideTheWindowAreSummarisedOnlyAndHeldNotesAreMarked() {
        Part pad = new Part("pad", "Pad", "texture", "Pad", 89, null, Part.StaffLayout.SINGLE,
                List.of(new Voice("v", null, null, null)), null);
        List<Note> notes = List.of(new Note("n1", 0, BAR / 4, 60, 80, "v"), new Note("n2", 5 * BAR, 2 * BAR, 64, 80, "v"),
                new Note("n3", 9 * BAR, BAR / 4, 67, 80, "v"));
        Composition piece = new Composition(null, TimeMap.of(120),
                List.of(new Section("s1", "A", null, 0, 12 * BAR, null, List.of(), null)), List.of(pad),
                List.of(new Track("t", "Pad", Track.Kind.MIDI, "pad", List.of(new MidiClip("c", 0, 12 * BAR, null, notes)))),
                List.of());
        MusicalContext context = ContextBuilder.build(piece, 7 * BAR, 8 * BAR, 1, null);
        String window = context.text().substring(context.text().indexOf("# NOTES"));
        assertTrue(window.startsWith("# NOTES bars 7-9"));
        assertTrue("held from bar 6 into the window", window.contains("  held 6 0 8 E4/64 80"));
        assertFalse(window.contains("C4/60"));
        assertFalse(window.contains("G4/67"));
        assertTrue(context.text().contains("notes per bar: 1 0 0 0 0 1 0 0 0 1 0 0"));
        assertEquals(6 * BAR, context.fromTick());
        assertEquals(9 * BAR, context.toTick());
    }
}
