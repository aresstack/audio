package com.aresstack.audio.studio.ui;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;

/** What the desk shows: the display, the waveform, the level meters. */
final class DeckDisplays {
    private DeckDisplays() {
    }

    static String clock(double seconds) {
        int whole = (int) Math.max(0, seconds);
        int tenths = (int) (Math.max(0, seconds) * 10) % 10;
        return String.format(java.util.Locale.ROOT, "%02d:%02d.%d", whole / 60, whole % 60, tenths);
    }

    /** The display of the desk: the time, what is loaded, what the studio is doing, and three flags. */
    static final class Lcd extends JComponent {
        private String time = "00:00.0";
        private String length = "00:00.0";
        private String source = "—";
        private boolean busy;
        private boolean playing;
        private boolean loaded;
        private float pulse;

        Lcd() {
            setPreferredSize(new Dimension(520, 56));
            setMinimumSize(new Dimension(300, 56));
        }

        void setTime(double seconds, double total) {
            String now = clock(seconds);
            String all = clock(total);
            if (!now.equals(time) || !all.equals(length)) {
                time = now;
                length = all;
                repaint();
            }
        }

        void setSource(String name, boolean available) {
            source = name == null ? "—" : name;
            loaded = available;
            repaint();
        }

        void setBusy(boolean working) {
            busy = working;
            repaint();
        }

        void setPlaying(boolean running) {
            if (playing != running) {
                playing = running;
                repaint();
            }
        }

