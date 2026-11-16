package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.studio.application.MovementDirector;
import com.aresstack.audio.studio.application.MusicTrack;
import com.aresstack.audio.studio.application.StageMovements;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * The stage page: the stage from above on the left; on the right the room, the
 * chosen instrument with its sliders, and the keys that have the AI mix or put
 * everything back into its usual seat.
 */
final class StagePanel extends JPanel {
    /** What the page asks of the studio. */
    interface Host {
        /**
         * The stage was changed here.
         *
         * @param settled {@code false} while a slider or a disc is still being moved
         */
        void sceneChanged(SpatialScene scene, boolean settled);

        /**
         * The user drew how a track moves over the bars: its place from left to right, its depth
         * or its share of the room (the level is part of the scene).
         *
         * @param movements all movements as the page shows them now
         * @param settled   {@code false} while a point is still being dragged
         */
        void movementChanged(StageMovements movements, String trackId, String parameter, boolean settled);

        /** The AI shall propose a mix. */
        void askDirector();

        /** The user switched whether the AI also proposes movements with the mix; kept in the settings. */
        default void movementWishChanged(boolean wanted) {
        }

        /**
         * The user took a proposal that came with movements: the stage and the movements are one
         * step, which the host keeps together (stage file and model) and then shows. The page does
         * not change its stage itself for it.
         *
         * @param before the stage as the page shows it
         * @param taken  the stage with the proposal taken
         * @return whether it was taken; if not, the proposal stays on the page
         */
        default boolean takeWithMovements(SpatialScene before, SpatialScene taken, MovementDirector.Proposal moves) {
            return false;
        }

        /** The folder of the piece, where it keeps which figure stands at which track; {@code null} without a piece. */
        default java.nio.file.Path pieceDirectory() {
            return null;
        }

        /** The AI that draws figures, with the model of the settings; {@code null} (and a word why) if there is none. */
        default com.aresstack.audio.studio.figure.FigureArtist figureArtist() {
            return null;
        }
    }

    /** One slider with its name on the left and its value in words on the right. */
    private abstract class Row {
        final JSlider slider;
        final JLabel value = new JLabel(" ");

        Row(JPanel form, int row, String name, int least, int most, String tip) {
            slider = new JSlider(least, most, least);
            slider.setOpaque(false);
            slider.setToolTipText(tip);
            value.setForeground(AudioStudioTheme.TEXT);
            value.setFont(AudioStudioTheme.display(11.5f));
            value.setPreferredSize(new Dimension(86, 18));
            value.setHorizontalAlignment(JLabel.RIGHT);
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row;
            c.gridx = 0;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(3, 0, 3, 8);
            JLabel label = DeckWidgets.lettering(name);
            label.setPreferredSize(new Dimension(74, 18));
            form.add(label, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(3, 0, 3, 0);
            form.add(slider, c);
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.insets = new Insets(3, 6, 3, 0);
            form.add(value, c);
            slider.addChangeListener(event -> {
                value.setText(words(slider.getValue()));
                if (!filling) {
                    moved(slider.getValue(), !slider.getValueIsAdjusting());
                }
            });
        }

        void show(int shown) {
            slider.setValue(shown);
            value.setText(words(slider.getValue()));
        }

        abstract String words(int shown);

        abstract void moved(int shown, boolean settled);
    }

    private final Host host;
    private final StageView view;
    private SpatialScene scene = SpatialScene.standard(new ArrayList<MusicTrack>());
    private SpatialScene proposal;
    /** The movements the AI proposed with {@link #proposal}; none unless the user asked for them. */
    private MovementDirector.Proposal motions = MovementDirector.Proposal.NONE;
    /** Whether "KI mischt ab" also asks for movements; off by default (Angelo, 2026-11-15). */
    private final javax.swing.JCheckBox withMovements = new javax.swing.JCheckBox("Mit Bewegungen");
    private List<String> order = new ArrayList<String>();
    private boolean filling;

    private final JComboBox<AcousticRoom> room = new JComboBox<AcousticRoom>(
            AcousticRoom.PRESETS.toArray(new AcousticRoom[0]));
    private final Row ring;
    private final Row amount;
    private final JLabel chosen = new JLabel("Kein Instrument gewählt");
    private final JLabel where = new JLabel(" ");
    private final Row side;
    private final Row depth;
    private final Row level;
    private final Row width;
    private final Row share;
    private final Row high;
    /** The status line: what was said last, and what the limiter of live playback does. */
    private String said = "";
    private String limiter = "";
    private final JToggleButton mute = new JToggleButton("Stumm");
    private final JToggleButton lock = new JToggleButton("Vor KI schützen");
    private final JLabel course = new JLabel(" ");
    private final Course curve = new Course();
    /** How the instruments move over the bars, beside the level the scene itself holds. */
    private StageMovements movements = StageMovements.NONE;
    /** Which curve the field shows: the level, or one of {@link StageMovements#PARAMETERS}. */
    private String parameter = AutomationLane.LEVEL;
    private final java.util.Map<String, JToggleButton> courseKeys = new java.util.LinkedHashMap<String, JToggleButton>();
    private Row master;
    private final JButton flat = new JButton("Verlauf löschen");
    private final JButton director = new JButton("KI mischt ab");
    private final JButton usual = new JButton("Standardaufstellung");
    private final JButton take = new JButton("Übernehmen");
    private final JButton drop = new JButton("Verwerfen");
    private final JPanel verdict = new JPanel(new GridLayout(1, 2, 8, 0));
    private final JLabel status = new JLabel(" ");

