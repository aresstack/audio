import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.conv.*;
import com.aresstack.audio.core.dsp.*;
import com.aresstack.audio.core.spatial.*;
public class D4Bench {
    public static void main(String[] a) {
        int rate = 44100;
        for (double seconds : new double[] {2.5, 4.5}) {
            ImpulseResponse ir = SyntheticRoom.generate(seconds, 0.4, 0.02, rate, 1);
            for (int block : new int[] {128, 512}) {
                ConvolutionReverb conv = new ConvolutionReverb(ir);
                conv.prepare(rate, block);
                AudioBlock send = new AudioBlock(block), bus = new AudioBlock(block);
                for (int i = 0; i < block; i++) { send.left[i] = (float) Math.random(); send.right[i] = (float) Math.random(); }
                int warm = rate * 20 / block;
                for (int i = 0; i < warm; i++) conv.process(send, block, bus, 1f);
                int n = rate * 30 / block; long worst = 0; long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) { long s = System.nanoTime(); conv.process(send, block, bus, 1f); worst = Math.max(worst, System.nanoTime() - s); }
                double mean = (System.nanoTime() - t0) / 1e3 / n; double budget = block * 1e6 / rate;
                System.out.printf("Faltung %.1f s, B=%d: Mittel %.0f µs (%.1f %% Budget), Spitze %.0f µs%n", seconds, block, mean, 100 * mean / budget, worst / 1e3);
            }
        }
        HrirSet head = SphericalHeadModel.hrirs(rate);
        int block = 512; int sources = 16;
        BinauralPanner[] p = new BinauralPanner[sources];
        for (int s = 0; s < sources; s++) { p[s] = new BinauralPanner(head); p[s].prepare(rate, block); }
        float[] mono = new float[block]; for (int i = 0; i < block; i++) mono[i] = (float) Math.random();
        AudioBlock bus = new AudioBlock(block);
        for (boolean moving : new boolean[] {false, true}) {
            int n = rate * 20 / block; long t0 = 0;
            for (int i = 0; i < 2 * n; i++) {
                if (i == n) t0 = System.nanoTime();
                for (int s = 0; s < sources; s++) { if (moving) p[s].aimAt((i * 3 + s * 20) % 360, 10, block); p[s].process(mono, block, bus); }
            }
            double mean = (System.nanoTime() - t0) / 1e3 / n; double budget = block * 1e6 / rate;
            System.out.printf("HRTF %d Quellen %s, B=512: %.0f µs (%.1f %% Budget)%n", sources, moving ? "bewegt" : "stehend", mean, 100 * mean / budget);
        }
    }
}
