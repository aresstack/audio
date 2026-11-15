package com.aresstack.audio.studio.ai;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/** As many requests at once as the connections hold: half as many when they break off, more again when they hold. */
public final class ConnectionGateTest {
    @After
    public void reset() {
        ConnectionGate.reset();
    }

    @Test
    public void connectionsThatBreakOffTogetherHalveTheLimitOnce() throws Exception {
        ConnectionGate.configure(8);
        ConnectionGate gate = ConnectionGate.of("gate-a");
        long[] entered = new long[8];
        for (int i = 0; i < 8; i++) {
            entered[i] = gate.enter(null);
        }
        for (int i = 0; i < 8; i++) {
            gate.leave(entered[i], true);
        }
        Assert.assertEquals("eight that broke off together count once", 4, gate.limit());
        gate.leave(gate.enter(null), true);
        Assert.assertEquals("one let in after the cut cuts again", 2, gate.limit());
    }

    @Test
    public void answersOpenTheGateStepByStepUpToTheSetting() throws Exception {
        ConnectionGate.configure(4);
        ConnectionGate gate = ConnectionGate.of("gate-b");
        gate.leave(gate.enter(null), true);
        gate.leave(gate.enter(null), true);
        Assert.assertEquals(1, gate.limit());
        for (int i = 0; i < 20; i++) {
            gate.leave(gate.enter(null), false);
        }
        Assert.assertEquals("never more than Parallel", 4, gate.limit());
    }

    @Test
    public void aRequestOverTheLimitWaitsForATurn() throws Exception {
        ConnectionGate.configure(1);
        ConnectionGate gate = ConnectionGate.of("gate-c");
        long first = gate.enter(null);
        Thread second = new Thread(() -> {
            try {
                gate.leave(gate.enter(null), false);
            } catch (java.io.InterruptedIOException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        second.start();
        second.join(300);
        Assert.assertTrue("the second waits while the first is under way", second.isAlive());
        gate.leave(first, false);
        second.join(5000);
        Assert.assertFalse(second.isAlive());
    }
}
