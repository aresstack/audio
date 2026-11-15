package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.settings.StudioSettingsStore;
import com.aresstack.audio.studio.visual.Effect;
import com.aresstack.audio.studio.visual.Effects;
import com.aresstack.audio.studio.visual.SoundAnalyzer;
import com.aresstack.audio.studio.visual.SoundFeed;
import com.aresstack.audio.studio.visual.SoundPicture;
import com.aresstack.audio.studio.visual.VisualPalette;
import com.aresstack.audio.studio.visual.VisualSettings;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The visualizer on the player page: a picture of the sound that is heard right now, or the score
 * to read along. The keys in the head of the page choose what is shown; a double-click makes it
 * fill the screen, Escape or another double-click brings it back.
 *
 * <p>The sound comes straight from the playback chain ({@link StageDeck#feed()}): the frames the
 * listener hears, and on a live stage every track with its place and level. All numbers are in
 * {@link VisualSettings}, kept with the settings of the studio.
 */
final class VisualizerPanel extends JPanel {
    /** The key of the score among the effects: not drawn by an effect, but the score view itself. */
    static final String SCORE = "score";
    private static final String PICTURE = "picture";

    private final SoundFeed feed;
    private final StudioSettingsStore store;
    private VisualSettings settings;
    private SoundAnalyzer analyzer;
    private final List<Effect> effects = Effects.all();
    private Effect effect;
    /** Read by the render thread of the graphics card too. */
    private volatile String shown;
    private final VisualPalette palette = new VisualPalette(new Color(0x0A0B0F), AudioStudioTheme.TEXT,
            AudioStudioTheme.TEXT_FAINT, AudioStudioTheme.ACCENT, AudioStudioTheme.GREEN, AudioStudioTheme.AMBER,
            AudioStudioTheme.RED, AudioStudioTheme.CYAN, AudioStudioTheme::channel);

    private final CardLayout cards = new CardLayout();
    /** What is shown, the picture or the score; moved into the full screen and back. */
    private final JPanel views = new JPanel(cards);
    private final Canvas canvas = new Canvas();
    private final ScoreView score = new ScoreView();
    private final JScrollPane scoreScroll = new JScrollPane(score);
    private final Timer timer;
    private final Map<String, DeckWidgets.SegmentKey> keys = new LinkedHashMap<String, DeckWidgets.SegmentKey>();
    private final JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private JFrame fullScreen;
    /** The full screen on the graphics card while it is open. */
    private com.aresstack.audio.gpu.GpuScreen gpu;
    private boolean gpuOpening;
    /** Why the graphics card could not be used in this run; then the full screen is drawn with Java2D. */
    private String gpuFailed;
    /** A line shown over the picture for a moment: which device draws, or why not the graphics card. */
    private String notice;
    private long noticeUntil;
    /** How long a notice stays over the picture. */
    static final long NOTICE_MILLIS = 6000;

    /**
     * @param feed  where the heard sound comes from
     * @param sheet the score of the piece, whose score, sections and play line this one follows;
     *              {@code null} leaves the score empty
     * @param store where the settings of the studio are kept
     */
    VisualizerPanel(SoundFeed feed, ScoreView sheet, StudioSettingsStore store) {
        super(new BorderLayout());
        this.feed = feed == null ? SoundFeed.SILENT : feed;
        this.store = store;
        this.settings = VisualSettings.from(load(store));
        setOpaque(true);
        setBackground(palette.background());
        scoreScroll.setBorder(null);
        scoreScroll.getViewport().setBackground(AudioStudioTheme.WELL);
        views.add(canvas, PICTURE);
        views.add(scoreScroll, SCORE);
        add(views, BorderLayout.CENTER);
        if (sheet != null) {
            sheet.follow(score);
        }
        MouseAdapter doubleClick = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    toggleFullScreen();
                }
            }
        };
        canvas.addMouseListener(doubleClick);
        score.addMouseListener(doubleClick);
        canvas.setToolTipText("Doppelklick: Vollbild, Escape: zurück");

        head.setOpaque(false);
        ButtonGroup one = new ButtonGroup();
        boolean first = true;
        for (Effect each : effects) {
            addKey(one, each.key(), each.label(), each.tip(), first);
            first = false;
        }
        addKey(one, SCORE, "Partitur", "Die Partitur zum Mitlesen, die Stelle läuft mit", false);
        DeckWidgets.HeadKey wide = new DeckWidgets.HeadKey("Vollbild");
        wide.setToolTipText("Füllt den Bildschirm; Escape oder Doppelklick bringt die Seite zurück");
        wide.addActionListener(event -> toggleFullScreen());
        JPanel gap = new JPanel();
        gap.setOpaque(false);
        gap.setPreferredSize(new Dimension(8, 1));
        head.add(gap);
        head.add(wide);

        timer = new Timer(Math.max(1, 1000 / settings.framesPerSecond()), event -> tick());
        timer.setCoalesce(true);
        show(settings.effect());
    }

    private void addKey(ButtonGroup group, String key, String label, String tip, boolean first) {
        DeckWidgets.SegmentKey button = new DeckWidgets.SegmentKey(label, first);
        button.setToolTipText(tip);
        button.addActionListener(event -> choose(key));
        group.add(button);
        keys.put(key, button);
        head.add(button);
    }

    /** The keys for the head of the page: one per effect, the score, and the full screen. */
    JComponent headKeys() {
        return head;
    }

    /** The settings of the visualizer as they stand, for the settings file. */
    VisualSettings settings() {
        return settings;
    }

    /** What is shown now: the key of an effect, or {@link #SCORE}. */
    String shown() {
        return shown;
    }

    /** Shows an effect or the score and keeps the choice in the settings. */
    void choose(String key) {
        show(key);
        if (gpu != null && !com.aresstack.audio.studio.visual.GpuFill.drawsOnGpu(shown)) {
            // The graphics card does not draw this one: the full screen goes on with Java2D.
            gpu.close();
            gpu = null;
            openFullScreen();
        }
        settings = settings.showing(shown);
        save();
    }

    private void show(String key) {
        shown = SCORE.equals(key) ? SCORE : Effects.find(effects, key).key();
        if (!SCORE.equals(shown)) {
            effect = Effects.find(effects, shown);
            canvas.forget();
        }
        DeckWidgets.SegmentKey button = keys.get(shown);
        if (button != null) {
            button.setSelected(true);
        }
        cards.show(views, SCORE.equals(shown) ? SCORE : PICTURE);
    }

    /** Whether the visualizer fills the screen now. */
    boolean fullScreen() {
        return fullScreen != null;
    }

    /**
     * Fills the screen with what is shown, or brings it back onto the page. An effect the graphics
     * card draws ({@link com.aresstack.audio.studio.visual.GpuFill#drawsOnGpu}) opens there if the
     * settings want it; where that fails, the full screen is drawn with Java2D, as the others are.
     */
    void toggleFullScreen() {
        if (gpu != null) {
            gpu.close();
            return;
        }
        if (fullScreen != null) {
            leaveFullScreen();
            return;
        }
        if (gpuOpening) {
            return;
        }
        if (settings.gpu() && gpuFailed == null && com.aresstack.audio.studio.visual.GpuFill.drawsOnGpu(shown)) {
            openOnGpu();
            return;
        }
        openFullScreen();
    }

    /** Opens the full screen on the graphics card off the event thread; falls back to Java2D. */
    private void openOnGpu() {
        gpuOpening = true;
        final VisualSettings now = settings;
        Thread opening = new Thread(() -> {
            try {
                com.aresstack.audio.gpu.GpuScreen screen = com.aresstack.audio.gpu.GpuScreen.open(
                        new com.aresstack.audio.gpu.GpuScreen.Settings("Audio Studio – Visualizer", now.bands(),
                                now.gpuVsync(), now.doubleClickMs(), now.gpuOpenSeconds(), now.gpuBackend()),
                        new com.aresstack.audio.studio.visual.GpuFill(feed, now, () -> shown),
                        () -> SwingUtilities.invokeLater(this::gpuClosed));
                SwingUtilities.invokeLater(() -> {
                    gpuOpening = false;
                    if (!com.aresstack.audio.studio.visual.GpuFill.drawsOnGpu(shown)) {
                        // Another effect was chosen while the card opened, one it does not draw.
                        screen.close();
                        openFullScreen();
                    } else if (screen.open()) {
                        gpu = screen;
                        notice("Vollbild auf der Grafikkarte: " + screen.description());
                    }
                });
            } catch (com.aresstack.audio.gpu.GpuUnavailableException unavailable) {
                SwingUtilities.invokeLater(() -> {
                    gpuOpening = false;
                    gpuFailed = unavailable.getMessage();
                    notice(gpuFailed + " Vollbild mit Java2D.");
                    openFullScreen();
                });
            }
        }, "visualizer-gpu-open");
        opening.setDaemon(true);
        opening.start();
    }

    private void gpuClosed() {
        gpu = null;
        canvas.forget();
    }

    /** Whether the graphics card draws the full screen now. */
    boolean onGpu() {
        return gpu != null;
    }

    private void notice(String text) {
        notice = text;
        noticeUntil = System.currentTimeMillis() + NOTICE_MILLIS;
    }

    /** Fills the screen with a window of Java2D. */
    private void openFullScreen() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        java.awt.GraphicsConfiguration screen = owner != null ? owner.getGraphicsConfiguration()
                : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration();
        JFrame frame = new JFrame(screen);
        frame.setTitle("Audio Studio – Visualizer");
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                leaveFullScreen();
            }
        });
        if (owner != null && owner.getIconImages() != null) {
            frame.setIconImages(owner.getIconImages());
        }
        remove(views);
        frame.getContentPane().setBackground(palette.background());
        frame.getContentPane().add(views, BorderLayout.CENTER);
        JComponent root = frame.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "leave");
        root.getActionMap().put("leave", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                leaveFullScreen();
            }
        });
        Rectangle bounds = screen.getBounds();
        frame.setBounds(bounds);
        fullScreen = frame;
        canvas.forget();
        frame.setVisible(true);
        frame.toFront();
        revalidate();
        repaint();
        timer.start();
    }

    private void leaveFullScreen() {
        JFrame frame = fullScreen;
        if (frame == null) {
            return;
        }
        fullScreen = null;
        frame.getContentPane().remove(views);
        frame.dispose();
        add(views, BorderLayout.CENTER);
        canvas.forget();
        revalidate();
        repaint();
        if (!isShowing()) {
            timer.stop();
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
    }

    @Override
    public void removeNotify() {
        if (fullScreen == null) {
            timer.stop();
        }
        super.removeNotify();
    }

    /** One picture: analyse what is heard now and draw it, while the picture can be seen. */
    void tick() {
        if (SCORE.equals(shown) || !(canvas.isShowing())) {
            return;
        }
        int rate = feed.sampleRate();
        if (analyzer == null || analyzer.sampleRate() != rate || analyzer.settings() != settings) {
            analyzer = new SoundAnalyzer(settings, rate);
        }
        SoundPicture picture = analyzer.analyze(feed);
        canvas.draw(effect, picture);
    }

    private void save() {
        if (store == null) {
            return;
        }
        try {
            Properties saved = store.load();
            settings.into(saved);
            store.save(saved);
        } catch (Exception unsaved) {
            // the choice stays for this run; the file may be read-only
        }
    }

    private static Properties load(StudioSettingsStore store) {
        if (store == null) {
            return new Properties();
        }
        try {
            return store.load();
        } catch (Exception unreadable) {
            return new Properties();
        }
    }

    /** The surface of the effects: a picture that keeps what was drawn, for the trails. */
    private final class Canvas extends JComponent {
        private BufferedImage image;

        Canvas() {
            setOpaque(true);
        }

        /** Starts the next picture on an empty ground (another effect, another size). */
        void forget() {
            image = null;
        }

        void draw(Effect drawing, SoundPicture picture) {
            int width = Math.max(1, getWidth());
            int height = Math.max(1, getHeight());
            if (image == null || image.getWidth() != width || image.getHeight() != height) {
                image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D clear = image.createGraphics();
                Effect.clear(clear, width, height, palette.background());
                clear.dispose();
            }
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(AudioStudioTheme.lettering(11f));
            try {
                drawing.paint(g, width, height, picture, palette, settings);
                if (notice != null && System.currentTimeMillis() < noticeUntil) {
                    g.setColor(palette.faint());
                    g.drawString(notice, 10, height - 10);
                }
            } finally {
                g.dispose();
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (image != null) {
                graphics.drawImage(image, 0, 0, null);
            } else {
                graphics.setColor(palette.background());
                graphics.fillRect(0, 0, getWidth(), getHeight());
            }
        }
    }
}
