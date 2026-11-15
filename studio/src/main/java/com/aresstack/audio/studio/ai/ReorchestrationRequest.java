package com.aresstack.audio.studio.ai;

import java.util.List;

/**
 * What the user asks for when a passage is to be re-orchestrated ({@code REORCHESTRATE},
 * architecture review E4: chosen parts × stretch of time, new parts allowed; work package C4):
 * which bars, which parts give their material and play it anew, of which kind, how many new
 * instruments may join, and optionally the intensity curve the passage follows.
 *
 * <p>The notes of the chosen parts that begin in the bars are written anew; notes that begin
 * before and sound into the bars stay (architecture review E5, border rule). Every other part and
 * every other bar stays as it is.
 *
 * @param firstBar    the first bar of the passage, counted from 1
 * @param lastBar     the last bar of the passage, counted from 1, at least {@code firstBar}
 * @param partIds     the parts whose material is redistributed and which play it anew; empty for all
 * @param preset      the kind of re-orchestration
 * @param instruction what it should be, in the user's words; may be empty except for
 *                    {@link OrchestrationPreset#FREE}
 * @param newParts    how many new instruments may join, 0 to {@value ContinuationRequest#MAX_NEW_PARTS}
 * @param curve       the intensity from the first to the last bar of the passage, or {@code null}
 *                    to take the intensity of the sections (and of the preset where they say none)
 */
public record ReorchestrationRequest(int firstBar, int lastBar, List<String> partIds, OrchestrationPreset preset,
                                     String instruction, int newParts, IntensityCurve curve) {
    public ReorchestrationRequest {
        if (firstBar < 1 || lastBar < firstBar) {
            throw new IllegalArgumentException("Ein Bereich reicht von Takt 1 an über mindestens einen Takt: "
                    + firstBar + "-" + lastBar);
        }
        if (lastBar - firstBar + 1 > ContinuationRequest.MAX_BARS) {
            throw new IllegalArgumentException("Höchstens " + ContinuationRequest.MAX_BARS
                    + " Takte auf einmal umorchestrieren.");
        }
        partIds = List.copyOf(partIds == null ? List.of() : partIds);
        preset = preset == null ? OrchestrationPreset.REDISTRIBUTE : preset;
        instruction = instruction == null ? "" : instruction.strip();
        if (preset == OrchestrationPreset.FREE && instruction.isEmpty()) {
            throw new IllegalArgumentException("Die freie Beschreibung braucht eine Anweisung.");
        }
        if (newParts < 0 || newParts > ContinuationRequest.MAX_NEW_PARTS) {
            throw new IllegalArgumentException("Höchstens " + ContinuationRequest.MAX_NEW_PARTS
                    + " neue Instrumente: " + newParts);
        }
    }

    /** All parts of bars {@code firstBar..lastBar}, with the defaults of the preset. */
    public static ReorchestrationRequest of(int firstBar, int lastBar, OrchestrationPreset preset) {
        OrchestrationPreset kind = preset == null ? OrchestrationPreset.REDISTRIBUTE : preset;
        return new ReorchestrationRequest(firstBar, lastBar, List.of(), kind, "", kind.newPartsByDefault() ? 2 : 0,
                null);
    }

    public int bars() {
        return lastBar - firstBar + 1;
    }

    public ReorchestrationRequest forParts(List<String> parts) {
        return new ReorchestrationRequest(firstBar, lastBar, parts, preset, instruction, newParts, curve);
    }

    public ReorchestrationRequest withInstruction(String words) {
        return new ReorchestrationRequest(firstBar, lastBar, partIds, preset, words, newParts, curve);
    }

    public ReorchestrationRequest withNewParts(int count) {
        return new ReorchestrationRequest(firstBar, lastBar, partIds, preset, instruction, count, curve);
    }

    public ReorchestrationRequest withCurve(IntensityCurve given) {
        return new ReorchestrationRequest(firstBar, lastBar, partIds, preset, instruction, newParts, given);
    }
}
