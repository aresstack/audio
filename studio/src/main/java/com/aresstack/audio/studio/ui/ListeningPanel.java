package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.application.StageListening;
import com.aresstack.audio.studio.hrtf.HrtfDataset;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * How the stage is heard (work package D4): the switches "Kopfhörer (3D)" and "Faltungshall", an
 * impulse response and a set of head responses of the user's own, and the key that downloads the
 * MIT KEMAR set after showing where it comes from, on which terms, and how it is cited. The page
 * only says what the user wants; reading files and the download are the host's.
 */
final class ListeningPanel extends JPanel {
    /** What the page needs from the window. */
    interface Host {
        /** The user chose another way of hearing. */
        void listeningChanged(StageListening wanted);

        /** Lets the user choose a WAV file with an impulse response; {@code null} if they do not. */
        String chooseImpulseResponse();

        /** Lets the user choose a folder with head responses; {@code null} if they do not. */
        String chooseHrtfFolder();

        /** The user confirmed source, terms and citation: download the set now. */
        void downloadHrtf(HrtfDataset dataset);
    }

    private final Host host;
    private final JCheckBox headphones = new JCheckBox("Kopfhörer (3D)");
    private final JCheckBox convolution = new JCheckBox("Faltungshall");
    private final JButton impulse = new DeckWidgets.HeadKey("Impulsantwort");
    private final JButton noImpulse = new DeckWidgets.HeadKey("×");
    private final JButton folder = new DeckWidgets.HeadKey("HRTF-Ordner");
    private final JButton noFolder = new DeckWidgets.HeadKey("×");
    private final JButton kemar = new DeckWidgets.HeadKey("KEMAR laden");
    private final JLabel room = new JLabel(" ");
    private final JLabel head = new JLabel(" ");
    /** The switches and keys of the panel, for the head of the stage's module. */
    private final JPanel keys = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private StageListening shown = StageListening.USUAL;
    private boolean filling;