    private DeckWidgets.Module stageModule;
    private final JButton drawFigures = new DeckWidgets.HeadKey("Figuren");
    private final JButton otherFigure = new DeckWidgets.HeadKey("Andere Figur");
    private final JButton newFigure = new DeckWidgets.HeadKey("Neu zeichnen");
    private final JButton undoFigure = new DeckWidgets.HeadKey("↶");
    /** The figures of the musicians of the piece; what they tell comes on their own thread and is shown on this one. */
    private final com.aresstack.audio.studio.figure.StageFigures figures =
            new com.aresstack.audio.studio.figure.StageFigures(
                    com.aresstack.audio.studio.figure.FigureLibrary.standard(),
                    new com.aresstack.audio.studio.figure.StageFigures.Listener() {
                        @Override
                        public void cast(com.aresstack.audio.studio.figure.FigureCast cast) {
                            javax.swing.SwingUtilities.invokeLater(() -> {
                                undoFigure.setEnabled(figures.canUndo());
                                view.repaint();
                            });
                        }

                        @Override
                        public void said(String text) {
                            javax.swing.SwingUtilities.invokeLater(() -> figureWord.setText(text));
                        }

                        @Override
                        public void busy(boolean busy) {
                            javax.swing.SwingUtilities.invokeLater(() -> {
                                drawFigures.setEnabled(!busy);
                                newFigure.setEnabled(!busy);
                            });
                        }
                    });
    /** What the figures say: which one the AI draws, what went wrong. */
    private final JLabel figureWord = new JLabel(" ");

    /** How the figures stand on the stage, from the settings of the studio. */
    void setFigureLook(com.aresstack.audio.studio.figure.FigureSettings look, double bob) {
        view.setFigures(figures::picture, look, bob);
        for (JButton key : new JButton[]{drawFigures, otherFigure, newFigure, undoFigure}) {
            key.setVisible(look.shown());
        }
    }

    /** The figures of the stage, for tests. */
    com.aresstack.audio.studio.figure.StageFigures figures() {
        return figures;
    }
    /** Under the stage: what is heard, and how the discs are moved. */
    private final JPanel stageFoot = new JPanel(new BorderLayout(14, 0));

    /**
     * How the stage is heard belongs to the stage: its switches and keys stand in the head of
     * the stage's module, beside the name, and what came of them in a line under the stage.
     */
    void setListening(javax.swing.JComponent keys, javax.swing.JComponent heard) {
        stageModule.setHeadStart(keys);
        JPanel words = new JPanel(new BorderLayout(14, 0));
        words.setOpaque(false);
        words.add(heard, BorderLayout.WEST);
        figureWord.setForeground(AudioStudioTheme.TEXT_DIM);
        figureWord.setFont(figureWord.getFont().deriveFont(11.5f));
        figureWord.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        words.add(figureWord, BorderLayout.CENTER);
        stageFoot.add(words, BorderLayout.CENTER);
    }

    StagePanel(Host host) {
        super(new BorderLayout(14, 0));
        this.host = host;
        setOpaque(false);
        view = new StageView(new StageView.Listener() {
            @Override
            public void changed(SoundSourcePlacement placement, boolean settled) {
                scene = scene.with(placement);
                showChosen();
                StagePanel.this.host.sceneChanged(scene, settled);
            }

            @Override
            public void selected(String trackId) {
                showChosen();
            }
        });
        JPanel seen = new JPanel(new BorderLayout(0, 6));
        seen.setOpaque(false);
        seen.add(view, BorderLayout.CENTER);
        view.setToolTipText("Scheiben ziehen · Mausrad: lauter und leiser");
        stageFoot.setOpaque(false);
        // At the edge under the stage: the keys of the figures the AI draws for the musicians.
        JPanel figureKeys = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        figureKeys.setOpaque(false);
        drawFigures.setToolTipText("Figuren: jede Spur bekommt eine Figur ihres Instruments; "
                + "aus der Bibliothek, und was fehlt, zeichnet die KI");
        otherFigure.setToolTipText("Andere Figur: der gewählten Spur eine andere Figur aus der Bibliothek geben");
        newFigure.setToolTipText("Neu zeichnen: die KI zeichnet für die gewählte Spur eine neue Figur");
        undoFigure.setToolTipText("Den letzten Schritt an den Figuren zurücknehmen");
        drawFigures.addActionListener(event -> figures.drawMissing(this.host::figureArtist));
        otherFigure.addActionListener(event -> {
            if (view.selected() != null) {
                figures.swap(view.selected());
            }
        });
        newFigure.addActionListener(event -> {
            if (view.selected() != null) {
                figures.redraw(view.selected(), this.host::figureArtist);
            }
        });
        undoFigure.addActionListener(event -> figures.undo());
        undoFigure.setEnabled(false);
        for (JButton key : new JButton[]{drawFigures, otherFigure, newFigure, undoFigure}) {
            figureKeys.add(key);
        }
        stageFoot.add(figureKeys, BorderLayout.EAST);
        view.setFigures(figures::picture, com.aresstack.audio.studio.figure.FigureSettings.DEFAULT,
                com.aresstack.audio.studio.figure.FigureSettings.DEFAULT_BOB);
        seen.add(stageFoot, BorderLayout.SOUTH);
        stageModule = new DeckWidgets.Module("Bühne", seen);
        DeckWidgets.Module stage = stageModule;
        add(stage, BorderLayout.CENTER);

        // The room.
        JPanel roomForm = new JPanel(new GridBagLayout());
        roomForm.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 6, 0);
        roomForm.add(room, c);
        ring = new Row(roomForm, 1, "Nachhall", 2, 60, "Wie lange der Raum nachklingt") {
            @Override
            String words(int shown) {
                return String.format(java.util.Locale.GERMANY, "%.1f s", shown / 10.0);
            }

            @Override
            void moved(int shown, boolean settled) {
                change(scene.inRoom(scene.room().withReverbSeconds(shown / 10.0)), settled);
            }
        };
        amount = new Row(roomForm, 2, "Anteil", 0, 100, "Wie viel vom Raum zu hören ist; 0 ist ganz trocken") {
            @Override
            String words(int shown) {
                return shown + " %";
            }

            @Override
            void moved(int shown, boolean settled) {
                change(scene.inRoom(scene.room().withAmount(shown / 100.0)), settled);
            }
        };
        room.setToolTipText("Der Raum, in dem die Bühne steht");
        room.addActionListener(event -> {
            if (!filling && room.getSelectedItem() != null) {
                change(scene.inRoom((AcousticRoom) room.getSelectedItem()), true);
                showRoom();
            }
        });

