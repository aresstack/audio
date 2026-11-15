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
    }

    private boolean taken;
    private final JButton take = new JButton("Übernehmen");
    private final JButton drop = new JButton("Verwerfen");
    private final JButton hearProposal = new JButton("Vorschlag anhören");
    private final JButton hearCurrent = new JButton("Bisherige Fassung anhören");
    private final JButton stop = new JButton("Stopp");

    ProposalDialog(Window owner, String title, String what, Listening listening) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        JLabel words = new JLabel("<html><b>" + what + "</b><br><br>"
                + "Das Stück ist noch unverändert. Der Vorschlag lässt sich anhören und mit der bisherigen<br>"
                + "Fassung vergleichen. Erst „Übernehmen“ ändert das Stück; das lässt sich danach unter<br>"
                + "„Rückgängig“ zurücknehmen und steht in der Liste der Revisionen.</html>");
        JPanel hearing = new JPanel(new GridLayout(1, 3, 8, 0));
        hearing.add(hearProposal);
        hearing.add(hearCurrent);
        hearing.add(stop);
        JPanel verdict = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
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
        take.addActionListener(event -> close(true));
        drop.addActionListener(event -> close(false));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().setDefaultButton(hearProposal);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void close(boolean take) {
        taken = take;
        dispose();
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
