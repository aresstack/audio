package com.aresstack.audio.studio.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.ToolTipManager;

/**
 * The form of the piece above the lanes: a field per section with its name and function, its
 * intensity as a level, and the chords bar by bar where they fit. It lies over the same bars as
 * the lanes below it and is not there for a piece without sections.
 */
final class SectionBar extends JComponent {
    static final int HEIGHT = 38;

    private final ArrangementView lanes;
    private List<SectionMark> sections = new ArrayList<SectionMark>();

    SectionBar(ArrangementView lanes) {
        this.lanes = lanes;
        setVisible(false);
        ToolTipManager.sharedInstance().registerComponent(this);
    }

    void setSections(List<SectionMark> shown) {
        sections = new ArrayList<SectionMark>(shown == null ? List.<SectionMark>of() : shown);
        setVisible(!sections.isEmpty());
        revalidate();
        repaint();
    }

    List<SectionMark> sections() {
        return new ArrayList<SectionMark>(sections);
    }

    /** The section under a place across the bar, or {@code null}. */
    SectionMark at(int x) {
        for (SectionMark section : sections) {
            if (x >= lanes.xOfBar(section.firstBar()) && x < lanes.xOfBar(section.firstBar() + section.bars())) {
                return section;
            }
        }
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        SectionMark section = at(event.getX());
        return section == null ? null : section.told();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(lanes.getPreferredSize().width, sections.isEmpty() ? 0 : HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = DeckWidgets.smooth(graphics);
        g.setColor(AudioStudioTheme.WELL);
        g.fillRect(0, 0, getWidth(), getHeight());
        for (int index = 0; index < sections.size(); index++) {
            SectionMark section = sections.get(index);
            float x = lanes.xOfBar(section.firstBar());
            float end = lanes.xOfBar(section.firstBar() + section.bars());
            float width = Math.max(2f, end - x - 1f);
            Color color = AudioStudioTheme.channel(index);
            g.setColor(AudioStudioTheme.withAlpha(color, 30));
            g.fill(new Rectangle2D.Float(x, 1, width, HEIGHT - 2));
            if (section.intensity() != null) {
                // The intensity as a level from the bottom: a strong section stands high.
                float level = (HEIGHT - 2) * (float) Math.max(0, Math.min(1, section.intensity()));
                g.setColor(AudioStudioTheme.withAlpha(color, 70));
                g.fill(new Rectangle2D.Float(x, HEIGHT - 1 - level, width, level));
            }
            g.setColor(color);
            g.fill(new Rectangle2D.Float(x, 1, 2, HEIGHT - 2));
            Shape clip = g.getClip();
            g.clip(new Rectangle2D.Float(x, 0, width, HEIGHT));
            g.setFont(AudioStudioTheme.lettering(11f));
            FontMetrics metrics = g.getFontMetrics();
            g.setColor(new Color(0xF2F0F6));
            String title = section.title() + (section.intensity() == null ? ""
                    : "  " + Math.round(section.intensity() * 100) + " %");
            g.drawString(title, x + 7, 3 + metrics.getAscent());
            g.setFont(AudioStudioTheme.lettering(10f));
            metrics = g.getFontMetrics();
            g.setColor(new Color(0xB9B6C6));
            float barWidth = (end - x) / section.bars();
            String before = null;
            for (int bar = 0; bar < section.chords().size() && bar < section.bars(); bar++) {
                String chord = section.chords().get(bar);
                if (chord == null || chord.isBlank() || chord.equals(before)) {
                    continue; // a chord that goes on is written once
                }
                before = chord;
                if (barWidth >= 14 || bar == 0) {
                    g.drawString(chord, x + 7 + bar * barWidth, HEIGHT - 6);
                }
            }
            g.setClip(clip);
        }
        g.setColor(new Color(255, 255, 255, 20));
        g.fillRect(0, HEIGHT - 1, getWidth(), 1);
    }
}
