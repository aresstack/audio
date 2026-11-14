package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.score.Score;

import javax.swing.JComponent;
import javax.swing.Scrollable;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The piece as a conductor's score: all tracks one below the other on a dark sheet, each
 * on its staff with its clef, the bars side by side, the notes with their heads,
 * stems, flags and beams, rests, dots, sharps, ledger lines and ties. Several
 * systems follow one another down the sheet. While the piece plays, a line shows
 * where it is and the sheet turns along.
 */
final class ScoreView extends JComponent implements Scrollable {
    // Dark as the rest of the desk: light notes on a dark sheet.
    private static final Color PAPER = AudioStudioTheme.WELL;
    private static final Color INK = new Color(0xF2F0F6);
    private static final Color FAINT = AudioStudioTheme.TEXT_FAINT;
    /** The five lines and the bar lines: lighter than the sheet, quieter than the notes. */
    private static final Color LINES = new Color(0x8D8B9A);
    /** The distance between two lines of a staff, in pixels; everything is measured in it. */
    private static final float SPACE = 9f;
    private static final int NAMES = 132;
    private static final int LEAD = 74;
    private static final int STAFF_STEP = 86;
    private static final int SYSTEM_GAP = 46;
    private static final int TOP = 54;
    private static final Map<String, Path2D> GLYPHS = new HashMap<String, Path2D>();

