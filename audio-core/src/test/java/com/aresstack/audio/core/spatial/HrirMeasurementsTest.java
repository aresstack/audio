package com.aresstack.audio.core.spatial;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.core.fft.RadixTwoFourierTransform;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class HrirMeasurementsTest {
    @Test
    public void theMinimumPhaseFilterKeepsTheMagnitude() {
        float[] response = new float[128];
        response[20] = 0.6f;
        response[23] = -0.3f;
        response[30] = 0.2f;
        float[] filter = HrirMeasurements.minimumPhase(response, 128);
        double[] want = magnitude(response, 512);
        double[] got = magnitude(filter, 512);
        for (int k = 0; k < 256; k++) {
            assertEquals("bin " + k, want[k], got[k], 0.02);
        }
        // Its energy sits at the start: the delay is gone.
        assertTrue(Math.abs(filter[0]) + Math.abs(filter[1]) + Math.abs(filter[2]) > 0.5);
    }

    @Test
    public void theOnsetIsFoundBetweenSamples() {
        float[] response = new float[64];
        response[10] = 0.05f;
        response[11] = 1f;
        double onset = HrirMeasurements.onset(response);
        assertEquals(10.05, onset, 0.01);
    }

    @Test
    public void oneMeasuredSideIsMirroredOntoTheOther() {
        List<HrirMeasurements.Measurement> measured = new ArrayList<HrirMeasurements.Measurement>();
        for (int az = 0; az <= 180; az += 30) {
            measured.add(new HrirMeasurements.Measurement(az, 0, click(10 + az / 30, 0.5f), click(10, 1f)));
        }
        HrirSet set = HrirMeasurements.toGrid(measured, 44100, 32, "test");
        float[] l = new float[32];
        float[] r = new float[32];
        float[] ml = new float[32];
        float[] mr = new float[32];
        float[] d = new float[2];
        float[] md = new float[2];
        set.lookup(60, 0, l, r, d);
        set.lookup(300, 0, ml, mr, md);
        assertArrayEquals(l, mr, 0f);
        assertArrayEquals(r, ml, 0f);
        assertEquals(d[0], md[1], 0f);
        assertEquals(d[1], md[0], 0f);
        assertTrue(d[0] > d[1]);
    }

    @Test
    public void readsTheCompactMitKemarLayout() throws Exception {
        Path folder = Files.createTempDirectory("kemar");
        try {
            for (int elevation : new int[] {-10, 0, 10}) {
                Path ring = Files.createDirectories(folder.resolve("elev" + elevation));
                for (int az = 0; az <= 180; az += 45) {
                    ByteBuffer data = ByteBuffer.allocate(128 * 4);
                    for (int i = 0; i < 128; i++) {
                        data.putShort((short) (i == 5 + az / 45 ? 20000 : 0));
                        data.putShort((short) (i == 5 ? 30000 : 0));
                    }
                    Files.write(ring.resolve(String.format("H%de%03da.dat", elevation, az)), data.array());
                }
            }
            HrirSet set = HrirMeasurements.readMitKemar(folder, 64);
            assertEquals(-10, set.lowestElevation(), 0);
            assertEquals(10, set.highestElevation(), 0);
            float[] l = new float[64];
            float[] r = new float[64];
            float[] d = new float[2];
            set.lookup(90, 0, l, r, d);
            assertEquals(2, d[0] - d[1], 0.1);
            assertTrue(set.source().contains("KEMAR"));
        } finally {
            try (var files = Files.walk(folder)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** The zip archives of MIT KEMAR hold the same responses as 16-bit stereo WAV files. */
    @Test
    public void readsTheCompactMitKemarSetAsWaveFilesTheWayTheZipArchiveHoldsIt() throws Exception {
        Path folder = Files.createTempDirectory("kemar-wav");
        try {
            for (int elevation : new int[] {-10, 0, 10}) {
                Path ring = Files.createDirectories(folder.resolve("elev" + elevation));
                for (int az = 0; az <= 180; az += 45) {
                    ByteBuffer wav = ByteBuffer.allocate(44 + 128 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    wav.putInt(0x46464952).putInt(36 + 128 * 4).putInt(0x45564157);
                    wav.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 2).putInt(44100)
                            .putInt(44100 * 4).putShort((short) 4).putShort((short) 16);
                    wav.putInt(0x61746164).putInt(128 * 4);
                    for (int i = 0; i < 128; i++) {
                        wav.putShort((short) (i == 5 + az / 45 ? 20000 : 0));
                        wav.putShort((short) (i == 5 ? 30000 : 0));
                    }
                    Files.write(ring.resolve(String.format("H%de%03da.wav", elevation, az)), wav.array());
                }
            }
            HrirSet set = HrirMeasurements.readMitKemar(folder, 64);
            float[] l = new float[64];
            float[] r = new float[64];
            float[] d = new float[2];
            set.lookup(90, 0, l, r, d);
            assertEquals("the first channel is the left ear", 2, d[0] - d[1], 0.1);
            set.lookup(270, 0, l, r, d);
            assertEquals(2, d[1] - d[0], 0.1);
        } finally {
            try (var files = Files.walk(folder)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static float[] click(int at, float height) {
        float[] response = new float[64];
        response[at] = height;
        response[at + 1] = -height / 3;
        return response;
    }

    private static double[] magnitude(float[] samples, int n) {
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < samples.length; i++) {
            re[i] = samples[i];
        }
        new RadixTwoFourierTransform(n).forward(re, im);
        double[] m = new double[n];
        for (int k = 0; k < n; k++) {
            m[k] = Math.hypot(re[k], im[k]);
        }
        return m;
    }
}
