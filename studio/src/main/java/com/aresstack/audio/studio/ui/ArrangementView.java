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
    /** The band at the top of a lane that carries the clips' names and is where a clip is grabbed. */
    static final int CLIP_BAND = 12;
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
        /** The time map of the model, to place audio clips, whose length is in samples; {@code null} for a file. */
        com.aresstack.audio.composition.TimeMap timeMap;
        /** The clips of each lane, for a piece with a model (work package B2). */
        final Map<String, List<ClipBox>> clips = new LinkedHashMap<String, List<ClipBox>>();
        /** The audio lanes of the model: rows of sound, without notes (work package B3). */
        final java.util.Set<String> audioLanes = new java.util.HashSet<String>();

        Arrangement(int tempo) {
            this.tempo = tempo;
        }

        /** How long the music is in seconds, without what rings on after the last note. */
        double seconds() {
            return ticks / (double) TICKS_PER_BEAT * 60.0 / tempo;
        }

        int bars() {
            return (int) Math.max(1, (ticks + barTicks - 1) / barTicks);
        }

        /** The notes of the model, by track; they carry their ids and can be edited. */
        static Arrangement of(Composition piece) {
            Arrangement arrangement = new Arrangement((int) Math.round(piece.timeMap().tempoAt(0)));
            arrangement.editable = true;
            arrangement.barTicks = piece.timeMap().meterAt(0).barTicks();
            arrangement.timeMap = piece.timeMap();
            long end = 0;
            for (Track track : piece.tracks()) {
                List<Note> lane = new ArrayList<Note>();
                List<ClipBox> boxes = new ArrayList<ClipBox>();
                arrangement.lanes.put(track.id(), lane);
                arrangement.clips.put(track.id(), boxes);
                if (track.kind() == Track.Kind.AUDIO) {
                    arrangement.audioLanes.add(track.id());
                }
                for (Clip clip : track.clips()) {
                    if (clip instanceof com.aresstack.audio.composition.AudioClip audio) {
                        ClipBox box = ClipBox.of(piece, track, audio);
                        end = Math.max(end, box.end);
                        boxes.add(box);
                    }
                    if (clip instanceof MidiClip midi) {
                        end = Math.max(end, midi.endTick());
                        boxes.add(ClipBox.of(piece, track, midi, false));
                        for (com.aresstack.audio.composition.Note note : midi.notes()) {
                            lane.add(new Note(midi.startTick() + note.tick(), note.length(), note.pitch(), midi.id(),
                                    note.id()));
                        }
                    }
                }
            }
            // A part that plays clips on lanes of others (a clip got another instrument) has a head of
            // its own, as every player has; its row repeats those clips, marked as lying elsewhere.
            for (com.aresstack.audio.composition.Part part : piece.parts()) {
                if (piece.track(part.id()) != null) {
                    continue;
                }
                List<Note> lane = new ArrayList<Note>();
                List<ClipBox> boxes = new ArrayList<ClipBox>();
                for (Track track : piece.tracks()) {
                    for (Clip clip : track.clips()) {
                        if (clip instanceof MidiClip midi && part.id().equals(track.partOf(midi))) {
                            boxes.add(ClipBox.of(piece, track, midi, true));
                            for (com.aresstack.audio.composition.Note note : midi.notes()) {
                                lane.add(new Note(midi.startTick() + note.tick(), note.length(), note.pitch(),
                                        midi.id(), note.id()));
                            }
                        }
                    }
                }
                arrangement.lanes.put(part.id(), lane);
                arrangement.clips.put(part.id(), boxes);
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
                // As long as the piece says it is: its bars line, else the bars its notes reach into
                // (and the eight bars every piece had before length, meter and key could be chosen).
                arrangement.barTicks = file.barTicks();
                boolean asEver = file.meter().map(ArrangementFile.Meter::fourFour).orElse(true)
                        && file.key().map(ArrangementFile.Key::cMajor).orElse(true);
                int covering = ArrangementFile.barsCovering(end, arrangement.barTicks);
                arrangement.ticks = arrangement.barTicks
                        * file.bars().orElse(ArrangementFile.defaultBars(covering, asEver));
                return arrangement;
            } catch (Exception unreadable) {
                return null;
            }
        }
    }

    /**
     * A MIDI clip as a lane shows it: where it lies, which part plays it, and whether that is not the
     * lane's own part (then it is labelled with the instrument). A clip in the row of a part without a
     * lane of its own is a mirror of the clip on the lane it lies on.
     */
    static final class ClipBox {
        final String id;
        final String trackId;
        final String trackName;
        final long start;
        final long end;
        final String partId;
        final String partName;
        final int partIndex;
        final boolean foreign;
        final boolean mirrored;
        /** For an audio clip (work package B3): where its fades end and begin, in ticks, and its gain. */
        boolean audio;
        long fadeInEnd;
        long fadeOutStart;
        double gainDb;

        ClipBox(String id, String trackId, String trackName, long start, long end, String partId, String partName,
                int partIndex, boolean foreign, boolean mirrored) {
            this.id = id;
            this.trackId = trackId;
            this.trackName = trackName;
            this.start = start;
            this.end = end;
            this.partId = partId;
            this.partName = partName;
            this.partIndex = partIndex;
            this.foreign = foreign;
            this.mirrored = mirrored;
        }

        static ClipBox of(Composition piece, Track track, com.aresstack.audio.composition.AudioClip clip) {
            com.aresstack.audio.composition.TimeMap time = piece.timeMap();
            com.aresstack.audio.composition.Asset asset = piece.asset(clip.assetId());
            double start = time.seconds(clip.startTick());
            double rate = com.aresstack.audio.composition.AudioClip.SAMPLE_RATE;
            ClipBox box = new ClipBox(clip.id(), track.id(), track.name(), clip.startTick(), clip.endTick(time), null,
                    asset == null ? clip.assetId() : asset.name(), piece.tracks().indexOf(track), false, false);
            box.audio = true;
            box.fadeInEnd = time.tickAt(start + clip.fadeInSamples() / rate);
            box.fadeOutStart = time.tickAt(start + (clip.lengthSamples() - clip.fadeOutSamples()) / rate);
            box.gainDb = clip.gainDb();
            return box;
        }

        static ClipBox of(Composition piece, Track track, MidiClip clip, boolean mirrored) {
            String partId = track.partOf(clip);
            com.aresstack.audio.composition.Part part = partId == null ? null : piece.part(partId);
            int index = part == null ? 0 : piece.parts().indexOf(part);
            return new ClipBox(clip.id(), track.id(), track.name(), clip.startTick(), clip.endTick(), partId,
                    part == null ? "?" : part.name(), index,
                    partId != null && !partId.equals(track.defaultPartId()), mirrored);
        }
    }

    /** What a click asks for. */
    interface Listener {
        /** Jump to this second of the playback. */
        void seek(double second);

        /** A passage was marked (or the mark was taken away: {@code null}). */
        default void marked(Range range) {
        }

        /**
         * The editor changed the piece, or tried to: what to tell the user, and whether the piece
         * is different now (then the lanes, the score and the stems follow).
         */
        default void edited(String said, boolean changed) {
        }
    }

    /**
     * A marked passage: the tracks and the bars it covers.
     *
     * @param firstBar the first bar, counted from 1
     * @param lastBar  the last bar, counted from 1
     */
    record Range(List<String> trackIds, int firstBar, int lastBar) {
        Range {
            trackIds = List.copyOf(trackIds);
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
    private Range marked;
    /** Where a mark began, in pixels; {@code null} while none is drawn. */
    private java.awt.Point markFrom;
    /** Whether the mark began in the ruler: then it covers every track. */
    private boolean markAllTracks;
    /** The menu of the marked passage, shown where the note editor has none of its own. */
    private javax.swing.JPopupMenu passageMenu;
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
                if (javax.swing.SwingUtilities.isRightMouseButton(event) || event.isPopupTrigger()) {
                    if (!editing(event) && passageMenu != null) {
                        passageMenu.show(ArrangementView.this, event.getX(), event.getY());
                    }
                    return; // in the lanes of a piece that can be edited the note editor shows its menu
                }
                if (event.isShiftDown() && !editing(event)) {
                    // Shift and drag marks a passage. Where notes can be edited, Shift in the lanes is
                    // the editor's (it adds to the selection): there a passage is marked in the ruler,
                    // across every track.
                    markFrom = event.getPoint();
                    markAllTracks = event.getY() < RULER;
                    markTo(event);
                    return;
                }
                markFrom = null;
                if (!editing(event)) {
                    if (marked != null) {
                        mark(null);
                    }
                    jump(event);
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (markFrom != null) {
                    markTo(event);
                } else if (event.getX() >= GUTTER && !javax.swing.SwingUtilities.isRightMouseButton(event)
                        && !editing(event)) {
                    jump(event);
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                markFrom = null;
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

    /** The number of bars the lanes show; 0 without an arrangement. */
    int bars() {
        return arrangement == null ? 0 : arrangement.bars();
    }

    /** The marked passage, or {@code null}. */
    Range marked() {
        return marked;
    }

    /**
     * Marks the passage between two points of the view: the bars from one to the other, and the
     * tracks whose lanes lie between them. A point above the lanes, in the ruler, means the first
     * track; one below them the last.
     */
    void mark(int x1, int y1, int x2, int y2) {
        if (arrangement == null || order.isEmpty()) {
            mark(null);
            return;
        }
        int bars = arrangement.bars();
        int firstBar = barAt(Math.min(x1, x2), bars);
        int lastBar = barAt(Math.max(x1, x2), bars);
        int firstLane = laneNear(Math.min(y1, y2));
        int lastLane = laneNear(Math.max(y1, y2));
        mark(new Range(order.subList(firstLane, lastLane + 1), firstBar, lastBar));
    }

    /** Draws the mark on to where the pointer is: from the ruler across every track, else across the lanes passed. */
    private void markTo(MouseEvent event) {
        if (markAllTracks) {
            mark(markFrom.x, RULER, event.getX(), RULER + Math.max(1, order.size()) * lane - 1);
        } else {
            mark(markFrom.x, markFrom.y, event.getX(), event.getY());
        }
    }

    /** The note editor of the lanes, or {@code null} before the window handed over the way into the piece. */
    LaneEditor editor() {
        return editor;
    }

    /** The menu of the marked passage; the view shows it where the note editor shows none. */
    void setPassageMenu(javax.swing.JPopupMenu menu) {
        passageMenu = menu;
    }

    /** Whether Shift and drag at this height marks a passage: not where the note editor takes Shift for its selection. */
    boolean marksAt(int y) {
        return editor == null || !editor.owns(y);
    }

    void mark(Range range) {
        if (range == null ? marked != null : !range.equals(marked)) {
            marked = range;
            listener.marked(range);
            repaint();
        }
    }

    /** The bar under a place across the view, counted from 1. */
    private int barAt(int x, int bars) {
        double total = totalSeconds();
        int span = Math.max(1, getWidth() - GUTTER - 10);
        double musicSpan = total <= 0 ? span : span * arrangement.seconds() / total;
        int bar = (int) Math.floor((x - GUTTER) / Math.max(1.0, musicSpan) * bars);
        return Math.max(0, Math.min(bars - 1, bar)) + 1;
    }

    /** Where a bar begins across the view, bars counted from 0; the end of the last bar is the end of the music. */
    float xOfBar(int bar) {
        return arrangement == null ? GUTTER
                : GUTTER + musicSpan() * Math.max(0, bar) / Math.max(1, arrangement.bars());
    }

    /** The lane at a height, or the nearest one for a place above or below the lanes. */
    private int laneNear(int y) {
        return Math.max(0, Math.min(order.size() - 1, (y - RULER) / Math.max(1, lane)));
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
        if (marked != null && (shown == null || !new ArrayList<String>(trackIds).containsAll(marked.trackIds())
                || marked.lastBar() > shown.bars())) {
            mark(null); // the passage is not there any more
        }
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
            Map<String, Color> clipColors = paintClips(g, index, id);
            List<Note> notes = arrangement == null ? null : arrangement.lanes.get(id);
            if (notes == null || notes.isEmpty()) {
                continue;
            }
            Color shade = chosen ? color : AudioStudioTheme.withAlpha(color, 215);
            for (Note note : notes) {
                boolean picked = editor != null && editor.selected(note);
                Color own = note.clipId == null ? null : clipColors.get(note.clipId);
                g.setColor(picked ? Color.WHITE : own != null ? AudioStudioTheme.withAlpha(own, 215) : shade);
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

        // The marked passage: its bars across the lanes of its tracks.
        if (marked != null && arrangement != null && !marked.trackIds().isEmpty()) {
            int bars = arrangement.bars();
            int first = order.indexOf(marked.trackIds().get(0));
            int last = order.indexOf(marked.trackIds().get(marked.trackIds().size() - 1));
            if (first >= 0 && last >= first) {
                float from = left + musicSpan * (marked.firstBar() - 1) / bars;
                float to = left + musicSpan * marked.lastBar() / bars;
                Rectangle2D area = new Rectangle2D.Float(from, RULER + first * LANE, to - from, (last - first + 1) * LANE);
                g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 46));
                g.fill(area);
                g.setColor(AudioStudioTheme.ACCENT);
                g.setStroke(new BasicStroke(1.4f));
                g.draw(area);
                g.fill(new Rectangle2D.Float(from, RULER - 4, to - from, 3));
            }
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

    /** The color of a part: the color of its head if it has one, else by its place among the parts. */
    Color partColor(String partId, int partIndex) {
        int at = order.indexOf(partId);
        return AudioStudioTheme.channel(at >= 0 ? at : partIndex);
    }

    /**
     * The clips of a lane as boxes behind the notes, with the clip band on top. A clip played by
     * another part than the lane's own carries that part's color and name; a mirrored one (in the
     * row of a part without a lane) is dashed and says on which lane it lies. Returns the color of
     * each clip whose notes take the color of another part.
     */
    private Map<String, Color> paintClips(Graphics2D g, int index, String laneId) {
        Map<String, Color> colors = new LinkedHashMap<String, Color>();
        List<ClipBox> boxes = arrangement == null ? null : arrangement.clips.get(laneId);
        if (boxes == null || boxes.isEmpty()) {
            return colors;
        }
        int top = laneTop(index);
        boolean several = boxes.size() > 1;
        g.setFont(AudioStudioTheme.lettering(9f));
        FontMetrics letters = g.getFontMetrics();
        for (ClipBox box : boxes) {
            Color color = partColor(box.partId, box.partIndex);
            if (box.foreign) {
                colors.put(box.id, color);
            }
            float x = xOf(box.start);
            float w = Math.max(3f, xOf(box.end) - x - 1f);
            boolean picked = editor != null && editor.selectedClip(box.id);
            RoundRectangle2D.Float body = new RoundRectangle2D.Float(x, top + 2f, w, lane - 4f, 5f, 5f);
            g.setColor(AudioStudioTheme.withAlpha(color, picked ? 44 : box.foreign || several ? 22 : 12));
            g.fill(body);
            g.setColor(AudioStudioTheme.withAlpha(color, picked ? 120 : 60));
            g.fill(new RoundRectangle2D.Float(x, top + 2f, w, CLIP_BAND, 5f, 5f));
            if (box.mirrored) {
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 3f}, 0f));
            } else {
                g.setStroke(new BasicStroke(picked ? 1.6f : 1f));
            }
            g.setColor(picked ? Color.WHITE : AudioStudioTheme.withAlpha(color, 150));
            g.draw(body);
            if (box.audio) {
                paintAudio(g, box, x, w, top, color);
            }
            String label = box.audio ? box.partName + (box.gainDb == 0 ? ""
                    : String.format(java.util.Locale.ROOT, " · %+.1f dB", box.gainDb))
                    : box.mirrored ? box.partName + " · auf " + box.trackName
                    : box.foreign ? box.partName : several ? bars(box) : "";
            if (!label.isEmpty() && w > 24) {
                g.setColor(picked ? Color.WHITE : AudioStudioTheme.TEXT);
                g.drawString(fit(letters, label.toUpperCase(java.util.Locale.ROOT), (int) w - 8), x + 5,
                        top + 2f + CLIP_BAND - 3f);
            }
        }
        g.setStroke(new BasicStroke(1f));
        return colors;
    }

    /** An audio clip: a flat body with its fades drawn as slopes, and the handles to drag them at the band. */
    private void paintAudio(Graphics2D g, ClipBox box, float x, float w, int top, Color color) {
        float bottom = top + lane - 3f;
        float high = top + 2f + CLIP_BAND + 4f;
        float in = Math.min(x + w, xOf(box.fadeInEnd));
        float out = Math.max(x, xOf(box.fadeOutStart));
        java.awt.geom.Path2D.Float shape = new java.awt.geom.Path2D.Float();
        shape.moveTo(x, bottom);
        shape.lineTo(in, high);
        shape.lineTo(out, high);
        shape.lineTo(x + w, bottom);
        shape.closePath();
        g.setColor(AudioStudioTheme.withAlpha(color, 70));
        g.fill(shape);
        g.setColor(AudioStudioTheme.withAlpha(color, 200));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Line2D.Float(x, bottom, in, high));
        g.draw(new Line2D.Float(out, high, x + w, bottom));
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Float(in - 2.5f, top + 2f + CLIP_BAND - 1f, 5f, 5f));
        g.fill(new Rectangle2D.Float(out - 2.5f, top + 2f + CLIP_BAND - 1f, 5f, 5f));
    }

    /** The bars a clip covers, as the user counts them. */
    private String bars(ClipBox box) {
        long bar = Math.max(1, arrangement.barTicks);
        long first = box.start / bar + 1;
        long last = Math.max(first, (box.end - 1) / bar + 1);
        return first == last ? "T. " + first : "T. " + first + "–" + last;
    }

    /** The clip of a lane at a tick, or {@code null}. */
    ClipBox clipAt(int index, long tick) {
        List<ClipBox> boxes = arrangement == null || index < 0 ? null : arrangement.clips.get(order.get(index));
        if (boxes != null) {
            for (ClipBox box : boxes) {
                if (tick >= box.start && tick < box.end) {
                    return box;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Where things are, for painting and for the editor

    Arrangement arrangement() {
        return arrangement;
    }

    List<String> order() {
        return order;
    }

    /**
     * The rows that hold notes, in their order: every row but the audio lanes. A selection of bars
     * across the rows (a passage to replace or vary) takes these, since an audio lane has no notes.
     */
    public List<String> noteLanes() {
        List<String> lanes = new ArrayList<String>();
        for (String id : order) {
            if (!isAudioLane(id)) {
                lanes.add(id);
            }
        }
        return lanes;
    }

    /** Whether a row is an audio lane of the model. */
    public boolean isAudioLane(String id) {
        return arrangement != null && arrangement.audioLanes.contains(id);
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
        return noteTop(index) + usable() * (1f - (pitch - window[0]) / (float) window[1]);
    }

    /** The pitch at a height within a lane. */
    int pitchAt(int index, int y) {
        int[] window = pitchWindow(order.get(index));
        float share = 1f - (y - noteTop(index) - noteThickness(index) / 2f) / usable();
        return Math.max(0, Math.min(127, Math.round(window[0] + share * window[1])));
    }

    /** How many pixels one semitone is in a lane. */
    float semitone(int index) {
        return usable() / pitchWindow(order.get(index))[1];
    }

    float noteThickness(int index) {
        int[] window = pitchWindow(order.get(index));
        return Math.max(3.4f, Math.min(7f, usable() / (window[1] + 1f)));
    }

    /** How high the band at the top of a lane is, where clips are grabbed; none without a model. */
    int clipBand() {
        return arrangement != null && arrangement.editable ? CLIP_BAND : 0;
    }

    private float noteTop(int index) {
        return laneTop(index) + 5f + clipBand();
    }

    private float usable() {
        return lane - 14f - clipBand();
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
