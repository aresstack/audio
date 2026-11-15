package com.aresstack.audio.studio.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * A proposal of the AI waits here to be heard and then taken or thrown away. As long as the
 * dialog is open the piece is as it was; closing it without a choice throws the proposal away.
 */
final class ProposalDialog extends JDialog {
    /** What the dialog lets the user hear. */
    interface Listening {
        /** Plays the piece as the proposal would make it. */
        void proposal();

        /** Plays the piece as it is. */
        void current();

        void stop();

        /** Plays only what the proposal adds; the dialog offers it if it was given a name for it. */
        default void alone() {
        }
    }

    /** What the user made of the proposal. */
    enum Verdict { TAKE, DROP, AGAIN }

    private boolean taken;
    private Verdict verdict = Verdict.DROP;
    private final JButton again = new JButton("Neu erzeugen");
    private final JButton take = new JButton("Übernehmen");
    private final JButton drop = new JButton("Verwerfen");
    private final JButton hearProposal = new JButton("Vorschlag anhören");
    private final JButton hearCurrent = new JButton("Bisherige Fassung anhören");
    private final JButton stop = new JButton("Stopp");

    ProposalDialog(Window owner, String title, String what, Listening listening) {
        this(owner, title, what, java.util.Collections.<String>emptyList(), null, false, listening);
    }

    /**
     * @param notes what is worth knowing before deciding, one line each
     * @param alone the name of the key that plays only what is new, or {@code null} for none
     * @param again whether the AI can be asked to do it again
     */
    ProposalDialog(Window owner, String title, String what, java.util.List<String> notes, String alone,
                   boolean again, Listening listening) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        StringBuilder said = new StringBuilder("<html><b>").append(escape(what)).append("</b><br>");
        for (String note : notes) {
            said.append("<br>· ").append(escape(note));
        }
        JLabel words = new JLabel(said.append(notes.isEmpty() ? "" : "<br>").append("<br>"
                + "Das Stück ist noch unverändert. Der Vorschlag lässt sich anhören und mit der bisherigen<br>"
                + "Fassung vergleichen. Erst „Übernehmen“ ändert das Stück; das lässt sich danach unter<br>"
                + "„Rückgängig“ zurücknehmen und steht in der Liste der Revisionen.</html>").toString());
        JPanel hearing = new JPanel(new GridLayout(1, 0, 8, 0));
        hearing.add(hearProposal);
        if (alone != null) {
            JButton hearAlone = new JButton(alone);
            hearAlone.addActionListener(event -> listening.alone());
            hearing.add(hearAlone);
        }
        hearing.add(hearCurrent);
        hearing.add(stop);
        JPanel verdict = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        if (again) {
            this.again.setToolTipText("Die KI schreibt es noch einmal, anders; dieser Vorschlag wird ersetzt.");
            this.again.addActionListener(event -> close(Verdict.AGAIN));
            verdict.add(this.again);
        }
        verdict.add(take);
        verdict.add(drop);
        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 14, 16));
        content.add(words, BorderLayout.NORTH);
        content.add(hearing, BorderLayout.CENTER);
        content.add(verdict, BorderLayout.SOUTH);
        setContentPane(content);
        hearProposal.addActionListener(event -> listening.proposal());
        hearCurrent.addActionListener(event -> listening.current());
        stop.addActionListener(event -> listening.stop());
        take.addActionListener(event -> close(Verdict.TAKE));
        drop.addActionListener(event -> close(Verdict.DROP));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().setDefaultButton(hearProposal);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void close(Verdict chosen) {
        verdict = chosen;
        taken = chosen == Verdict.TAKE;
        dispose();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Shows the dialog and waits; closing it without a choice throws the proposal away. */
    Verdict decide() {
        setVisible(true);
        return verdict;
    }

    Verdict verdict() {
        return verdict;
    }

    /** Presses "Neu erzeugen" as a user would; for tests. */
    void pressAgain() {
        again.doClick();
    }

    /** Shows the dialog and waits; returns whether the proposal was taken. */
    boolean ask() {
        setVisible(true);
        return taken;
    }

    /** Presses a key as a user would; for tests. */
    void press(boolean take) {
        (take ? this.take : this.drop).doClick();
    }

    boolean taken() {
        return taken;
    }
}
