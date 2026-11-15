package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The piece laid out in time, as in a program for cutting music or video: a ruler
 * with the bars, one lane per track with its notes, and below them the mix as a
 * waveform. A line marks where the playback is; a click in the lanes jumps there.
 * The names of the lanes and what can be set for a track stand in front of the
 * lanes, in {@link TrackLanes}; both use the same heights.
 *
 * <p>When the piece has a model, its notes can be edited right in the lanes ({@link LaneEditor},
 * work package B1); a click into a lane then selects instead of only jumping, and the ruler is
 * where the playback is moved.
 */
final class ArrangementView extends JComponent {
    private static final int TICKS_PER_BEAT = 480;
    private static final int GUTTER = 0;
    static final int RULER = 24;
    static final int MIX = 54;
    /** How high a lane is; the heads of the lanes are given the same height. */
    private int lane = 60;

    /**
     * A note of a lane: where it starts in the piece, how long it is, how high it sounds, and for
     * a piece with a model the clip and id that name it.
     */
    static final class Note {
        final long start;
        final long length;
        final int pitch;
        final String clipId;
        final String id;

        Note(long start, long length, int pitch) {
            this(start, length, pitch, null, null);
        }

        Note(long start, long length, int pitch, String clipId, String id) {
            this.start = start;
            this.length = length;
            this.pitch = pitch;
            this.clipId = clipId;
            this.id = id;
        }

        /** The note for the editor, or {@code null} if it comes from a file without ids. */
        com.aresstack.audio.studio.application.NoteEdits.NoteRef ref() {
            return id == null ? null : new com.aresstack.audio.studio.application.NoteEdits.NoteRef(clipId, id);
        }
    }

    /** What the lanes show: the tracks of an arrangement file with their notes. */
    static final class Arrangement {
        final int tempo;
        final Map<String, List<Note>> lanes = new LinkedHashMap<String, List<Note>>();
        long ticks;
        /** The length of a bar in ticks, as the meter of the piece says it; four quarters if it names none. */
        long barTicks = TICKS_PER_BEAT * 4L;
        /** Whether the notes carry ids, so that they can be edited. */
        boolean editable;

        Arrangement(int tempo) {
            this.tempo = tempo;
        }

        /** How long the music is in seconds, without what rings on after the last note. */
        double seconds() {
            return ticks / (double) TICKS_PER_BEAT * 60.0 / tempo;
        }

        int bars() {
            return (int) Math.max(1, Math.ceil(ticks / (TICKS_PER_BEAT * 4.0)));
        }

        /** The notes of the model, by track; they carry their ids and can be edited. */
        static Arrangement of(Composition piece) {
            Arrangement arrangement = new Arrangement((int) Math.round(piece.timeMap().tempoAt(0)));
            arrangement.editable = true;
            arrangement.barTicks = piece.timeMap().meterAt(0).barTicks();
            long end = 0;
            for (Track track : piece.tracks()) {
                List<Note> lane = new ArrayList<Note>();
                arrangement.lanes.put(track.id(), lane);
                for (Clip clip : track.clips()) {
                    if (clip instanceof MidiClip midi) {
                        end = Math.max(end, midi.endTick());
                        for (com.aresstack.audio.composition.Note note : midi.notes()) {
                            lane.add(new Note(midi.startTick() + note.tick(), note.length(), note.pitch(), midi.id(),
                                    note.id()));
                        }
                    }
                }
            }
            end = Math.max(end, piece.endTick());
            long bar = arrangement.barTicks;
            arrangement.ticks = Math.max(bar, (end + bar - 1) / bar * bar);
            return arrangement;
        }

        /** Reads an arrangement file; {@code null} if there is none or it cannot be read. */
        static Arrangement read(Path path) {
            if (path == null || !Files.isRegularFile(path)) {
                return null;
            }
            try {
                ArrangementFile file = ArrangementFile.read(path);
                Arrangement arrangement = new Arrangement(file.tempoOrDefault());
                for (ArrangementFile.Track track : file.tracks()) {
                    arrangement.lanes.put(track.id(), new ArrayList<Note>());
                }
                long end = 0;
                for (ArrangementFile.Note line : file.notes()) {
                    List<Note> lane = arrangement.lanes.get(line.trackId());
                    if (lane != null) {
                        Note note = new Note(line.tick(), line.length(), line.pitch());
                        lane.add(note);
                        end = Math.max(end, note.start + note.length);
                    }
                }
                long bar = TICKS_PER_BEAT * 4L;
                arrangement.ticks = Math.max(bar * 8, (end + bar - 1) / bar * bar); // whole bars, eight at least
                return arrangement;
            } catch (Exception unreadable) {
                return null;
            }
        }
    }

