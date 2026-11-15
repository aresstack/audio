package com.aresstack.audio.studio.hrtf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.core.spatial.HrirSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The real download from the MIT Media Lab, and the parser against the original files (format,
 * which channel is which ear, the range of the grid); only in CI, with {@code -Dhrtf.live=true}.
 */
public final class HrtfDownloadIntegrationTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theOriginalMitKemarSetIsDownloadedCheckedAndHeardFromTheRightSide() throws Exception {
        Assume.assumeTrue("Run with -Dhrtf.live=true", Boolean.getBoolean("hrtf.live"));
        HrtfLibrary library = new HrtfLibrary(folder.getRoot().toPath());
        library.download(HrtfDataset.MIT_KEMAR, null, System.out::println);
        assertTrue(library.isInstalled(HrtfDataset.MIT_KEMAR));
        Path data = library.folder(HrtfDataset.MIT_KEMAR);
        assertTrue(Files.readString(data.resolve(HrtfLibrary.SOURCE_FILE)).contains("Bill Gardner and Keith Martin"));
        long files;
        try (Stream<Path> all = Files.list(data)) {
            files = all.filter(p -> p.getFileName().toString().matches(".*\\.(wav|dat)")).count();
        }
        System.out.println("responses: " + files);
        assertEquals("the compact set has 368 directions", 368, files);

        HrirSet set = library.load(HrtfDataset.MIT_KEMAR);
        System.out.println(set.source());
        assertEquals(44100, set.sampleRate());
        assertEquals(-40, set.lowestElevation(), 0);
        assertEquals(90, set.highestElevation(), 0);

        double[] right = ears(set, 90, 0);
        double[] left = ears(set, 270, 0);
        double[] ahead = ears(set, 0, 0);
        System.out.printf("right: L %.4f R %.4f, delays %.2f/%.2f%n", right[0], right[1], right[2], right[3]);
        System.out.printf("left:  L %.4f R %.4f, delays %.2f/%.2f%n", left[0], left[1], left[2], left[3]);
        System.out.printf("ahead: L %.4f R %.4f, delays %.2f/%.2f%n", ahead[0], ahead[1], ahead[2], ahead[3]);
        // From the right (azimuth 90, clockwise) the right ear hears louder and earlier.
        assertTrue("right ear louder from the right", right[1] > 2 * right[0]);
        assertTrue("right ear earlier from the right", right[3] < right[2]);
        // The left side is the mirror (the compact set is measured on one side).
        assertTrue("left ear louder from the left", left[0] > 2 * left[1]);
        assertTrue("left ear earlier from the left", left[2] < left[3]);
        assertEquals("ahead both ears alike", 1, ahead[0] / ahead[1], 0.5);
        assertEquals("ahead both ears at once", ahead[2], ahead[3], 2);
    }

    /** Energy of the left and right filter, then the left and right delay. */
    private static double[] ears(HrirSet set, double azimuth, double elevation) {
        float[] l = new float[set.taps()];
        float[] r = new float[set.taps()];
        float[] delays = new float[2];
        set.lookup(azimuth, elevation, l, r, delays);
        double el = 0;
        double er = 0;
        for (int i = 0; i < l.length; i++) {
            el += l[i] * l[i];
            er += r[i] * r[i];
        }
        return new double[] {el, er, delays[0], delays[1]};
    }
}
