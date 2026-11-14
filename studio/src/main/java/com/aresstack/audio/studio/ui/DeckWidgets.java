package com.aresstack.audio.studio.ui;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** The parts a mixing desk is built from: modules, lamps, round transport keys. */
final class DeckWidgets {
    private DeckWidgets() {
    }

    static Graphics2D smooth(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** Lettering as on a desk: small, spaced, dim. */
    static JLabel lettering(String text) {
        JLabel label = new JLabel(text.toUpperCase(java.util.Locale.ROOT));
        label.setFont(AudioStudioTheme.lettering(10.5f));
        label.setForeground(AudioStudioTheme.TEXT_DIM);
        return label;
    }

    /** A rounded module of the desk, with its name lettered in the upper left corner. */
    static class Module extends JPanel {
        private final JPanel head = new JPanel(new BorderLayout(8, 0));
        private final Lamp lamp = new Lamp(AudioStudioTheme.ACCENT);

        Module(String name, JComponent contents) {
            super(new BorderLayout(0, 10));
            setOpaque(false);
            setBorder(AudioStudioTheme.modulePadding());
            head.setOpaque(false);
            JPanel title = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 7, 0));
            title.setOpaque(false);
            lamp.setOn(true);
            title.add(lamp);
            title.add(lettering(name));
            head.add(title, BorderLayout.WEST);
            add(head, BorderLayout.NORTH);
            if (contents != null) {
                add(contents, BorderLayout.CENTER);
            }
        }

