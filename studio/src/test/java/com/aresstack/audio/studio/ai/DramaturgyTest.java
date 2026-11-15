package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Voice;
import com.aresstack.audio.studio.ai.VoiceNormalizer.ShapedNote;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * C4, report 10.1: intensity acts on the composition, not only on the volume. It decides how many
 * and which parts play, how dense and wide each plays, and bars far too dense for it are thinned;
 * a curve of the user gives the intensity of each section.
 */
public final class DramaturgyTest {
    private static Part part(String id, String role) {
        return new Part(id, id, role, id, 0, null, null, List.of(new Voice(id + "-v", id, null, null)), null);
    }

    private static final List<Part> BAND = List.of(part("drums", Role.DRUMS), part("pad", Role.TEXTURE),
            part("melody", Role.MELODY), part("chords", Role.HARMONY), part("bass", Role.BASS), part("lead2", Role.MELODY));

    @Test
    public void theEnsembleGrowsWithIntensityAndTheCarryingPartsComeFirst() {
        assertEquals(List.of(3, 4, 5, 6), List.of(Dramaturgy.ensembleSize(0, 6), Dramaturgy.ensembleSize(0.3, 6),
                Dramaturgy.ensembleSize(0.6, 6), Dramaturgy.ensembleSize(1, 6)));
        assertEquals(1, Dramaturgy.ensembleSize(0, 1));
        // quiet: melodies and bass; the drums join last; the order of the piece is kept
        assertEquals(List.of("melody", "bass", "lead2"), ids(Dramaturgy.ensemble(0, BAND, Part::role)));
        assertEquals(List.of("melody", "chords", "bass", "lead2"), ids(Dramaturgy.ensemble(0.3, BAND, Part::role)));
        assertEquals(List.of("pad", "melody", "chords", "bass", "lead2"), ids(Dramaturgy.ensemble(0.6, BAND, Part::role)));
        assertEquals(ids(BAND), ids(Dramaturgy.ensemble(1, BAND, Part::role)));
    }

    @Test
    public void densityRegisterAndVelocityGrowWithIntensity() {
        for (String role : Role.ALL) {
            Dramaturgy.Texture quiet = Dramaturgy.texture(0.1, role);
            Dramaturgy.Texture loud = Dramaturgy.texture(0.9, role);
            assertTrue(role, quiet.most() < loud.most());
            assertTrue(role, quiet.fewest() <= loud.fewest());
            assertTrue(role, quiet.loudest() < loud.loudest());
            assertTrue(role, quiet.fewest() <= quiet.most());
        }
        assertTrue(Dramaturgy.guidance(0.2, Role.MELODY).contains("narrow range"));
        assertTrue(Dramaturgy.guidance(0.9, Role.MELODY).contains("wide range"));
        assertTrue(Dramaturgy.guidance(0.9, Role.MELODY).startsWith("Dramaturgy for intensity 0.9: about "));
    }

    @Test
    public void aBarFarTooDenseForItsIntensityIsThinnedAndRecorded() {
        TimeMap time = TimeMap.of(100);
        VoiceFrame frame = new VoiceFrame("s1", "A", null, 0, 2 * 1920, 0, 2, List.of(), 0.0, null);
        List<ShapedNote> notes = new ArrayList<>();
        for (int i = 0; i < 16; i++) { // sixteenths in bar 1, alternately loud and soft
            notes.add(new ShapedNote(i * 120L, 120, 60 + i, i % 2 == 0 ? 90 : 50));
        }
        notes.add(new ShapedNote(1920, 960, 72, 80)); // bar 2: one note
        List<String> corrections = new ArrayList<>();
        List<ShapedNote> kept = Dramaturgy.thin(frame, time, 0.0, Role.MELODY, notes, corrections);
        int limit = (int) Math.ceil(Dramaturgy.texture(0, Role.MELODY).most() * Dramaturgy.THIN_ABOVE);
        assertEquals(limit + 1, kept.size());
        // the notes on the beat stay, the order stays
        for (long beat = 0; beat < 1920; beat += 480) {
            long tick = beat;
            assertTrue(kept.stream().anyMatch(n -> n.tick() == tick));
        }
        assertEquals(notes.get(notes.size() - 1), kept.get(kept.size() - 1));
        assertEquals(1, corrections.size());
        assertTrue(corrections.get(0).contains("Takt 1 zu dicht"));
        // at full intensity the same bar is fine
        assertEquals(notes, Dramaturgy.thin(frame, time, 1.0, Role.MELODY, notes, new ArrayList<>()));
    }

    @Test
    public void aCurveGivesEachSectionTheValueAtItsMiddle() {
        IntensityCurve build = IntensityCurve.ramp(0.2, 1.0);
        assertEquals(0.2, build.at(0), 0);
        assertEquals(0.6, build.at(0.5), 0);
        assertEquals(1.0, build.at(1), 0);
        assertEquals(List.of(0.33, 0.67, 0.93), build.forSections(List.of(4, 6, 2)).stream()
                .map(v -> Math.round(v * 100) / 100.0).toList());
        IntensityCurve arch = IntensityCurve.arch(0.3, 1.0, 0.2);
        assertEquals(1.0, arch.at(0.5), 0);
        assertEquals("0.30 -> 1.00 -> 0.20", arch.toString());
        assertEquals(List.of(0.5, 0.5), IntensityCurve.flat(0.5).forSections(List.of(8, 8)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aCurveStaysWithinZeroAndOne() {
        IntensityCurve.of(0.2, 1.2);
    }

    @Test
    public void aPlanWithoutAnswerFollowsTheCurveAndLetsItShapeTheInstrumentation() {
        Composition piece = ContinuationContextTest.piece(8);
        ContinuationRequest request = ContinuationRequest.of(16, ContinuationPreset.FREE, ContinuationEnding.OPEN,
                "aufbauen").withCurve(IntensityCurve.ramp(0.1, 0.9));
        ContinuationPlan plan = ContinuationPlan.fallback(ContinuationTask.of(piece, request, 1), piece);
        assertEquals(List.of(0.3, 0.7), plan.sections().stream().map(ContinuationPlan.PlannedSection::intensity).toList());
        // and a planning answer is brought onto the curve, with a record
        ContinuationPlan read = ChatContinuationPlanner.read(com.google.gson.JsonParser.parseString(
                "{\"sections\":[{\"name\":\"Eins\",\"bars\":8,\"intensity\":0.9,\"parts\":[\"melody\"]},"
                        + "{\"name\":\"Zwei\",\"bars\":8,\"intensity\":0.7,\"parts\":[\"melody\"]}]}").getAsJsonObject(),
                ContinuationTask.of(piece, request, 1), piece);
        assertEquals(List.of(0.3, 0.7), read.sections().stream().map(ContinuationPlan.PlannedSection::intensity).toList());
        assertTrue(read.repairs().stream().anyMatch(r -> r.contains("Eins") && r.contains("Kurve")));
        assertTrue(ChatContinuationPlanner.prompt(ContinuationTask.of(piece, request, 1))
                .contains("Intensity curve given by the user from the first to the last new bar: 0.10 -> 0.90"));
    }

    private static List<String> ids(List<Part> parts) {
        return parts.stream().map(Part::id).toList();
    }
}
