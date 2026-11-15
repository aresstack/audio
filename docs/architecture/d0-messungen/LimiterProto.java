import java.lang.management.ManagementFactory;
import java.util.Random;

/**
 * D0 design check (scratch prototype, NOT production code): look-ahead brickwall peak limiter,
 * stereo-linked, float blocks, allocation-free in process(). Gain path:
 * required gain -> release (one-pole, only upwards) -> sliding minimum over n+1 -> moving average over n,
 * audio delayed by n. The moving average of the min-hold guarantees gain <= required at the peak sample.
 */
public class LimiterProto {

    final int n; final float ceiling; final double rel;
    final float[] dl, dr; int dpos;           // audio delay lines
    final double[] mv; final int[] mi; int mHead, mTail, mCount; long t;   // monotonic deque for sliding min
    final double[] box; int bpos; double bsum; // moving average
    double g1 = 1.0; double lastG = 1.0;

    LimiterProto(int rate, double lookaheadMs, double releaseMs, double ceilingDb) {
        n = Math.max(1, (int) Math.round(lookaheadMs * rate / 1000.0));
        ceiling = (float) Math.pow(10, ceilingDb / 20);
        rel = 1.0 - Math.exp(-1.0 / (releaseMs * rate / 1000.0));
        dl = new float[n]; dr = new float[n];
        mv = new double[n + 2]; mi = new int[n + 2];
        box = new double[n]; java.util.Arrays.fill(box, 1.0); bsum = n;
    }

    int latency() { return n; }

    void process(float[] l, float[] r, int frames) {
        for (int i = 0; i < frames; i++) {
            float xl = l[i], xr = r[i];
            double peak = Math.max(Math.abs(xl), Math.abs(xr));
            double req = peak > ceiling ? ceiling / peak : 1.0;
            g1 = req < g1 ? req : g1 + rel * (req - g1);
            if (g1 > 1.0 - 1e-6 && req == 1.0) g1 = 1.0;          // land exactly on unity
            // sliding minimum over the last n+1 values
            while (mCount > 0 && mv[(mTail - 1 + mv.length) % mv.length] >= g1) { mTail = (mTail - 1 + mv.length) % mv.length; mCount--; }
            mv[mTail] = g1; mi[mTail] = (int) t; mTail = (mTail + 1) % mv.length; mCount++;
            if ((int) t - mi[mHead] > n) { mHead = (mHead + 1) % mv.length; mCount--; }
            double h = mv[mHead];
            // moving average over n values; exact recompute once per wrap removes rounding drift
            bsum += h - box[bpos]; box[bpos] = h; bpos++;
            if (bpos == n) { bpos = 0; double s = 0; for (double v : box) s += v; bsum = s; }
            double g = bsum / n;
            if (g > 1.0) g = 1.0;
            lastG = g;
            // delayed audio
            float yl = dl[dpos], yr = dr[dpos]; dl[dpos] = xl; dr[dpos] = xr; dpos = (dpos + 1) % n;
            l[i] = (float) (yl * g); r[i] = (float) (yr * g);
            t++;
        }
    }

