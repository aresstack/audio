package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The proposal of the AI is a part of the page: it never asks for more width than the page has. */
public final class ProposalPanelTest {
    private static final ProposalPanel.Listening SILENT = new ProposalPanel.Listening() {
        @Override
        public void proposal() {
        }

        @Override
        public void current() {
        }

        @Override
        public void stop() {
        }
    };

    private static String longLine() {
        StringBuilder line = new StringBuilder("Abschnitt B");
        for (int word = 0; word < 400; word++) {
            line.append(" melancholic");
        }
        return line.toString();
    }

    @Test
    public void aLongPlanWrapsInsteadOfWideningThePage() {
        List<ProposalPanel.Verdict> answers = new ArrayList<ProposalPanel.Verdict>();
        ProposalPanel panel = new ProposalPanel("Die KI hat das Stück um 16 Takte verlängert.",
                List.of(longLine(), longLine()), Collections.<String>emptyList(), null, true, SILENT, answers::add);
        panel.setSize(900, panel.getPreferredSize().height);
        panel.doLayout();
        assertTrue("the text keeps every line of the plan", panel.text().contains("melancholic"));
        assertTrue("the panel asks for no more width than its keys need: " + panel.getPreferredSize().width,
                panel.getPreferredSize().width < 1200);
        assertTrue("and a long plan scrolls instead of taking the page: " + panel.getPreferredSize().height,
                panel.getPreferredSize().height < 320);
        assertNull(panel.verdict());
        panel.press(false);
        panel.press(true); // one proposal, one answer
        assertEquals(List.of(ProposalPanel.Verdict.DROP), answers);
    }

    @Test
    public void whatTheOriginalLosesHasToBeConfirmedBeforeTaking() {
        List<ProposalPanel.Verdict> answers = new ArrayList<ProposalPanel.Verdict>();
        ProposalPanel panel = new ProposalPanel("Die KI hat 4 Takte eingefügt.", Collections.<String>emptyList(),
                List.of("Melodie, Takt 8: 2 Schläge statt 4"), null, true, SILENT, answers::add);
        assertTrue(panel.asksToConfirm());
        assertFalse(panel.canTake());
        panel.press(true);
        assertTrue("nothing is taken without the user's word", answers.isEmpty());
        panel.tickConfirm(true);
        assertTrue(panel.canTake());
        panel.press(true);
        assertEquals(List.of(ProposalPanel.Verdict.TAKE), answers);
    }
}
