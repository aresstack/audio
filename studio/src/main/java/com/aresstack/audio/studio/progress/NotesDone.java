package com.aresstack.audio.studio.progress;

import java.util.HashMap;
import java.util.Map;

/**
 * How far the writing of notes is: every request for notes is a job with a size (its bars), and a
 * job is as far as its last note reaches until its answer is whole. The share of all bars that are
 * written is what a progress bar shows. Safe to call from the worker threads.
 */
public final class NotesDone {
    /** Each job as {@code {size, done}}. */
    private final Map<String, double[]> jobs = new HashMap<String, double[]>();

    /** A job that will be written; told before the work begins, so that the share never runs back. */
    public synchronized void expect(String job, double size) {
        if (!jobs.containsKey(job)) {
            jobs.put(job, new double[]{Math.max(0, size), 0});
        }
    }

    /** A job is this far, in the measure of its size; it never runs back and never past its size. */
    public synchronized void reached(String job, double done) {
        double[] known = jobs.get(job);
        if (known != null && !Double.isNaN(done)) {
            known[1] = Math.max(known[1], Math.min(known[0], done));
        }
    }

    /** A job is done: its answer is whole, or there is nothing to write for it. */
    public synchronized void whole(String job) {
        double[] known = jobs.get(job);
        if (known != null) {
            known[1] = known[0];
        }
    }

    /** The share of all that is expected which is written, from 0 to 1; 0 while nothing is expected. */
    public synchronized double fraction() {
        double size = 0;
        double done = 0;
        for (double[] job : jobs.values()) {
            size += job[0];
            done += job[1];
        }
        return size <= 0 ? 0 : Math.min(1, done / size);
    }
}