        // The chosen instrument.
        chosen.setFont(chosen.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        chosen.setForeground(AudioStudioTheme.TEXT);
        where.setForeground(AudioStudioTheme.TEXT_DIM);
        where.setFont(where.getFont().deriveFont(12f));
        JPanel source = new JPanel(new GridBagLayout());
        source.setOpaque(false);
        side = new Row(source, 0, "Seite", -100, 100, "Wo das Instrument auf der Bühne steht, von links nach rechts") {
            @Override
            String words(int shown) {
                return shown == 0 ? "Mitte" : Math.abs(shown) + (shown < 0 ? " L" : " R");
            }

            @Override
            void moved(int shown, boolean settled) {
                SoundSourcePlacement placement = placement();
                if (placement != null) {
                    put(placement.at(shown / 100.0, placement.depth()), settled);
                }
            }
        };
        depth = new Row(source, 1, "Tiefe", 0, 100, "Wie weit hinten das Instrument steht: hinten ist es leiser, dumpfer und halliger") {
            @Override
            String words(int shown) {
                return String.format(java.util.Locale.GERMANY, "%.1f m", 3 + shown * 0.09);
            }

            @Override
            void moved(int shown, boolean settled) {
                SoundSourcePlacement placement = placement();
                if (placement != null) {
                    put(placement.at(placement.x(), shown / 100.0), settled);
                }
            }
        };
        level = new Row(source, 2, "Pegel", (int) SoundSourcePlacement.MIN_LEVEL, (int) SoundSourcePlacement.MAX_LEVEL,
                "Die Lautstärke der Spur in der Mischung") {
            @Override
            String words(int shown) {
                return StageView.decibels(shown);
            }

            @Override
            void moved(int shown, boolean settled) {
                SoundSourcePlacement placement = placement();
                if (placement != null) {
                    put(placement.withLevel(shown), settled);
                }
            }
        };
        width = new Row(source, 3, "Breite", 0, 100, "0: ein Punkt · 100: so breit, wie das Instrument aufgenommen ist") {
            @Override
            String words(int shown) {
                return shown + " %";
            }

            @Override
            void moved(int shown, boolean settled) {
                SoundSourcePlacement placement = placement();
                if (placement != null) {
                    put(placement.withWidth(shown / 100.0), settled);
                }
            }
        };
        share = new Row(source, 4, "Hall", 0, 100, "Wie viel von diesem Instrument in den Raum geht") {
            @Override
            String words(int shown) {
                return shown + " %";
            }

            @Override
            void moved(int shown, boolean settled) {
                SoundSourcePlacement placement = placement();
                if (placement != null) {
                    put(placement.withReverb(shown / 100.0), settled);
                }
            }
        };
        high = new Row(source, 5, "Höhe", 0, 100,
                "Wie hoch das Instrument über den Ohren steht (0 bis 6 m); auf Kopfhörern (3D) am deutlichsten") {
            @Override
            String words(int shown) {
                return StagePanel.words(AutomationLane.HEIGHT, shown / 100.0);
            }

            @Override
            void moved(int shown, boolean settled) {
                SoundSourcePlacement placement = placement();
                if (placement != null) {
                    put(placement.atHeight(shown / 100.0), settled);
                }
            }
        };
        mute.setToolTipText("Die Spur aus der Mischung nehmen, ohne sie zu löschen");
        lock.setToolTipText("Diese Spur bleibt, wo und wie sie ist, wenn die KI eine Abmischung vorschlägt");
        mute.addActionListener(event -> {
            SoundSourcePlacement placement = placement();
            if (placement != null) {
                put(placement.withMuted(mute.isSelected()), true);
            }
        });
        lock.addActionListener(event -> {
            SoundSourcePlacement placement = placement();
            if (placement != null) {
                put(placement.withLocked(lock.isSelected()), true);
            }
        });
        JPanel flags = new JPanel(new GridLayout(1, 2, 8, 0));
        flags.setOpaque(false);
        flags.add(mute);
        flags.add(lock);
        course.setForeground(AudioStudioTheme.TEXT_DIM);
        course.setFont(course.getFont().deriveFont(12f));
        flat.setToolTipText("Nimmt den gezeigten Verlauf weg: die Spur bleibt darin über das ganze Stück gleich");
        flat.setMargin(new Insets(3, 8, 3, 8));
        flat.addActionListener(event -> curve.set(new ArrayList<StageMovements.Point>(), true));
        // Which curve the field shows: the level, or how the instrument moves on the stage.
        JPanel kinds = new JPanel(new GridLayout(1, 5, 4, 0));
        kinds.setOpaque(false);
        javax.swing.ButtonGroup oneKind = new javax.swing.ButtonGroup();
        String[][] named = {
                {AutomationLane.LEVEL, "Pegel", "Wie laut die Spur über die Takte ist"},
                {AutomationLane.X, "Seite", "Wie die Spur über die Takte von links nach rechts wandert"},
                {AutomationLane.DEPTH, "Tiefe", "Wie die Spur über die Takte nach vorn und hinten wandert"},
                {AutomationLane.REVERB, "Hall", "Wie viel von der Spur über die Takte in den Raum geht"},
                {AutomationLane.HEIGHT, "Höhe", "Wie die Spur über die Takte steigt und sinkt"}};
        for (String[] kind : named) {
            final String chosenKind = kind[0];
            JToggleButton key = new JToggleButton(kind[1], chosenKind.equals(parameter));
            key.setToolTipText(kind[2]);
            key.setMargin(new Insets(2, 4, 2, 4));
            key.setFont(key.getFont().deriveFont(11.5f));
            key.addActionListener(event -> {
                parameter = chosenKind;
                showChosen();
            });
            oneKind.add(key);
            kinds.add(key);
            courseKeys.put(chosenKind, key);
        }
        JPanel courseRow = new JPanel(new BorderLayout(8, 0));
        courseRow.setOpaque(false);
        courseRow.add(course, BorderLayout.CENTER);
        courseRow.add(flat, BorderLayout.EAST);
        JPanel sourceBox = column(chosen, where, Box.createVerticalStrut(6), source, Box.createVerticalStrut(6),
                flags, Box.createVerticalStrut(8), kinds, Box.createVerticalStrut(4), courseRow,
                Box.createVerticalStrut(4), curve);

        // Mixing.
        director.setFont(director.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        director.setPreferredSize(new Dimension(100, 40));
        director.setToolTipText("Die KI bekommt gesagt, was jede Spur spielt, und schlägt Lautstärken, deren Verlauf, "
                + "Plätze und den Raum vor");
        director.addActionListener(event -> this.host.askDirector());
        withMovements.setOpaque(false);
        withMovements.setToolTipText("<html>Aus: die KI stellt jede Spur fest auf ihren Platz und schlägt nur den Pegelverlauf vor.<br>"
                + "An: danach fragt eine eigene Anfrage, ob sich einzelne Spuren über die Takte bewegen sollen<br>"
                + "(Seite, Tiefe, Hall, Höhe). Geschützte Spuren bewegen sich nicht. Auch das ist nur ein Vorschlag.</html>");
        withMovements.addActionListener(event -> this.host.movementWishChanged(withMovements.isSelected()));
        usual.setToolTipText("Alle Instrumente, die nicht geschützt sind, auf den üblichen Platz ihrer Rolle setzen");
        usual.addActionListener(event -> {
            List<MusicTrack> tracks = new ArrayList<MusicTrack>();
            for (MusicTrack track : roles) {
                tracks.add(track);
            }
            change(scene.taking(new SpatialScene(scene.room(), null).fitting(tracks)), true);
        });
        take.setToolTipText("Den Vorschlag der KI übernehmen, mit seinen Bewegungen; geschützte Instrumente bleiben");
        drop.setToolTipText("Den Vorschlag fallen lassen; die Bühne bleibt, wie sie ist");
        take.addActionListener(event -> {
            if (proposal != null) {
                SpatialScene taken = scene.taking(proposal);
                if (!motions.empty()) {
                    // One proposal, one step: the host keeps stage and movements together and shows them.
                    if (host.takeWithMovements(scene, taken, motions)) {
                        setProposal(null);
                    }
                    return;
                }
                setProposal(null);
                change(taken, true);
            }
        });
        drop.addActionListener(event -> setProposal(null));
        verdict.setOpaque(false);
        verdict.add(take);
        verdict.add(drop);
        verdict.setVisible(false);
        status.setForeground(AudioStudioTheme.TEXT_DIM);
        status.setFont(status.getFont().deriveFont(12f));
        JPanel masterForm = new JPanel(new GridBagLayout());
        masterForm.setOpaque(false);
        master = new Row(masterForm, 0, "Master", (int) SpatialScene.MIN_MASTER, 0,
                "Die Lautstärke der fertigen Mischung; 0 dB ist voll ausgesteuert") {
            @Override
            String words(int shown) {
                return StageView.decibels(shown);
            }

            @Override
            void moved(int shown, boolean settled) {
                change(scene.withMaster(shown), settled);
            }
        };
        JPanel mixing = column(masterForm, Box.createVerticalStrut(8), director, Box.createVerticalStrut(4),
                withMovements, Box.createVerticalStrut(8), verdict,
                Box.createVerticalStrut(8), usual, Box.createVerticalStrut(10), status);

        JPanel modules = new JPanel();
        modules.setOpaque(false);
        modules.setLayout(new BoxLayout(modules, BoxLayout.Y_AXIS));
        modules.add(new DeckWidgets.Module("Instrument", sourceBox));
        modules.add(Box.createVerticalStrut(14));
        modules.add(new DeckWidgets.Module("Raum", roomForm));
        modules.add(Box.createVerticalStrut(14));
        modules.add(new DeckWidgets.Module("Abmischung", mixing));
        JPanel top = new Column();
        top.add(modules, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(top, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.setPreferredSize(new Dimension(372, 100));
        add(scroll, BorderLayout.EAST);
        showRoom();
        showChosen();
    }

    private List<MusicTrack> roles = new ArrayList<MusicTrack>();

    /** The column of modules: as wide as the place it gets, scrolling only up and down. */
    private static final class Column extends JPanel implements javax.swing.Scrollable {
        Column() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return visible.height - 24;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static JPanel column(java.awt.Component... parts) {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        for (java.awt.Component part : parts) {
            if (part instanceof javax.swing.JComponent) {
                ((javax.swing.JComponent) part).setAlignmentX(0f);
                part.setMaximumSize(new Dimension(Integer.MAX_VALUE, part.getPreferredSize().height));
            }
            column.add(part);
        }
        return column;
    }

    /** How many bars the piece has: the course of the level is drawn over them. */
    void setBars(int count) {
        bars = Math.max(1, count);
        curve.repaint();
    }

    private int bars = 8;

    /**
     * The course of one parameter of a track over the bars, to be set by hand: its level, its
     * place from left to right, its depth or its share of the room. A click in the field sets a
     * point, a point is dragged to its place, and the right mouse button takes it away. Between
     * the points the value moves in a straight line; before the first and after the last it stays.
     *
     * <p>The level is part of the placement ({@code dynamics}); the others are the movements of
     * the mix document. The dashed line is where the track stands when nothing moves.
     */
    private final class Course extends javax.swing.JComponent {
        private SoundSourcePlacement shown;
        private int held = -1;

        Course() {
            setPreferredSize(new Dimension(100, 78));
            setMinimumSize(new Dimension(100, 78));
            java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    if (shown == null || !editable()) {
                        return;
                    }
                    List<StageMovements.Point> points = new ArrayList<StageMovements.Point>(points());
                    int near = near(event.getX(), event.getY());
                    if (javax.swing.SwingUtilities.isRightMouseButton(event)) {
                        if (near >= 0) {
                            points.remove(near);
                            set(points, true);
                        }
                        return;
                    }
                    if (near < 0) {
                        if (points.size() >= SoundSourcePlacement.MAX_POINTS) {
                            return;
                        }
                        StageMovements.Point added = point(event.getX(), event.getY());
                        points.add(added);
                        set(points, false);
                        near = points().indexOf(added);
                    }
                    held = near;
                }

                @Override
                public void mouseDragged(java.awt.event.MouseEvent event) {
                    move(event, false);
                }

                @Override
                public void mouseReleased(java.awt.event.MouseEvent event) {
                    if (held >= 0) {
                        move(event, true);
                        held = -1;
                    }
                }

                private void move(java.awt.event.MouseEvent event, boolean settled) {
                    if (shown == null || held < 0 || held >= points().size()) {
                        return;
                    }
                    List<StageMovements.Point> points = new ArrayList<StageMovements.Point>(points());
                    StageMovements.Point moved = point(event.getX(), event.getY());
                    points.set(held, moved);
                    set(points, settled);
                    held = points().indexOf(moved); // it may have passed a neighbour
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        void show(SoundSourcePlacement placement) {
            shown = placement;
            setToolTipText(!editable() && placement != null
                    ? "Bewegungen auf der Bühne gehören zum Werkmodell des Stücks: erst komponieren, dann lassen sie sich zeichnen"
                    : "Verlauf über die Takte: klicken setzt einen Punkt, ziehen verschiebt, Rechtsklick entfernt");
            repaint();
        }

        /** Whether the shown curve can be drawn: the level always, a movement only with a mix document. */
        boolean editable() {
            return shown != null && (level() || movements.editable(shown.trackId()));
        }

        private boolean level() {
            return AutomationLane.LEVEL.equals(parameter);
        }

        /** The points of the shown curve, in order. */
        List<StageMovements.Point> points() {
            if (shown == null) {
                return new ArrayList<StageMovements.Point>();
            }
            if (!level()) {
                return movements.of(shown.trackId(), parameter);
            }
            List<StageMovements.Point> points = new ArrayList<StageMovements.Point>();
            for (SoundSourcePlacement.LevelPoint point : shown.dynamics()) {
                points.add(new StageMovements.Point(point.bar(), point.db()));
            }
            return points;
        }

        /** Another curve for the shown parameter; without points the track stays as it stands. */
        void set(List<StageMovements.Point> points, boolean settled) {
            if (shown == null || !editable()) {
                return;
            }
            if (level()) {
                List<SoundSourcePlacement.LevelPoint> dynamics = new ArrayList<SoundSourcePlacement.LevelPoint>();
                for (StageMovements.Point point : points) {
                    dynamics.add(new SoundSourcePlacement.LevelPoint(point.bar(), point.value()));
                }
                put(shown.withDynamics(dynamics.isEmpty() ? null : dynamics), settled);
                return;
            }
            String trackId = shown.trackId();
            movements = movements.with(trackId, parameter, points);
            showChosen();
            host.movementChanged(movements, trackId, parameter, settled);
        }

        /** The highest value of the field. */
        private double top() {
            return level() ? 6 : 1;
        }

        /** The lowest value of the field. */
        private double bottom() {
            return level() ? -18 : AutomationLane.X.equals(parameter) ? -1 : 0;
        }

        /** Where the track stands in the shown parameter when nothing moves. */
        private double still() {
            return level() || shown == null ? 0 : AutomationLane.X.equals(parameter) ? shown.x()
                    : AutomationLane.DEPTH.equals(parameter) ? shown.depth()
                    : AutomationLane.HEIGHT.equals(parameter) ? shown.height() : shown.reverb();
        }

        private float x(double bar) {
            return 6 + (float) ((getWidth() - 12) * Math.min(1, bar / bars));
        }

        private float y(double value) {
            double top = top();
            double bottom = bottom();
            return 5 + (float) ((getHeight() - 10) * (top - Math.max(bottom, Math.min(top, value))) / (top - bottom));
        }

        /** The point under the mouse: on a quarter of a bar, on a whole dB or a twentieth of the way. */
        private StageMovements.Point point(int px, int py) {
            double top = top();
            double bottom = bottom();
            double bar = Math.max(0, Math.min(bars, (px - 6) / (double) Math.max(1, getWidth() - 12) * bars));
            double value = Math.max(bottom, Math.min(top,
                    top - (py - 5) / (double) Math.max(1, getHeight() - 10) * (top - bottom)));
            return new StageMovements.Point(Math.round(bar * 4) / 4.0,
                    level() ? Math.round(value) : Math.round(value * 20) / 20.0);
        }

        private int near(int px, int py) {
            List<StageMovements.Point> points = points();
            for (int i = 0; i < points.size(); i++) {
                if (Math.hypot(x(points.get(i).bar()) - px, y(points.get(i).value()) - py) < 8) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            java.awt.Graphics2D g = DeckWidgets.smooth(graphics);
            java.awt.geom.RoundRectangle2D well = new java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f,
                    getWidth() - 1f, getHeight() - 1f, 10f, 10f);
            g.setColor(AudioStudioTheme.WELL);
            g.fill(well);
            g.setColor(AudioStudioTheme.STROKE);
            g.draw(well);
            // The bars, and the line of where the track stands when nothing moves.
            for (int bar = 0; bar <= bars; bar += bars > 32 ? 4 : bars > 16 ? 2 : 1) {
                g.setColor(new java.awt.Color(255, 255, 255, bar % 4 == 0 ? 30 : 14));
                g.draw(new java.awt.geom.Line2D.Float(x(bar), 4, x(bar), getHeight() - 4));
            }
            double still = still();
            g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.TEXT_FAINT, 170));
            g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER,
                    1f, new float[]{3f, 4f}, 0f));
            g.draw(new java.awt.geom.Line2D.Float(6, y(still), getWidth() - 6, y(still)));
            g.setFont(AudioStudioTheme.lettering(8.5f));
            if (level()) {
                g.drawString("±0", getWidth() - 22, y(0) - 3);
            } else {
                // What the upper and the lower edge of the field mean.
                String[] edges = AutomationLane.X.equals(parameter) ? new String[]{"R", "L"}
                        : AutomationLane.DEPTH.equals(parameter) ? new String[]{"hinten", "vorn"}
                        : new String[]{"100 %", "0 %"};
                java.awt.FontMetrics metrics = g.getFontMetrics();
                g.drawString(edges[0], getWidth() - 8 - metrics.stringWidth(edges[0]), 14);
                g.drawString(edges[1], getWidth() - 8 - metrics.stringWidth(edges[1]), getHeight() - 7);
            }
            if (shown == null) {
                g.dispose();
                return;
            }
            java.awt.Color color = AudioStudioTheme.channel(Math.max(0, order.indexOf(shown.trackId())));
            List<StageMovements.Point> proposed = proposal == null || level() ? List.of()
                    : motions.of(shown.trackId(), parameter);
            if (!proposed.isEmpty()) {
                // What the AI proposes, dashed as the proposal on the stage, under the curve as it is.
                java.awt.geom.Path2D dashed = new java.awt.geom.Path2D.Float();
                dashed.moveTo(6, y(proposed.get(0).value()));
                for (StageMovements.Point point : proposed) {
                    dashed.lineTo(x(point.bar()), y(point.value()));
                }
                dashed.lineTo(getWidth() - 6, y(proposed.get(proposed.size() - 1).value()));
                g.setStroke(new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_BUTT,
                        java.awt.BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 4f}, 0f));
                g.setColor(AudioStudioTheme.withAlpha(color, 200));
                g.draw(dashed);
            }
            List<StageMovements.Point> points = points();
            java.awt.geom.Path2D line = new java.awt.geom.Path2D.Float();
            if (points.isEmpty()) {
                line.moveTo(6, y(still));
                line.lineTo(getWidth() - 6, y(still));
            } else {
                line.moveTo(6, y(points.get(0).value()));
                for (StageMovements.Point point : points) {
                    line.lineTo(x(point.bar()), y(point.value()));
                }
                line.lineTo(getWidth() - 6, y(points.get(points.size() - 1).value()));
            }
            g.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            g.setColor(points.isEmpty() ? AudioStudioTheme.withAlpha(color, editable() ? 110 : 50) : color);
            g.draw(line);
            for (StageMovements.Point point : points) {
                float px = x(point.bar());
                float py = y(point.value());
                g.setColor(AudioStudioTheme.WELL);
                g.fill(new java.awt.geom.Ellipse2D.Float(px - 5, py - 5, 10, 10));
                g.setColor(color);
                g.setStroke(new java.awt.BasicStroke(2f));
                g.draw(new java.awt.geom.Ellipse2D.Float(px - 4, py - 4, 8, 8));
            }
            g.dispose();
        }
    }

