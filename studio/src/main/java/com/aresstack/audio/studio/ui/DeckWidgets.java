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
            // Lamp and name stand in the middle of the head's height, so that a key at its right
            // end (see setHeadEnd) sits on the same line as the name.
            javax.swing.Box title = javax.swing.Box.createHorizontalBox();
            lamp.setMaximumSize(lamp.getPreferredSize());
            title.add(javax.swing.Box.createHorizontalStrut(7));
            title.add(lamp);
            title.add(javax.swing.Box.createHorizontalStrut(7));
            title.add(lettering(name));
            title.add(javax.swing.Box.createHorizontalStrut(7));
            head.add(title, BorderLayout.WEST);
            add(head, BorderLayout.NORTH);
            if (contents != null) {
                add(contents, BorderLayout.CENTER);
            }
        }

        /**
         * The lamp in front of the name says where the pointer is: it glows while the mouse is
         * over this module and is dark otherwise. The module hears the mouse of the whole
         * window for that, since its own contents would swallow the movement.
         */
        private final java.awt.event.AWTEventListener pointer = event -> {
            if (event instanceof java.awt.event.MouseEvent) {
                java.awt.event.MouseEvent mouse = (java.awt.event.MouseEvent) event;
                pointerAt(mouse.getComponent(), mouse.getPoint());
            }
        };

        @Override
        public void addNotify() {
            super.addNotify();
            try {
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(pointer,
                        java.awt.AWTEvent.MOUSE_EVENT_MASK | java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK);
            } catch (SecurityException | java.awt.HeadlessException unheard) {
                lamp.setOn(true); // without the mouse of the window the lamp stays on, as it was
            }
        }

        @Override
        public void removeNotify() {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(pointer);
            lamp.setOn(false);
            super.removeNotify();
        }

        /** The pointer is at this point of a component: the lamp glows if that is over this module. */
        void pointerAt(java.awt.Component source, java.awt.Point point) {
            boolean over = false;
            if (source != null && point != null && isShowing()
                    && javax.swing.SwingUtilities.getWindowAncestor(this) == window(source)) {
                over = contains(javax.swing.SwingUtilities.convertPoint(source, point, this));
            }
            lamp.setOn(over);
        }

        private static java.awt.Window window(java.awt.Component component) {
            return component instanceof java.awt.Window ? (java.awt.Window) component
                    : javax.swing.SwingUtilities.getWindowAncestor(component);
        }

        /** Whether the lamp glows, for tests. */
        boolean lit() {
            return lamp.on;
        }

        /** Something for the head right after the module's name, from the left: its keys. */
        void setHeadStart(JComponent component) {
            head.add(component, BorderLayout.CENTER);
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
        // Off, the lamp is still there: its glass in a dark shade of its colour, with a rim and a
        // faint gleam, so that it reads as a lamp that is switched off and not as a hole.
        Color glass = on ? color : new Color((color.getRed() * 3 + 0x30 * 7) / 10,
                (color.getGreen() * 3 + 0x33 * 7) / 10, (color.getBlue() * 3 + 0x3E * 7) / 10);
        g.setColor(glass);
        g.fill(new Ellipse2D.Float(x - radius, y - radius, radius * 2, radius * 2));
        if (!on) {
            g.setColor(new Color(0x15161B));
            g.setStroke(new java.awt.BasicStroke(1f));
            g.draw(new Ellipse2D.Float(x - radius, y - radius, radius * 2, radius * 2));
        }
        g.setColor(new Color(255, 255, 255, on ? 150 : 45));
        g.fill(new Ellipse2D.Float(x - radius * 0.45f, y - radius * 0.6f, radius * 0.7f, radius * 0.5f));
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
     * A circle that turns while something is waited for (a list the AI is writing), in the pink of
     * the desk. It lies over what it waits for, lets the mouse through and is seen only while it
     * turns.
     */
    static final class WaitCircle extends javax.swing.JComponent {
        private int angle;
        private final javax.swing.Timer turning = new javax.swing.Timer(40, event -> {
            angle = (angle + 12) % 360;
            repaint();
        });

        WaitCircle() {
            setOpaque(false);
            setVisible(false);
        }

        /** Starts or stops the circle; stopped, it is not there. */
        void turn(boolean on) {
            setVisible(on);
            if (on) {
                turning.start();
            } else {
                turning.stop();
            }
        }

        boolean turning() {
            return turning.isRunning();
        }

        @Override
        public boolean contains(int x, int y) {
            return false; // what lies under it stays in reach
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
            double r = 17;
            double cx = getWidth() / 2.0;
            double cy = getHeight() / 2.0;
            g.setStroke(new java.awt.BasicStroke(4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            Color pink = AudioStudioTheme.ACCENT;
            g.setColor(new Color(pink.getRed(), pink.getGreen(), pink.getBlue(), 50));
            g.draw(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            g.setColor(pink);
            g.draw(new java.awt.geom.Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, -angle, 100, java.awt.geom.Arc2D.OPEN));
            g.dispose();
        }
    }

    /**
     * A small sign beside a heading that fetches something anew: two arrows that turn in a
     * circle. No frame and no words; it lights up under the pointer.
     */
    static final class RefreshKey extends javax.swing.JButton {
        RefreshKey(String tip) {
            setToolTipText(tip);
            getAccessibleContext().setAccessibleName(tip);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFocusable(false);
            setPreferredSize(new Dimension(18, 18));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
            double cx = getWidth() / 2.0;
            double cy = getHeight() / 2.0 + (getModel().isPressed() ? 1 : 0);
            double r = Math.min(getWidth(), getHeight()) / 2.0 - 3.5;
            g.setColor(getModel().isRollover() ? AudioStudioTheme.TEXT : AudioStudioTheme.TEXT_DIM);
            g.setStroke(new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER));
            for (int half = 0; half < 2; half++) {
                // An arc of 130 degrees, clockwise, with a head at its end; the second is the first turned half round.
                double from = Math.toRadians(half * 180 + 200);
                double to = from + Math.toRadians(130);
                g.draw(new java.awt.geom.Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r,
                        -Math.toDegrees(from), -130, java.awt.geom.Arc2D.OPEN));
                double x = cx + r * Math.cos(to);
                double y = cy + r * Math.sin(to);
                double along = to + Math.PI / 2; // the way the arc runs at its end
                java.awt.geom.Path2D head = new java.awt.geom.Path2D.Double();
                head.moveTo(x + 3.2 * Math.cos(along), y + 3.2 * Math.sin(along));
                head.lineTo(x + 2.6 * Math.cos(to), y + 2.6 * Math.sin(to));
                head.lineTo(x - 2.6 * Math.cos(to), y - 2.6 * Math.sin(to));
                head.closePath();
                g.fill(head);
            }
            g.dispose();
        }
    }

    /**
     * A small round key in the upper corner of the desk for a page that is needed now and then
     * (the settings behind a gear, the sound banks behind a stack of discs). Not a page key: the
     * keys under the display belong to the work on the piece. Each has its own colour while its
     * page is shown.
     */
    static final class CornerKey extends JToggleButton {
        /** What the key shows. */
        enum Sign { GEAR, BANK }

        private final Sign sign;
        private final Color lit;

        CornerKey(Sign sign, String tip, Color lit) {
            this.sign = sign;
            this.lit = lit;
            setToolTipText(tip);
            getAccessibleContext().setAccessibleName(tip);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFocusable(false);
            setPreferredSize(new Dimension(22, 22));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            boolean chosen = isSelected();
            float size = Math.min(getWidth(), getHeight()) - 2f;
            float x = (getWidth() - size) / 2f;
            float y = (getHeight() - size) / 2f;
            java.awt.geom.Ellipse2D body = new java.awt.geom.Ellipse2D.Float(x, y, size, size);
            Color light = new Color(getModel().isRollover() ? 0x4A4E5C : 0x3F434F);
            Color dark = new Color(0x23252D);
            g.setPaint(new GradientPaint(0, y, light, 0, y + size, dark));
            g.fill(body);
            g.setColor(chosen ? lit : new Color(0x15161B));
            g.draw(body);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            // The sign always has the colour of its key; the ring around the key says that its page is shown.
            g.setColor(chosen || getModel().isRollover() ? lit
                    : new Color(lit.getRed(), lit.getGreen(), lit.getBlue(), 190));
            if (sign == Sign.GEAR) {
                // Eight teeth around a ring.
                float ring = size * 0.24f;
                g.setStroke(new java.awt.BasicStroke(size * 0.12f, java.awt.BasicStroke.CAP_BUTT,
                        java.awt.BasicStroke.JOIN_MITER));
                for (int tooth = 0; tooth < 8; tooth++) {
                    double angle = tooth * Math.PI / 4;
                    g.draw(new java.awt.geom.Line2D.Double(cx + Math.cos(angle) * ring, cy + Math.sin(angle) * ring,
                            cx + Math.cos(angle) * ring * 1.55, cy + Math.sin(angle) * ring * 1.55));
                }
                g.setStroke(new java.awt.BasicStroke(size * 0.11f));
                g.draw(new java.awt.geom.Ellipse2D.Float(cx - ring, cy - ring, 2 * ring, 2 * ring));
            } else {
                // A bank: three discs one on the other, seen from the side and a little from above.
                float half = size * 0.27f;
                float flat = size * 0.09f;
                float step = size * 0.17f;
                g.setStroke(new java.awt.BasicStroke(size * 0.075f));
                float top = cy - step - flat;
                g.draw(new java.awt.geom.Ellipse2D.Float(cx - half, top, 2 * half, 2 * flat));
                for (int disc = 1; disc <= 3; disc++) {
                    g.draw(new java.awt.geom.Arc2D.Float(cx - half, top + disc * step * 0.8f, 2 * half, 2 * flat,
                            180, 180, java.awt.geom.Arc2D.OPEN));
                }
                float bottom = top + 3 * step * 0.8f + flat;
                g.draw(new java.awt.geom.Line2D.Float(cx - half, top + flat, cx - half, bottom));
                g.draw(new java.awt.geom.Line2D.Float(cx + half, top + flat, cx + half, bottom));
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

    /**
     * A key in the head of a module, beside its name: flat and narrow, so that it fits the line
     * of the name, and built like the keys under the display of a player, standing out of the
     * panel and sitting lower while pressed. It has no lamp: it does something, it shows no state.
     */
    static final class HeadKey extends javax.swing.JButton {
        HeadKey(String text) {
            super(text);
            setFont(AudioStudioTheme.lettering(9.2f));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(getFont());
            return new Dimension(metrics.stringWidth(getText().toUpperCase(java.util.Locale.ROOT)) + 22, 24);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            boolean down = isEnabled() && getModel().isPressed();
            float width = getWidth();
            float height = getHeight();
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(new RoundRectangle2D.Float(0.5f, 2f, width - 1f, height - 2.5f, 7f, 7f));
            float top = down ? 2f : 0.5f;
            RoundRectangle2D body = new RoundRectangle2D.Float(0.5f, top, width - 1f, height - 3.5f, 6f, 6f);
            Color light = new Color(isEnabled() && getModel().isRollover() ? 0x4A4E5C : 0x3F434F);
            g.setPaint(new GradientPaint(0, top, light, 0, top + height - 3.5f, new Color(0x23252D)));
            g.fill(body);
            g.setColor(new Color(0x15161B));
            g.draw(body);
            if (!down) {
                g.setColor(new Color(255, 255, 255, 46));
                g.draw(new java.awt.geom.Line2D.Float(4f, top + 1.2f, width - 4f, top + 1.2f));
            }
            g.setFont(getFont());
            g.setColor(!isEnabled() ? AudioStudioTheme.withAlpha(AudioStudioTheme.TEXT_DIM, 110)
                    : getModel().isRollover() ? AudioStudioTheme.TEXT : AudioStudioTheme.TEXT_DIM);
            FontMetrics metrics = g.getFontMetrics();
            String text = getText().toUpperCase(java.util.Locale.ROOT);
            g.drawString(text, (width - metrics.stringWidth(text)) / 2f,
                    top + (height - 3.5f + metrics.getAscent() - metrics.getDescent()) / 2f);
            g.dispose();
        }
    }

    /**
     * A square key that shows a sign instead of words, drawn here so that it looks the same with
     * every font: the sparkle of the AI help, the arrows that open a field to a large box. What it
     * does is said by its tooltip.
     */
    static final class IconKey extends javax.swing.JButton {
        /** The signs a key can show. */
        enum Sign {
            SPARKLE, EXPAND, COLLAPSE, SEND
        }

        private Sign sign;

        IconKey(Sign sign, String tooltip) {
            this.sign = sign;
            setToolTipText(tooltip);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
        }

        void setSign(Sign shown) {
            sign = shown;
            repaint();
        }

        Sign sign() {
            return sign;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(28, 26);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            boolean down = isEnabled() && getModel().isPressed();
            float width = getWidth();
            float height = getHeight();
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(new RoundRectangle2D.Float(0.5f, 2f, width - 1f, height - 2.5f, 7f, 7f));
            float top = down ? 2f : 0.5f;
            RoundRectangle2D body = new RoundRectangle2D.Float(0.5f, top, width - 1f, height - 3.5f, 6f, 6f);
            Color light = new Color(isEnabled() && getModel().isRollover() ? 0x4A4E5C : 0x3F434F);
            g.setPaint(new GradientPaint(0, top, light, 0, top + height - 3.5f, new Color(0x23252D)));
            g.fill(body);
            g.setColor(new Color(0x15161B));
            g.draw(body);
            if (!down) {
                g.setColor(new Color(255, 255, 255, 46));
                g.draw(new java.awt.geom.Line2D.Float(4f, top + 1.2f, width - 4f, top + 1.2f));
            }
            float cx = width / 2f;
            float cy = top + (height - 3.5f) / 2f;
            boolean lit = isEnabled() && getModel().isRollover();
            Color ink = !isEnabled() ? AudioStudioTheme.withAlpha(AudioStudioTheme.TEXT_DIM, 110)
                    : lit ? AudioStudioTheme.TEXT : AudioStudioTheme.TEXT_DIM;
            switch (sign) {
                case SPARKLE:
                    g.setColor(!isEnabled() ? ink : lit ? AudioStudioTheme.ACCENT_SOFT : AudioStudioTheme.ACCENT);
                    g.fill(star(cx - 1.5f, cy + 1f, 6f));
                    g.fill(star(cx + 5f, cy - 4.5f, 2.8f));
                    break;
                case SEND:
                    g.setColor(!isEnabled() ? ink : lit ? AudioStudioTheme.ACCENT_SOFT : AudioStudioTheme.ACCENT);
                    java.awt.geom.Path2D.Float plane = new java.awt.geom.Path2D.Float();
                    plane.moveTo(cx - 6.5f, cy - 5.5f);
                    plane.lineTo(cx + 7f, cy);
                    plane.lineTo(cx - 6.5f, cy + 5.5f);
                    plane.lineTo(cx - 4f, cy);
                    plane.closePath();
                    g.fill(plane);
                    break;
                default:
                    g.setColor(ink);
                    g.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND,
                            java.awt.BasicStroke.JOIN_ROUND));
                    boolean out = sign == Sign.EXPAND;
                    // Two arrows on the diagonal: apart to open the field, towards each other to fold it.
                    for (int side = -1; side <= 1; side += 2) {
                        float nearX = cx + side * 1.6f;
                        float nearY = cy - side * 1.6f;
                        float farX = cx + side * 5.6f;
                        float farY = cy - side * 5.6f;
                        g.draw(new java.awt.geom.Line2D.Float(nearX, nearY, farX, farY));
                        float tipX = out ? farX : nearX;
                        float tipY = out ? farY : nearY;
                        float back = out ? -side * 3.4f : side * 3.4f;
                        g.draw(new java.awt.geom.Line2D.Float(tipX, tipY, tipX + back, tipY));
                        g.draw(new java.awt.geom.Line2D.Float(tipX, tipY, tipX, tipY - back));
                    }
                    break;
            }
            g.dispose();
        }

        /** A star with four points, as a sparkle has them. */
        private static java.awt.Shape star(float x, float y, float r) {
            java.awt.geom.Path2D.Float star = new java.awt.geom.Path2D.Float();
            float in = r * 0.16f;
            star.moveTo(x, y - r);
            star.quadTo(x + in, y - in, x + r, y);
            star.quadTo(x + in, y + in, x, y + r);
            star.quadTo(x - in, y + in, x - r, y);
            star.quadTo(x - in, y - in, x, y - r);
            star.closePath();
            return star;
        }
    }

    /**
     * One half of a switch between two views, in the head of a module: two such keys stand
     * joined, and the one whose view is shown has the colour of the desk.
     */
    static final class SegmentKey extends JToggleButton {
        private final boolean first;

        SegmentKey(String text, boolean first) {
            super(text);
            this.first = first;
            setFont(AudioStudioTheme.lettering(9.2f));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(getFont());
            return new Dimension(metrics.stringWidth(getText().toUpperCase(java.util.Locale.ROOT)) + 26, 24);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = smooth(graphics);
            boolean chosen = isSelected();
            float width = getWidth();
            float height = getHeight();
            // The two halves are one body: each draws it wider than itself and shows only its own half.
            float left = first ? 0.5f : -12f;
            float span = width + 11.5f;
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(new RoundRectangle2D.Float(left, 2f, span, height - 2.5f, 7f, 7f));
            RoundRectangle2D body = new RoundRectangle2D.Float(left, 0.5f, span, height - 3.5f, 6f, 6f);
            if (chosen) {
                g.setPaint(new GradientPaint(0, 0, AudioStudioTheme.ACCENT, 0, height,
                        AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 170)));
            } else {
                g.setPaint(new GradientPaint(0, 0, new Color(getModel().isRollover() ? 0x4A4E5C : 0x3F434F),
                        0, height - 3.5f, new Color(0x23252D)));
            }
            g.fill(body);
            g.setColor(new Color(0x15161B));
            g.draw(body);
            if (!first) {
                g.draw(new java.awt.geom.Line2D.Float(0f, 0.5f, 0f, height - 3f));
            }
            g.setFont(getFont());
            g.setColor(chosen ? Color.WHITE : getModel().isRollover() ? AudioStudioTheme.TEXT : AudioStudioTheme.TEXT_DIM);
            FontMetrics metrics = g.getFontMetrics();
            String text = getText().toUpperCase(java.util.Locale.ROOT);
            g.drawString(text, (width - metrics.stringWidth(text)) / 2f,
                    0.5f + (height - 3.5f + metrics.getAscent() - metrics.getDescent()) / 2f);
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
