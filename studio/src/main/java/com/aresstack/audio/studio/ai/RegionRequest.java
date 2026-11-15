package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.edit.Selection;

/**
 * What the user asks for when a marked passage is to be written anew (work package C2,
 * architecture review E4): replace it or vary it, in which direction, in the user's words.
 *
 * @param mode        {@link Mode#REPLACE} writes the passage anew without looking at what was
 *                    there, {@link Mode#VARIATE} gives the notes that were there as template
 * @param selection   the parts or clips and the bars to write anew
 * @param preset      a direction the change should take, {@link Preset#NONE} for none
 * @param instruction what should be different, in the user's words; may be empty
 */
public record RegionRequest(Mode mode, Selection selection, Preset preset, String instruction) {
    /** {@code REPLACE_REGION} or {@code VARIATE_REGION}. */
    public enum Mode {
        /** New material for the passage, fitting what comes before and after. */
        REPLACE("replace-region"),
        /** The passage as it is, changed: the old notes are the template. */
        VARIATE("variate-region");

        private final String id;

        Mode(String id) {
            this.id = id;
        }

        /** The neutral name of the kind of change, for run ids and cache folders. */
        public String id() {
            return id;
        }
    }

    /** A direction the passage should take; the user's instruction may add to it. */
    public enum Preset {
        NONE(""),
        /** Fewer notes, longer values, softer, smaller leaps (acceptance A6: "calmer strings"). */
        CALMER("Make the passage calmer: fewer notes, longer note values, softer velocities, smaller leaps, "
                + "no new rhythmic activity."),
        /** More motion: shorter values, more rhythm, more drive. */
        LIVELIER("Make the passage livelier: more rhythmic motion, shorter note values, a bit more drive, "
                + "without leaving the harmony."),
        /** The same idea with fewer notes and plainer rhythm. */
        SIMPLER("Make the passage simpler: keep the core idea, fewer notes, plainer rhythm, no ornaments."),
        /** The same idea with more detail: passing notes, ornaments, counter-movement. */
        RICHER("Make the passage richer: keep the core idea, add passing notes, ornaments or counter-movement.");

        private final String rule;

        Preset(String rule) {
            this.rule = rule;
        }

        /** The instruction for the model, empty for {@link #NONE}. */
        public String rule() {
            return rule;
        }
    }

    public RegionRequest {
        if (mode == null || selection == null) {
            throw new IllegalArgumentException("Art und Auswahl der Änderung fehlen.");
        }
        preset = preset == null ? Preset.NONE : preset;
        instruction = instruction == null ? "" : instruction.strip();
    }

    public static RegionRequest replace(Selection selection, String instruction) {
        return new RegionRequest(Mode.REPLACE, selection, Preset.NONE, instruction);
    }

    public static RegionRequest variate(Selection selection, Preset preset, String instruction) {
        return new RegionRequest(Mode.VARIATE, selection, preset, instruction);
    }
}
