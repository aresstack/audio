import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;

import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;
import org.jtransforms.fft.DoubleFFT_1D;

/**
 * D0 measurement harness (not production code). Measures forward+inverse FFT pairs at the
 * partition sizes of a uniformly partitioned convolution: time per pair, allocation per pair,
 * latency distribution (p50/p99/p99.9/max) and round-trip accuracy.
 */
public class FftBench {

    interface Fft {
        String name();
        void prepare(int n);
        /** forward then inverse on the given split real/imag data (converted if the impl needs it). */
        void pair(double[] re, double[] im);
    }

    /** Exactly the AskAI CommonsMathFourierTransform call path (allocates the double[][] wrapper). */
    static final class AskAiCommons implements Fft {
        public String name() { return "AskAI CommonsMathFourierTransform"; }
        public void prepare(int n) { }
        public void pair(double[] re, double[] im) {
            check(re, im);
            FastFourierTransformer.transformInPlace(new double[][]{re, im}, DftNormalization.STANDARD, TransformType.FORWARD);
            check(re, im);
            FastFourierTransformer.transformInPlace(new double[][]{re, im}, DftNormalization.STANDARD, TransformType.INVERSE);
        }
        private static void check(double[] re, double[] im) {
            if (re.length != im.length) throw new IllegalArgumentException();
            if (Integer.bitCount(re.length) != 1) throw new IllegalArgumentException();
        }
    }

    /** Same library, wrapper array reused: isolates the cost of the wrapper allocation. */
    static final class CommonsReusedWrapper implements Fft {
        private final double[][] w = new double[2][];
        public String name() { return "Commons Math, Hüllfeld wiederverwendet"; }
        public void prepare(int n) { }
        public void pair(double[] re, double[] im) {
            w[0] = re; w[1] = im;
            FastFourierTransformer.transformInPlace(w, DftNormalization.STANDARD, TransformType.FORWARD);
            FastFourierTransformer.transformInPlace(w, DftNormalization.STANDARD, TransformType.INVERSE);
        }
    }

    /** JDK-only iterative radix-2 with precomputed twiddles and bit-reversal table (~50 lines). */
    static final class JdkRadix2 implements Fft {
        private int n; private double[] cos, sin; private int[] rev;
        public String name() { return "JDK-only Radix-2, Tabellen vorab"; }
        public void prepare(int n) {
            this.n = n; cos = new double[n / 2]; sin = new double[n / 2]; rev = new int[n];
            for (int i = 0; i < n / 2; i++) { cos[i] = Math.cos(2 * Math.PI * i / n); sin[i] = Math.sin(2 * Math.PI * i / n); }
            int bits = Integer.numberOfTrailingZeros(n);
            for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        }
        void transform(double[] re, double[] im, boolean inverse) {
            for (int i = 0; i < n; i++) { int j = rev[i]; if (j > i) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; } }
            double sign = inverse ? 1 : -1;
            for (int size = 2; size <= n; size <<= 1) {
                int half = size >>> 1, step = n / size;
                for (int start = 0; start < n; start += size) {
                    for (int k = 0, t = 0; k < half; k++, t += step) {
                        double wr = cos[t], wi = sign * sin[t];
                        int a = start + k, b = a + half;
                        double xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                    }
                }
            }
            if (inverse) { double s = 1.0 / n; for (int i = 0; i < n; i++) { re[i] *= s; im[i] *= s; } }
        }
        public void pair(double[] re, double[] im) { transform(re, im, false); transform(re, im, true); }
    }

    /** JTransforms complex FFT on an interleaved buffer (conversion included, single-threaded). */
    static final class JTransformsComplex implements Fft {
        private DoubleFFT_1D fft; private double[] buf;
        public String name() { return "JTransforms complex (inkl. Umpacken)"; }
        public void prepare(int n) { fft = new DoubleFFT_1D(n); buf = new double[2 * n]; }
        public void pair(double[] re, double[] im) {
            int n = re.length;
            for (int i = 0; i < n; i++) { buf[2 * i] = re[i]; buf[2 * i + 1] = im[i]; }
            fft.complexForward(buf); fft.complexInverse(buf, true);
            for (int i = 0; i < n; i++) { re[i] = buf[2 * i]; im[i] = buf[2 * i + 1]; }
        }
    }

    /** JTransforms real FFT: what a convolution of real audio actually needs (imag ignored). */
    static final class JTransformsReal implements Fft {
        private DoubleFFT_1D fft; private double[] buf;
        public String name() { return "JTransforms real (nur Realteil)"; }
        public void prepare(int n) { fft = new DoubleFFT_1D(n); buf = new double[n]; }
        public void pair(double[] re, double[] im) {
            System.arraycopy(re, 0, buf, 0, re.length);
            fft.realForward(buf); fft.realInverse(buf, true);
            System.arraycopy(buf, 0, re, 0, re.length);
        }
    }

    static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) {
        pl.edu.icm.jlargearrays.ConcurrencyUtils.setNumberOfThreads(1);
        int[] sizes = {128, 256, 512, 1024, 2048, 4096, 8192, 16384};
        Fft[] impls = {new AskAiCommons(), new CommonsReusedWrapper(), new JdkRadix2(), new JTransformsComplex(), new JTransformsReal()};
        System.out.println("java " + System.getProperty("java.version") + ", cpus " + Runtime.getRuntime().availableProcessors());
        System.out.printf("%-38s %6s %10s %10s %10s %10s %10s %9s %10s%n", "impl", "N", "mean_us", "p50_us", "p99_us", "p999_us", "max_us", "B/pair", "rt_err");
        for (int n : sizes) {
            for (Fft f : impls) run(f, n);
            System.out.println();
        }
    }

    static void run(Fft f, int n) {
        f.prepare(n);
        Random r = new Random(42);
        double[] re0 = new double[n], im0 = new double[n];
        for (int i = 0; i < n; i++) re0[i] = r.nextGaussian();
        boolean real = f instanceof JTransformsReal;
        if (!real) for (int i = 0; i < n; i++) im0[i] = r.nextGaussian();
        double[] re = re0.clone(), im = im0.clone();
        // accuracy
        f.pair(re, im);
        double err = 0;
        for (int i = 0; i < n; i++) err = Math.max(err, Math.max(Math.abs(re[i] - re0[i]), real ? 0 : Math.abs(im[i] - im0[i])));
        // warmup: enough to reach C2
        long warmNs = 3_000_000_000L, t0 = System.nanoTime();
        while (System.nanoTime() - t0 < warmNs) { for (int k = 0; k < 100; k++) f.pair(re, im); }
        int iters = (int) Math.max(2000, Math.min(200_000, 2_000_000_000L / Math.max(1, n * 40L)));
        long[] lat = new long[iters];
        long tid = Thread.currentThread().getId();
        long a0 = MX.getThreadAllocatedBytes(tid);
        long s0 = System.nanoTime();
        for (int k = 0; k < iters; k++) {
            long a = System.nanoTime();
            f.pair(re, im);
            lat[k] = System.nanoTime() - a;
        }
        long total = System.nanoTime() - s0;
        long alloc = MX.getThreadAllocatedBytes(tid) - a0;
        Arrays.sort(lat);
        System.out.printf("%-38s %6d %10.2f %10.2f %10.2f %10.2f %10.2f %9.1f %10.1e%n", f.name(), n,
                total / 1e3 / iters, lat[iters / 2] / 1e3, lat[(int) (iters * 0.99)] / 1e3,
                lat[(int) (iters * 0.999)] / 1e3, lat[iters - 1] / 1e3, (double) alloc / iters, err);
    }
}
