package com.aresstack.audio.studio.ai;

/** How the continuation joins the original (report 5.4). */
public enum TransitionMode {
    /** Every note of the original stays as it is; the AI writes only in the new bars. */
    PROTECT_ORIGINAL("Original schützen"),
    /**
     * The last bars of the original may be rewritten together with the new bars, for a more natural
     * transition. The original stays recoverable: the change is a proposal and, once accepted, a
     * revision that can be undone.
     */
    REVISE_TAIL("Übergang überarbeiten");

    private final String label;

    TransitionMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
