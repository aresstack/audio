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
 * stems, flags and beams, rests, dots, accidentals, ledger lines and ties, in the
 * meter and with the key signature of the piece, every note spelled as its key writes
 * it. Several systems follow one another down the sheet. While the piece plays, a line
 * shows where it is and the sheet turns along.
 */
final class ScoreView extends JComponent implements Scrollable {
    // Dark as the rest of the desk: light notes on a dark sheet. A picture for printing is the other way round.
    private Color paper = AudioStudioTheme.WELL;
    private Color ink = new Color(0xF2F0F6);
    private Color faint = AudioStudioTheme.TEXT_FAINT;
    /** The five lines and the bar lines: lighter than the sheet, quieter than the notes. */
    private Color lines = new Color(0x8D8B9A);
    /** The distance between two lines of a staff, in pixels; everything is measured in it. */
    private static final float SPACE = 9f;
    private static final int NAMES = 132;
    private static final int LEAD = 74;
    /** From one sign of the key signature to the next. */
    private static final float KEY_STEP = 1.05f * SPACE;
    /** Where the sharps of a key signature stand in the treble clef, in their order; the bass clef has them two places lower. */
    private static final int[] SHARP_PLACES = {8, 5, 9, 6, 3, 7, 4};
    private static final int[] FLAT_PLACES = {4, 7, 3, 6, 2, 5, 1};
    private static final int STAFF_STEP = 86;
    /** From the upper to the lower system of a grand staff: closer than two parts are. */
    private static final int GRAND_STEP = 62;
    private static final int SYSTEM_GAP = 46;
    private static final int TOP = 54;
    /** The room over a line of the sheet for tempo marks, where the piece changes its tempo. */
    private static final int TEMPO_BAND = 18;
    /** How far below its staff the dynamics of a staff stand; between the systems of a grand staff they stand in the middle. */
    private static final float DYNAMICS_BELOW = 26f;
    /** How wide a hairpin opens. */
    private static final float HAIRPIN_OPENING = 9f;
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
        /** How far a rest is moved from the middle of the staff, in places: up for the upper of two voices. */
        int shift;
        /** A rest that fills its bar: written as a whole rest in every meter. */
        boolean wholeBar;
        /** The sixteenth of the piece the event begins on, counted from 0 over all bars. */
        int position;

