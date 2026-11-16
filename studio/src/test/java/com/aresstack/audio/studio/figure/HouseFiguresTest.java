package com.aresstack.audio.studio.figure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/** The figures the studio brings along: one for every track, without the AI. */
public final class HouseFiguresTest {
    @Test
    public void anInstrumentFindsItsFamily() {
        assertEquals("drums", HouseFigures.family("Soft Drum Kit"));
        assertEquals("drums", HouseFigures.family("Bass Drum"));
        assertEquals("bass", HouseFigures.family("Acoustic Bass"));
        assertEquals("guitar", HouseFigures.family("Bass Guitar"));
        assertEquals("strings", HouseFigures.family("String Ensemble"));
        assertEquals("keys", HouseFigures.family("Music Box"));
        assertEquals("wind", HouseFigures.family("Flöte"));
        assertEquals("brass", HouseFigures.family("Trompete"));
        assertEquals("voice", HouseFigures.family("Chor"));
        assertEquals("musician", HouseFigures.family("Glasharmonika"));
        assertEquals("musician", HouseFigures.family(null));
    }

    @Test
    public void everyFamilyDrawsAFigure() {
        for (String words : new String[]{"drums", "flute", "trumpet", "guitar", "piano", "synth", "bass", "violin",
                "voice", "something else"}) {
            assertNotNull(words, HouseFigures.picture(words, "track"));
        }
    }

    @Test
    public void clearingTheLibraryMovesItsFiguresAside() throws Exception {
        java.nio.file.Path home = java.nio.file.Files.createTempDirectory("figures-test");
        FigureLibrary library = new FigureLibrary(home.resolve("figures"));
        String id = library.add("violine", HouseFigures.svg("strings", 0), "test", "Violine");
        library.add("violine", HouseFigures.svg("strings", 1), "test", "Violine");
        assertNotNull(library.picture(id));
        assertEquals(2, library.clear());
        assertEquals(0, library.figures("violine").size());
        assertEquals(null, library.picture(id));
        try (java.util.stream.Stream<java.nio.file.Path> kept = java.nio.file.Files.walk(home.resolve("figures-entfernt"))) {
            assertEquals(2, kept.filter(file -> file.toString().endsWith(".svg")).count());
        }
        assertEquals(0, library.clear());
    }

    @Test
    public void aTrackKeepsItsFigureAndTwoTracksDiffer() {
        assertSame(HouseFigures.picture("violin", "v1"), HouseFigures.picture("violin", "v1"));
        assertNotSame(HouseFigures.picture("violin", "v1"), HouseFigures.picture("violin", "v2"));
    }
}
