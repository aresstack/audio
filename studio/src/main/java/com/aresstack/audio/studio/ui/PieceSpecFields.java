package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.album.AlbumTrack;
import com.aresstack.audio.studio.application.CompositionSpec;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * What a new piece is to be, beside the words for the AI: its advanced settings (length, tempo,
 * key and meter, see {@link SpecChoice}), folded away under "Erweitert" and automatic unless the
 * user sets them, and whether pitches are pulled onto the harmony. A piece that is planned
 * already shows what it was planned with and cannot be changed here: other values are a new piece.
 */
final class PieceSpecFields extends JPanel {
    private final SpecChoice choice = new SpecChoice(SpecChoice.Length.BARS);
    private final JButton fold = new DeckWidgets.HeadKey("Erweitert ▸");
    private final JLabel summary = new JLabel(" ");
    private final JCheckBox snap = new JCheckBox("auf Harmonie ziehen", true);
    private static final String SNAP_HINT = "An: Töne außerhalb von Tonart und Akkord des Takts werden auf die Harmonie"
            + " gezogen. Aus: Sie bleiben stehen und werden nur gemeldet.";
    private final JLabel planned = new JLabel(" ");
    private boolean locked;

    PieceSpecFields() {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 0, 0, 0));
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        head.setOpaque(false);
        fold.setToolTipText("Takte, Tempo, Tonart und Taktart: automatisch wählt die KI sie aus der Beschreibung; "
                + "was du hier setzt, gilt");
        fold.addActionListener(event -> unfold(!choice.isVisible()));
        summary.setForeground(AudioStudioTheme.TEXT_DIM);
        snap.setOpaque(false);
        snap.setToolTipText(SNAP_HINT);
        planned.setForeground(AudioStudioTheme.TEXT_DIM);
        head.add(fold);
        head.add(summary);
        head.add(snap);
        head.add(planned);
        add(head, BorderLayout.NORTH);
        add(choice, BorderLayout.CENTER);
        choice.setVisible(false);
        choice.onChange(this::summarize);
        show(null);
        choose(CompositionSpec.legacy());
    }

    private void unfold(boolean open) {
        choice.setVisible(open);
        fold.setText(open ? "Erweitert ▾" : "Erweitert ▸");
        revalidate();
        repaint();
    }

    /** One line on what the advanced settings say: what is automatic and what the user set. */
    private void summarize() {
        Set<String> set = choice.fixed();
        if (set.isEmpty()) {
            summary.setText("alles automatisch");
            return;
        }
        SpecChoice.Values values;
        try {
            values = choice.values();
        } catch (IllegalArgumentException mistyped) {
            summary.setText(mistyped.getMessage());
            return;
        }
        StringBuilder text = new StringBuilder("vorgegeben: ");
        if (set.contains(AlbumTrack.LENGTH)) {
            text.append((int) values.length()).append(" Takte  ");
        }
        if (set.contains(AlbumTrack.BPM)) {
            text.append(values.bpm()).append(" BPM  ");
        }
        if (set.contains(AlbumTrack.KEY)) {
            text.append(values.key().germanName()).append("  ");
        }
        if (set.contains(AlbumTrack.METER)) {
            text.append(values.numerator()).append('/').append(values.denominator());
        }
        summary.setText(text.toString().trim());
    }

    /**
     * The length a new piece has unless the user says otherwise (setting "Takte je Stück und
     * Schritt"): taken while the length is automatic and no planned piece locks the fields.
     */
    void suggestBars(int suggested) {
        if (!locked) {
            choice.suggestLength(suggested);
        }
    }

    /**
     * What the fields say. A value that is still being typed counts as typed; one that is out of
     * range is refused with the words of {@link CompositionSpec}.
     */
    CompositionSpec spec() {
        SpecChoice.Values values = choice.values();
        return CompositionSpec.of((int) Math.round(values.length()), values.bpm(), values.key(), values.numerator(),
                values.denominator()).withSnapToHarmony(snap.isSelected());
    }

    /** Which settings the user set ({@link AlbumTrack#LENGTH} for the bars, ...); the others are the AI's. */
    Set<String> fixed() {
        return locked ? Set.copyOf(AlbumTrack.FIXABLE) : choice.fixed();
    }

    /** Sets the fields to a spec, all as set by the user. */
    void choose(CompositionSpec spec) {
        choice.show(values(spec), choice.fixed());
        snap.setSelected(spec.snapToHarmony());
        summarize();
    }

    /** Sets the fields to a spec; the settings named in {@code fixed} as set by the user, the others automatic. */
    void choose(CompositionSpec spec, Set<String> fixed) {
        choice.show(values(spec), fixed);
        snap.setSelected(spec.snapToHarmony());
        summarize();
    }

    /** Shows what the AI chose for the automatic settings. */
    void showChosen(CompositionSpec spec) {
        choice.showChosen(values(spec));
        summarize();
    }

    private static SpecChoice.Values values(CompositionSpec spec) {
        return new SpecChoice.Values(spec.bars(), spec.bpm(), spec.key(), spec.numerator(), spec.denominator());
    }

    /**
     * Shows the piece that is open: one that is planned shows what it was planned with and locks
     * the fields; one that is not ({@code null}) leaves them free, with what they said last.
     */
    void show(CompositionSpec ofPlannedPiece) {
        locked = ofPlannedPiece != null;
        if (locked) {
            choice.showChosen(values(ofPlannedPiece));
            choice.show(values(ofPlannedPiece), choice.fixed());
            snap.setSelected(ofPlannedPiece.snapToHarmony());
        }
        choice.lock(locked);
        snap.setEnabled(!locked);
        planned.setText(locked ? "Gesperrt: Das Stück ist mit diesen Vorgaben geplant" : " ");
        String why = locked ? "Dieses Stück ist mit diesen Vorgaben geplant. Für andere Vorgaben ein neues Stück beginnen"
                + " oder beim Komponieren „Neues Stück“ oder „Dieses ersetzen“ wählen." : null;
        planned.setToolTipText(why);
        setToolTipText(why);
        // The switch says what it does, and for a planned piece also why it cannot be changed.
        snap.setToolTipText(locked ? SNAP_HINT + " " + why : SNAP_HINT);
        summarize();
    }

    /** Whether the fields show a planned piece and cannot be changed. */
    boolean locked() {
        return locked;
    }

    /** The key a planned piece is in, for whoever needs it; {@code null} if the fields cannot be read. */
    Key key() {
        try {
            return choice.values().key();
        } catch (IllegalArgumentException mistyped) {
            return null;
        }
    }
}
