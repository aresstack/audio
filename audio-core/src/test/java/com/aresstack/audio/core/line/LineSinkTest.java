package com.aresstack.audio.core.line;

import com.aresstack.audio.core.AudioBlock;
import java.lang.reflect.Proxy;
import javax.sound.sampled.SourceDataLine;
import org.junit.Assert;
import org.junit.Test;

/**
 * The line sink on a stand-in line: samples are rounded and clamped like the WAV sink, and a line
 * that runs empty while it plays is counted as a drop-out (which the render time cannot show).
 */
public final class LineSinkTest {
    private static final int BUFFER = 4096 * 4;

    /** A line whose queue holds {@code waiting[0]} bytes; it keeps what was written last. */
    private static SourceDataLine line(int[] waiting, byte[][] last) {
        return (SourceDataLine) Proxy.newProxyInstance(LineSinkTest.class.getClassLoader(),
                new Class<?>[]{SourceDataLine.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isOpen" -> true;
                    case "getFormat" -> LineSink.format(44100);
                    case "getBufferSize" -> BUFFER;
                    case "available" -> BUFFER - waiting[0];
                    case "write" -> {
                        last[0] = java.util.Arrays.copyOf((byte[]) args[0], (Integer) args[2]);
                        yield args[2];
                    }
                    default -> null;
                });
    }

    @Test
    public void writesRoundedClampedSixteenBitStereo() {
        byte[][] last = new byte[1][];
        LineSink sink = new LineSink(line(new int[]{0}, last), 4);
        AudioBlock block = new AudioBlock(4);
        block.left[0] = 0.5f;
        block.right[0] = -2f;
        block.left[1] = 1.5f;
        sink.write(block);
        Assert.assertEquals(16, last[0].length);
        Assert.assertEquals(Math.round(0.5f * 32767), (short) ((last[0][0] & 0xFF) | (last[0][1] << 8)));
        Assert.assertEquals(-32767, (short) ((last[0][2] & 0xFF) | (last[0][3] << 8)));
        Assert.assertEquals(32767, (short) ((last[0][4] & 0xFF) | (last[0][5] << 8)));
    }

    @Test
    public void aLineThatRunsEmptyWhilePlayingIsCounted() {
        int[] waiting = {0};
        LineSink sink = new LineSink(line(waiting, new byte[1][]), 512);
        AudioBlock block = new AudioBlock(512);
        sink.write(block); // not started: nothing counted
        sink.start();
        // right after the start the line is empty and fills: neither a drop-out nor a margin
        for (int fill = 0; fill < 4; fill++) {
            waiting[0] = fill * 512 * 4;
            sink.write(block);
        }
        Assert.assertEquals(0, sink.underruns());
        Assert.assertEquals(-1, sink.lowestBufferedFrames());
        waiting[0] = BUFFER - 512 * 4; // full: from now on the fill is the margin
        sink.write(block);
        waiting[0] = BUFFER / 2;
        sink.write(block);
        Assert.assertEquals(0, sink.underruns());
        Assert.assertEquals(BUFFER / 2 / 4, sink.lowestBufferedFrames());
        waiting[0] = 0; // the card has played everything: a drop-out
        sink.write(block);
        sink.write(block);
        Assert.assertEquals(2, sink.underruns());
        Assert.assertEquals(0, sink.lowestBufferedFrames());
        sink.pause();
        sink.write(block);
        Assert.assertEquals("paused, an empty line is no drop-out", 2, sink.underruns());
    }
}
