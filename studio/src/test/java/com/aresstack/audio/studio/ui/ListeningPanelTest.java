package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.application.StageListening;
import com.aresstack.audio.studio.hrtf.HrtfDataset;
import com.aresstack.audio.studio.mix.StageSound;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.Assert;
import org.junit.Test;

/**
 * The switches of how the stage is heard (work package D4) tell the window what the user wants,
 * show whose head responses are heard on headphones, and the download of the MIT KEMAR set is
 * offered only with its source, its terms and its citation.
 */
public final class ListeningPanelTest {
    private static final class Told implements ListeningPanel.Host {
        final List<StageListening> wanted = new ArrayList<StageListening>();
        final List<HrtfDataset> downloads = new ArrayList<HrtfDataset>();
        String impulse;
        String folder;

        @Override
        public void listeningChanged(StageListening chosen) {
            wanted.add(chosen);
        }

        @Override
        public String chooseImpulseResponse() {
            return impulse;
        }

        @Override
        public String chooseHrtfFolder() {
            return folder;
        }

        @Override
        public void downloadHrtf(HrtfDataset dataset) {
            downloads.add(dataset);
        }
    }

    @Test
    public void theSwitchesTellTheWindowAndTheHeadIsNamedOnHeadphones() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (javax.swing.UIManager.getFont("defaultFont") == null) {
                javax.swing.UIManager.put("defaultFont", new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12));
            }
            Told told = new Told();
            ListeningPanel panel = new ListeningPanel(told);
            StageListening.Loaded usual = new StageListening.Loaded(StageSound.LOUDSPEAKERS, "Kugelkopfmodell",
                    "algorithmischer Raum", List.of());
            panel.show(StageListening.USUAL, usual, false, false);
            Assert.assertTrue("showing a choice tells nobody", told.wanted.isEmpty());
            Assert.assertFalse(panel.headphones().isSelected());
            Assert.assertEquals("on loudspeakers no head is named", " ", panel.headLine());
            Assert.assertTrue(panel.kemar().isEnabled());

            panel.headphones().doClick();
            Assert.assertEquals(List.of(StageListening.USUAL.onHeadphones(true)), told.wanted);
            panel.convolution().doClick();
            Assert.assertEquals(StageListening.USUAL.onHeadphones(true).withConvolution(true), told.wanted.get(1));

            // What came of it: on headphones the source of the head responses is shown, authors included.
            String source = "MIT KEMAR (Gardner & Martin, MIT Media Lab 1994)";
            panel.show(told.wanted.get(1), new StageListening.Loaded(StageSound.binaural().withConvolution(true),
                    source, "Faltungshall aus den Zahlen des Raums", List.of()), true, false);
            Assert.assertTrue(panel.headLine().contains(source));
            Assert.assertTrue(panel.roomLine().contains("Faltungshall"));
            Assert.assertFalse("a set that is there is not downloaded again", panel.kemar().isEnabled());
            Assert.assertEquals(2, told.wanted.size());
            Assert.assertTrue(told.downloads.isEmpty());
        });
    }

    @Test
    public void beforeTheDownloadSourceTermsAndCitationAreShown() {
        String shown = ListeningPanel.terms(HrtfDataset.MIT_KEMAR);
        Assert.assertTrue(shown.contains("https://sound.media.mit.edu/resources/KEMAR/compact.zip"));
        Assert.assertTrue(shown.contains("https://sound.media.mit.edu/resources/KEMAR.html"));
        String flat = shown.replace('\n', ' ');
        Assert.assertTrue(flat.contains("provided free with no restrictions on use, provided the authors are cited"));
        Assert.assertTrue(flat.contains("Bill Gardner and Keith Martin"));
        Assert.assertTrue(flat.contains("Technical Report #280"));
        Assert.assertTrue(shown.contains("Jetzt laden?"));
    }
}