    static {
        // The outlines of the music symbols; see score-glyphs.txt for where they come from.
        try (InputStream input = ScoreView.class.getResourceAsStream("/score-glyphs.txt")) {
            if (input != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    String[] fields = line.split("\\t");
                    if (line.startsWith("#") || fields.length != 3) {
                        continue;
                    }
                    Path2D path = new Path2D.Float(Path2D.WIND_NON_ZERO);
                    String[] words = fields[2].trim().split(" ");
                    for (int i = 0; i < words.length; ) {
                        String command = words[i++];
                        if ("M".equals(command)) {
                            path.moveTo(number(words[i++]), -number(words[i++]));
                        } else if ("L".equals(command)) {
                            path.lineTo(number(words[i++]), -number(words[i++]));
                        } else if ("C".equals(command)) {
                            path.curveTo(number(words[i++]), -number(words[i++]), number(words[i++]),
                                    -number(words[i++]), number(words[i++]), -number(words[i++]));
                        } else {
                            path.closePath();
                        }
                    }
                    GLYPHS.put(fields[0], path);
                }
            }
        } catch (Exception unreadable) {
            GLYPHS.clear(); // the score then shows heads and stems only
        }
    }

    private static float number(String word) {
        return Float.parseFloat(word);
    }

    /** One written event with the place it got on the sheet. */
    private static final class Placed {
        final Score.Event event;
        final float x;
        final float staffTop;
        final boolean stemUp;
        float stemEnd;
        boolean beamed;

        Placed(Score.Event event, float x, float staffTop, boolean stemUp) {
            this.event = event;
            this.x = x;
            this.staffTop = staffTop;
            this.stemUp = stemUp;
        }
    }

    private Score score;
    private List<String> order = new ArrayList<String>();
    private String hint = "";
    private double second = -1;
    private int shownSystem = -1;

    ScoreView() {
        setOpaque(true);
        setBackground(PAPER);
    }

    /** Shows a score; the order of the tracks gives each staff the colour of its channel. */
    void setScore(Score shown, List<String> trackIds, String emptyHint) {
        score = shown;
        order = new ArrayList<String>(trackIds);
        hint = emptyHint == null ? "" : emptyHint;
        revalidate();
        repaint();
    }

    /** Where the playback is, in seconds; negative when nothing plays. */
    void setSecond(double played) {
        if (Math.abs(played - second) < 0.01) {
            return;
        }
        second = played;
        if (score != null && played >= 0 && isShowing()) {
            int system = Math.min(score.bars() - 1, (int) (played / score.secondsPerBar())) / barsPerSystem();
            if (system != shownSystem) {
                shownSystem = system;
                scrollRectToVisible(new Rectangle(0, TOP - 30 + system * systemHeight(), 10,
                        Math.min(systemHeight(), getVisibleRect().height)));
            }
        }
        repaint();
    }

    private List<Score.Staff> staves() {
        List<Score.Staff> shown = new ArrayList<Score.Staff>();
        if (score != null) {
            for (String id : order) {
                for (Score.Staff staff : score.staves()) {
                    if (staff.trackId().equals(id)) {
                        shown.add(staff);
                    }
                }
            }
        }
        return shown;
    }

    private int barsPerSystem() {
        int fitting = Math.max(1, (getWidth() - NAMES - LEAD - 24) / 250);
        return fitting >= 8 ? 8 : fitting >= 4 ? 4 : fitting >= 2 ? 2 : 1;
    }

    private int systemHeight() {
        return Math.max(1, staves().size()) * STAFF_STEP + SYSTEM_GAP;
    }

    @Override
    public Dimension getPreferredSize() {
        if (score == null || staves().isEmpty()) {
            return new Dimension(600, 200);
        }
        int systems = (score.bars() + barsPerSystem() - 1) / barsPerSystem();
        return new Dimension(600, TOP + systems * systemHeight());
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(600, 300);
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 40;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(40, visible.height - 60);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = DeckWidgets.smooth(graphics);
        g.setColor(PAPER);
        g.fillRect(0, 0, getWidth(), getHeight());
        List<Score.Staff> staves = staves();
        if (score == null || staves.isEmpty()) {
            g.setColor(FAINT);
            g.setFont(getFont().deriveFont(java.awt.Font.ITALIC, 15f));
            String text = hint.isEmpty() ? "Noch keine Noten" : hint;
            g.drawString(text, (getWidth() - g.getFontMetrics().stringWidth(text)) / 2f, getHeight() / 2f);
            g.dispose();
            return;
        }
        int perSystem = barsPerSystem();
        int systems = (score.bars() + perSystem - 1) / perSystem;
        float left = NAMES;
        float right = getWidth() - 24f;
        float barWidth = (right - left - LEAD) / perSystem;
        Rectangle visible = g.getClipBounds();

        g.setColor(INK);
        g.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.ITALIC, 14));
        g.drawString("♩ = " + score.tempo(), left, 30);

        for (int system = 0; system < systems; system++) {
            float systemTop = TOP + system * systemHeight();
            if (visible != null && (systemTop > visible.y + visible.height + 60
                    || systemTop + systemHeight() < visible.y - 60)) {
                continue;
            }
            int firstBar = system * perSystem;
            int lastBar = Math.min(score.bars(), firstBar + perSystem);
            float systemRight = left + LEAD + (lastBar - firstBar) * barWidth;
            float lowest = systemTop + (staves.size() - 1) * STAFF_STEP + 4 * SPACE;

            // The line that joins the staves at the left, and the number of the first bar.
            g.setColor(LINES);
            g.setStroke(new BasicStroke(1.6f));
            g.draw(new Line2D.Float(left, systemTop, left, lowest));
            g.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.ITALIC, 12));
            g.setColor(FAINT);
            g.drawString(Integer.toString(firstBar + 1), left + 2, systemTop - 12);

            for (int row = 0; row < staves.size(); row++) {
                Score.Staff staff = staves.get(row);
                float top = systemTop + row * STAFF_STEP;
                // Name and the colour of the channel.
                int channel = Math.max(0, order.indexOf(staff.trackId()));
                g.setColor(AudioStudioTheme.channel(channel));
                g.fill(new Ellipse2D.Float(14, top + 2 * SPACE - 4, 8, 8));
                g.setColor(INK);
                g.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.PLAIN, 14));
                FontMetrics metrics = g.getFontMetrics();
                String name = staff.trackId().replace('_', ' ');
                while (name.length() > 2 && metrics.stringWidth(name) > NAMES - 40) {
                    name = name.substring(0, name.length() - 2) + "…";
                }
                g.drawString(name, 28, top + 2 * SPACE + 5);

                // The five lines, the clef, the time on the first system, the bar lines.
                g.setColor(LINES);
                g.setStroke(new BasicStroke(1f));
                for (int line = 0; line < 5; line++) {
                    g.draw(new Line2D.Float(left, top + line * SPACE, systemRight, top + line * SPACE));
                }
                g.setColor(INK);
                if (staff.clef() == Score.Clef.TREBLE) {
                    glyph(g, "gClef", left + 8, top + 3 * SPACE);
                } else if (staff.clef() == Score.Clef.BASS) {
                    glyph(g, "fClef", left + 8, top + SPACE);
                } else {
                    glyph(g, "percussionClef", left + 12, top + 2 * SPACE);
                }
                if (system == 0) {
                    glyph(g, "time4", left + 42, top + SPACE);
                    glyph(g, "time4", left + 42, top + 3 * SPACE);
                }
                for (int bar = firstBar; bar < lastBar; bar++) {
                    float barRight = left + LEAD + (bar - firstBar + 1) * barWidth;
                    boolean last = bar == score.bars() - 1;
                    g.setColor(LINES);
                    g.setStroke(new BasicStroke(1.2f));
                    g.draw(new Line2D.Float(barRight - (last ? 6 : 0), top, barRight - (last ? 6 : 0), top + 4 * SPACE));
                    if (last) {
                        g.fill(new Rectangle2D.Float(barRight - 3, top, 3.6f, 4 * SPACE));
                    }
                }

                // The notes of this staff in this system.
                List<Placed> placed = new ArrayList<Placed>();
                for (int bar = firstBar; bar < lastBar; bar++) {
                    float barLeft = left + LEAD + (bar - firstBar) * barWidth;
                    float inner = barWidth - 30;
                    List<Score.Event> events = staff.bar(bar);
                    Map<Integer, Boolean> sharpened = new HashMap<Integer, Boolean>();
                    List<Placed> inBar = new ArrayList<Placed>();
                    for (Score.Event event : events) {
                        float x = events.size() == 1 && event.rest() ? barLeft + barWidth / 2 - 6
                                : barLeft + 18 + inner * event.start() / Score.BAR;
                        Placed place = new Placed(event, x, top, stemUp(event, staff.clef()));
                        inBar.add(place);
                        if (!event.rest()) {
                            accidentals(g, place, staff.clef(), sharpened);
                        }
                    }
                    beam(g, inBar, staff.clef());
                    placed.addAll(inBar);
                }
                for (int index = 0; index < placed.size(); index++) {
                    Placed place = placed.get(index);
                    if (place.event.rest()) {
                        rest(g, place);
                    } else {
                        note(g, place, staff.clef());
                        if (place.event.tied()) {
                            float to = index + 1 < placed.size() ? placed.get(index + 1).x : systemRight - 4;
                            tie(g, place, to, staff.clef());
                        }
                    }
                }
            }

            // Where the playback is.
            if (second >= 0) {
                double bar = second / score.secondsPerBar();
                if (bar >= firstBar && bar < lastBar) {
                    int whole = (int) bar;
                    float x = (float) (left + LEAD + (whole - firstBar) * barWidth + 18 + (barWidth - 30) * (bar - whole));
                    g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 60));
                    g.fill(new Rectangle2D.Float(x - 4, systemTop - 10, 8, lowest - systemTop + 20));
                    g.setColor(AudioStudioTheme.ACCENT);
                    g.setStroke(new BasicStroke(1.6f));
                    g.draw(new Line2D.Float(x, systemTop - 10, x, lowest + 10));
                }
            }
        }
        g.dispose();
    }

    /** The height on the sheet of a place on the staff: place 0 is the lowest line. */
    private static float y(float staffTop, int position) {
        return staffTop + 4 * SPACE - position * SPACE / 2;
    }

    private static boolean stemUp(Score.Event event, Score.Clef clef) {
        if (event.rest()) {
            return true;
        }
        if (clef == Score.Clef.PERCUSSION) {
            return true;
        }
        int[] pitches = event.pitches();
        int low = Score.position(pitches[0], clef);
        int high = Score.position(pitches[pitches.length - 1], clef);
        return (low + high) / 2f < 4; // below the middle line the stem goes up
    }

    private static void glyph(Graphics2D g, String name, float x, float y) {
        Path2D outline = GLYPHS.get(name);
        if (outline == null) {
            return;
        }
        AffineTransform place = AffineTransform.getTranslateInstance(x, y);
        place.scale(SPACE / 250.0, SPACE / 250.0);
        g.fill(place.createTransformedShape(outline));
    }

    /** Sharps before the notes that need one, and a natural where a sharp earlier in the bar no longer holds. */
    private static void accidentals(Graphics2D g, Placed place, Score.Clef clef, Map<Integer, Boolean> sharpened) {
        if (clef == Score.Clef.PERCUSSION) {
            return;
        }
        for (int pitch : place.event.pitches()) {
            int position = Score.position(pitch, clef);
            boolean sharp = Score.sharp(pitch);
            boolean before = Boolean.TRUE.equals(sharpened.get(position));
            if (sharp != before) {
                g.setColor(INK);
                glyph(g, sharp ? "sharp" : "natural", place.x - (sharp ? 11.5f : 9f), y(place.staffTop, position));
                sharpened.put(position, sharp);
            }
        }
    }

    /** Joins eighths and sixteenths of one beat with a beam; the stems of a group all go the same way. */
    private static void beam(Graphics2D g, List<Placed> bar, Score.Clef clef) {
        int index = 0;
        while (index < bar.size()) {
            Placed first = bar.get(index);
            int beat = first.event.start() / 4;
            List<Placed> group = new ArrayList<Placed>();
            int next = index;
            while (next < bar.size() && !bar.get(next).event.rest() && bar.get(next).event.length() <= 3
                    && bar.get(next).event.start() / 4 == beat) {
                group.add(bar.get(next));
                next++;
            }
            if (group.size() < 2) {
                index = Math.max(index + 1, next);
                continue;
            }
            float sum = 0;
            int highest = Integer.MIN_VALUE;
            int lowest = Integer.MAX_VALUE;
            for (Placed place : group) {
                int[] pitches = place.event.pitches();
                int low = Score.position(pitches[0], clef);
                int high = Score.position(pitches[pitches.length - 1], clef);
                sum += (low + high) / 2f;
                highest = Math.max(highest, high);
                lowest = Math.min(lowest, low);
            }
            boolean up = clef == Score.Clef.PERCUSSION || sum / group.size() < 4;
            float top = group.get(0).staffTop;
            float beamY = up ? Math.min(y(top, highest) - 3.3f * SPACE, y(top, 4) - SPACE)
                    : Math.max(y(top, lowest) + 3.3f * SPACE, y(top, 4) + SPACE);
            g.setColor(INK);
            float thick = SPACE * 0.5f;
            float firstX = stemX(group.get(0).x, up);
            float lastX = stemX(group.get(group.size() - 1).x, up);
            g.fill(new Rectangle2D.Float(firstX, up ? beamY : beamY - thick, lastX - firstX, thick));
            for (int i = 0; i < group.size(); i++) {
                Placed place = group.get(i);
                Placed stemmed = new Placed(place.event, place.x, place.staffTop, up);
                stemmed.beamed = true;
                stemmed.stemEnd = beamY;
                bar.set(bar.indexOf(place), stemmed);
                if (place.event.length() == 1) {
                    // The second beam of a sixteenth: to its neighbour if that is one too, else a stub.
                    float second = up ? beamY + thick * 1.5f : beamY - thick * 2.5f;
                    float from = stemX(place.x, up);
                    boolean joinsNext = i + 1 < group.size() && group.get(i + 1).event.length() == 1;
                    boolean joinsBefore = i > 0 && group.get(i - 1).event.length() == 1;
                    if (joinsNext) {
                        g.fill(new Rectangle2D.Float(from, second, stemX(group.get(i + 1).x, up) - from, thick));
                    } else if (!joinsBefore) {
                        float stub = SPACE * 1.1f;
                        g.fill(new Rectangle2D.Float(i == 0 ? from : from - stub, second, stub, thick));
                    }
                }
            }
            index = next;
        }
    }

    private static float stemX(float x, boolean up) {
        return up ? x + 1.18f * SPACE - 0.6f : x + 0.6f;
    }

    private static void note(Graphics2D g, Placed place, Score.Clef clef) {
        Score.Event event = place.event;
        int[] pitches = event.pitches();
        int length = event.length();
        g.setColor(INK);
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int pitch : pitches) {
            int position = Score.position(pitch, clef);
            low = Math.min(low, position);
            high = Math.max(high, position);
            float y = y(place.staffTop, position);
            boolean cross = clef == Score.Clef.PERCUSSION && Score.cross(pitch);
            glyph(g, cross ? "noteX" : length == 16 ? "noteWhole" : length >= 8 ? "noteHalf" : "noteBlack", place.x, y);
            if (event.dotted()) {
                glyph(g, "dot", place.x + (length == 16 ? 1.9f : 1.45f) * SPACE,
                        position % 2 == 0 ? y - SPACE / 2 : y);
            }
        }
        // Short lines for notes above or below the staff.
        g.setStroke(new BasicStroke(1.2f));
        float wide = (length == 16 ? 1.69f : 1.18f) * SPACE;
        for (int line = -2; line >= low; line -= 2) {
            g.draw(new Line2D.Float(place.x - 3.5f, y(place.staffTop, line), place.x + wide + 3.5f, y(place.staffTop, line)));
        }
        for (int line = 10; line <= high; line += 2) {
            g.draw(new Line2D.Float(place.x - 3.5f, y(place.staffTop, line), place.x + wide + 3.5f, y(place.staffTop, line)));
        }
        if (length == 16) {
            return;
        }
        boolean up = place.stemUp;
        float x = stemX(place.x, up);
        float from = up ? y(place.staffTop, low) - 0.17f * SPACE : y(place.staffTop, high) + 0.17f * SPACE;
        float to = place.beamed ? place.stemEnd
                : up ? Math.min(y(place.staffTop, high) - 3.5f * SPACE, y(place.staffTop, 4))
                : Math.max(y(place.staffTop, low) + 3.5f * SPACE, y(place.staffTop, 4));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Line2D.Float(x, from, x, to));
        if (!place.beamed && length <= 3) {
            glyph(g, (length == 1 ? "flag16" : "flag8") + (up ? "Up" : "Down"), x - 0.6f, to);
        }
    }

    private static void rest(Graphics2D g, Placed place) {
        int length = place.event.length();
        g.setColor(INK);
        float top = place.staffTop;
        if (length == 16) {
            glyph(g, "restWhole", place.x, y(top, 6));
        } else if (length >= 8) {
            glyph(g, "restHalf", place.x, y(top, 4));
        } else if (length >= 4) {
            glyph(g, "restQuarter", place.x, y(top, 4));
        } else if (length >= 2) {
            glyph(g, "rest8", place.x, y(top, 4));
        } else {
            glyph(g, "rest16", place.x, y(top, 4));
        }
    }

    /** The arc from a note to the one it is tied to, on the side away from the stem. */
    private static void tie(Graphics2D g, Placed place, float to, Score.Clef clef) {
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int pitch : place.event.pitches()) {
            float y = y(place.staffTop, Score.position(pitch, clef)) + (place.stemUp ? 0.75f : -0.75f) * SPACE;
            float from = place.x + 1.25f * SPACE;
            float end = Math.max(from + 6, to - 2);
            float bulge = (place.stemUp ? 0.55f : -0.55f) * SPACE;
            Path2D arc = new Path2D.Float();
            arc.moveTo(from, y);
            arc.curveTo(from + (end - from) * 0.3f, y + bulge, from + (end - from) * 0.7f, y + bulge, end, y);
            g.draw(arc);
        }
    }
}
