package com.aresstack.audio.studio.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Where and how a new voice plays, decided before any note is written (architecture review 2.4,
 * planner): one entry per frame, a register, and how motifs are taken up. The generator calls of
 * all frames follow the same plan, so that they do not contradict one another.
 *
 * @param low      the lowest pitch the voice should use, within the instrument's range
 * @param high     the highest
 * @param sections one entry per frame, in the order of the piece
 * @param motifs   how the voice relates to the motifs of the piece, in words; may be empty
 */
public record VoicePlan(int low, int high, List<SectionPlan> sections, String motifs) {
    /**
     * @param plays whether the voice plays in the frame at all
     * @param idea  what it does there, in words; may be empty
     */
    public record SectionPlan(String sectionId, boolean plays, String idea) {
        public SectionPlan {
            idea = idea == null ? "" : idea.strip();
        }
    }

    public VoicePlan {
        sections = List.copyOf(sections);
        motifs = motifs == null ? "" : motifs.strip();
        if (low < 0 || high > 127 || high <= low) {
            throw new IllegalArgumentException("Invalid register " + low + ".." + high);
        }
    }

    /** The plan without a planning step: the voice plays in every frame, over its whole range. */
    public static VoicePlan everywhere(List<VoiceFrame> frames, InstrumentRange range, String instruction) {
        List<SectionPlan> plans = new ArrayList<>();
        for (VoiceFrame frame : frames) {
            plans.add(new SectionPlan(frame.sectionId(), true, instruction));
        }
        return new VoicePlan(range.low(), range.high(), plans, "");
    }

    public SectionPlan section(String sectionId) {
        for (SectionPlan plan : sections) {
            if (plan.sectionId().equals(sectionId)) {
                return plan;
            }
        }
        return null;
    }

    public boolean playsAnywhere() {
        return sections.stream().anyMatch(SectionPlan::plays);
    }
}