        /** Advances the blinking of the busy flag; called by the desk's clock. */
        void tick() {
            if (busy) {
                pulse = (pulse + 0.06f) % 1f;
                repaint(getWidth() - 140, 0, 140, 30);
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = DeckWidgets.smooth(graphics);
            int width = getWidth();
            int height = getHeight();
            RoundRectangle2D glass = new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 14f, 14f);
            g.setPaint(new GradientPaint(0, 0, new Color(0x0A110E), 0, height, AudioStudioTheme.LCD_GLASS));
            g.fill(glass);
            g.setColor(new Color(0x24352C));
            g.draw(glass);
            g.setClip(glass);

            // The time, large; behind it the same figures dark, as unlit segments show through.
            g.setFont(AudioStudioTheme.display(30f));
            FontMetrics big = g.getFontMetrics();
            int baseline = (height + big.getAscent()) / 2 - 4;
            g.setColor(AudioStudioTheme.LCD_DIM);
            g.drawString("88:88.8", 16, baseline);
            g.setColor(AudioStudioTheme.LCD_TEXT);
            g.drawString(time, 16, baseline);
            int afterTime = 16 + big.stringWidth(time) + 10;
            g.setFont(AudioStudioTheme.display(13f));
            FontMetrics middle = g.getFontMetrics();
            int afterLength = afterTime + middle.stringWidth("/ " + length);
            // A narrow display drops, one after the other, the name of what is loaded, the flags and the length.
            boolean roomForFlags = width >= afterTime + 150;
            boolean roomForLength = width >= afterLength + (roomForFlags ? 150 : 16);
            if (roomForLength) {
                g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.LCD_TEXT, 150));
                g.drawString("/ " + length, afterTime, baseline);
            }
            if (roomForFlags) {
                int flagX = width - 16;
                flagX = flag(g, "AI", busy, busy ? 0.55f + 0.45f * (float) Math.abs(Math.sin(pulse * Math.PI * 2)) : 1f,
                        flagX, 21);
                flagX = flag(g, "PLAY", playing, 1f, flagX, 21);
                flag(g, "FILE", loaded, 1f, flagX, 21);
            }
            g.setFont(AudioStudioTheme.display(12f));
            FontMetrics small = g.getFontMetrics();
            // What is loaded stands under the flags, at the right.
            g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.LCD_TEXT, 200));
            String shown = fit(small, source, width - afterLength - 40);
            g.drawString(shown, width - 16 - small.stringWidth(shown), height - 11);
            g.dispose();
        }

        private static int flag(Graphics2D g, String text, boolean lit, float strength, int right, int baseline) {
            g.setFont(AudioStudioTheme.display(11f));
            FontMetrics metrics = g.getFontMetrics();
            int width = metrics.stringWidth(text) + 12;
            int x = right - width;
            RoundRectangle2D box = new RoundRectangle2D.Float(x, baseline - 13, width, 17, 6, 6);
            if (lit) {
                g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.LCD_TEXT, Math.round(230 * strength)));
                g.fill(box);
                g.setColor(AudioStudioTheme.LCD_GLASS);
            } else {
                g.setColor(AudioStudioTheme.LCD_DIM);
                g.draw(box);
            }
            g.drawString(text, x + 6, baseline);
            return x - 6;
        }

        private static String fit(FontMetrics metrics, String text, int width) {
            if (width <= 0 || metrics.stringWidth(text) <= width) {
                return text;
            }
            String shown = text;
            while (shown.length() > 1 && metrics.stringWidth(shown + "…") > width) {
                shown = shown.substring(0, shown.length() - 1);
            }
            return shown + "…";
        }
    }

    /** Asked to jump to a share of the file. */
    interface Seek {
        void to(double share);
    }

    /** The loaded file as a waveform with the place of the playback; a click jumps there. */
    static final class Waveform extends JComponent {
        private DeckPlayer.Envelope envelope;
        private double share;
        private String hint = "";
        private Color color = AudioStudioTheme.ACCENT;

        Waveform(final Seek seek) {
            setPreferredSize(new Dimension(600, 104));
            setMinimumSize(new Dimension(200, 90));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    jump(event);
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    jump(event);
                }

                private void jump(MouseEvent event) {
                    if (envelope != null && getWidth() > 24) {
                        seek.to(Math.max(0, Math.min(1, (event.getX() - 12) / (double) (getWidth() - 24))));
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        void setEnvelope(DeckPlayer.Envelope shown, String emptyHint, Color tint) {
            envelope = shown;
            hint = emptyHint == null ? "" : emptyHint;
            color = tint;
            share = 0;
            repaint();
        }

        void setShare(double played) {
            if (Math.abs(played - share) > 0.0005) {
                share = played;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = DeckWidgets.smooth(graphics);
            int width = getWidth();
            int height = getHeight();
            RoundRectangle2D well = new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 14f, 14f);
            g.setColor(AudioStudioTheme.WELL);
            g.fill(well);
            g.setColor(AudioStudioTheme.STROKE);
            g.draw(well);
            g.setClip(well);
            float middle = height / 2f;
            int left = 12;
            int span = width - 24;
            // A grid: the middle line and a line each quarter.
            g.setColor(new Color(0x23252E));
            g.draw(new Line2D.Float(left, middle, left + span, middle));
            for (int quarter = 1; quarter < 4; quarter++) {
                float x = left + span * quarter / 4f;
                g.draw(new Line2D.Float(x, 8, x, height - 8));
            }
            if (envelope == null || envelope.left.length == 0) {
                g.setFont(AudioStudioTheme.lettering(11f));
                g.setColor(AudioStudioTheme.TEXT_FAINT);
                FontMetrics metrics = g.getFontMetrics();
                String text = hint.toUpperCase(java.util.Locale.ROOT);
                g.drawString(text, (width - metrics.stringWidth(text)) / 2f, middle + 4);
                g.dispose();
                return;
            }
            // Bars three pixels apart: the left side upward, the right side downward.
            int bars = Math.max(1, span / 3);
            float reach = middle - 10f;
            int playedBars = (int) Math.round(share * bars);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int bar = 0; bar < bars; bar++) {
                int from = (int) ((long) bar * envelope.left.length / bars);
                int to = Math.max(from + 1, (int) ((long) (bar + 1) * envelope.left.length / bars));
                float up = 0f;
                float down = 0f;
                for (int i = from; i < to && i < envelope.left.length; i++) {
                    up = Math.max(up, envelope.left[i]);
                    down = Math.max(down, envelope.right[i]);
                }
                float x = left + bar * 3f + 1f;
                g.setColor(bar < playedBars ? color : AudioStudioTheme.withAlpha(color, 95));
                g.draw(new Line2D.Float(x, middle - Math.max(0.6f, up * reach), x, middle + Math.max(0.6f, down * reach)));
            }
            // The place of the playback.
            float at = left + (float) (share * span);
            g.setStroke(new BasicStroke(1.6f));
            g.setColor(Color.WHITE);
            g.draw(new Line2D.Float(at, 6, at, height - 6));
            g.setColor(AudioStudioTheme.withAlpha(Color.WHITE, 40));
            g.fill(new java.awt.geom.Rectangle2D.Float(at - 3, 6, 6, height - 12));
            // The length in seconds at the quarters.
            g.setFont(AudioStudioTheme.display(10f));
            g.setColor(AudioStudioTheme.TEXT_FAINT);
            for (int quarter = 0; quarter <= 4; quarter++) {
                String label = clock(envelope.seconds * quarter / 4);
                FontMetrics metrics = g.getFontMetrics();
                float x = left + span * quarter / 4f;
                float textX = quarter == 0 ? x + 3 : quarter == 4 ? x - metrics.stringWidth(label) - 3 : x + 4;
                g.drawString(label, textX, height - 9);
            }
            g.dispose();
        }
    }

    /** A level meter of lamps in a row, green to amber to red, with a mark that holds the loudest moment. */
    static final class Meter extends JComponent {
        private final boolean upright;
        private float level;
        private float held;
        private int holdTicks;

        Meter(boolean upright) {
            this.upright = upright;
            setPreferredSize(upright ? new Dimension(10, 96) : new Dimension(150, 8));
        }

        /** Sets the level between 0 and 1; called by the desk's clock so that the mark can fall back. */
        void setLevel(float value) {
            float shown = Math.max(value, level * 0.82f); // falls back softly, as a needle does
            if (shown < 0.004f) {
                shown = 0f;
            }
            if (shown >= held) {
                held = shown;
                holdTicks = 22;
            } else if (holdTicks > 0) {
                holdTicks--;
            } else {
                held = Math.max(shown, held - 0.03f);
            }
            if (shown != level || held > 0) {
                level = shown;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = DeckWidgets.smooth(graphics);
            int length = upright ? getHeight() : getWidth();
            int breadth = upright ? getWidth() : getHeight();
            int lamps = Math.max(6, length / 5);
            float step = length / (float) lamps;
            // Loudness is heard in decibels: the meter spans the upper 48 dB.
            float lit = level <= 0 ? 0 : Math.max(0f, 1f + (float) (20 * Math.log10(level)) / 48f);
            float mark = held <= 0 ? -1 : Math.max(0f, 1f + (float) (20 * Math.log10(held)) / 48f);
            for (int lamp = 0; lamp < lamps; lamp++) {
                float share = (lamp + 1) / (float) lamps;
                Color color = share > 0.92f ? AudioStudioTheme.RED : share > 0.76f ? AudioStudioTheme.AMBER
                        : AudioStudioTheme.GREEN;
                boolean on = share <= lit + 0.0001f;
                boolean marked = mark >= 0 && Math.abs(share - Math.max(1f / lamps, Math.round(mark * lamps) / (float) lamps)) < 0.0001f;
                g.setColor(on || marked ? color : new Color(0x343846));
                float offset = lamp * step;
                if (upright) {
                    g.fill(new RoundRectangle2D.Float(0, length - offset - step + 1, breadth, step - 1.6f, 2, 2));
                } else {
                    g.fill(new RoundRectangle2D.Float(offset, 0, step - 1.6f, breadth, 2, 2));
                }
            }
            g.dispose();
        }
    }
}
