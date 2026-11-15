package com.aresstack.audio.core.spatial;

import com.aresstack.audio.core.fft.RadixTwoFourierTransform;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Measured head-related impulse responses turned into a {@link HrirSet}: each response is split
 * into its arrival time (the onset) and a minimum-phase filter of the same magnitude (real
 * cepstrum), and the measured directions are put on the regular grid of the set.
 *
 * <p><b>Data sets and licences</b> (checked 2026-11-15; none is bundled with the core):
 * <ul>
 *   <li>MIT KEMAR (Gardner and Martin, MIT Media Lab, 1994): "provided free with no
 *       restrictions on use, provided the authors are cited when the data is used in any research
 *       or commercial application".
 *       {@link #readMitKemar} reads the compact and the full set as published
 *       ({@code elevEE/HEEeAAAa}, or {@code LEEeAAAa} plus {@code REEeAAAa}, 44.1 kHz, 16 bit):
 *       the {@code .zip} archives hold WAV files, the {@code .tar.Z} archives raw big-endian
 *       {@code .dat} files.</li>
 *   <li>SADIE II (University of York, 2018): Apache License 2.0, attribution required; WAV and
 *       SOFA. Its WAV files can be read with {@link com.aresstack.audio.core.wav.WavFileSource}
 *       and handed to {@link #toGrid}; mind that SADIE counts the azimuth anticlockwise.</li>
 * </ul>
 */
public final class HrirMeasurements {
    private static final Pattern KEMAR = Pattern.compile("([HLR])(-?\\d+)e(\\d{3})a\\.(dat|wav)",
            Pattern.CASE_INSENSITIVE);

    /** One measured direction: azimuth clockwise from ahead (90 = right), elevation up, both ears. */
    public record Measurement(double azimuth, double elevation, float[] left, float[] right) {
        public Measurement {
            if (left == null || right == null || left.length == 0 || left.length != right.length) {
                throw new IllegalArgumentException("A measurement has both ears, equally long.");
            }
        }
    }

    private HrirMeasurements() {
    }

    /**
     * Puts measurements on a grid of 5° in azimuth and 10° in elevation, from the lowest to the
     * highest measured elevation. A missing half (only one side of the head measured, as in
     * the compact KEMAR set) is filled by mirroring with the ears swapped.
     *
     * @param sampleRate the rate of the measurements
     * @param taps       the length of the minimum-phase filters
     * @param source     name and licence of the data, shown to the user
     */
    public static HrirSet toGrid(List<Measurement> measurements, int sampleRate, int taps, String source) {
        if (measurements.isEmpty()) {
            throw new IllegalArgumentException("No measurements to put on a grid.");
        }
        List<Measurement> all = new ArrayList<Measurement>(measurements);
        boolean leftHalf = false;
        boolean rightHalf = false;
        for (Measurement m : measurements) {
            double a = normalized(m.azimuth());
            rightHalf |= a > 0.5 && a < 179.5;
            leftHalf |= a > 180.5 && a < 359.5;
        }
        if (rightHalf != leftHalf) {
            for (Measurement m : measurements) {
                double a = normalized(m.azimuth());
                if (a > 0.5 && a < 359.5 && Math.abs(a - 180) > 0.5) {
                    all.add(new Measurement(360 - a, m.elevation(), m.right(), m.left()));
                }
            }
        }
        // Split every response into onset and minimum-phase filter.
        int count = all.size();
        float[][] filtersLeft = new float[count][];
        float[][] filtersRight = new float[count][];
        double[] onsetLeft = new double[count];
        double[] onsetRight = new double[count];
        double earliest = Double.MAX_VALUE;
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            Measurement m = all.get(i);
            onsetLeft[i] = onset(m.left());
            onsetRight[i] = onset(m.right());
            earliest = Math.min(earliest, Math.min(onsetLeft[i], onsetRight[i]));
            filtersLeft[i] = minimumPhase(m.left(), taps);
            filtersRight[i] = minimumPhase(m.right(), taps);
            lowest = Math.min(lowest, m.elevation());
            highest = Math.max(highest, m.elevation());
        }
        double step = 10;
        lowest = Math.floor(lowest / step) * step;
        int elevations = (int) Math.round((Math.ceil(highest / step) * step - lowest) / step) + 1;
        int azimuths = 72;
        int points = azimuths * elevations;
        float[][] left = new float[points][];
        float[][] right = new float[points][];
        float[] delayLeft = new float[points];
        float[] delayRight = new float[points];
        double[][] directions = new double[count][];
        for (int i = 0; i < count; i++) {
            directions[i] = vector(all.get(i).azimuth(), all.get(i).elevation());
        }
        for (int e = 0; e < elevations; e++) {
            for (int a = 0; a < azimuths; a++) {
                double[] wanted = vector(a * 5.0, lowest + e * step);
                int best = 0;
                double closest = -2;
                for (int i = 0; i < count; i++) {
                    double dot = wanted[0] * directions[i][0] + wanted[1] * directions[i][1]
                            + wanted[2] * directions[i][2];
                    if (dot > closest + 1e-12) {
                        closest = dot;
                        best = i;
                    }
                }
                int p = e * azimuths + a;
                left[p] = filtersLeft[best];
                right[p] = filtersRight[best];
                delayLeft[p] = (float) (onsetLeft[best] - earliest);
                delayRight[p] = (float) (onsetRight[best] - earliest);
            }
        }
        return new HrirSet(sampleRate, taps, azimuths, lowest, step, elevations, left, right, delayLeft, delayRight,
                source);
    }

    /**
     * Reads the MIT KEMAR responses from a directory (searched to any depth): the compact set
     * ({@code H…} files, stereo) or the full set ({@code L…} and {@code R…} files).
     */
    public static HrirSet readMitKemar(Path directory, int taps) throws IOException {
        Map<String, float[][]> found = new TreeMap<String, float[][]>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Matcher name = KEMAR.matcher(file.getFileName().toString());
                if (!name.matches() || !Files.isRegularFile(file)) {
                    continue;
                }
                String key = Integer.parseInt(name.group(2)) + "/" + Integer.parseInt(name.group(3));
                byte[] bytes = Files.readAllBytes(file);
                short[] samples = name.group(4).equalsIgnoreCase("wav") ? wave(bytes, file) : bigEndian(bytes);
                float[][] ears = found.computeIfAbsent(key, k -> new float[2][]);
                char kind = Character.toUpperCase(name.group(1).charAt(0));
                if (kind == 'H') {
                    float[] l = new float[samples.length / 2];
                    float[] r = new float[samples.length / 2];
                    for (int i = 0; i < l.length; i++) {
                        l[i] = samples[2 * i] / 32768f;
                        r[i] = samples[2 * i + 1] / 32768f;
                    }
                    ears[0] = l;
                    ears[1] = r;
                } else {
                    float[] ear = new float[samples.length];
                    for (int i = 0; i < ear.length; i++) {
                        ear[i] = samples[i] / 32768f;
                    }
                    ears[kind == 'L' ? 0 : 1] = ear;
                }
            }
        }
        List<Measurement> measurements = new ArrayList<Measurement>();
        for (Map.Entry<String, float[][]> entry : found.entrySet()) {
            float[][] ears = entry.getValue();
            if (ears[0] == null || ears[1] == null || ears[0].length == 0 || ears[0].length != ears[1].length) {
                continue;
            }
            String[] parts = entry.getKey().split("/");
            measurements.add(new Measurement(Double.parseDouble(parts[1]), Double.parseDouble(parts[0]), ears[0],
                    ears[1]));
        }
        if (measurements.isEmpty()) {
            throw new IOException("Im Ordner " + directory + " liegen keine MIT-KEMAR-Antworten (.wav oder .dat).");
        }
        return toGrid(measurements, 44100, taps,
                "MIT KEMAR, Bill Gardner und Keith Martin, MIT Media Lab 1994 (frei nutzbar bei Nennung der Autoren)");
    }

    /** The samples of a 16-bit PCM WAV file at 44.1 kHz, interleaved as stored. */
    private static short[] wave(byte[] bytes, Path file) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || buffer.getInt(0) != 0x46464952 || buffer.getInt(8) != 0x45564157) {
            throw new IOException("Keine WAV-Datei: " + file);
        }
        boolean format = false;
        int at = 12;
        while (at + 8 <= bytes.length) {
            int id = buffer.getInt(at);
            int size = buffer.getInt(at + 4);
            int body = at + 8;
            if (size < 0 || body + size > bytes.length) {
                size = bytes.length - body; // a data chunk that claims more than is there
            }
            if (id == 0x20746d66) { // "fmt "
                int code = buffer.getShort(body) & 0xffff;
                int rate = buffer.getInt(body + 4);
                int bits = buffer.getShort(body + 14) & 0xffff;
                if (code != 1 || bits != 16 || rate != 44100) {
                    throw new IOException("Erwartet 16-bit-PCM mit 44,1 kHz: " + file);
                }
                format = true;
            } else if (id == 0x61746164) { // "data"
                if (!format) {
                    throw new IOException("WAV ohne Formatangabe vor den Daten: " + file);
                }
                short[] samples = new short[size / 2];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = buffer.getShort(body + 2 * i);
                }
                return samples;
            }
            at = body + size + (size & 1);
        }
        throw new IOException("WAV ohne Daten: " + file);
    }

    private static short[] bigEndian(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        short[] samples = new short[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buffer.getShort();
        }
        return samples;
    }

    private static double normalized(double azimuth) {
        double a = azimuth % 360;
        return a < 0 ? a + 360 : a;
    }

    private static double[] vector(double azimuth, double elevation) {
        double az = Math.toRadians(azimuth);
        double el = Math.toRadians(elevation);
        return new double[] {Math.sin(az) * Math.cos(el), Math.cos(az) * Math.cos(el), Math.sin(el)};
    }

    /** Where the response starts: the first sample above a tenth of its peak, refined between samples. */
    static double onset(float[] response) {
        float peak = 0;
        for (float sample : response) {
            peak = Math.max(peak, Math.abs(sample));
        }
        if (peak == 0) {
            return 0;
        }
        float threshold = peak * 0.1f;
        for (int i = 0; i < response.length; i++) {
            float now = Math.abs(response[i]);
            if (now >= threshold) {
                float before = i == 0 ? 0 : Math.abs(response[i - 1]);
                return i == 0 ? 0 : i - 1 + (threshold - before) / (now - before);
            }
        }
        return 0;
    }

    /**
     * A minimum-phase filter with the magnitude of the response (homomorphic method: the real
     * cepstrum folded onto positive times), cut to {@code taps} with a short fade.
     */
    static float[] minimumPhase(float[] response, int taps) {
        int n = Integer.highestOneBit(Math.max(256, Math.max(response.length, taps) * 8) - 1) * 2;
        RadixTwoFourierTransform fft = new RadixTwoFourierTransform(n);
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < response.length; i++) {
            re[i] = response[i];
        }
        fft.forward(re, im);
        double floor = 1e-9;
        for (int k = 0; k < n; k++) {
            double magnitude = Math.hypot(re[k], im[k]);
            re[k] = Math.log(Math.max(magnitude, floor));
            im[k] = 0;
        }
        fft.inverse(re, im);
        // Fold: keep c[0] and c[n/2], double the positive times, drop the negative ones.
        for (int i = 1; i < n / 2; i++) {
            re[i] *= 2;
            re[n - i] = 0;
        }
        for (int i = 0; i < n; i++) {
            im[i] = 0;
        }
        fft.forward(re, im);
        for (int k = 0; k < n; k++) {
            double magnitude = Math.exp(re[k]);
            double phase = im[k];
            re[k] = magnitude * Math.cos(phase);
            im[k] = magnitude * Math.sin(phase);
        }
        fft.inverse(re, im);
        float[] filter = new float[taps];
        int fade = taps / 4;
        for (int i = 0; i < taps; i++) {
            double window = i < taps - fade ? 1 : 0.5 + 0.5 * Math.cos(Math.PI * (i - (taps - fade) + 1) / (fade + 1));
            filter[i] = (float) (re[i] * window);
        }
        return filter;
    }
}