    /** What a click asks for. */
    interface Listener {
        /** Jump to this second of the playback. */
        void seek(double second);

        /**
         * The editor changed the piece, or tried to: what to tell the user, and whether the piece
         * is different now (then the lanes, the score and the stems follow).
         */
        default void edited(String said, boolean changed) {
        }
    }

    private final Listener listener;
    private Arrangement arrangement;
    private List<String> order = new ArrayList<String>();
    private DeckPlayer.Envelope mix;
    private String mixName = "MIX";
    private String alone;
    private double second;
    private double audioSeconds;
    private String hint = "";
    /** The note editor, once the window hands over the way into the piece. */
    private LaneEditor editor;
    /** The pitch windows of the lanes while the editor drags, so that they stay where they were. */
    private Map<String, int[]> frozenWindows;

    ArrangementView(Listener listener) {
        this.listener = listener;
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (!editing(event)) {
                    jump(event);
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (event.getX() >= GUTTER && !editing(event)) {
                    jump(event);
                }
            }

            private void jump(MouseEvent event) {
                seekTo(event.getX());
            }

            private boolean editing(MouseEvent event) {
                return editor != null && editor.owns(event.getY());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Lets the notes of a piece with a model be edited in the lanes. */
    void setEditing(com.aresstack.audio.studio.application.PieceEditing editing) {
        if (editor == null) {
            editor = new LaneEditor(this, editing, listener);
        }
    }

    /** Moves the playback to where a place across the view stands. */
    void seekTo(int x) {
        double total = totalSeconds();
        if (total > 0) {
            double share = (x - GUTTER) / (double) Math.max(1, getWidth() - GUTTER - 10);
            listener.seek(Math.max(0, Math.min(1, share)) * total);
        }
    }

    /** The lanes: the tracks of the mixer in their order, with the notes of the arrangement file if there is one. */
    void setArrangement(Arrangement shown, List<String> trackIds, String emptyHint) {
        arrangement = shown;
        order = new ArrayList<String>(trackIds);
        hint = emptyHint == null ? "" : emptyHint;
        if (editor != null) {
            editor.shown(shown);
        }
        revalidate();
        repaint();
    }

    /** The file that is loaded for playing, as the waveform below the lanes. */
    void setMix(DeckPlayer.Envelope envelope, String name, String aloneTrack, double length) {
        mix = envelope;
        mixName = name == null ? "MIX" : name;
        alone = aloneTrack;
        audioSeconds = length;
        repaint();
    }

    void setSecond(double played) {
        if (Math.abs(played - second) > 0.004) {
            second = played;
            repaint();
        }
    }

    void setLaneHeight(int height) {
        if (height != lane) {
            lane = height;
            revalidate();
            repaint();
        }
    }

    private double totalSeconds() {
        return Math.max(audioSeconds, arrangement == null ? 0 : arrangement.seconds());
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(400, RULER + order.size() * lane + MIX);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(200, RULER + lane + MIX);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = DeckWidgets.smooth(graphics);
        int width = getWidth();
        int height = getHeight();
        g.setColor(AudioStudioTheme.WELL);
        g.fillRect(0, 0, width, height);

        int left = GUTTER;
        int span = Math.max(1, width - GUTTER - 10);
        double total = totalSeconds();
        float musicSpan = musicSpan();
        final int LANE = lane;
        int lanesBottom = RULER + order.size() * LANE;

        // Lanes: a band each, the notes as small bars placed by time and pitch.
        for (int index = 0; index < order.size(); index++) {
            String id = order.get(index);
            Color color = AudioStudioTheme.channel(index);
            int top = RULER + index * LANE;
            boolean chosen = id.equals(alone);
            g.setColor(index % 2 == 0 ? new Color(0x171920) : new Color(0x14161C));
            g.fillRect(left, top, width - left, LANE);
            if (chosen) {
                g.setColor(AudioStudioTheme.withAlpha(color, 34));
                g.fillRect(0, top, width, LANE);
            }
            g.setColor(new Color(255, 255, 255, 14));
            g.fillRect(left, top + LANE - 1, width - left, 1);
            List<Note> notes = arrangement == null ? null : arrangement.lanes.get(id);
            if (notes == null || notes.isEmpty()) {
                continue;
            }
            Color shade = chosen ? color : AudioStudioTheme.withAlpha(color, 215);
            for (Note note : notes) {
                boolean picked = editor != null && editor.selected(note);
                g.setColor(picked ? Color.WHITE : shade);
                g.fill(noteBounds(index, note));
                if (picked) {
                    g.setColor(AudioStudioTheme.withAlpha(color, 230));
                    g.draw(noteBounds(index, note));
                }
            }
        }


        // The mix below the lanes, as a waveform.
        int mixTop = lanesBottom;
        g.setColor(new Color(0x101116));
        g.fillRect(left, mixTop, width - left, height - mixTop);
        g.setFont(AudioStudioTheme.lettering(10f));
        FontMetrics letters = g.getFontMetrics();
        if (mix != null && mix.left.length > 0 && total > 0) {
            float middle = mixTop + (height - mixTop) / 2f;
            float reach = (height - mixTop) / 2f - 5f;
            float audioSpan = (float) (span * mix.seconds / total);
            int bars = Math.max(1, (int) (audioSpan / 3));
            int playedBars = mix.seconds <= 0 ? 0 : (int) Math.round(second / mix.seconds * bars);
            Color color = AudioStudioTheme.ACCENT;
            if (alone != null && order.contains(alone)) {
                color = AudioStudioTheme.channel(order.indexOf(alone));
            }
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int bar = 0; bar < bars; bar++) {
                int from = (int) ((long) bar * mix.left.length / bars);
                int to = Math.max(from + 1, (int) ((long) (bar + 1) * mix.left.length / bars));
                float up = 0f;
                float down = 0f;
                for (int i = from; i < to && i < mix.left.length; i++) {
                    up = Math.max(up, mix.left[i]);
                    down = Math.max(down, mix.right[i]);
                }
                float x = left + bar * 3f + 1f;
                g.setColor(bar < playedBars ? color : AudioStudioTheme.withAlpha(color, 95));
                g.draw(new Line2D.Float(x, middle - Math.max(0.6f, up * reach), x, middle + Math.max(0.6f, down * reach)));
            }
        } else if (!hint.isEmpty()) {
            g.setColor(AudioStudioTheme.TEXT_FAINT);
            String text = hint.toUpperCase(java.util.Locale.ROOT);
            g.drawString(fit(letters, text, span - 20), left + 14, mixTop + (height - mixTop) / 2f + 4);
        }

        // The ruler: bars, every beat as a faint line through the lanes.
        g.setColor(new Color(0x1B1D24));
        g.fillRect(left, 0, width - left, RULER);
        g.setColor(AudioStudioTheme.STROKE);
        g.draw(new Line2D.Float(0, RULER, width, RULER));
        g.draw(new Line2D.Float(0, lanesBottom, width, lanesBottom));
        g.setFont(AudioStudioTheme.display(10f));
        if (arrangement != null) {
            int bars = arrangement.bars();
            int every = bars > 32 ? 4 : bars > 16 ? 2 : 1;
            for (int bar = 0; bar <= bars; bar++) {
                float x = left + musicSpan * bar / bars;
                g.setStroke(new BasicStroke(1f));
                g.setColor(new Color(255, 255, 255, bar % 4 == 0 ? 38 : 20));
                g.draw(new Line2D.Float(x, RULER - 6, x, lanesBottom));
                if (bar < bars && bar % every == 0) {
                    g.setColor(AudioStudioTheme.TEXT_DIM);
                    g.drawString(Integer.toString(bar + 1), x + 4, RULER - 7);
                }
            }
        } else {
            g.setColor(AudioStudioTheme.TEXT_FAINT);
            g.drawString("TAKTE", left + 8, RULER - 7);
        }

        // Where the playback is.
        if (total > 0) {
            float at = left + (float) (span * Math.min(1, second / total));
            g.setStroke(new BasicStroke(1.6f));
            g.setColor(Color.WHITE);
            g.draw(new Line2D.Float(at, 2, at, height - 2));
            g.setColor(AudioStudioTheme.withAlpha(Color.WHITE, 36));
            g.fill(new Rectangle2D.Float(at - 3, 2, 6, height - 4));
        }
        if (editor != null) {
            editor.paintOver(g); // over the ruler, so that the grid it names stands there
        }
        g.dispose();
    }

    // ---------------------------------------------------------------------------------------
    // Where things are, for painting and for the editor

    Arrangement arrangement() {
        return arrangement;
    }

    List<String> order() {
        return order;
    }

    int laneHeight() {
        return lane;
    }

    /** How wide the music is drawn: the share of the width its seconds take of all there is to show. */
    float musicSpan() {
        int span = Math.max(1, getWidth() - GUTTER - 10);
        double total = totalSeconds();
        double music = arrangement == null ? 0 : arrangement.seconds();
        return total <= 0 ? span : (float) (span * music / total);
    }

    float xOf(long tick) {
        return arrangement == null || arrangement.ticks <= 0 ? GUTTER
                : GUTTER + musicSpan() * tick / (float) arrangement.ticks;
    }

    long tickAt(int x) {
        float span = musicSpan();
        if (arrangement == null || span <= 0) {
            return 0;
        }
        return Math.max(0, Math.round((x - GUTTER) / (double) span * arrangement.ticks));
    }

    /** Ticks per pixel across the view. */
    double ticksPerPixel() {
        float span = musicSpan();
        return arrangement == null || span <= 0 ? 1 : arrangement.ticks / (double) span;
    }

    /** The lane under a height, or -1 above or below the lanes. */
    int laneAt(int y) {
        if (y < RULER) {
            return -1;
        }
        int index = (y - RULER) / lane;
        return index < order.size() ? index : -1;
    }

    int laneTop(int index) {
        return RULER + index * lane;
    }

    /**
     * The pitches a lane shows: its lowest and how many semitones up from there. A lane that can be
     * edited keeps room above and below its notes, so that a note can be moved or inserted there.
     */
    int[] pitchWindow(String laneId) {
        if (frozenWindows != null && frozenWindows.containsKey(laneId)) {
            return frozenWindows.get(laneId);
        }
        List<Note> notes = arrangement == null ? null : arrangement.lanes.get(laneId);
        boolean editable = arrangement != null && arrangement.editable;
        if (notes == null || notes.isEmpty()) {
            return editable ? new int[]{54, 12} : new int[]{60, 6};
        }
        int low = 127;
        int high = 0;
        for (Note note : notes) {
            low = Math.min(low, note.pitch);
            high = Math.max(high, note.pitch);
        }
        if (!editable) {
            return new int[]{low, Math.max(6, high - low)};
        }
        low = Math.max(0, low - 2);
        high = Math.min(127, high + 2);
        int range = Math.max(12, high - low);
        if (low + range > 127) {
            low = Math.max(0, 127 - range);
        }
        return new int[]{low, range};
    }

    /** Keeps the pitch windows as they are now while a note is dragged, or lets them follow the notes again. */
    void freezeWindows(boolean freeze) {
        if (!freeze) {
            frozenWindows = null;
            return;
        }
        frozenWindows = new LinkedHashMap<String, int[]>();
        for (String id : order) {
            frozenWindows.put(id, pitchWindow(id));
        }
    }

    float yOf(int index, int pitch) {
        int[] window = pitchWindow(order.get(index));
        float usable = lane - 14f;
        return laneTop(index) + 5f + usable * (1f - (pitch - window[0]) / (float) window[1]);
    }

    /** The pitch at a height within a lane. */
    int pitchAt(int index, int y) {
        int[] window = pitchWindow(order.get(index));
        float usable = lane - 14f;
        float share = 1f - (y - laneTop(index) - 5f - noteThickness(index) / 2f) / usable;
        return Math.max(0, Math.min(127, Math.round(window[0] + share * window[1])));
    }

    /** How many pixels one semitone is in a lane. */
    float semitone(int index) {
        return (lane - 14f) / pitchWindow(order.get(index))[1];
    }

    float noteThickness(int index) {
        int[] window = pitchWindow(order.get(index));
        return Math.max(3.4f, Math.min(7f, (lane - 14f) / (window[1] + 1f)));
    }

    RoundRectangle2D.Float noteBounds(int index, Note note) {
        return noteBounds(index, note.start, note.length, note.pitch);
    }

    RoundRectangle2D.Float noteBounds(int index, long start, long length, int pitch) {
        float x = xOf(start);
        float w = Math.max(2f, (float) (length / ticksPerPixel()) - 1f);
        return new RoundRectangle2D.Float(x, yOf(index, pitch), w, noteThickness(index), 2.4f, 2.4f);
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
