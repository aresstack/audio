package com.aresstack.audio.studio.ai;

import java.io.InterruptedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * How many requests go to one model at the same time, adapted to how the connections hold. Up to
 * the setting "Parallel" while the answers come; when connections break off (an overloaded
 * service or proxy) half as many, and one more again after as many answers as are allowed at
 * once. Requests over the limit wait for their turn instead of breaking off as well.
 */
public final class ConnectionGate {
    private static final Map<String, ConnectionGate> GATES = new ConcurrentHashMap<String, ConnectionGate>();
    /** The most at the same time, per model: the setting "Parallel". */
    private static volatile int ceiling = Integer.MAX_VALUE;

    private final String model;
    private int limit;
    private int inUse;
    private int held;
    private int waiting;
    private long lastCut;
    private boolean cut;

    private ConnectionGate(String model) {
        this.model = model;
        this.limit = ceiling;
    }

    /** The gate of this model. */
    public static ConnectionGate of(String model) {
        return GATES.computeIfAbsent(model == null ? "" : model, ConnectionGate::new);
    }

    /** Sets the most requests at the same time (the setting "Parallel"); every gate starts from it again. */
    public static void configure(int most) {
        ceiling = Math.max(1, most);
        for (ConnectionGate gate : GATES.values()) {
            synchronized (gate) {
                gate.limit = ceiling;
                gate.held = 0;
                gate.notifyAll();
            }
        }
    }

    /** For tests: forget every gate and allow any number. */
    static void reset() {
        GATES.clear();
        ceiling = Integer.MAX_VALUE;
    }

    /** How many requests may go to this model at the same time now. */
    public synchronized int limit() {
        return limit;
    }

    /**
     * Waits for a turn; returns when the request was let in, as the time to hand back to
     * {@link #leave}. Ends with an error when the thread is interrupted or {@code cancelled} says so.
     */
    public synchronized long enter(BooleanSupplier cancelled) throws InterruptedIOException {
        waiting++;
        try {
            while (inUse >= limit) {
                if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
                    throw new InterruptedIOException("KI-Anfrage abgebrochen.");
                }
                try {
                    wait(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("KI-Anfrage abgebrochen.");
                }
            }
        } finally {
            waiting--;
        }
        inUse++;
        return System.nanoTime();
    }

    /**
     * Where the requests are held back, in words for the status line, or an empty text when none
     * waits: a request that waits here has not reached the service yet, so no text can come for it.
     */
    public static String heldBack() {
        StringBuilder said = new StringBuilder();
        for (ConnectionGate gate : GATES.values()) {
            synchronized (gate) {
                if (gate.waiting > 0) {
                    said.append(said.length() == 0 ? "" : "; ").append(gate.waiting)
                            .append(gate.waiting == 1 ? " Anfrage wartet" : " Anfragen warten")
                            .append(" auf eine freie Verbindung zu ").append(gate.model).append(" (unterwegs ")
                            .append(gate.inUse).append(", höchstens ").append(gate.limit).append(" gleichzeitig)");
                }
            }
        }
        return said.toString();
    }

    /**
     * Hands the turn back. {@code broke}: the connection broke off or the service was busy. Only a
     * request let in after the last cut cuts again: many that broke off together count once.
     */
    public void leave(long entered, boolean broke) {
        String said = null;
        synchronized (this) {
            inUse = Math.max(0, inUse - 1);
            if (broke) {
                held = 0;
                if ((!cut || entered - lastCut >= 0) && limit > 1) {
                    int before = limit == Integer.MAX_VALUE ? Math.max(2, inUse + 1) : limit;
                    limit = Math.max(1, before / 2);
                    lastCut = System.nanoTime();
                    cut = true;
                    said = "Verbindung zur KI (" + model + "): Verbindungen brechen ab; höchstens " + limit
                            + " Anfragen gleichzeitig, bis sie wieder halten.";
                }
            } else if (limit < ceiling && ++held >= limit) {
                held = 0;
                limit++;
                if (limit == ceiling) {
                    said = "Verbindung zur KI (" + model + "): die Verbindungen halten wieder; bis zu " + limit
                            + " Anfragen gleichzeitig.";
                }
            }
            notifyAll();
        }
        if (said != null) {
            Backoff.tell(said);
        }
    }
}