    public static void main(String[] a) {
        int rate = 44100, block = 512;
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        LimiterProto lim = new LimiterProto(rate, 2.0, 80, -1.0);
        System.out.println("lookahead samples=" + lim.latency() + " ceiling=" + lim.ceiling);
        Random rnd = new Random(7);
        int seconds = 120, total = seconds * rate;
        float maxOut = 0, maxIn = 0; double maxOver = 0; long overCount = 0;
        float[] l = new float[block], r = new float[block];
        // drum-like bursts, dense music, extreme clicks: amplitudes up to +18 dBFS
        double prevG = 1; double maxStep = 0;
        long a0 = mx.getThreadAllocatedBytes(Thread.currentThread().getId());
        long ns = 0, procAlloc = 0; long tid0 = Thread.currentThread().getId(); long p0 = mx.getThreadAllocatedBytes(tid0); long probe = mx.getThreadAllocatedBytes(tid0) - p0;
        for (int off = 0; off < total; off += block) {
            for (int i = 0; i < block; i++) {
                int s = off + i; double tt = (double) s / rate;
                double env = (s % 22050 < 400) ? 8.0 : 0.6 + 0.5 * Math.sin(tt * 0.7);
                double v = env * (0.5 * Math.sin(2 * Math.PI * 110 * tt) + 0.4 * rnd.nextGaussian());
                if (rnd.nextInt(20000) == 0) v = (rnd.nextBoolean() ? 1 : -1) * 8.0;   // isolated click
                l[i] = (float) v; r[i] = (float) (v * 0.8 + 0.1 * rnd.nextGaussian());
                maxIn = Math.max(maxIn, Math.max(Math.abs(l[i]), Math.abs(r[i])));
            }
            long tid = Thread.currentThread().getId(); long b0 = mx.getThreadAllocatedBytes(tid); long t0 = System.nanoTime(); lim.process(l, r, block); ns += System.nanoTime() - t0; procAlloc += mx.getThreadAllocatedBytes(tid) - b0 - probe;
            for (int i = 0; i < block; i++) {
                float m = Math.max(Math.abs(l[i]), Math.abs(r[i]));
                maxOut = Math.max(maxOut, m);
                if (m > lim.ceiling) { overCount++; maxOver = Math.max(maxOver, m - lim.ceiling); }
            }
        }
        long alloc = mx.getThreadAllocatedBytes(Thread.currentThread().getId()) - a0;
        System.out.printf("in max=%.2f (%.1f dBFS)  out max=%.7f  ceiling=%.7f  samples over ceiling=%d (max over %.2e)%n",
                maxIn, 20 * Math.log10(maxIn), maxOut, lim.ceiling, overCount, maxOver);
        System.out.printf("cost %.1f ns/frame stereo (%.3f%% of real time), allocation inside process() %d B over %d blocks%n",
                (double) ns / total, 100.0 * ns / 1e9 / seconds, procAlloc, total / block);

        // gain continuity: largest gain change per sample on a step from quiet to +12 dB
        LimiterProto l2 = new LimiterProto(rate, 2.0, 80, -1.0);
        float[] x = new float[rate], y = new float[rate];
        for (int i = 0; i < rate; i++) { x[i] = (float) ((i < rate / 2 ? 0.1 : 4.0) * Math.sin(2 * Math.PI * 1000 * i / rate)); y[i] = x[i]; }
        float[] y2 = y.clone(); float[] one = new float[1], one2 = new float[1]; double pg = 1, step = 0, minG = 1;
        for (int i = 0; i < rate; i++) { one[0] = y[i]; one2[0] = y2[i]; l2.process(one, one2, 1); step = Math.max(step, Math.abs(l2.lastG - pg)); pg = l2.lastG; minG = Math.min(minG, pg); }
        System.out.printf("step quiet -> +12 dBFS: min gain %.4f, largest gain change per sample %.5f (unity->min spread over %d samples)%n", minG, step, l2.latency());

        // below ceiling: sine at -3 dBFS after a limiting event must come out bit-identical (only delayed)
        LimiterProto l3 = new LimiterProto(rate, 2.0, 80, -1.0);
        float[] burst = new float[rate], burst2 = new float[rate];
        for (int i = 0; i < rate; i++) burst[i] = burst2[i] = (float) (6.0 * Math.sin(2 * Math.PI * 300 * i / rate));
        l3.process(burst, burst2, rate);
        int len = 3 * rate; float[] s = new float[len], s2 = new float[len], ref = new float[len];
        for (int i = 0; i < len; i++) s[i] = s2[i] = ref[i] = (float) (0.7071 * Math.sin(2 * Math.PI * 997 * i / rate));
        l3.process(s, s2, len);
        int d = l3.latency(); int mismatches = 0; int firstExact = -1;
        int lastMismatch = -1;
        for (int i = d; i < len; i++) { if (s[i] != ref[i - d]) { mismatches++; lastMismatch = i; } }
        System.out.printf("sine -3 dBFS after a +15 dBFS burst: last non-bit-identical sample at %.3f s after burst end (release 80 ms, snap at -0.00001 dB); identical afterwards%n", (double) lastMismatch / rate);
        LimiterProto l4 = new LimiterProto(rate, 2.0, 80, -1.0);
        float[] q = new float[len], q2 = new float[len];
        for (int i = 0; i < len; i++) q[i] = q2[i] = ref[i];
        l4.process(q, q2, len); mismatches = 0;
        for (int i = d; i < len; i++) if (q[i] != ref[i - d]) mismatches++;
        System.out.println("sine -3 dBFS, fresh limiter: non-bit-identical samples = " + mismatches);
    }
}