        /** Something for the right end of the module's head: a key, a counter. */
        void setHeadEnd(JComponent component) {
            head.add(component, BorderLayout.EAST);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 18f, 18f);
            g.setColor(AudioStudioTheme.MODULE);
            g.fill(shape);
            // A faint light along the upper edge, as on a bevelled panel.
            g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 16), 0, 26, new Color(255, 255, 255, 0)));
            g.fill(shape);
            g.setColor(AudioStudioTheme.STROKE);
            g.draw(shape);
            g.dispose();
        }
    }

    /** A small lamp: dark when off, glowing in its colour when on. */
    static final class Lamp extends JComponent {
        private Color color;
        private boolean on;

        Lamp(Color color) {
            this.color = color;
            setPreferredSize(new Dimension(12, 12));
        }

        void setOn(boolean on) {
            if (this.on != on) {
                this.on = on;
                repaint();
            }
        }

        void setColor(Color color) {
            this.color = color;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            paintLamp(g, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f - 2f, color, on);
            g.dispose();
        }
    }

    static void paintLamp(Graphics2D g, float x, float y, float radius, Color color, boolean on) {
        if (on) {
            g.setPaint(new RadialGradientPaint(x, y, radius * 2.2f, new float[]{0f, 1f},
                    new Color[]{AudioStudioTheme.withAlpha(color, 110), AudioStudioTheme.withAlpha(color, 0)}));
            g.fill(new Ellipse2D.Float(x - radius * 2.2f, y - radius * 2.2f, radius * 4.4f, radius * 4.4f));
        }
        g.setColor(on ? color : new Color(0x30333E));
        g.fill(new Ellipse2D.Float(x - radius, y - radius, radius * 2, radius * 2));
        if (on) {
            g.setColor(new Color(255, 255, 255, 150));
            g.fill(new Ellipse2D.Float(x - radius * 0.45f, y - radius * 0.6f, radius * 0.7f, radius * 0.5f));
        }
    }

    /** What a transport key shows. */
    enum Symbol { PLAY, STOP, RECORD, CANCEL }

    /** A round key of the transport: play, stop, render, cancel. */
    static final class TransportKey extends JButton {
        private final Symbol symbol;
        private final Color color;

        TransportKey(Symbol symbol, Color color, String tip) {
            this.symbol = symbol;
            this.color = color;
            setToolTipText(tip);
            setPreferredSize(new Dimension(54, 54));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            float size = Math.min(getWidth(), getHeight()) - 6f;
            float x = (getWidth() - size) / 2f;
            float y = (getHeight() - size) / 2f;
            boolean enabled = isEnabled();
            boolean pressed = getModel().isArmed() && getModel().isPressed();
            Color signal = enabled ? color : AudioStudioTheme.TEXT_FAINT;
            if (enabled && (getModel().isRollover() || isFocusOwner())) {
                g.setPaint(new RadialGradientPaint(getWidth() / 2f, getHeight() / 2f, size * 0.62f, new float[]{0.6f, 1f},
                        new Color[]{AudioStudioTheme.withAlpha(signal, 70), AudioStudioTheme.withAlpha(signal, 0)}));
                g.fill(new Ellipse2D.Float(0, 0, getWidth(), getHeight()));
            }
            Ellipse2D key = new Ellipse2D.Float(x, y, size, size);
            g.setPaint(new GradientPaint(0, y, pressed ? new Color(0x23252D) : new Color(0x3A3D49), 0, y + size,
                    pressed ? new Color(0x2E313B) : new Color(0x23252D)));
            g.fill(key);
            g.setStroke(new BasicStroke(1.6f));
            g.setColor(enabled ? AudioStudioTheme.withAlpha(signal, 200) : AudioStudioTheme.STROKE);
            g.draw(key);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f + (pressed ? 1f : 0f);
            float unit = size * 0.19f;
            g.setColor(signal);
            switch (symbol) {
                case PLAY: {
                    Path2D triangle = new Path2D.Float();
                    triangle.moveTo(cx - unit * 0.75f, cy - unit);
                    triangle.lineTo(cx + unit * 1.1f, cy);
                    triangle.lineTo(cx - unit * 0.75f, cy + unit);
                    triangle.closePath();
                    g.fill(triangle);
                    break;
                }
                case STOP:
                    g.fill(new RoundRectangle2D.Float(cx - unit * 0.9f, cy - unit * 0.9f, unit * 1.8f, unit * 1.8f, 3f, 3f));
                    break;
                case RECORD:
                    g.fill(new Ellipse2D.Float(cx - unit, cy - unit, unit * 2, unit * 2));
                    break;
                default:
                    g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new java.awt.geom.Line2D.Float(cx - unit * 0.8f, cy - unit * 0.8f, cx + unit * 0.8f, cy + unit * 0.8f));
                    g.draw(new java.awt.geom.Line2D.Float(cx + unit * 0.8f, cy - unit * 0.8f, cx - unit * 0.8f, cy + unit * 0.8f));
                    break;
            }
            g.dispose();
        }
    }

    /**
     * A key that chooses a page of the desk, as the keys under the display of a player: a
     * block with slightly rounded corners that stands out of the panel, and a light along
     * its upper edge that is lit while its page is shown. Pressed, it sits lower.
     */
    static final class PageKey extends JToggleButton {
        PageKey(String text) {
            super(text);
            setFont(AudioStudioTheme.lettering(9.2f));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFocusable(false);
            setPreferredSize(new Dimension(96, 32));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            boolean chosen = isSelected();
            boolean down = chosen || getModel().isPressed();
            float width = getWidth();
            float height = getHeight();
            // The shadow the key throws on the panel, then the key itself, bulging: light above, dark below.
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(new RoundRectangle2D.Float(0.5f, 2f, width - 1f, height - 2.5f, 7f, 7f));
            float top = down ? 2f : 0.5f;
            RoundRectangle2D body = new RoundRectangle2D.Float(0.5f, top, width - 1f, height - 3.5f, 6f, 6f);
            Color light = new Color(chosen ? 0x40323C : getModel().isRollover() ? 0x4A4E5C : 0x3F434F);
            Color dark = new Color(chosen ? 0x2A2029 : 0x23252D);
            g.setPaint(new GradientPaint(0, top, light, 0, top + height - 3.5f, dark));
            g.fill(body);
            g.setColor(new Color(0x15161B));
            g.draw(body);
            if (!down) {
                g.setColor(new Color(255, 255, 255, 46));
                g.draw(new java.awt.geom.Line2D.Float(4f, top + 1.2f, width - 4f, top + 1.2f));
            }
            // The light along the upper edge.
            RoundRectangle2D lamp = new RoundRectangle2D.Float(width / 2f - 13f, top + 4f, 26f, 3f, 3f, 3f);
            if (chosen) {
                g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 70));
                g.fill(new RoundRectangle2D.Float(width / 2f - 17f, top + 2f, 34f, 7f, 7f, 7f));
                g.setColor(AudioStudioTheme.ACCENT_SOFT);
            } else {
                g.setColor(new Color(0x16171C));
            }
            g.fill(lamp);
            g.setFont(getFont());
            g.setColor(chosen ? AudioStudioTheme.TEXT : AudioStudioTheme.TEXT_DIM);
            FontMetrics metrics = g.getFontMetrics();
            String text = getText().toUpperCase(java.util.Locale.ROOT);
            g.drawString(text, (width - metrics.stringWidth(text)) / 2f,
                    top + 7f + (height - 10.5f + metrics.getAscent() - metrics.getDescent()) / 2f);
            g.dispose();
        }
    }

    /** Gives a key the look of a step of the work: a number in a circle before its name. */
    static void asStep(AbstractButton button, int number) {
        button.putClientProperty("deck.step", Integer.valueOf(number));
        button.setIcon(new StepIcon(number));
        button.setIconTextGap(8);
    }

    private static final class StepIcon implements javax.swing.Icon {
        private final int number;

        StepIcon(int number) {
            this.number = number;
        }

        @Override
        public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g = smooth(graphics);
            boolean enabled = component.isEnabled();
            boolean main = component instanceof JButton && ((JButton) component).isDefaultButton();
            g.setColor(main ? new Color(255, 255, 255, 60) : enabled ? AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 60)
                    : new Color(0x2A2D37));
            g.fill(new Ellipse2D.Float(x, y, 18, 18));
            g.setFont(component.getFont().deriveFont(java.awt.Font.BOLD, 11f));
            g.setColor(main ? Color.WHITE : enabled ? AudioStudioTheme.ACCENT_SOFT : AudioStudioTheme.TEXT_FAINT);
            String text = Integer.toString(number);
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(text, x + (18 - metrics.stringWidth(text)) / 2f, y + (18 + metrics.getAscent() - metrics.getDescent()) / 2f);
            g.dispose();
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
    }
}
