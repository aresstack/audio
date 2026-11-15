package com.aresstack.audio.studio.ai;

/** How a continuation ends (report 5.3). */
public enum ContinuationEnding {
    /** A real ending: cadence to the tonic, the piece is over. */
    CLOSED("mit Abschluss", "End the piece: the last section resolves with a clear cadence to the tonic and the "
            + "parts come to rest on the last bar."),
    /** Open: no final cadence, so that the piece can be continued again. */
    OPEN("offen für eine weitere Verlängerung", "Do NOT end the piece: the last section stays open (no final "
            + "cadence, momentum continues), so that it can be continued later."),
    /** A loop: the last bar leads seamlessly back into the first bar of the piece. */
    LOOP("als wiederholbarer Loop", "The piece will be played as a seamless loop: the last bars must lead back into "
            + "the first bar of the piece (see the loop target notes) without a break in harmony, rhythm or "
            + "dynamics; no final cadence, no ritardando, no notes held over the loop point.");

    private final String label;
    private final String brief;

    ContinuationEnding(String label, String brief) {
        this.label = label;
        this.brief = brief;
    }

    public String label() {
        return label;
    }

    /** What the ending means, in words for the model. */
    public String brief() {
        return brief;
    }
}