    /** A value of a curve in the words of its slider: dB, left and right, metres, per cent. */
    static String words(String parameter, double value) {
        if (AutomationLane.LEVEL.equals(parameter)) {
            return StageView.decibels(value);
        }
        if (AutomationLane.X.equals(parameter)) {
            long side = Math.round(value * 100);
            return side == 0 ? "Mitte" : Math.abs(side) + (side < 0 ? " L" : " R");
        }
        if (AutomationLane.DEPTH.equals(parameter)) {
            return String.format(java.util.Locale.GERMANY, "%.1f m", 3 + value * 9);
        }
        if (AutomationLane.HEIGHT.equals(parameter)) {
            // 0 is the height of the ears, 1 is six metres above them.
            return value == 0 ? "Ohrhöhe" : String.format(java.util.Locale.GERMANY, "+%.1f m", value * 6);
        }
        return Math.round(value * 100) + " %";
    }

    /** What a curve is called on the page. */
    private static String name(String parameter) {
        return AutomationLane.LEVEL.equals(parameter) ? "Pegel" : AutomationLane.X.equals(parameter) ? "Seite"
                : AutomationLane.DEPTH.equals(parameter) ? "Tiefe"
                : AutomationLane.HEIGHT.equals(parameter) ? "Höhe" : "Hall";
    }

