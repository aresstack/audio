package com.aresstack.audio.studio.figure;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The figures of a piece: taken from the library, different for players of one instrument, and kept with the piece. */
public final class FigureCastTest {
    private static final String FIGURE = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 256 512'>"
            + "<rect x='96' y='40' width='64' height='440' fill='#335577'/></svg>";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void anInstrumentNameGivesItsKind() {
        Assert.assertEquals("violine", FigureLibrary.kind("Violine 2"));
        Assert.assertEquals("streicher-ensemble", FigureLibrary.kind("Streicher (Ensemble)"));
        Assert.assertEquals("floete", FigureLibrary.kind("Flöte"));
        Assert.assertEquals("musiker", FigureLibrary.kind(" "));
    }

    @Test
    public void theLibraryKeepsFiguresByKindAndDrawsThem() throws Exception {
        FigureLibrary library = new FigureLibrary(folder.newFolder("figures").toPath());

        String first = library.add("violine", FIGURE, "test", "Violine");
        String second = library.add("violine", FIGURE, "test", "Violine");

        Assert.assertEquals(List.of("violine/1", "violine/2"), library.figures("violine"));
        Assert.assertEquals(List.of(first, second), library.figures("violine"));
        Assert.assertNotNull(library.picture(first));
        Assert.assertEquals(FigureLibrary.PICTURE_HEIGHT, library.picture(first).getHeight());
        Assert.assertNull(library.picture("violine/9"));
    }

    @Test
    public void playersOfOneInstrumentGetDifferentFiguresWhileThereAreEnough() throws Exception {
        FigureLibrary library = new FigureLibrary(folder.newFolder("figures").toPath());
        library.add("violine", FIGURE, "test", "Violine");
        library.add("violine", FIGURE, "test", "Violine");
        List<FigureCast.Seat> seats = List.of(new FigureCast.Seat("v1", "Violine 1"),
                new FigureCast.Seat("v2", "Violine 2"), new FigureCast.Seat("tr", "Trompete"));

        FigureCast cast = FigureCast.EMPTY.cast(seats, library, new Random(7));

        Assert.assertNotNull(cast.figure("v1"));
        Assert.assertNotNull(cast.figure("v2"));
        Assert.assertNotEquals(cast.figure("v1"), cast.figure("v2"));
        Assert.assertNull(cast.figure("tr"));
        Assert.assertEquals(List.of(new FigureCast.Seat("tr", "Trompete")), cast.missing(seats));
    }

    @Test
    public void thePieceKeepsItsFigures() throws Exception {
        Path piece = folder.newFolder("piece").toPath();
        FigureCast cast = FigureCast.EMPTY.with("v1", "violine/2");

        cast.save(piece);

        Assert.assertEquals(cast, FigureCast.of(piece));
        Assert.assertEquals(FigureCast.EMPTY, FigureCast.of(folder.newFolder("other").toPath()));
    }
}
