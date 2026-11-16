package com.aresstack.audio.studio.ai;

/**
 * The most tokens an answer with notes may take when the AI adds to a piece that is there
 * (continuation, new voice, passage, orchestration): the setting "Max. Tokens" of the studio,
 * the same limit the first composition has. It was a fixed 16384 in each of those composers.
 */
public final class AnswerRoom {
    /** The limit while the setting was not read. */
    public static final int NOTES = 16384;
    private static volatile int notes = NOTES;

    /** The room of a plan while the setting was not read. */
    public static final int PLAN = 4096;
    private static volatile int plan = PLAN;

    private AnswerRoom() {
    }

    /** Sets the limit (the setting "Max. Tokens"). */
    public static void notes(int tokens) {
        notes = Math.max(1, tokens);
    }

    /** The most tokens an answer with notes may take. */
    public static int notes() {
        return notes;
    }

    /** Sets the most tokens a plan or a proposal may take (the setting "Max. Tokens für Pläne"). */
    public static void plan(int tokens) {
        plan = Math.max(1, tokens);
    }

    /** The most tokens a plan or a proposal (instruments, mix, movements) may take. */
    public static int plan() {
        return plan;
    }
}