    /**
     * How the instruments of the piece move over the bars, as its mix document says: shown and
     * drawn in the field under the sliders, beside the level.
     */
    void setMovements(StageMovements shown) {
        movements = shown == null ? StageMovements.NONE : shown;
        showChosen();
    }

    StageMovements movements() {
        return movements;
    }

    /** Shows the curve of this parameter in the field, as its key does. */
    void showCourse(String wanted) {
        if (courseKeys.containsKey(wanted)) {
            parameter = wanted;
            showChosen();
        }
    }

    /** The field the curves are drawn in. */
    /** The slider of the height of the chosen track (for a test). */
    JSlider heightSlider() {
        return high.slider;
    }

    javax.swing.JComponent courseField() {
        return curve;
    }

    /** Shows the stage of the piece for its tracks, as the studio holds it. */
    void setScene(SpatialScene shown, List<MusicTrack> tracks) {
        scene = shown;
        roles = new ArrayList<MusicTrack>(tracks);
        order = new ArrayList<String>();
        for (MusicTrack track : tracks) {
            order.add(track.id());
        }
        view.setScene(shown, order);
        // The figures of this piece: those it keeps, the others from the library; nothing is drawn by this.
        List<com.aresstack.audio.studio.figure.FigureCast.Seat> seats =
                new ArrayList<com.aresstack.audio.studio.figure.FigureCast.Seat>();
        Map<String, String> playing = new HashMap<String, String>();
        for (MusicTrack track : tracks) {
            seats.add(new com.aresstack.audio.studio.figure.FigureCast.Seat(track.id(), track.instrument()));
            playing.put(track.id(), track.role());
        }
        figures.roles(playing);
        figures.open(host.pieceDirectory(), seats);
        if (view.selected() == null && !order.isEmpty()) {
            view.select(order.get(0));
        }
        showRoom();
        showChosen();
    }

