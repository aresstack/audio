package com.aresstack.audio.studio.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A title nobody has heard long enough is new; produced anew it is new again, mixed on the stage it is not. */
public final class HeardTitlesTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aTitleWithAMixIsNewUntilItIsHeard() throws Exception {
        Path piece = folder.newFolder("piece").toPath();
        Assert.assertFalse("nothing to hear yet", HeardTitles.isNew(piece));
        Files.write(piece.resolve(HeardTitles.MIX), new byte[]{1, 2, 3});
        Assert.assertTrue(HeardTitles.isNew(piece));
        HeardTitles.markHeard(piece);
        Assert.assertFalse(HeardTitles.isNew(piece));
        Assert.assertTrue(HeardTitles.heard(piece));
    }

    @Test
    public void aNewMixFromAProductionMakesItNewAgain() throws Exception {
        Path piece = folder.newFolder("piece").toPath();
        Path mix = piece.resolve(HeardTitles.MIX);
        Files.write(mix, new byte[]{1});
        Files.setLastModifiedTime(mix, FileTime.fromMillis(1_000_000L));
        HeardTitles.markHeard(piece);

        HeardTitles.producedAgain(piece); // failed: the old mix stays, so does the mark
        Assert.assertFalse(HeardTitles.isNew(piece));

        Files.setLastModifiedTime(mix, FileTime.fromMillis(2_000_000L));
        HeardTitles.producedAgain(piece);
        Assert.assertTrue(HeardTitles.isNew(piece));
    }

    @Test
    public void theCounterTellsOnceWhenTheShareIsHeard() {
        HeardTitles.Counter counter = new HeardTitles.Counter();
        Path piece = folder.getRoot().toPath().resolve("a");
        Assert.assertFalse(counter.heard(piece, 70, 100, 80));
        Assert.assertTrue(counter.heard(piece, 10, 100, 80));
        Assert.assertFalse("told once", counter.heard(piece, 10, 100, 80));

        Path other = folder.getRoot().toPath().resolve("b");
        Assert.assertFalse("another piece counts anew", counter.heard(other, 50, 100, 80));
        Assert.assertEquals(50, counter.seconds(), 1e-9);
    }

    @Test
    public void atAHundredPercentTheLastMomentMayBeMissed() {
        HeardTitles.Counter counter = new HeardTitles.Counter();
        Path piece = folder.getRoot().toPath().resolve("a");
        Assert.assertTrue(counter.heard(piece, 99.5, 100, 100));
    }
}
