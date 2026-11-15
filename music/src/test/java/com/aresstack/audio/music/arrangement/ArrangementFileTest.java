package com.aresstack.audio.music.arrangement;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.junit.Assert;
import org.junit.Test;

/** The one reader of arrangement.tsv: what it understands, what it keeps as a problem, and renaming. */
public final class ArrangementFileTest {
    @Test
    public void readsTempoTracksAndNotesInTheirWrittenOrder() {
        ArrangementFile file = ArrangementFile.parse(Arrays.asList("﻿tempo\t96", "", "# a comment",
                "track\tlead\t73\t0\tmelody", "track\tlow\t 33 \t2", "note\tlead\t0\t480\t72\t90",
                "note\tlow\t 960 \t480\t36\t100"));

        Assert.assertEquals(OptionalInt.of(96), file.tempo());
        Assert.assertEquals(96, file.tempoOrDefault());
        Assert.assertEquals(Arrays.asList("lead", "low"), file.trackIds());
        ArrangementFile.Track low = file.tracks().get(1);
        Assert.assertNull("no fifth column, no role", low.role());
        Assert.assertEquals(OptionalInt.of(33), low.programNumber());
        Assert.assertEquals("melody", file.tracks().get(0).role());
        Assert.assertEquals(2, file.notes().size());
        Assert.assertEquals(960L, file.notes().get(1).tick());
        Assert.assertEquals(7, file.notes().get(1).line());
        Assert.assertEquals(5, file.entries().size());
        Assert.assertTrue(file.problems().isEmpty());
    }

    @Test
    public void keepsWhatItCannotUnderstandAsProblems() {
        ArrangementFile file = ArrangementFile.parse(Arrays.asList("tempo\t96\t1", "chord\tC", "track\ta\t0",
                "note\ta\t0\t480\t60", "note\ta\tzero\t480\t60\t90", " #not a comment"));

        Assert.assertEquals(6, file.problems().size());
        Assert.assertTrue(file.tracks().isEmpty());
        Assert.assertTrue(file.notes().isEmpty());
        Assert.assertEquals("chord\tC", file.problems().get(1).text());
        Assert.assertEquals(2, file.problems().get(1).line());
    }

    @Test
    public void theTempoOfAViewIsKeptInRangeAndHasADefault() {
        Assert.assertEquals(108, ArrangementFile.parse(Arrays.asList("track\ta\t0\t0")).tempoOrDefault());
        Assert.assertEquals(108, ArrangementFile.parse(Arrays.asList("tempo\tfast")).tempoOrDefault());
        Assert.assertEquals(400, ArrangementFile.parse(Arrays.asList("tempo\t500")).tempoOrDefault());
        Assert.assertEquals(20, ArrangementFile.parse(Arrays.asList("tempo\t5")).tempoOrDefault());
        Assert.assertEquals("the last tempo line counts", 90,
                ArrangementFile.parse(Arrays.asList("tempo\t120", "tempo\t90")).tempoOrDefault());
    }

    @Test
    public void drumsAreOnChannelNine() {
        ArrangementFile file = ArrangementFile.parse(Arrays.asList("track\tkit\t0\t 9", "track\tpad\t0\t1"));
        Assert.assertTrue(file.tracks().get(0).onDrumChannel());
        Assert.assertFalse(file.tracks().get(1).onDrumChannel());
    }

    @Test
    public void renamingTouchesOnlyTheTrackAndItsNotes() {
        List<String> renamed = ArrangementFile.renameTrack(Arrays.asList("﻿track\tlead\t73\t0", "tempo\t108",
                "note\tlead\t0\t480\t72\t90\t", "note\tleader\t0\t1\t60\t90", "# lead", "other\tlead"), "lead", "flute");

        Assert.assertEquals(Arrays.asList("﻿track\tflute\t73\t0", "tempo\t108", "note\tflute\t0\t480\t72\t90\t",
                "note\tleader\t0\t1\t60\t90", "# lead", "other\tlead"), renamed);
    }
}