    SpatialScene proposal() {
        return proposal;
    }

    /** Shows what the AI proposes over the stage, with the keys to take or drop it; {@code null} ends that. */
    void setProposal(SpatialScene proposed) {
        setProposal(proposed, MovementDirector.Proposal.NONE);
    }

    /**
     * Shows what the AI proposes over the stage and, if it proposed any, how tracks move: those
     * curves are drawn dashed in the field under the sliders. {@code null} ends the proposal.
     */
    void setProposal(SpatialScene proposed, MovementDirector.Proposal moves) {
        proposal = proposed;
        motions = proposed == null || moves == null ? MovementDirector.Proposal.NONE : moves;
        view.setProposal(proposed);
        verdict.setVisible(proposed != null);
        if (proposed != null) {
            setStatus("Vorschlag der KI: gestrichelt auf der Bühne · " + proposed.room().name()
                    + (motions.empty() ? "" : " · Bewegungen für " + motions.tracks()
                    + (motions.tracks() == 1 ? " Spur" : " Spuren")));
        } else {
            setStatus(" ");
        }
        showChosen();
        revalidate();
    }

    /** The movements proposed with the mix (for a test). */
    MovementDirector.Proposal proposedMovements() {
        return motions;
    }

    /** Whether "KI mischt ab" also asks for movements. */
    boolean movementsWanted() {
        return withMovements.isSelected();
    }

