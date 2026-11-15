package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.studio.album.Album;
import com.aresstack.audio.studio.album.AlbumConcept;
import com.aresstack.audio.studio.album.AlbumFiles;
import com.aresstack.audio.studio.album.AlbumMembership;
import com.aresstack.audio.studio.application.SongLibrary;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The tree of the albums: every album with its titles; a click tells the window what to show. */
public final class AlbumTreeTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void albumsShowTheirTitlesAndAClickOpensAlbumOrPiece() throws Exception {
        UIManager.put("defaultFont", new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        Path library = temporary.newFolder("compositions").toPath();
        SongLibrary songs = new SongLibrary(library);
        AlbumFiles files = new AlbumFiles(library);
        AlbumMembership members = new AlbumMembership(files, songs);
        Album winter = files.create("Winter", new AlbumConcept("", "", 12, 300));
        Path snow = songs.create("Schnee");
        members.add(winter.id(), snow);
        songs.create("Alt");
        members.adoptLoosePieces();
        files.create(AlbumMembership.UNTITLED, new AlbumConcept("", "", 12, 300));
        List<String> told = new ArrayList<String>();
        AlbumTree[] tree = new AlbumTree[1];
        SwingUtilities.invokeAndWait(() -> {
            tree[0] = new AlbumTree(new AlbumTree.Host() {
                @Override
                public void albumClicked(String albumId) {
                    told.add("album " + albumId);
                }

                @Override
                public void pieceClicked(String albumId, Path folder) {
                    told.add("piece " + folder.getFileName());
                }

                @Override
                public void moveTitle(String fromAlbum, String trackId, String toAlbum, int index) {
                }

                @Override
                public void newAlbum() {
                }

                @Override
                public void newComposition() {
                }

                @Override
                public void deleteAlbum(String albumId) {
                }
            });
            tree[0].show(files.albums(), songs, winter.id(), snow);
        });
        List<String> shown = tree[0].shown();
        assertTrue(shown.toString(), shown.contains("Winter   ·   1") && shown.contains("  Schnee"));
        assertTrue("two unnamed albums are told apart: " + shown,
                shown.contains("Unbenanntes Album   ·   1") && shown.contains("Unbenanntes Album 2   ·   0"));
        assertTrue("showing selects the open piece without telling the window", told.isEmpty());

        SwingUtilities.invokeAndWait(() -> tree[0].tree().setSelectionRow(0));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, told.size());
        assertTrue(told.toString(), told.get(0).startsWith("album "));
    }
}
