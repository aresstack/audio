package com.aresstack.audio.studio.ai;

/**
 * The kinds of continuation the user can choose from (report 5.3). Each preset says the planner in
 * words what the continuation should be, gives the intensity curve of the plan without a planning
 * answer ({@link ContinuationPlan#fallback}), and whether new instruments may come in.
 */
public enum ContinuationPreset {
    /** Film music: restrained, in the background, no melody that dominates all the time. */
    UNDERSCORE("Filmmusik / Underscore", "Underscore for film: a restrained, subliminal development in the "
            + "background. No constantly dominating main melody; sustained harmonies, small motivic cells, slow "
            + "changes of colour and density. Keep the intensity low to moderate.", false, 0.35, 0.45),
    /** A piece of its own: themes, variation, contrast and an arc over the whole piece. */
    STANDALONE("Eigenständiges Musikstück", "A standalone piece of music: clearly recognisable themes, "
            + "variations and contrasts, and a musical arc over the whole piece (for example a contrasting B part "
            + "and a varied return of the main theme).", false, 0.5, 0.7),
    /** Dramatic build: more and more instruments and tension, up to an orchestral climax. */
    DRAMATIC_BUILD("Dramatischer Aufbau", "A dramatic build: instrumentation and tension grow step by step, "
            + "parts enter one after another, rhythm gets denser, drums and brass may join, up to an orchestral "
            + "climax. New instruments may enter where the build needs them.", true, 0.4, 1.0),
    /** Variation or reprise: a known motif of the piece comes back and is developed. */
    VARIATION_REPRISE("Variation / Reprise", "A variation or reprise: take up the main motif of the piece "
            + "(see the theme notes) recognisably and develop it further in rhythm, harmony, register and "
            + "instrumentation.", false, 0.5, 0.6),
    /** Only the user's own words say what the continuation should be. */
    FREE("Freie Beschreibung", "Follow the user's instruction.", false, 0.5, 0.5);

    private final String label;
    private final String brief;
    private final boolean newParts;
    private final double startIntensity;
    private final double peakIntensity;

    ContinuationPreset(String label, String brief, boolean newParts, double startIntensity, double peakIntensity) {
        this.label = label;
        this.brief = brief;
        this.newParts = newParts;
        this.startIntensity = startIntensity;
        this.peakIntensity = peakIntensity;
    }

    /** The name of the preset as the user sees it. */
    public String label() {
        return label;
    }

    /** What the preset means, in words for the model. */
    public String brief() {
        return brief;
    }

    /** Whether new instruments may enter by default. */
    public boolean newPartsByDefault() {
        return newParts;
    }

    /** The intensity of the first new section in a plan without planning answer. */
    public double startIntensity() {
        return startIntensity;
    }

    /** The highest intensity a plan without planning answer reaches. */
    public double peakIntensity() {
        return peakIntensity;
    }
}