    /** Sets the switch as the settings keep it, without telling the host. */
    void setMovementsWanted(boolean wanted) {
        withMovements.setSelected(wanted);
    }

    /** The switch itself (for a test). */
    javax.swing.JCheckBox movementSwitch() {
        return withMovements;
    }

    void setStatus(String text) {
        said = text == null || text.isBlank() ? "" : text;
        showStatus();
    }

    /**
     * What the limiter of live playback does right now (e.g. "Limiter −2,3 dB"), shown at the
     * end of the status line; an empty text while it rests. Files and exports have no limiter.
     */
    void setLimiter(String text) {
        String now = text == null ? "" : text;
        if (!now.equals(limiter)) {
            limiter = now;
            showStatus();
        }
    }

    /** The status line as it reads now, for tests. */
    String statusLine() {
        return status.getText();
    }

    private void showStatus() {
        String line = said.isEmpty() ? limiter : limiter.isEmpty() ? said : said + " · " + limiter;
        status.setText(line.isEmpty() ? " " : line);
        status.setToolTipText(line.isEmpty() ? null : line);
    }

    void setLevels(Map<String, Float> levels) {
        view.setLevels(levels);
    }

    void select(String trackId) {
        view.select(trackId);
    }

    void setBusy(boolean busy) {
        director.setEnabled(!busy);
    }

