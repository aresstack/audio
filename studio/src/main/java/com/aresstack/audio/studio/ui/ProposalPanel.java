package com.aresstack.audio.studio.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * A proposal of the AI waits here, on the page of the arrangement above the tracks, to be heard
 * and then taken or thrown away. It is part of the window and no dialog: its text wraps to the
 * width the window has, and a long plan scrolls. As long as it is shown the piece is as it was.
 */
final class ProposalPanel extends JPanel {
    /** What the panel lets the user hear. */
    interface Listening {
        /** Plays the piece as the proposal would make it. */
        void proposal();

        /** Plays the piece as it is. */
        void current();

        void stop();

        /** Plays only what the proposal adds; the panel offers it if it was given a name for it. */
        default void alone() {
        }
    }

    /** What the user made of the proposal. */
    enum Verdict { TAKE, DROP, AGAIN }

    /** The most the text may take of the page before it scrolls. */
    private static final int TEXT_HEIGHT = 150;

    private final JButton again = new JButton("Neu erzeugen");
    private final JButton take = new JButton("Übernehmen");
    private final JButton drop = new JButton("Verwerfen");
    private final JTextArea words = new JTextArea();
    private final Consumer<Verdict> decided;
    /** The tick that says the user saw what the original loses; {@code null} if it loses nothing. */
    private LossConfirmation confirm;
    private Verdict verdict;

    /**
     * @param what      what the AI did, one sentence
     * @param notes     what is worth knowing before deciding, one line each
     * @param toConfirm what the proposal takes from the original and cannot give back, one line
     *                  each: every line is shown, and the proposal can only be taken once the user
     *                  ticked that they saw them
     * @param alone     the name of the key that plays only what is new, or {@code null} for none
     * @param again     whether the AI can be asked to do it again
     * @param decided   told once, when the user takes the proposal, throws it away or asks again
     */
    ProposalPanel(String what, List<String> notes, List<String> toConfirm, String alone, boolean again,
                  Listening listening, Consumer<Verdict> decided) {
        super(new BorderLayout(0, 8));
        this.decided = decided;
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0),
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(AudioStudioTheme.ACCENT, 1, true),
                        BorderFactory.createEmptyBorder(10, 12, 10, 12))));
        JLabel head = new JLabel("Vorschlag der KI");
        head.setFont(head.getFont().deriveFont(java.awt.Font.BOLD));

        StringBuilder said = new StringBuilder(what);
        for (String note : notes) {
            said.append("\n· ").append(note);
        }
        said.append("\nDas Stück ist noch unverändert. Erst „Übernehmen“ ändert es; das lässt sich danach unter"
                + " „Rückgängig“ zurücknehmen und steht in der Liste der Revisionen.");
        words.setText(said.toString());
        words.setEditable(false);
        words.setFocusable(false);
        words.setLineWrap(true);
        words.setWrapStyleWord(true);
        words.setOpaque(false);
        words.setFont(head.getFont().deriveFont(java.awt.Font.PLAIN));
        words.setCaretPosition(0);
        JScrollPane text = new JScrollPane(words, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override
            public Dimension getPreferredSize() {
                // As high as the wrapped text, but never more than a part of the page; the width is the page's.
                Dimension wanted = super.getPreferredSize();
                return new Dimension(10, Math.min(TEXT_HEIGHT, wanted.height));
            }
        };
        text.setBorder(BorderFactory.createEmptyBorder());
        text.setOpaque(false);
        text.getViewport().setOpaque(false);

        JPanel hearing = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        hearing.setOpaque(false);
        JButton hearProposal = new JButton("Vorschlag anhören");
        hearProposal.addActionListener(event -> listening.proposal());
        hearing.add(hearProposal);
        if (alone != null) {
            JButton hearAlone = new JButton(alone);
            hearAlone.addActionListener(event -> listening.alone());
            hearing.add(hearAlone);
        }
        JButton hearCurrent = new JButton("Bisherige Fassung anhören");
        hearCurrent.addActionListener(event -> listening.current());
        hearing.add(hearCurrent);
        JButton stop = new JButton("Stopp");
        stop.addActionListener(event -> listening.stop());
        hearing.add(stop);

        JPanel choice = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        choice.setOpaque(false);
        if (again) {
            this.again.setToolTipText("Die KI schreibt es noch einmal, anders; dieser Vorschlag wird ersetzt.");
            this.again.addActionListener(event -> decide(Verdict.AGAIN));
            choice.add(this.again);
        }
        choice.add(take);
        choice.add(drop);
        take.addActionListener(event -> {
            if (confirm == null || confirm.confirmed()) { // never taken without the user's word
                decide(Verdict.TAKE);
            }
        });
        drop.addActionListener(event -> decide(Verdict.DROP));
        for (JButton key : new JButton[]{hearProposal, hearCurrent, stop, this.again, take, drop}) {
            key.setFocusable(false);
        }

        JPanel keys = new JPanel(new BorderLayout(12, 0));
        keys.setOpaque(false);
        keys.add(hearing, BorderLayout.WEST);
        keys.add(choice, BorderLayout.EAST);

        add(head, BorderLayout.NORTH);
        if (toConfirm.isEmpty()) {
            add(text, BorderLayout.CENTER);
        } else {
            // What the original loses is listed in full, and taking waits for the user's word.
            confirm = new LossConfirmation(toConfirm, () -> take.setEnabled(confirm.confirmed()));
            take.setEnabled(false);
            take.setToolTipText("Erst die gekürzten Noten bestätigen");
            JPanel middle = new JPanel(new BorderLayout(0, 8));
            middle.setOpaque(false);
            middle.add(text, BorderLayout.NORTH);
            middle.add(confirm, BorderLayout.CENTER);
            add(middle, BorderLayout.CENTER);
        }
        add(keys, BorderLayout.SOUTH);
    }

    private void decide(Verdict chosen) {
        if (verdict != null) {
            return; // one proposal, one answer
        }
        verdict = chosen;
        decided.accept(chosen);
    }

    /** What the user chose, or {@code null} while the proposal waits. */
    Verdict verdict() {
        return verdict;
    }

    /** The text shown, as it wraps in the panel. */
    String text() {
        return words.getText();
    }

    /** Whether the panel asks the user to confirm what the original loses. */
    boolean asksToConfirm() {
        return confirm != null;
    }

    /** Ticks or unticks the confirmation as a user would; for tests. */
    void tickConfirm(boolean ticked) {
        confirm.tick(ticked);
    }

    boolean canTake() {
        return take.isEnabled();
    }

    /** Presses "Neu erzeugen" as a user would; for tests. */
    void pressAgain() {
        again.doClick();
    }

    /** Presses "Übernehmen" or "Verwerfen" as a user would; for tests. */
    void press(boolean take) {
        (take ? this.take : this.drop).doClick();
    }
}
