package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.application.HeardTitles;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

/**
 * The page "Player": the titles of an album are played one after the other. They are chosen in
 * the tree at the left (a click on a title plays it, a click on an album makes it the one that
 * plays); titles nobody has heard yet are marked there. The large area right of the tree belongs
 * to a visualizer ({@link #setVisualizer}); until one is put there it shows what plays. The
 * settings of the player stand right-aligned under it.
 */
final class PlayerPanel extends JPanel {
    /** The longest overlap of two titles the setting takes, in seconds. */
    static final double MOST_CROSSFADE_SECONDS = 30.0;
    /** How the overlap setting steps, in seconds. */
    static final double CROSSFADE_STEP_SECONDS = 0.5;
    /** How the setting "Gehört ab" steps, in percent. */
    static final int HEARD_STEP_PERCENT = 5;

    private final JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JPanel area = new JPanel(new BorderLayout());
    private final NowPlaying nowPlaying = new NowPlaying();
    /** "Nur neue": only the titles not heard yet, in any order; with none left the player waits. */
    private final JCheckBox onlyNew = new JCheckBox("Nur neue");
    private final JSpinner heardPercent = new JSpinner(new SpinnerNumberModel(HeardTitles.DEFAULT_PERCENT,
            HeardTitles.LEAST_PERCENT, HeardTitles.MOST_PERCENT, HEARD_STEP_PERCENT));
    private final JSpinner crossfade = new JSpinner(new SpinnerNumberModel(0.0, 0.0, MOST_CROSSFADE_SECONDS,
            CROSSFADE_STEP_SECONDS));
    /** What the player does right now, e.g. that it waits for a new title. */
    private final JLabel note = new JLabel(" ");
    private final List<Runnable> changed = new ArrayList<Runnable>();

    PlayerPanel() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        head.setOpaque(false);
        area.setOpaque(false);
        area.add(nowPlaying, BorderLayout.CENTER);
        add(area, BorderLayout.CENTER);
        add(options(), BorderLayout.SOUTH);
    }

    /** The keys of the page, for the head of its module (a visualizer adds its own with {@link #addHeadKeys}). */
    JComponent headKeys() {
        return head;
    }

    /** Puts keys into the head of the page, beside its name. */
    void addHeadKeys(JComponent keys) {
        head.add(keys);
        head.revalidate();
    }

    /** The large area right of the tree shows this component (a visualizer) instead of what plays. */
    void setVisualizer(JComponent visualizer) {
        area.removeAll();
        area.add(visualizer == null ? nowPlaying : visualizer, BorderLayout.CENTER);
        area.revalidate();
        area.repaint();
    }

    /** Says what plays: the title and its album; {@code null} says nothing plays. */
    void setNowPlaying(String album, String title) {
        nowPlaying.album = album == null ? "" : album;
        nowPlaying.title = title == null ? "" : title;
        nowPlaying.repaint();
    }

    private JPanel options() {
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setOpaque(false);
        note.setForeground(AudioStudioTheme.ACCENT_SOFT);
        row.add(note, BorderLayout.CENTER);
        JPanel settings = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        settings.setOpaque(false);
        onlyNew.setOpaque(false);
        onlyNew.setToolTipText("Play spielt nur Titel, die noch nicht gehört sind, in beliebiger Reihenfolge;"
                + " gibt es keinen mehr, wartet der Player, bis ein neuer fertig wird");
        heardPercent.setToolTipText("Ab so viel Prozent gehört gilt ein Titel als gehört; ungehörte Titel sind im"
                + " Baum farbig hinterlegt");
        crossfade.setToolTipText("So viele Sekunden überblenden zwei Titel ineinander; 0 = keine Überblendung");
        narrow(heardPercent);
        narrow(crossfade);
        settings.add(onlyNew);
        settings.add(javax.swing.Box.createHorizontalStrut(10));
        settings.add(dim("Gehört ab"));
        settings.add(heardPercent);
        settings.add(dim("%"));
        settings.add(javax.swing.Box.createHorizontalStrut(10));
        settings.add(dim("Überblendung"));
        settings.add(crossfade);
        settings.add(dim("s"));
        row.add(settings, BorderLayout.EAST);
        onlyNew.addActionListener(event -> changed());
        heardPercent.addChangeListener(event -> changed());
        crossfade.addChangeListener(event -> changed());
        return row;
    }

    private static JLabel dim(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(AudioStudioTheme.TEXT_DIM);
        return label;
    }

    private static void narrow(JSpinner spinner) {
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            editor.getTextField().setColumns(4);
        }
    }

    private void changed() {
        for (Runnable each : changed) {
            each.run();
        }
    }

    /** Told whenever the user changes a setting of the player. */
    void onOptions(Runnable listener) {
        changed.add(listener);
    }

    /** Sets the settings as they were saved, without telling anyone. */
    void setOptions(int percent, double seconds, boolean onlyNewTitles) {
        List<Runnable> told = new ArrayList<Runnable>(changed);
        changed.clear();
        try {
            heardPercent.setValue(Math.max(HeardTitles.LEAST_PERCENT, Math.min(HeardTitles.MOST_PERCENT, percent)));
            crossfade.setValue(Math.max(0.0, Math.min(MOST_CROSSFADE_SECONDS, seconds)));
            onlyNew.setSelected(onlyNewTitles);
        } finally {
            changed.addAll(told);
        }
    }

    int heardPercent() {
        return ((Number) heardPercent.getValue()).intValue();
    }

    double crossfadeSeconds() {
        return ((Number) crossfade.getValue()).doubleValue();
    }

    boolean onlyNew() {
        return onlyNew.isSelected();
    }

    /** Says beside the settings what the player does; empty says nothing. */
    void setNote(String text) {
        note.setText(text == null || text.isEmpty() ? " " : text);
    }

    String note() {
        return note.getText().trim();
    }

    /** What plays, large, until a visualizer takes the area. */
    private static final class NowPlaying extends JComponent {
        String album = "";
        String title = "";

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(AudioStudioTheme.WELL);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                String big = title.isEmpty() ? "Ein Titel links im Baum spielt hier" : title;
                Font titleFont = getFont().deriveFont(Font.BOLD, Math.max(18f, Math.min(42f, getHeight() / 9f)));
                g.setFont(titleFont);
                FontMetrics metrics = g.getFontMetrics();
                int y = getHeight() / 2;
                g.setColor(title.isEmpty() ? AudioStudioTheme.TEXT_FAINT : AudioStudioTheme.TEXT);
                g.drawString(big, Math.max(12, (getWidth() - metrics.stringWidth(big)) / 2), y);
                if (!album.isEmpty()) {
                    g.setFont(getFont().deriveFont(Font.PLAIN, titleFont.getSize2D() * 0.5f));
                    FontMetrics small = g.getFontMetrics();
                    g.setColor(AudioStudioTheme.TEXT_DIM);
                    g.drawString(album, Math.max(12, (getWidth() - small.stringWidth(album)) / 2),
                            y + small.getHeight() + 6);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