    private SoundSourcePlacement placement() {
        return view.selected() == null ? null : scene.placement(view.selected());
    }

    private void put(SoundSourcePlacement placement, boolean settled) {
        change(scene.with(placement), settled);
    }

    private void change(SpatialScene changed, boolean settled) {
        scene = changed;
        view.setScene(changed, order);
        showRoom();
        showChosen();
        host.sceneChanged(changed, settled);
    }

    private void showRoom() {
        filling = true;
        try {
            room.setSelectedItem(AcousticRoom.of(scene.room().kind()));
            ring.show((int) Math.round(scene.room().reverbSeconds() * 10));
            if (master != null) {
                master.show((int) Math.round(scene.masterDb()));
            }
            amount.show((int) Math.round(scene.room().amount() * 100));
        } finally {
            filling = false;
        }
    }

    private void showChosen() {
        SoundSourcePlacement placement = placement();
        filling = true;
        try {
            boolean there = placement != null;
            for (Row row : new Row[]{side, depth, level, width, share, high}) {
                row.slider.setEnabled(there);
            }
            mute.setEnabled(there);
            lock.setEnabled(there);
            if (!there) {
                chosen.setText("Kein Instrument gewählt");
                where.setText("Eine Scheibe auf der Bühne anklicken");
                course.setText(" ");
                flat.setVisible(false);
                curve.show(null);
                for (JToggleButton key : courseKeys.values()) {
                    key.setEnabled(false);
                }
                return;
            }
            int index = order.indexOf(placement.trackId());
            chosen.setText("CH " + (index + 1) + " · " + placement.trackId());
            chosen.setForeground(AudioStudioTheme.channel(Math.max(0, index)));
            where.setText(StageView.words(placement));
            side.show((int) Math.round(placement.x() * 100));
            depth.show((int) Math.round(placement.depth() * 100));
            level.show((int) Math.round(placement.levelDb()));
            width.show((int) Math.round(placement.width() * 100));
            share.show((int) Math.round(placement.reverb() * 100));
            high.show((int) Math.round(placement.height() * 100));
            mute.setSelected(placement.muted());
            lock.setSelected(placement.locked());
            curve.show(placement);
            // The keys of the curves: a dot marks what moves.
            for (java.util.Map.Entry<String, JToggleButton> key : courseKeys.entrySet()) {
                boolean moving = AutomationLane.LEVEL.equals(key.getKey()) ? !placement.dynamics().isEmpty()
                        : !movements.of(placement.trackId(), key.getKey()).isEmpty();
                key.getValue().setEnabled(true);
                boolean proposed = proposal != null && motions.has(placement.trackId(), key.getKey());
                key.getValue().setText(name(key.getKey()) + (moving ? " •" : "") + (proposed ? " ◌" : ""));
                key.getValue().setSelected(key.getKey().equals(parameter));
            }
            List<StageMovements.Point> points = curve.points();
            String named = name(parameter);
            if (proposal != null && !AutomationLane.LEVEL.equals(parameter)
                    && motions.has(placement.trackId(), parameter)) {
                course.setText(named + ": Vorschlag der KI gestrichelt");
            } else if (!curve.editable()) {
                course.setText(named + ": erst komponieren, dann lässt sich die Bewegung zeichnen");
            } else if (points.isEmpty()) {
                course.setText(named + ": gleichbleibend · ins Feld klicken setzt Punkte");
            } else {
                StringBuilder text = new StringBuilder(named + ": ");
                for (int i = 0; i < points.size() && i < 4; i++) {
                    text.append(i == 0 ? "" : " → ").append("T").append((int) Math.floor(points.get(i).bar()) + 1)
                            .append(' ').append(words(parameter, points.get(i).value()).replace(" dB", ""));
                }
                course.setText(text + (points.size() > 4 ? " …" : AutomationLane.LEVEL.equals(parameter) ? " dB" : ""));
            }
            course.setToolTipText(AutomationLane.LEVEL.equals(parameter)
                    ? "Wie sich die Lautstärke der Spur über die Takte ändert; die KI schlägt das mit vor"
                    : "Wie sich die Spur über die Takte bewegt; solange hier Punkte stehen, gilt der Verlauf statt des Reglers");
            flat.setVisible(curve.editable() && !points.isEmpty());
        } finally {
            filling = false;
        }
    }
}
