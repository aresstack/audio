package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.album.AlbumFiles;
import com.aresstack.audio.studio.album.AlbumProducer;
import com.aresstack.audio.studio.album.AlbumSuggester.Field;
import com.aresstack.audio.studio.album.AlbumProductionState;
import com.aresstack.audio.studio.album.AlbumProductionState.Status;
import com.aresstack.audio.studio.album.PieceProduction;
import com.aresstack.audio.studio.application.PieceAutopilot;
import com.aresstack.audio.studio.application.SongLibrary;
import com.aresstack.audio.studio.progress.Progress;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The page "Alben": an album is created, the AI drafts its titles, the user changes the list, the
 * album is produced without further questions, and a finished title is opened in the editor.
 */
public final class AlbumsPanelTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path library;
    private SongLibrary songs;
    private final List<Path> opened = new ArrayList<Path>();
    private final List<String> asked = new ArrayList<String>();
    private AlbumsPanel panel;

    private final AlbumsPanel.Host host = new AlbumsPanel.Host() {
        @Override
        public ChatModel model() {
            return new FakeChatModel((instruction, prompt) -> {
                asked.add(prompt);
                return "{\"tracks\":[{\"title\":\"Christmas Overture\",\"mood\":\"festive\",\"tempoBpm\":120,"
                        + "\"key\":\"D major\",\"meter\":\"4/4\",\"instrumentation\":\"orchestra\",\"targetSeconds\":300},"
                        + "{\"title\":\"Winter Waltz\",\"mood\":\"lively\",\"tempoBpm\":90,\"key\":\"G major\","
                        + "\"meter\":\"3/4\",\"instrumentation\":\"strings\",\"targetSeconds\":300},"
                        + "{\"title\":\"Silent Winter Night\",\"mood\":\"calm\",\"tempoBpm\":60,\"key\":\"F major\","
                        + "\"meter\":\"4/4\",\"instrumentation\":\"piano\",\"targetSeconds\":300}]}";
            });
        }

        @Override
        public int tokens() {
            return 4096;
        }

        @Override
        public int parallelRequests() {
            return 4;
        }

        @Override
        public PieceAutopilot.Rendering rendering() {
            return PieceAutopilot.Rendering.BUILT_IN;
        }

        @Override
        public void openPiece(Path folder) {
            opened.add(folder);
        }

        @Override
        public Path chooseFolder(String title) {
            return null;
        }

        @Override
        public void log(String line) {
        }

        @Override
        public String ask(String question, String proposal) {
            return "Christmas Memories";
        }

        @Override
        public boolean confirm(String question) {
            return true;
        }

        @Override
        public void piecesChanged() {
        }
    };

    @Before
    public void aPage() throws Exception {
        UIManager.put("defaultFont", new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        library = temporary.newFolder("compositions").toPath();
        songs = new SongLibrary(library);
        onEvents(() -> {
            panel = new AlbumsPanel(host, songs, new InstrumentRegistry(library.resolve("none.properties")));
            // Proposals for the fields have their own test; elsewhere nobody proposes.
            panel.suggester((field, title, description, style, known) -> List.of());
            return null;
        });
    }

    private static <T> T onEvents(Callable<T> action) throws Exception {
        Object[] result = new Object[1];
        Exception[] problem = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = action.call();
            } catch (Exception failed) {
                problem[0] = failed;
            }
        });
        if (problem[0] != null) {
            throw problem[0];
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    private void until(Callable<Boolean> reached) throws Exception {
        for (long end = System.nanoTime() + 20_000_000_000L; !onEvents(reached); ) {
            assertTrue("never reached; the page says: " + onEvents(() -> panel.status()), System.nanoTime() < end);
            Thread.sleep(10);
        }
    }

    private Object cell(int row, int column) throws Exception {
        return onEvents(() -> panel.table().getValueAt(row, column));
    }

    @Test
    public void anAlbumIsPlannedChangedProducedAndItsPiecesAreOpenedInTheEditor() throws Exception {
        assertEquals(0, (int) onEvents(() -> panel.table().getRowCount()));
        onEvents(() -> {
            panel.createAlbum();
            return null;
        });
        assertEquals("Christmas Memories", onEvents(() -> panel.album().title()));
        assertEquals(12, (int) onEvents(() -> panel.album().concept().trackCount()));

        // Without words about the album the AI is not asked.
        onEvents(() -> {
            panel.planTracks();
            return null;
        });
        assertEquals(0, asked.size());
        assertTrue(onEvents(() -> panel.status()).startsWith("Erst sagen"));

        // What the album is about is said in the fields; the plan asks for three titles of five minutes.
        onEvents(() -> {
            field("description").setText("A Christmas album, instrumental");
            ((javax.swing.JSpinner) member("trackCount")).setValue(3);
            panel.planTracks();
            return null;
        });
        until(() -> panel.table().getRowCount() == 3);
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).contains("A Christmas album, instrumental") && asked.get(0).contains("\"tracksWanted\":3"));
        assertEquals("Winter Waltz", cell(1, 1));
        assertEquals("3/4", cell(1, 5));
        assertEquals("5:00 (150 Takte)", cell(0, 7));
        assertEquals("300 s × 90 BPM ÷ 180", "5:00 (150 Takte)", cell(1, 7));
        assertEquals("geplant", cell(2, 8));

        // The list is changed in its cells and kept at once: a shorter, slower last title.
        onEvents(() -> {
            panel.table().setValueAt("Stille Nacht im Schnee", 2, 1);
            panel.table().setValueAt("0:16", 2, 7);
            panel.table().setValueAt("120", 2, 3);
            panel.table().setValueAt("0:16", 0, 7);
            panel.table().setValueAt("0:16", 1, 7);
            panel.table().setValueAt("zu schnell", 1, 3);
            return null;
        });
        assertEquals("0:16 (8 Takte)", cell(2, 7));
        assertEquals("what is not a number changes nothing", 90, cell(1, 3));
        assertEquals("Stille Nacht im Schnee",
                new AlbumFiles(library).album("christmas-memories").orElseThrow().tracks().get(2).title());
        assertEquals(5 * 60, AlbumsPanel.seconds("5"));
        assertEquals(270, AlbumsPanel.seconds("4,5"));
        assertEquals(300, AlbumsPanel.seconds("5:00 (150 Takte)"));

        // Produced with pieces of a test: the second title fails, the others become pieces of the library.
        PieceProduction pieces = new PieceProduction() {
            @Override
            public ProducedPiece produce(PieceOrder order, Progress progress) throws Exception {
                progress.update("komponiert " + order.name());
                if (order.name().equals("Winter Waltz")) {
                    throw new java.io.IOException("Die KI hat keine Noten geliefert.");
                }
                Path mix = order.folder().resolve("arrangement-instruments.wav");
                Files.write(mix, order.name().getBytes(StandardCharsets.UTF_8));
                return new ProducedPiece(order.folder(), mix, order.spec().seconds());
            }
        };
        onEvents(() -> {
            panel.start(new AlbumProducer(new AlbumFiles(library), songs, pieces, 2));
            return null;
        });
        until(() -> panel.status().contains("1 Titel fehlgeschlagen"));
        assertEquals("fertig", cell(0, 8));
        assertEquals("Fehler: Die KI hat keine Noten geliefert.", cell(1, 8));
        assertEquals("fertig", cell(2, 8));
        assertEquals("the real length of the finished piece", "0:16", cell(0, 7));
        AlbumProductionState kept = AlbumProductionState.load(new AlbumFiles(library).folder("christmas-memories"));
        assertEquals(Status.FAILED, kept.status("t2"));
        assertEquals("the failed title has its folder too, where the next start goes on", 3, songs.songs().size());

        // A finished title keeps what it was composed with, but can be renamed; it opens in the editor.
        assertFalse(onEvents(() -> panel.table().isCellEditable(0, 3)));
        assertTrue(onEvents(() -> panel.table().isCellEditable(0, 1)));
        assertTrue(onEvents(() -> panel.table().isCellEditable(1, 3)));
        onEvents(() -> {
            panel.table().setRowSelectionInterval(0, 0);
            ((javax.swing.JButton) member("open")).doClick();
            return null;
        });
        assertEquals(1, opened.size());
        assertEquals(library.resolve(onEvents(() -> panel.album().tracks().get(0).piece())), opened.get(0));
        assertTrue(Files.isDirectory(opened.get(0)));
    }

    private Object member(String name) throws Exception {
        java.lang.reflect.Field field = AlbumsPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(panel);
    }

    private javax.swing.JTextField field(String name) throws Exception {
        return (javax.swing.JTextField) member(name);
    }

    /**
     * The AI proposes for the fields: once the title stands, rough directions for the style and
     * ideas for the description and other titles; when the user types a style, finer ones. The
     * lists only grow, never change what was typed, and are there again when the album is opened
     * again.
     */
    @Test
    public void theAiProposesStylesTitlesAndDescriptionsAndTheListsGrowAndAreKept() throws Exception {
        List<String> requests = new ArrayList<String>();
        com.aresstack.audio.studio.album.AlbumSuggester proposer = (field, title, description, style, known) -> {
            synchronized (requests) {
                requests.add(field + "|" + title + "|" + style + "|" + known.size());
            }
            if (field == Field.STYLE) {
                return style.isEmpty() ? List.of("Chor a cappella", "Orgelmusik", "Blechbläserensemble")
                        : List.of("Wiener Klassik, Streichquartett", "Barock, Cembalo und Streicher", "Orgelmusik");
            }
            return field == Field.TITLE ? List.of("Winterlichter") : List.of("Festliche Stücke für den Advent.");
        };
        onEvents(() -> {
            panel.suggester(proposer);
            panel.createAlbum();
            return null;
        });
        until(() -> !panel.proposingNow() && panel.box(Field.STYLE).getItemCount() == 3);
        assertEquals(List.of("Chor a cappella", "Orgelmusik", "Blechbläserensemble"),
                onEvents(() -> panel.box(Field.STYLE).proposals()));
        assertEquals(List.of("Winterlichter"), onEvents(() -> panel.box(Field.TITLE).proposals()));
        assertEquals(List.of("Festliche Stücke für den Advent."),
                onEvents(() -> panel.box(Field.DESCRIPTION).proposals()));
        // Nothing was written into the fields: the title is the user's, the style is still empty.
        assertEquals("Christmas Memories", onEvents(() -> panel.album().title()));
        assertEquals("", onEvents(() -> (String) panel.box(Field.STYLE).getEditor().getItem()));

        // The user types a style: finer proposals come in front, the old ones stay, none twice.
        onEvents(() -> {
            ((javax.swing.JTextField) panel.box(Field.STYLE).getEditor().getEditorComponent()).setText("klassisch");
            panel.propose(Field.STYLE);
            panel.propose(Field.STYLE);
            return null;
        });
        until(() -> !panel.proposingNow() && panel.box(Field.STYLE).getItemCount() == 5);
        assertEquals(List.of("Wiener Klassik, Streichquartett", "Barock, Cembalo und Streicher", "Chor a cappella",
                "Orgelmusik", "Blechbläserensemble"), onEvents(() -> panel.box(Field.STYLE).proposals()));
        assertEquals("klassisch", onEvents(() -> (String) panel.box(Field.STYLE).getEditor().getItem()));
        synchronized (requests) {
            assertEquals("the same words are asked once: " + requests, 4, requests.size());
            assertEquals("STYLE|Christmas Memories|klassisch|3", requests.get(3));
        }
        // Choosing a proposal takes it into the field.
        onEvents(() -> {
            panel.box(Field.STYLE).setSelectedItem("Orgelmusik");
            return null;
        });
        assertEquals("Orgelmusik", onEvents(() -> (String) panel.box(Field.STYLE).getEditor().getItem()));

        // Opened again, the proposals are there without asking.
        AlbumsPanel[] again = new AlbumsPanel[1];
        onEvents(() -> {
            again[0] = new AlbumsPanel(host, songs, new InstrumentRegistry(library.resolve("none.properties")));
            return null;
        });
        assertEquals(5, (int) onEvents(() -> again[0].box(Field.STYLE).getItemCount()));
        assertFalse(onEvents(() -> again[0].proposingNow()));
    }
}