    ListeningPanel(Host host) {
        this.host = host;
        setOpaque(false);
        setLayout(new FlowLayout(FlowLayout.LEFT, 14, 0));
        headphones.setOpaque(false);
        convolution.setOpaque(false);
        headphones.setToolTipText("Die Bühne für Kopfhörer: Richtung und Höhe über Kopfdaten (HRTF) statt über das Panorama. "
                + "Auf Lautsprechern klingt das falsch.");
        convolution.setToolTipText("Der Raum klingt durch Faltung mit einer Impulsantwort statt algorithmisch");
        impulse.setToolTipText("Eine gemessene Impulsantwort (WAV) als Raum laden; gilt mit „Faltungshall“");
        folder.setToolTipText("Einen eigenen Ordner mit Kopfdaten im Format des MIT-KEMAR-Satzes wählen");
        kemar.setToolTipText("Kopfdaten des MIT-KEMAR-Satzes laden: zeigt Quelle, Bedingungen und Zitat und lädt den Satz erst nach Bestätigung");
        noImpulse.setToolTipText("Keine eigene Impulsantwort: der Raum klingt aus seinen Zahlen");
        noFolder.setToolTipText("Keinen eigenen Ordner: der geladene Satz oder das Kopfmodell");
        for (JLabel line : new JLabel[]{room, head}) {
            line.setForeground(AudioStudioTheme.TEXT_DIM);
            line.setFont(line.getFont().deriveFont(11.5f));
        }
        // The switches and keys stand in the head of the stage, beside its name; here, under the
        // stage, is only what came of them: the room and the head that are heard.
        keys.setOpaque(false);
        keys.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 10, 0, 0));
        for (java.awt.Component part : new java.awt.Component[]{headphones, convolution, impulse, noImpulse,
                folder, noFolder, kemar}) {
            keys.add(part);
        }
        add(room);
        add(head);

        headphones.addActionListener(event -> change(shown.onHeadphones(headphones.isSelected())));
        convolution.addActionListener(event -> change(shown.withConvolution(convolution.isSelected())));
        impulse.addActionListener(event -> {
            String file = this.host.chooseImpulseResponse();
            if (file != null) {
                // A response of one's own is chosen to be heard: the convolved room goes on with it.
                change(shown.withImpulseFile(file).withConvolution(true));
            }
        });
        noImpulse.addActionListener(event -> change(shown.withImpulseFile("")));
        folder.addActionListener(event -> {
            String chosen = this.host.chooseHrtfFolder();
            if (chosen != null) {
                change(shown.withHrtfFolder(chosen));
            }
        });
        noFolder.addActionListener(event -> change(shown.withHrtfFolder("")));
        kemar.addActionListener(event -> askForDownload());
    }

    /** The switches and keys, to stand in the head of the stage's module. */
    JPanel keys() {
        return keys;
    }

    private void change(StageListening wanted) {
        if (filling || wanted.equals(shown)) {
            return;
        }
        shown = wanted;
        host.listeningChanged(wanted);
    }

    /** What the user is shown before anything is downloaded; also the text of a test. */
    static String terms(HrtfDataset dataset) {
        return dataset.title() + "\n\n"
                + "Quelle: " + dataset.uri() + "\n"
                + "Beschreibung: " + dataset.page() + "\n"
                + "Version: " + dataset.version() + "\n\n"
                + "Nutzungsbedingungen (Wortlaut):\n" + wrapped(dataset.terms()) + "\n\n"
                + "Bitte zitieren:\n" + wrapped(dataset.citation()) + "\n\n"
                + "Der Satz wird jetzt einmal von der Originalquelle geladen, gegen seine Prüfsumme geprüft und\n"
                + "auf diesem Rechner abgelegt. Danach braucht das Studio dafür kein Netz mehr.\n\n"
                + "Jetzt laden?";
    }

    private static String wrapped(String text) {
        StringBuilder lines = new StringBuilder();
        int length = 0;
        for (String word : text.split(" ")) {
            if (length > 0 && length + word.length() > 92) {
                lines.append('\n');
                length = 0;
            }
            lines.append(length == 0 ? "" : " ").append(word);
            length += word.length() + 1;
        }
        return lines.toString();
    }

    private void askForDownload() {
        HrtfDataset dataset = HrtfDataset.MIT_KEMAR;
        if (JOptionPane.showConfirmDialog(this, terms(dataset), "HRTF-Satz laden", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE) == JOptionPane.OK_OPTION) {
            kemar.setEnabled(false);
            host.downloadHrtf(dataset);
        }
    }

    /**
     * Shows a way of hearing and what came of it.
     *
     * @param loaded    what was read for it; {@code null} while it is being read
     * @param installed whether the MIT KEMAR set is on this machine already
     * @param busy      whether a download runs
     */
    void show(StageListening listening, StageListening.Loaded loaded, boolean installed, boolean busy) {
        filling = true;
        try {
            shown = listening;
            headphones.setSelected(listening.headphones());
            convolution.setSelected(listening.convolution());
            noImpulse.setVisible(!listening.impulseFile().isEmpty());
            noFolder.setVisible(!listening.hrtfFolder().isEmpty());
            kemar.setEnabled(!installed && !busy);
            kemar.setText(installed ? "KEMAR geladen" : busy ? "KEMAR lädt …" : "KEMAR laden");
            if (loaded == null) {
                room.setText("Wird gelesen …");
                head.setText(" ");
            } else {
                room.setText("Raum: " + loaded.room());
                // On headphones the user is told whose measurements they hear, as the authors ask.
                head.setText(listening.headphones() ? "Kopf: " + loaded.head() : " ");
                head.setToolTipText(listening.headphones() ? loaded.head() : null);
            }
        } finally {
            filling = false;
        }
    }

    JCheckBox headphones() {
        return headphones;
    }

    JCheckBox convolution() {
        return convolution;
    }

    JButton kemar() {
        return kemar;
    }

    String headLine() {
        return head.getText();
    }

    String roomLine() {
        return room.getText();
    }
}