        Placed(Score.Event event, float x, float staffTop, boolean stemUp) {
            this.event = event;
            this.x = x;
            this.staffTop = staffTop;
            this.stemUp = stemUp;
        }
    }

    private Score score;
    private List<String> order = new ArrayList<String>();
    private List<SectionMark> sections = new ArrayList<SectionMark>();
    private String hint = "";
    private double second = -1;
    private int shownSystem = -1;

    ScoreView() {
        setOpaque(true);
        setBackground(paper);
        javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
    }

    /** Shows a score; the order of the tracks gives each staff the colour of its channel. */
    /** The form of the piece: the name of a section stands over its first bar, the chords over theirs. */
    void setSections(List<SectionMark> shown) {
        sections = new ArrayList<SectionMark>(shown == null ? List.<SectionMark>of() : shown);
        repaint();
    }

    void setScore(Score shown, List<String> trackIds, String emptyHint) {
        score = shown;
        order = new ArrayList<String>(trackIds);
        hint = emptyHint == null ? "" : emptyHint;
        revalidate();
        repaint();
    }

    /**
     * The whole score as a picture of this width, dark on white as on paper; {@code null}
     * when there are no notes yet.
     */
    java.awt.image.BufferedImage picture(int width) {
        if (score == null || staves().isEmpty()) {
            return null;
        }
        ScoreView sheet = new ScoreView();
        sheet.paper = Color.WHITE;
        sheet.ink = new Color(0x111111);
        sheet.faint = new Color(0x666666);
        sheet.lines = new Color(0x333333);
        sheet.setSize(width, 100);
        sheet.setScore(score, order, "");
        sheet.setSize(width, sheet.getPreferredSize().height + 20);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(sheet.getWidth(), sheet.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        sheet.paint(g);
        g.dispose();
        return image;
    }

    /**
     * The score for the printer, dark on white: as many lines of the sheet on a page as fit,
     * never a line cut in two. {@code null} when there are no notes yet.
     */
    java.awt.print.Printable printable() {
        if (score == null || staves().isEmpty()) {
            return null;
        }
        final ScoreView sheet = new ScoreView();
        sheet.paper = Color.WHITE;
        sheet.ink = new Color(0x111111);
        sheet.faint = new Color(0x666666);
        sheet.lines = new Color(0x333333);
        sheet.setSize(1500, 100);
        sheet.setScore(score, order, "");
        sheet.setSize(1500, sheet.getPreferredSize().height + 20);
        return (graphics, format, page) -> {
            double scale = format.getImageableWidth() / sheet.getWidth();
            int high = sheet.systemHeight();
            int systems = (sheet.score.bars() + sheet.barsPerSystem() - 1) / sheet.barsPerSystem();
            int top = TOP + sheet.band();
            int perPage = Math.max(1, (int) ((format.getImageableHeight() / scale - top) / high));
            int first = page * perPage;
            if (first >= systems) {
                return java.awt.print.Printable.NO_SUCH_PAGE;
            }
            int from = first == 0 ? 0 : top + first * high - 40 - sheet.band();
            int to = top + Math.min(systems, first + perPage) * high - 40 - sheet.band();
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(format.getImageableX(), format.getImageableY());
            g.scale(scale, scale);
            g.translate(0, -from);
            g.clipRect(0, from, sheet.getWidth(), to - from);
            sheet.paint(g);
            g.dispose();
            return java.awt.print.Printable.PAGE_EXISTS;
        };
    }

    /** Where the playback is, in seconds; negative when nothing plays. */
    void setSecond(double played) {
        if (Math.abs(played - second) < 0.01) {
            return;
        }
        second = played;
        if (score != null && played >= 0 && isShowing()) {
            int system = Math.min(score.bars() - 1, (int) score.barAt(played)) / barsPerSystem();
            if (system != shownSystem) {
                shownSystem = system;
                scrollRectToVisible(new Rectangle(0, TOP + band() - 30 + system * systemHeight(), 10,
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

    /** The sharps or flats of the key signature: positive for sharps, negative for flats, none without a score. */
    private int fifths() {
        return score == null ? 0 : Math.max(-7, Math.min(7, score.keySignature()));
    }

    /** How wide the key signature is at the head of a staff. */
    private float keyWidth() {
        return fifths() == 0 ? 0 : Math.abs(fifths()) * KEY_STEP + 5; // and a little air before the meter
    }

    /** The room at the head of every staff for clef, key signature and meter, before the first bar. */
    private int lead() {
        boolean wide = score != null && (score.meterNumerator() > 9 || score.meterDenominator() > 9);
        return LEAD + Math.round(keyWidth()) + (wide ? 16 : 0);
    }

    /** Sixteenths that are beamed together: a dotted quarter in 6/8, 9/8 and 12/8, else a quarter. */
    private int beamGroup() {
        return score != null && score.meterDenominator() == 8 && score.meterNumerator() % 3 == 0 ? 6 : 4;
    }

    private int barsPerSystem() {
        int fitting = Math.max(1, (getWidth() - NAMES - lead() - 24) / 250);
        return fitting >= 8 ? 8 : fitting >= 4 ? 4 : fitting >= 2 ? 2 : 1;
    }

    private int systemHeight() {
        List<Score.Staff> staves = staves();
        float[] tops = tops(staves);
        return Math.round(staves.isEmpty() ? STAFF_STEP : tops[tops.length - 1] + STAFF_STEP) + SYSTEM_GAP + band();
    }

    /** The room for tempo marks over every line of the sheet: none for a piece of one tempo. */
    private int band() {
        return score == null || score.tempoMarks().isEmpty() ? 0 : TEMPO_BAND;
    }

    /** Where each staff begins below the top of its line of the sheet: the two systems of a grand staff stand closer. */
    private static float[] tops(List<Score.Staff> staves) {
        float[] tops = new float[staves.size()];
        for (int row = 1; row < tops.length; row++) {
            tops[row] = tops[row - 1] + (upperOfGrand(staves, row - 1) ? GRAND_STEP : STAFF_STEP);
        }
        return tops;
    }

    /** Whether this staff is the upper system of a grand staff whose lower system follows it. */
    private static boolean upperOfGrand(List<Score.Staff> staves, int row) {
        return staves.get(row).system() == Score.Placement.UPPER && row + 1 < staves.size()
                && staves.get(row + 1).system() == Score.Placement.LOWER
                && staves.get(row + 1).trackId().equals(staves.get(row).trackId());
    }

    /** Whether this staff is the lower system of a grand staff whose upper system stands above it. */
    private static boolean lowerOfGrand(List<Score.Staff> staves, int row) {
        return row > 0 && upperOfGrand(staves, row - 1);
    }

    /** The voices of the staff under the mouse, where a staff has more than one or belongs to a grand staff. */
    @Override
    public String getToolTipText(java.awt.event.MouseEvent event) {
        List<Score.Staff> staves = staves();
        if (score == null || staves.isEmpty() || event.getY() < TOP + band() - 20) {
            return null;
        }
        float[] tops = tops(staves);
        float within = (event.getY() - TOP - band()) % (float) systemHeight();
        for (int row = 0; row < staves.size(); row++) {
            Score.Staff staff = staves.get(row);
            if (within >= tops[row] - 2 * SPACE && within <= tops[row] + 6 * SPACE
                    && (staff.voices() > 1 || staff.grand())) {
                StringBuilder names = new StringBuilder(staff.trackId().replace('_', ' ')).append(" · ");
                for (int voice = 0; voice < staff.voices(); voice++) {
                    names.append(voice == 0 ? "" : ", ").append(staff.voiceName(voice));
                }
                return names.toString();
            }
        }
        return null;
    }

    @Override
    public Dimension getPreferredSize() {
        if (score == null || staves().isEmpty()) {
            return new Dimension(600, 200);
        }
        int systems = (score.bars() + barsPerSystem() - 1) / barsPerSystem();
        return new Dimension(600, TOP + band() + systems * systemHeight());
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
        g.setColor(paper);
        g.fillRect(0, 0, getWidth(), getHeight());
        List<Score.Staff> staves = staves();
        if (score == null || staves.isEmpty()) {
            g.setColor(faint);
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
        final int LEAD = lead();
        final int barLength = Math.max(1, score.barLength());
        final int beat = beamGroup();
        float barWidth = (right - left - LEAD) / perSystem;
        Rectangle visible = g.getClipBounds();
        float[] tops = tops(staves);

        g.setColor(ink);
        g.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.ITALIC, 14));
        g.drawString("♩ = " + score.tempo(), left, 30);

        for (int system = 0; system < systems; system++) {
            float systemTop = TOP + band() + system * systemHeight();
            if (visible != null && (systemTop > visible.y + visible.height + 60
                    || systemTop + systemHeight() < visible.y - 60)) {
                continue;
            }
            int firstBar = system * perSystem;
            int lastBar = Math.min(score.bars(), firstBar + perSystem);
            float systemRight = left + LEAD + (lastBar - firstBar) * barWidth;
            float lowest = systemTop + tops[staves.size() - 1] + 4 * SPACE;

            // The line that joins the staves at the left, and the number of the first bar.
            g.setColor(lines);
            g.setStroke(new BasicStroke(1.6f));
            g.draw(new Line2D.Float(left, systemTop, left, lowest));
            // Which bars this line of the sheet holds: the piece goes on in the next line, as in a book.
            g.setFont(AudioStudioTheme.lettering(10.5f));
            g.setColor(ink);
            g.drawString(lastBar - firstBar == 1 ? "TAKT " + (firstBar + 1) : "TAKTE " + (firstBar + 1) + " – " + lastBar,
                    14, systemTop - 16);
            g.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.ITALIC, 12));
            g.setColor(faint);
            for (int bar = firstBar; bar < lastBar; bar++) {
                g.drawString(Integer.toString(bar + 1), left + LEAD + (bar - firstBar) * barWidth + 4, systemTop - 8);
            }
            // The form: over the first bar of a section its name, over a bar its chord where it changes.
            g.setFont(AudioStudioTheme.lettering(10.5f));
            for (SectionMark section : sections) {
                String sounding = null;
                for (int bar = section.firstBar(); bar < section.firstBar() + section.bars(); bar++) {
                    String chord = section.chordAt(bar);
                    boolean first = bar == section.firstBar();
                    boolean changes = chord != null && !chord.isBlank() && !chord.equals(sounding);
                    sounding = chord == null || chord.isBlank() ? sounding : chord;
                    if (bar < firstBar || bar >= lastBar || !(first || changes)) {
                        continue;
                    }
                    float x = left + LEAD + (bar - firstBar) * barWidth + 22;
                    if (first) {
                        g.setColor(ink);
                        String title = section.title();
                        g.drawString(title, x, systemTop - 21);
                        x += g.getFontMetrics().stringWidth(title) + 8;
                    }
                    if (changes) {
                        g.setColor(faint);
                        g.drawString(chord, first ? x : x - 18, systemTop - 21);
                    }
                }
            }
            if (system > 0) {
                g.setColor(AudioStudioTheme.withAlpha(lines, 70));
                g.setStroke(new BasicStroke(1f));
                g.draw(new Line2D.Float(14, systemTop - 34 - band(), getWidth() - 24, systemTop - 34 - band()));
            }
            tempoMarks(g, systemTop, firstBar, lastBar, left + LEAD, barWidth, barLength, systemRight);

            for (int row = 0; row < staves.size(); row++) {
                Score.Staff staff = staves.get(row);
                float top = systemTop + tops[row];
                boolean upper = upperOfGrand(staves, row);
                boolean lower = lowerOfGrand(staves, row);
                // The two systems of a grand staff are one instrument: one name between them, one
                // brace before them, and the bar lines go through both.
                float reach = upper ? systemTop + tops[row + 1] + 4 * SPACE : top + 4 * SPACE;
                if (!lower) {
                    // Name and the colour of the channel.
                    float middle = (top + reach) / 2;
                    int channel = Math.max(0, order.indexOf(staff.trackId()));
                    g.setColor(AudioStudioTheme.channel(channel));
                    g.fill(new Ellipse2D.Float(14, middle - 4, 8, 8));
                    g.setColor(ink);
                    g.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.PLAIN, 14));
                    FontMetrics metrics = g.getFontMetrics();
                    String name = staff.trackId().replace('_', ' ');
                    while (name.length() > 2 && metrics.stringWidth(name) > NAMES - (upper ? 52 : 40)) {
                        name = name.substring(0, name.length() - 2) + "…";
                    }
                    g.drawString(name, 28, middle + 5);
                }
                if (upper) {
                    g.setColor(ink);
                    brace(g, left - 3, top, reach);
                }

                // The five lines, the clef, the time on the first system, the bar lines.
                g.setColor(lines);
                g.setStroke(new BasicStroke(1f));
                for (int line = 0; line < 5; line++) {
                    g.draw(new Line2D.Float(left, top + line * SPACE, systemRight, top + line * SPACE));
                }
                g.setColor(ink);
                if (staff.clef() == Score.Clef.TREBLE) {
                    glyph(g, "gClef", left + 8, top + 3 * SPACE);
                } else if (staff.clef() == Score.Clef.BASS) {
                    glyph(g, "fClef", left + 8, top + SPACE);
                } else {
                    glyph(g, "percussionClef", left + 12, top + 2 * SPACE);
                }
                if (staff.clef() != Score.Clef.PERCUSSION) {
                    keySignature(g, left + 38, top, staff.clef());
                }
                if (system == 0) {
                    meter(g, left + 42 + keyWidth(), top);
                }
                for (int bar = firstBar; bar < lastBar; bar++) {
                    float barRight = left + LEAD + (bar - firstBar + 1) * barWidth;
                    boolean last = bar == score.bars() - 1;
                    g.setColor(lines);
                    g.setStroke(new BasicStroke(1.2f));
                    if (lower) {
                        continue; // drawn with the upper system, through both
                    }
                    g.draw(new Line2D.Float(barRight - (last ? 6 : 0), top, barRight - (last ? 6 : 0), reach));
                    if (last) {
                        g.fill(new Rectangle2D.Float(barRight - 3, top, 3.6f, reach - top));
                    }
                }

                // Dynamics under the staff; between the two systems of a grand staff.
                float dynamicsLine = upper ? (top + 4 * SPACE + systemTop + tops[row + 1]) / 2 + 5
                        : top + 4 * SPACE + DYNAMICS_BELOW;
                dynamics(g, staff, dynamicsLine, firstBar, lastBar, left + LEAD, barWidth, barLength, systemRight);

                // The notes of this staff in this system, one voice after the other.
                // Where the heads of earlier voices stand in each bar: a later voice steps aside from them.
                Map<Integer, Map<Integer, List<Integer>>> heads = new HashMap<Integer, Map<Integer, List<Integer>>>();
                for (int voice = 0; voice < staff.voices(); voice++) {
                    List<Placed> placed = new ArrayList<Placed>();
                    for (int bar = firstBar; bar < lastBar; bar++) {
                        float barLeft = left + LEAD + (bar - firstBar) * barWidth;
                        float inner = barWidth - 30;
                        // Where several voices play in a bar, each has its stems the way it asks (the
                        // upper ones up, the lower ones down) and their rests move apart; a voice that
                        // rests the whole bar is left out, and a bar where all rest shows one rest.
                        int sounding = 0;
                        for (int other = 0; other < staff.voices(); other++) {
                            sounding += staff.silent(other, bar) ? 0 : 1;
                        }
                        if (staff.silent(voice, bar) && (voice > 0 || sounding > 0)) {
                            continue;
                        }
                        boolean several = sounding > 1;
                        com.aresstack.audio.composition.Voice.Stem asked = staff.stem(voice);
                        Boolean forced = !several ? null
                                : asked == com.aresstack.audio.composition.Voice.Stem.UP ? Boolean.TRUE
                                : asked == com.aresstack.audio.composition.Voice.Stem.DOWN ? Boolean.FALSE
                                : staff.voices() > 1 ? Boolean.valueOf(voice == 0) : null;
                        // Rests of the first upper and the first lower voice move a little, those of
                        // further voices in the same direction move further out.
                        int restShift = 0;
                        if (several && forced != null) {
                            int before = 0;
                            for (int other = 0; other < voice; other++) {
                                com.aresstack.audio.composition.Voice.Stem stem = staff.stem(other);
                                boolean otherUp = stem == com.aresstack.audio.composition.Voice.Stem.UP
                                        || stem == com.aresstack.audio.composition.Voice.Stem.AUTO && other == 0;
                                before += otherUp == forced.booleanValue() && !staff.silent(other, bar) ? 1 : 0;
                            }
                            restShift = (forced.booleanValue() ? 1 : -1) * (4 + 3 * before);
                        }
                        Map<Integer, List<Integer>> taken = heads.get(bar);
                        if (taken == null) {
                            taken = new HashMap<Integer, List<Integer>>();
                            heads.put(bar, taken);
                        }
                        List<Score.Event> events = staff.bar(voice, bar);
                        List<Placed> inBar = new ArrayList<Placed>();
                        // A bar of nothing but rests is one whole rest in its middle, in every meter.
                        boolean silentBar = !events.isEmpty();
                        for (Score.Event event : events) {
                            silentBar &= event.rest();
                        }
                        for (Score.Event event : events) {
                            if (silentBar && event != events.get(0)) {
                                continue;
                            }
                            boolean wholeBar = silentBar;
                            float x = wholeBar ? barLeft + barWidth / 2 - 6
                                    : barLeft + 18 + inner * event.start() / barLength;
                            if (!event.rest()) {
                                // From the third voice on a head that would sit on or right beside a
                                // head of an earlier voice at the same moment moves one head aside.
                                List<Integer> there = taken.get(event.start());
                                if (there == null) {
                                    there = new ArrayList<Integer>();
                                    taken.put(event.start(), there);
                                }
                                boolean collides = false;
                                for (int i = 0; i < event.pitches().length; i++) {
                                    int position = place(event, i, staff.clef());
                                    for (int other : there) {
                                        collides |= Math.abs(other - position) <= 1;
                                    }
                                }
                                if (voice >= 2 && collides) {
                                    x += 1.18f * SPACE + 1.5f;
                                }
                                for (int i = 0; i < event.pitches().length; i++) {
                                    there.add(place(event, i, staff.clef()));
                                }
                            }
                            Placed place = new Placed(event, x, top,
                                    forced != null ? forced.booleanValue() : stemUp(event, staff.clef()));
                            place.shift = restShift;
                            place.wholeBar = wholeBar;
                            place.position = bar * barLength + event.start();
                            inBar.add(place);
                            if (!event.rest()) {
                                accidentals(g, place, staff.clef());
                            }
                        }
                        beam(g, inBar, staff.clef(), forced, beat);
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
                            com.aresstack.audio.composition.Articulation articulation =
                                    staff.articulationAt(place.position);
                            if (articulation != null && !place.wholeBar) {
                                float next = index + 1 < placed.size() ? placed.get(index + 1).x : systemRight - 4;
                                articulation(g, place, articulation, next, staff.clef());
                            }
                        }
                    }
                }
            }

            // Where the playback is.
            if (second >= 0) {
                double bar = score.barAt(second);
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
        int low = place(event, 0, clef);
        int high = place(event, event.pitches().length - 1, clef);
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

    /**
     * Where a note of an event sits on its staff: as the key of the piece spells it (B flat, not
     * A sharp, in D minor), or by its pitch where it has no spelling, as on a percussion staff.
     */
    private static int place(Score.Event event, int index, Score.Clef clef) {
        com.aresstack.audio.composition.Key.Spelling spelling =
                clef == Score.Clef.PERCUSSION ? null : event.spelling(index);
        return spelling != null ? Score.position(spelling, clef) : Score.position(event.pitches()[index], clef);
    }

    /** The sharps or flats of the key at the head of a staff. */
    private void keySignature(Graphics2D g, float x, float staffTop, Score.Clef clef) {
        int fifths = fifths();
        int lower = clef == Score.Clef.BASS ? 2 : 0;
        for (int i = 0; i < Math.abs(fifths); i++) {
            int position = (fifths > 0 ? SHARP_PLACES[i] : FLAT_PLACES[i]) - lower;
            glyph(g, fifths > 0 ? "sharp" : "flat", x + i * KEY_STEP, y(staffTop, position));
        }
    }

    /** The brace before the two systems of a grand staff, from the top line of the upper to the bottom line of the lower. */
    private static void brace(Graphics2D g, float right, float top, float bottom) {
        Path2D outline = GLYPHS.get("brace");
        if (outline == null) {
            return;
        }
        Rectangle2D bounds = outline.getBounds2D();
        // The outline is made for one staff; stretched over two it is widened too, so that it stays a brace.
        double wide = 1.1 * SPACE;
        AffineTransform place = AffineTransform.getTranslateInstance(right - wide, top);
        place.scale(wide / bounds.getWidth(), (bottom - top) / bounds.getHeight());
        place.translate(-bounds.getX(), -bounds.getY());
        g.fill(place.createTransformedShape(outline));
    }

    /** The meter: its two numbers above one another, centred on each other. */
    private void meter(Graphics2D g, float x, float staffTop) {
        String upper = Integer.toString(score.meterNumerator());
        String lower = Integer.toString(score.meterDenominator());
        float width = Math.max(digitsWidth(upper), digitsWidth(lower));
        digits(g, upper, x + (width - digitsWidth(upper)) / 2, staffTop + SPACE);
        digits(g, lower, x + (width - digitsWidth(lower)) / 2, staffTop + 3 * SPACE);
    }

    /** As wide as a digit of the meter is, on the scale of the sheet. */
    private static final float DIGIT = 470 * SPACE / 250f;

    private static float digitsWidth(String number) {
        return number.length() * DIGIT * 0.92f;
    }

    private static void digits(Graphics2D g, String number, float x, float y) {
        for (int i = 0; i < number.length(); i++) {
            glyph(g, "time" + number.charAt(i), x + i * DIGIT * 0.92f, y);
        }
    }

    /** The signs before the notes that need one in their bar, as the score says it. */
    private void accidentals(Graphics2D g, Placed place, Score.Clef clef) {
        if (clef == Score.Clef.PERCUSSION) {
            return;
        }
        for (int i = 0; i < place.event.pitches().length; i++) {
            String sign;
            float before;
            switch (place.event.accidental(i)) {
                case SHARP: sign = "sharp"; before = 11.5f; break;
                case FLAT: sign = "flat"; before = 10f; break;
                case NATURAL: sign = "natural"; before = 9f; break;
                case DOUBLE_SHARP: sign = "doubleSharp"; before = 11.5f; break;
                case DOUBLE_FLAT: sign = "doubleFlat"; before = 17f; break;
                default: continue;
            }
            g.setColor(ink);
            glyph(g, sign, place.x - before, y(place.staffTop, place(place.event, i, clef)));
        }
    }

    /** Joins eighths and sixteenths of one beat with a beam; the stems of a group all go the same way. */
    private void beam(Graphics2D g, List<Placed> bar, Score.Clef clef, Boolean forced, int beatLength) {
        int index = 0;
        while (index < bar.size()) {
            Placed first = bar.get(index);
            int beat = first.event.start() / beatLength;
            List<Placed> group = new ArrayList<Placed>();
            int next = index;
            while (next < bar.size() && !bar.get(next).event.rest() && bar.get(next).event.length() <= 3
                    && bar.get(next).event.start() / beatLength == beat) {
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
                int low = place(place.event, 0, clef);
                int high = place(place.event, place.event.pitches().length - 1, clef);
                sum += (low + high) / 2f;
                highest = Math.max(highest, high);
                lowest = Math.min(lowest, low);
            }
            boolean up = forced != null ? forced.booleanValue() : clef == Score.Clef.PERCUSSION || sum / group.size() < 4;
            float top = group.get(0).staffTop;
            // The beam leans the way the notes go, but only a little, and every stem keeps a fair length.
            float[] reach = new float[group.size()];
            float[] xs = new float[group.size()];
            for (int i = 0; i < group.size(); i++) {
                Score.Event grouped = group.get(i).event;
                int outer = place(grouped, up ? grouped.pitches().length - 1 : 0, clef);
                reach[i] = y(top, outer) + (up ? -3.2f : 3.2f) * SPACE;
                xs[i] = stemX(group.get(i).x, up);
            }
            float firstX = xs[0];
            float lastX = xs[xs.length - 1];
            float lean = Math.max(-1.25f * SPACE, Math.min(1.25f * SPACE, reach[reach.length - 1] - reach[0]));
            float slope = lean / Math.max(1f, lastX - firstX);
            float start = reach[0];
            for (int i = 0; i < xs.length; i++) {
                float on = start + slope * (xs[i] - firstX);
                if (up ? on > reach[i] : on < reach[i]) {
                    start += reach[i] - on; // a stem would be too short: the whole beam moves away
                }
            }
            float middle = y(top, 4);
            float centre = start + slope * (lastX - firstX) / 2;
            if (up ? centre > middle - SPACE : centre < middle + SPACE) {
                start += (up ? middle - SPACE : middle + SPACE) - centre; // notes far outside: the beam reaches the staff
            }
            g.setColor(ink);
            float thick = SPACE * 0.5f;
            band(g, firstX, start, lastX, start + slope * (lastX - firstX), up ? thick : -thick);
            for (int i = 0; i < group.size(); i++) {
                Placed place = group.get(i);
                Placed stemmed = new Placed(place.event, place.x, place.staffTop, up);
                stemmed.shift = place.shift;
                stemmed.wholeBar = place.wholeBar;
                stemmed.beamed = true;
                stemmed.stemEnd = start + slope * (xs[i] - firstX);
                bar.set(bar.indexOf(place), stemmed);
                if (place.event.length() == 1) {
                    // The second beam of a sixteenth: to its neighbour if that is one too, else a stub.
                    float away = up ? thick * 1.5f : -thick * 1.5f;
                    boolean joinsNext = i + 1 < group.size() && group.get(i + 1).event.length() == 1;
                    boolean joinsBefore = i > 0 && group.get(i - 1).event.length() == 1;
                    float from = xs[i];
                    float to = joinsNext ? xs[i + 1] : i == 0 ? from + SPACE * 1.1f : from - SPACE * 1.1f;
                    if (joinsNext || !joinsBefore) {
                        band(g, Math.min(from, to), start + slope * (Math.min(from, to) - firstX) + away,
                                Math.max(from, to), start + slope * (Math.max(from, to) - firstX) + away,
                                up ? thick : -thick);
                    }
                }
            }
            index = next;
        }
    }

    /** A beam: a band from one point to another, as thick as said (downwards for a positive thickness). */
    private static void band(Graphics2D g, float x1, float y1, float x2, float y2, float thickness) {
        Path2D shape = new Path2D.Float();
        shape.moveTo(x1, y1);
        shape.lineTo(x2, y2);
        shape.lineTo(x2, y2 + thickness);
        shape.lineTo(x1, y1 + thickness);
        shape.closePath();
        g.fill(shape);
    }

    private static float stemX(float x, boolean up) {
        return up ? x + 1.18f * SPACE - 0.6f : x + 0.6f;
    }

    private void note(Graphics2D g, Placed place, Score.Clef clef) {
        Score.Event event = place.event;
        int[] pitches = event.pitches();
        int length = event.length();
        g.setColor(ink);
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        // Two notes next to each other on the staff cannot stand above one another: counted from
        // the end the stem starts at, every second one of such neighbours moves to the other side.
        boolean[] aside = new boolean[pitches.length];
        boolean any = false;
        for (int step = 1; step < pitches.length; step++) {
            int i = place.stemUp ? step : pitches.length - 1 - step;
            int before = place.stemUp ? i - 1 : i + 1;
            if (Math.abs(place(event, i, clef) - place(event, before, clef)) == 1
                    && !aside[before]) {
                aside[i] = true;
                any = true;
            }
        }
        float headWidth = (length == 16 ? 1.69f : 1.18f) * SPACE;
        for (int i = 0; i < pitches.length; i++) {
            int pitch = pitches[i];
            int position = place(event, i, clef);
            low = Math.min(low, position);
            high = Math.max(high, position);
            float y = y(place.staffTop, position);
            float x = aside[i] ? place.x + (place.stemUp ? headWidth - 1.2f : -headWidth + 1.2f) : place.x;
            boolean cross = clef == Score.Clef.PERCUSSION && Score.cross(pitch);
            glyph(g, cross ? "noteX" : length == 16 ? "noteWhole" : length >= 8 ? "noteHalf" : "noteBlack", x, y);
            if (event.dotted()) {
                glyph(g, "dot", place.x + (length == 16 ? 1.9f : 1.45f) * SPACE + (any && place.stemUp ? headWidth : 0),
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

    private void rest(Graphics2D g, Placed place) {
        int length = place.event.length();
        g.setColor(ink);
        float top = place.staffTop;
        if (place.wholeBar || length >= 16) {
            glyph(g, "restWhole", place.x, y(top, 6 + place.shift));
        } else if (length >= 8) {
            glyph(g, "restHalf", place.x, y(top, 4 + place.shift));
        } else if (length >= 4) {
            glyph(g, "restQuarter", place.x, y(top, 4 + place.shift));
        } else if (length >= 2) {
            glyph(g, "rest8", place.x, y(top, 4 + place.shift));
        } else {
            glyph(g, "rest16", place.x, y(top, 4 + place.shift));
        }
    }

    /** The arc from a note to the one it is tied to, on the side away from the stem. */
    private void tie(Graphics2D g, Placed place, float to, Score.Clef clef) {
        g.setColor(ink);
        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int[] pitches = place.event.pitches();
        for (int i = 0; i < pitches.length; i++) {
            if (!place.event.tied(i)) {
                continue;
            }
            float y = y(place.staffTop, place(place.event, i, clef)) + (place.stemUp ? 0.75f : -0.75f) * SPACE;
            float from = place.x + 1.25f * SPACE;
            float end = Math.max(from + 6, to - 2);
            float bulge = (place.stemUp ? 0.55f : -0.55f) * SPACE;
            Path2D arc = new Path2D.Float();
            arc.moveTo(from, y);
            arc.curveTo(from + (end - from) * 0.3f, y + bulge, from + (end - from) * 0.7f, y + bulge, end, y);
            g.draw(arc);
        }
    }

    /** Where a sixteenth of the piece lies in a line of the sheet, or NaN outside of it; its end is the right edge. */
    private static float xOf(int position, int firstBar, int lastBar, float barsLeft, float barWidth, int barLength,
                             float systemRight) {
        int bar = position / barLength;
        if (bar == lastBar && position % barLength == 0) {
            return systemRight;
        }
        if (bar < firstBar || bar >= lastBar) {
            return Float.NaN;
        }
        return barsLeft + (bar - firstBar) * barWidth + 18 + (barWidth - 30) * (position % barLength) / barLength;
    }

    /** The tempo marks over a line of the sheet, and the dashed line of an accelerando or ritardando through it. */
    private void tempoMarks(Graphics2D g, float systemTop, int firstBar, int lastBar, float barsLeft, float barWidth,
                            int barLength, float systemRight) {
        if (score.tempoMarks().isEmpty()) {
            return;
        }
        float y = systemTop - 36;
        int lineStart = firstBar * barLength;
        int lineEnd = lastBar * barLength;
        g.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.BOLD | java.awt.Font.ITALIC, 13));
        FontMetrics metrics = g.getFontMetrics();
        for (Score.TempoMark mark : score.tempoMarks()) {
            float x = xOf(mark.position(), firstBar, lastBar, barsLeft, barWidth, barLength, systemRight);
            float dashFrom = Float.NaN;
            if (!Float.isNaN(x) && mark.position() < lineEnd) {
                g.setColor(ink);
                g.drawString(mark.text(), x - 4, y);
                dashFrom = x - 4 + metrics.stringWidth(mark.text()) + 4;
            } else if (mark.until() > lineStart && mark.position() < lineStart) {
                dashFrom = barsLeft; // the glide goes on from the line before
            }
            if (mark.until() >= 0 && !Float.isNaN(dashFrom)) {
                float to = mark.until() >= lineEnd ? systemRight
                        : xOf(mark.until(), firstBar, lastBar, barsLeft, barWidth, barLength, systemRight) - 8;
                if (to > dashFrom) {
                    g.setColor(faint);
                    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                            new float[]{4f, 4f}, 0f));
                    g.draw(new Line2D.Float(dashFrom, y - 4, to, y - 4));
                }
            }
        }
    }

    /** The dynamic marks and hairpins of a staff in a line of the sheet, on a line under it. */
    private void dynamics(Graphics2D g, Score.Staff staff, float y, int firstBar, int lastBar, float barsLeft,
                          float barWidth, int barLength, float systemRight) {
        if (staff.dynamics().isEmpty() && staff.hairpins().isEmpty()) {
            return;
        }
        int lineStart = firstBar * barLength;
        int lineEnd = lastBar * barLength;
        g.setColor(ink);
        g.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.BOLD | java.awt.Font.ITALIC, 15));
        FontMetrics metrics = g.getFontMetrics();
        for (Score.DynamicMark mark : staff.dynamics()) {
            float x = xOf(mark.position(), firstBar, lastBar, barsLeft, barWidth, barLength, systemRight);
            if (!Float.isNaN(x) && mark.position() < lineEnd) {
                g.drawString(mark.level().symbol(), x - 2, y);
            }
        }
        g.setStroke(new BasicStroke(1.2f));
        for (Score.HairpinMark pin : staff.hairpins()) {
            if (pin.to() <= lineStart || pin.from() >= lineEnd) {
                continue;
            }
            float from = pin.from() < lineStart ? barsLeft
                    : xOf(pin.from(), firstBar, lastBar, barsLeft, barWidth, barLength, systemRight);
            float to = pin.to() >= lineEnd ? systemRight - 4
                    : xOf(pin.to(), firstBar, lastBar, barsLeft, barWidth, barLength, systemRight) - 6;
            // A hairpin starting on a mark leaves room for it.
            for (Score.DynamicMark mark : staff.dynamics()) {
                if (mark.position() == pin.from()) {
                    from += metrics.stringWidth(mark.level().symbol()) + 2;
                }
            }
            if (to <= from + 4) {
                continue;
            }
            // Where the hairpin is cut by the end of a line, it opens (or closes) only part of the way.
            float span = Math.max(1, pin.to() - pin.from());
            float atFrom = Math.max(0, Math.min(1, (Math.max(pin.from(), lineStart) - pin.from()) / span));
            float atTo = Math.max(0, Math.min(1, (Math.min(pin.to(), lineEnd) - pin.from()) / span));
            float openFrom = (pin.crescendo() ? atFrom : 1 - atFrom) * HAIRPIN_OPENING / 2;
            float openTo = (pin.crescendo() ? atTo : 1 - atTo) * HAIRPIN_OPENING / 2;
            float middle = y - 5;
            g.draw(new Line2D.Float(from, middle - openFrom, to, middle - openTo));
            g.draw(new Line2D.Float(from, middle + openFrom, to, middle + openTo));
        }
    }

    /** The sign of an articulation at a note, on the side of the head away from the stem; legato as a slur to the next note. */
    private void articulation(Graphics2D g, Placed place, com.aresstack.audio.composition.Articulation articulation,
                              float next, Score.Clef clef) {
        int[] pitches = place.event.pitches();
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int i = 0; i < pitches.length; i++) {
            low = Math.min(low, place(place.event, i, clef));
            high = Math.max(high, place(place.event, i, clef));
        }
        boolean below = place.stemUp;
        float head = below ? y(place.staffTop, low) : y(place.staffTop, high);
        float y = below ? Math.max(head + 1.3f * SPACE, y(place.staffTop, -1)) : Math.min(head - 1.3f * SPACE,
                y(place.staffTop, 9));
        float x = place.x + 0.59f * SPACE;
        g.setColor(ink);
        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (articulation) {
            case STACCATO -> g.fill(new Ellipse2D.Float(x - 1.8f, y - 1.8f, 3.6f, 3.6f));
            case STACCATISSIMO -> {
                Path2D wedge = new Path2D.Float();
                float tip = below ? y - 3.5f : y + 3.5f;
                wedge.moveTo(x - 2.2f, below ? y + 3.5f : y - 3.5f);
                wedge.lineTo(x + 2.2f, below ? y + 3.5f : y - 3.5f);
                wedge.lineTo(x, tip);
                wedge.closePath();
                g.fill(wedge);
            }
            case TENUTO -> g.draw(new Line2D.Float(x - 4.5f, y, x + 4.5f, y));
            case ACCENT -> {
                g.draw(new Line2D.Float(x - 4.5f, y - 3f, x + 4.5f, y));
                g.draw(new Line2D.Float(x + 4.5f, y, x - 4.5f, y + 3f));
            }
            case MARCATO -> {
                float point = below ? y + 3.5f : y - 3.5f;
                float foot = below ? y - 3.5f : y + 3.5f;
                g.draw(new Line2D.Float(x - 3.5f, foot, x, point));
                g.draw(new Line2D.Float(x, point, x + 3.5f, foot));
            }
            case LEGATO -> {
                float from = place.x + 0.6f * SPACE;
                float end = Math.max(from + 8, next + 0.6f * SPACE);
                float bulge = (below ? 0.8f : -0.8f) * SPACE;
                Path2D arc = new Path2D.Float();
                arc.moveTo(from, y);
                arc.curveTo(from + (end - from) * 0.3f, y + bulge, from + (end - from) * 0.7f, y + bulge, end, y);
                g.draw(arc);
            }
            default -> {
            }
        }
    }
}
