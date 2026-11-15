package com.aresstack.audio.studio.album;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.studio.application.SongLibrary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Every piece belongs to an album: old pieces and pieces without an album land in the unnamed album. */
public final class AlbumMembershipTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static List<String> pieces(Album album) {
        List<String> names = new ArrayList<String>();
        for (AlbumTrack track : album.tracks()) {
            names.add(track.piece());
        }
        return names;
    }

    @Test
    public void oldPiecesLandInTheUnnamedAlbumAndNothingIsMovedOrLost() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        SongLibrary songs = new SongLibrary(library);
        Path old = songs.create("Alte Nacht");
        Files.write(old.resolve("arrangement.tsv"), "keep me".getBytes(StandardCharsets.UTF_8));
        Files.write(old.resolve("composition-plan.json"),
                "{\"title\":\"Alte Nacht\",\"tempoBpm\":90,\"key\":\"D minor\",\"timeSignature\":\"3/4\",\"bars\":60,\"mood\":\"still\"}"
                        .getBytes(StandardCharsets.UTF_8));
        Path other = songs.create("Morgen");
        AlbumFiles files = new AlbumFiles(library);
        Album real = files.create("Echt", new AlbumConcept("", "", 12, 300));
        files.save(real.withTracks(List.of(new AlbumTrack("t1", "Morgen", "", 100, "C major", "4/4", "", "", 180,
                other.getFileName().toString()))));
        AlbumMembership members = new AlbumMembership(files, songs);

        assertEquals("only the piece without an album", 1, members.adoptLoosePieces());
        Album untitled = members.untitled();
        assertTrue(AlbumMembership.isUntitled(untitled));
        assertEquals(List.of(old.getFileName().toString()), pieces(untitled));
        AlbumTrack adopted = untitled.tracks().get(0);
        assertEquals("Alte Nacht", adopted.title());
        assertEquals(90, adopted.bpm());
        assertEquals("3/4", adopted.meter());
        assertEquals("still", adopted.mood());
        assertEquals("60 bars of 3/4 at 90 BPM", 120, adopted.targetSeconds());
        // The piece stays where it was, with everything in it.
        assertEquals("keep me", new String(Files.readAllBytes(old.resolve("arrangement.tsv")), StandardCharsets.UTF_8));
        assertEquals(2, songs.songs().size());
        assertEquals(untitled.id(), members.albumOf(old).get().id());
        assertEquals(real.id(), members.albumOf(other).get().id());

        // Again: nothing changes, nothing is doubled.
        assertEquals(0, members.adoptLoosePieces());
        assertEquals(1, members.untitled().tracks().size());
        assertEquals(2, files.albums().size());
    }

    @Test
    public void aProducedPieceLeftOverInTheUnnamedAlbumBelongsToItsRealAlbum() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        SongLibrary songs = new SongLibrary(library);
        AlbumFiles files = new AlbumFiles(library);
        AlbumMembership members = new AlbumMembership(files, songs);
        Path piece = songs.create("Titel eins");
        members.adoptLoosePieces(); // looked at while the production had not yet written its album
        Album real = files.create("Weihnacht", new AlbumConcept("", "", 12, 300));
        members.add(real.id(), piece);

        assertEquals(real.id(), members.albumOf(piece).get().id());
        members.adoptLoosePieces();
        assertTrue(members.untitled().tracks().isEmpty());
        assertEquals(List.of(piece.getFileName().toString()), pieces(files.album(real.id()).get()));
    }

    @Test
    public void aNewPieceGoesIntoTheChosenAlbumOnceAndAFolderOutsideTheLibraryIntoNone() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        SongLibrary songs = new SongLibrary(library);
        AlbumFiles files = new AlbumFiles(library);
        AlbumMembership members = new AlbumMembership(files, songs);
        Album album = files.create("Winter", new AlbumConcept("", "", 12, 300));
        Path piece = songs.create("Schnee");

        members.add(album.id(), piece);
        members.add(album.id(), piece);
        assertEquals(1, files.album(album.id()).get().tracks().size());
        assertEquals(1, members.pieces(files.album(album.id()).get()).size());
        assertEquals(0, members.adoptLoosePieces());

        Path outside = temporary.newFolder("elsewhere").toPath();
        assertFalse(members.albumOf(outside).isPresent());
        try {
            members.add(album.id(), outside);
            org.junit.Assert.fail("a folder outside the library is not a piece of an album");
        } catch (java.io.IOException expected) {
            // fine
        }
    }

    @Test
    public void aRenamedUnnamedAlbumIsARealOneAndTheNextLoosePieceGetsANewUnnamedAlbum() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        SongLibrary songs = new SongLibrary(library);
        AlbumFiles files = new AlbumFiles(library);
        AlbumMembership members = new AlbumMembership(files, songs);
        Path first = songs.create("Eins");
        members.adoptLoosePieces();
        Album named = members.untitled().withTitle("Sommer");
        files.save(named);

        Path second = songs.create("Zwei");
        assertEquals(1, members.adoptLoosePieces());
        Album untitled = members.untitled();
        assertFalse(untitled.id().equals(named.id()));
        assertEquals(List.of(second.getFileName().toString()), pieces(untitled));
        assertEquals(named.id(), members.albumOf(first).get().id());
    }

    @Test
    public void aNewCompositionTakesTitleAndTempoFromItsPlanButAProducedTitleStaysAsPlanned() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        SongLibrary songs = new SongLibrary(library);
        AlbumFiles files = new AlbumFiles(library);
        AlbumMembership members = new AlbumMembership(files, songs);
        Album album = files.create("Winter", new AlbumConcept("", "", 12, 300));
        Path piece = songs.createUntitled("ein ruhiges stueck");
        members.add(album.id(), piece);
        Files.write(piece.resolve("composition-plan.json"),
                "{\"title\":\"Eisblume\",\"tempoBpm\":72,\"key\":\"E minor\",\"timeSignature\":\"6/8\",\"bars\":40}"
                        .getBytes(StandardCharsets.UTF_8));

        assertTrue(members.refresh(piece));
        AlbumTrack track = files.album(album.id()).get().tracks().get(0);
        assertEquals("Eisblume", track.title());
        assertEquals(72, track.bpm());
        assertEquals("6/8", track.meter());
        assertFalse("again: nothing to change", members.refresh(piece));

        AlbumProductionState.load(files.folder(album.id())).with(track.id(), AlbumProductionState.Status.DONE)
                .save(files.folder(album.id()));
        Files.write(piece.resolve("composition-plan.json"),
                "{\"title\":\"Anders\",\"tempoBpm\":120}".getBytes(StandardCharsets.UTF_8));
        assertFalse("a produced title says what it was made from", members.refresh(piece));
        assertEquals("Eisblume", files.album(album.id()).get().tracks().get(0).title());
    }

    @Test
    public void aTitleMovesIntoAnotherAlbumWithItsPieceAndTheAlbumContextIsToldApart() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        SongLibrary songs = new SongLibrary(library);
        AlbumFiles files = new AlbumFiles(library);
        AlbumMembership members = new AlbumMembership(files, songs);
        Album winter = files.create("Winter", new AlbumConcept("Snow and silence", "", 12, 300));
        Album summer = files.create("Sommer", new AlbumConcept("", "", 12, 300));
        Path one = songs.create("Eins");
        Path two = songs.create("Zwei");
        members.add(winter.id(), one);
        members.add(winter.id(), two);

        assertTrue(members.albumContext(two).contains("track 2 of 2") && members.albumContext(two).contains("\"Eins\""));
        assertTrue(members.move(winter.id(), "t2", winter.id(), 0));
        assertEquals(List.of(two.getFileName().toString(), one.getFileName().toString()),
                pieces(files.album(winter.id()).get()));

        AlbumProductionState.load(files.folder(winter.id())).with("t1", AlbumProductionState.Status.FAILED, "abgebrochen", 0)
                .save(files.folder(winter.id()));
        assertTrue(members.move(winter.id(), "t1", summer.id(), -1));
        String arrived = files.album(summer.id()).get().tracks().get(0).id();
        assertEquals("an unfinished piece stays unfinished in its new album", AlbumProductionState.Status.FAILED,
                AlbumProductionState.load(files.folder(summer.id())).status(arrived));
        assertEquals(AlbumProductionState.Status.PLANNED,
                AlbumProductionState.load(files.folder(winter.id())).status("t1"));
        assertEquals(List.of("Winter", "Unbenanntes Album", "Unbenanntes Album 2"), new ArrayList<String>(
                AlbumMembership.labels(List.of(winter, members.untitled(), files.create(AlbumMembership.UNTITLED,
                        new AlbumConcept("", "", 12, 300)))).values()));
        assertEquals(List.of(two.getFileName().toString()), pieces(files.album(winter.id()).get()));
        assertEquals(List.of(one.getFileName().toString()), pieces(files.album(summer.id()).get()));
        assertEquals(summer.id(), members.albumOf(one).get().id());
        assertEquals(0, members.adoptLoosePieces());

        // An unnamed album tells the AI nothing about itself; old descriptions lose the album part.
        Path loose = songs.create("Lose");
        members.adoptLoosePieces();
        assertEquals("", members.albumContext(loose));
        assertEquals("Eins: ruhig.", Album.withoutBrief("Eins: ruhig.\nThis piece is track 1 of 2 of the album ..."));
        assertEquals("nur eigene Worte", Album.withoutBrief("nur eigene Worte"));
    }
}
