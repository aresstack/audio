package com.aresstack.audio.studio.application;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SongLibraryTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aNewPieceGetsAFolderOfItsOwnAndKeepsNameAndDescription() throws Exception {
        SongLibrary library = new SongLibrary(folder.getRoot().toPath().resolve("compositions"));
        Path first = library.create("Blütenviertel am Morgen");
        Path second = library.create("Blütenviertel am Morgen");
        Assert.assertEquals("bluetenviertel-am-morgen", first.getFileName().toString());
        Assert.assertEquals("bluetenviertel-am-morgen-2", second.getFileName().toString());
        library.save(first, null, "A calm morning theme.");
        Assert.assertEquals("Blütenviertel am Morgen", library.name(first));
        Assert.assertEquals("A calm morning theme.", library.description(first));
        Assert.assertNull(library.description(second));
    }

    @Test
    public void anOlderFolderIsCalledByThePlansTitleOrByItsFolder() throws Exception {
        Path root = folder.getRoot().toPath().resolve("compositions");
        SongLibrary library = new SongLibrary(root);
        Path planned = Files.createDirectories(root.resolve("song-1700000000000"));
        Files.write(planned.resolve("composition-plan.json"), "{\"title\":\"District Dawn\"}".getBytes("UTF-8"));
        Path bare = Files.createDirectories(root.resolve("district-dawn"));
        Assert.assertEquals("District Dawn", library.name(planned));
        Assert.assertEquals("district-dawn", library.name(bare));
        List<SongLibrary.Song> songs = library.songs();
        Assert.assertEquals(2, songs.size());
    }

    @Test
    public void aLibraryThatDoesNotExistYetIsEmpty() {
        Assert.assertTrue(new SongLibrary(folder.getRoot().toPath().resolve("nothing")).songs().isEmpty());
    }
}
