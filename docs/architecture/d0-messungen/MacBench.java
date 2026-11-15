import java.util.Random;

/** D0: cost of the spectral multiply-accumulate of a uniformly partitioned convolution (per block, per channel). */
public class MacBench {
    public static void main(String[] args) {
        int rate = 44100; double irSeconds = 2.5;
        for (int b : new int[]{128, 256, 512, 1024, 2048}) {
            int bins = b + 1; int parts = (int) Math.ceil(irSeconds * rate / b);
            Random r = new Random(1);
            double[][] hr = new double[parts][bins], hi = new double[parts][bins], xr = new double[parts][bins], xi = new double[parts][bins];
            for (int p = 0; p < parts; p++) for (int k = 0; k < bins; k++) { hr[p][k] = r.nextGaussian(); hi[p][k] = r.nextGaussian(); xr[p][k] = r.nextGaussian(); xi[p][k] = r.nextGaussian(); }
            double[] yr = new double[bins], yi = new double[bins];
            long t0 = System.nanoTime(); while (System.nanoTime() - t0 < 2_000_000_000L) mac(hr, hi, xr, xi, yr, yi, parts, bins);
            int it = 2000; long s = System.nanoTime();
            for (int i = 0; i < it; i++) mac(hr, hi, xr, xi, yr, yi, parts, bins);
            double us = (System.nanoTime() - s) / 1e3 / it;
            double budget = b * 1e6 / rate;
            System.out.printf("B=%5d partitions=%4d mac_us=%8.1f budget_us=%8.1f share=%5.1f%% (one channel, %.1fs IR) chk=%.1f%n", b, parts, us, budget, 100 * us / budget, irSeconds, yr[0]);
        }
    }
    static void mac(double[][] hr, double[][] hi, double[][] xr, double[][] xi, double[] yr, double[] yi, int parts, int bins) {
        java.util.Arrays.fill(yr, 0); java.util.Arrays.fill(yi, 0);
        for (int p = 0; p < parts; p++) {
            double[] a = hr[p], b = hi[p], c = xr[p], d = xi[p];
            for (int k = 0; k < bins; k++) { yr[k] += a[k] * c[k] - b[k] * d[k]; yi[k] += a[k] * d[k] + b[k] * c[k]; }
        }
    }
}
