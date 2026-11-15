package com.aresstack.audio.studio.ai;

/**
 * The kinds of re-orchestration the user can choose from ({@code REORCHESTRATE}, architecture
 * review E4; work package C4). Each says the planner in words what to do with the material, gives
 * the intensity of a passage that has none of its own, and whether new instruments may come in.
 */
public enum OrchestrationPreset {
    /** The same music, other instruments: melody, harmony and bass change hands. */
    REDISTRIBUTE("Material neu verteilen", "Redistribute the existing material among the parts: let other "
            + "instruments take over the melody, the motifs, the harmony and the bass line, double or split lines "
            + "where it helps. Harmony, form, rhythm and motifs stay recognisable.", false, 0.6),
    /** Lighter, chamber-like: fewer parts, more air. */
    LIGHTER("Kammermusikalisch ausdünnen", "Re-orchestrate lighter and chamber-like: fewer parts play at a "
            + "time, lines are thinner and more transparent, the essential material is kept.", false, 0.3),
    /** Fuller, tutti: more parts, doublings, new instruments may join. */
    FULLER("Voller / Tutti", "Re-orchestrate fuller, towards a tutti: more parts play, lines are doubled in "
            + "octaves, the harmony gets richer voicing; new instruments may join where they strengthen the "
            + "material.", true, 0.9),
    /** As the intensity of the sections says: instrumentation and density after the intensity curve. */
    FOLLOW_INTENSITY("Nach Intensitätskurve", "Re-orchestrate after the intensity of each section: at low "
            + "intensity few parts, sparse and in a narrow register; at high intensity many parts, dense, wide and "
            + "with doublings.", false, 0.6),
    /** Only the user's own words. */
    FREE("Freie Beschreibung", "Follow the user's instruction.", false, 0.6);

    private final String label;
    private final String brief;
    private final boolean newParts;
    private final double intensity;

    OrchestrationPreset(String label, String brief, boolean newParts, double intensity) {
        this.label = label;
        this.brief = brief;
        this.newParts = newParts;
        this.intensity = intensity;
    }

    /** The name of the preset as the user sees it. */
    public String label() {
        return label;
    }

    /** What the preset means, in words for the model. */
    public String brief() {
        return brief;
    }

    /** Whether new instruments may join by default. */
    public boolean newPartsByDefault() {
        return newParts;
    }

    /** The intensity of a passage whose section says none and for which no curve is given. */
    public double defaultIntensity() {
        return intensity;
    }
}
