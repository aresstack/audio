package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.edit.Selection;
import com.aresstack.audio.studio.ai.RegionRequest;
import com.aresstack.audio.studio.application.RegionReworkTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Marking a passage in the lanes and the form "Passage ersetzen oder variieren" (work package C2, the window's part). */
public final class RegionFormTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final Composition piece = RegionReworkTest.flutePiece();

    @Test
    public void theMarkedPassageFillsTheFormAndBecomesTheSelection() {
        RegionForm form = new RegionForm(piece, 12, new ArrangementView.Range(List.of("strings", "bass"), 5, 8));
        assertEquals(List.of("strings", "bass"), form.partIds());
        RegionRequest request = form.read();
        assertEquals(RegionRequest.Mode.VARIATE, request.mode());
        assertEquals(RegionRequest.Preset.NONE, request.preset());
        assertEquals(Selection.bars(piece, List.of("strings", "bass"), 5, 8), request.selection());
    }

    @Test
    public void itSaysWhatWasChosen() {
        RegionForm form = new RegionForm(piece, 12, null);
        assertEquals("without a mark: the first track over the whole piece", List.of("flute"), form.partIds());
        assertEquals(1, form.firstBar());
        assertEquals(12, form.lastBar());
        form.choose(false, new int[]{1}, 5, 8, 1, " ruhiger, bitte ");
        RegionRequest calmer = form.read();
        assertEquals(RegionRequest.Mode.VARIATE, calmer.mode());
        assertEquals(RegionRequest.Preset.CALMER, calmer.preset());
        assertEquals("ruhiger, bitte", calmer.instruction());
        assertEquals(Selection.bars(piece, List.of("strings"), 5, 8), calmer.selection());
        assertTrue(form.presetOffered());
        form.choose(true, new int[]{0}, 3, 3, 4, "");
        RegionRequest replaced = form.read();
        assertEquals(RegionRequest.Mode.REPLACE, replaced.mode());
        assertEquals("replacing knows no direction", RegionRequest.Preset.NONE, replaced.preset());
        assertFalse(form.presetOffered());
    }

    @Test
    public void aPassageThatIsNoneIsRefusedInWords() {
        RegionForm form = new RegionForm(piece, 12, null);
        form.choose(false, new int[0], 1, 2, 0, "");
        try {
            form.read();
            fail();
        } catch (IllegalArgumentException refused) {
            assertEquals("Mindestens eine Spur wählen.", refused.getMessage());
        }
        form.choose(false, new int[]{0}, 6, 2, 0, "");
        try {
            form.read();
            fail();
        } catch (IllegalArgumentException refused) {
            assertEquals("Der letzte Takt liegt vor dem ersten.", refused.getMessage());
        }
    }

    @Test
    public void draggingAcrossTheLanesMarksBarsAndTracks() throws Exception {
        Path file = temporary.newFile("arrangement.tsv").toPath();
        List<String> lines = new ArrayList<String>(Arrays.asList("tempo\t120", "bars\t8", "track\ta\t0\t0\tmelody",
                "track\tb\t0\t1\tharmony", "track\tc\t32\t2\tbass"));
        for (int bar = 0; bar < 8; bar++) {
            lines.add("note\ta\t" + bar * 1920 + "\t1920\t60\t90");
        }
        Files.write(file, lines);
        final List<ArrangementView.Range> told = new ArrayList<ArrangementView.Range>();
        ArrangementView view = new ArrangementView(new ArrangementView.Listener() {
            @Override
            public void seek(double second) {
            }

            @Override
            public void marked(ArrangementView.Range range) {
                told.add(range);
            }
        });
        view.setSize(810, 400); // eight bars of 100 pixels
        view.setLaneHeight(60);
        view.setArrangement(ArrangementView.Arrangement.read(file), Arrays.asList("a", "b", "c"), "");
        assertNull(view.marked());

        view.mark(250, ArrangementView.RULER + 70, 520, ArrangementView.RULER + 130);
        assertEquals(new ArrangementView.Range(List.of("b", "c"), 3, 6), view.marked());
        assertEquals(view.marked(), told.get(told.size() - 1));
        view.mark(520, ArrangementView.RULER + 130, 250, ArrangementView.RULER + 70);
        assertEquals("the same dragged the other way", new ArrangementView.Range(List.of("b", "c"), 3, 6), view.marked());
        view.mark(-40, 2, 5000, 5000);
        assertEquals("beyond the lanes is their edge", new ArrangementView.Range(List.of("a", "b", "c"), 1, 8), view.marked());

        view.setArrangement(ArrangementView.Arrangement.read(file), Arrays.asList("a", "b"), "");
        assertNull("a mark on a track that is gone is gone", view.marked());
        assertNull(told.get(told.size() - 1));
    }
}
