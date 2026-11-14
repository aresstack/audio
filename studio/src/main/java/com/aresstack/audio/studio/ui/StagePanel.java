package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.application.MusicTrack;
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

        /** The AI shall propose a mix. */
        void askDirector();
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
    private final JToggleButton mute = new JToggleButton("Stumm");
    private final JToggleButton lock = new JToggleButton("Vor KI schützen");
    private final JLabel course = new JLabel(" ");
    private final JButton flat = new JButton("Verlauf löschen");
    private final JButton director = new JButton("KI mischt ab");
    private final JButton usual = new JButton("Standardaufstellung");
    private final JButton take = new JButton("Übernehmen");
    private final JButton drop = new JButton("Verwerfen");
    private final JPanel verdict = new JPanel(new GridLayout(1, 2, 8, 0));
    private final JLabel status = new JLabel(" ");

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
        DeckWidgets.Module stage = new DeckWidgets.Module("Bühne · von oben", view);
        JLabel hint = new JLabel("Scheiben ziehen · Mausrad: lauter und leiser");
        hint.setForeground(AudioStudioTheme.TEXT_FAINT);
        hint.setFont(hint.getFont().deriveFont(11.5f));
        stage.setHeadEnd(hint);
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
        flat.setToolTipText("Die Spur bleibt über das ganze Stück gleich laut");
        flat.setMargin(new Insets(3, 8, 3, 8));
        flat.addActionListener(event -> {
            SoundSourcePlacement placement = placement();
            if (placement != null) {
                put(placement.withDynamics(null), true);
            }
        });
        JPanel courseRow = new JPanel(new BorderLayout(8, 0));
        courseRow.setOpaque(false);
        courseRow.add(course, BorderLayout.CENTER);
        courseRow.add(flat, BorderLayout.EAST);
        JPanel sourceBox = column(chosen, where, Box.createVerticalStrut(6), source, Box.createVerticalStrut(6),
                flags, Box.createVerticalStrut(8), courseRow);

        // Mixing.
        director.setFont(director.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        director.setPreferredSize(new Dimension(100, 40));
        director.setToolTipText("Die KI bekommt gesagt, was jede Spur spielt, und schlägt Lautstärken, deren Verlauf, "
                + "Plätze und den Raum vor");
        director.addActionListener(event -> this.host.askDirector());
        usual.setToolTipText("Alle Instrumente, die nicht geschützt sind, auf den üblichen Platz ihrer Rolle setzen");
        usual.addActionListener(event -> {
            List<MusicTrack> tracks = new ArrayList<MusicTrack>();
            for (MusicTrack track : roles) {
                tracks.add(track);
            }
            change(scene.taking(new SpatialScene(scene.room(), null).fitting(tracks)), true);
        });
        take.setToolTipText("Den Vorschlag der KI übernehmen; geschützte Instrumente bleiben");
        drop.setToolTipText("Den Vorschlag fallen lassen; die Bühne bleibt, wie sie ist");
        take.addActionListener(event -> {
            if (proposal != null) {
                SpatialScene taken = scene.taking(proposal);
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
        JPanel mixing = column(director, Box.createVerticalStrut(8), verdict, Box.createVerticalStrut(8), usual,
                Box.createVerticalStrut(10), status);

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

    /** Shows the stage of the piece for its tracks, as the studio holds it. */
    void setScene(SpatialScene shown, List<MusicTrack> tracks) {
        scene = shown;
        roles = new ArrayList<MusicTrack>(tracks);
        order = new ArrayList<String>();
        for (MusicTrack track : tracks) {
            order.add(track.id());
        }
        view.setScene(shown, order);
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
        proposal = proposed;
        view.setProposal(proposed);
        verdict.setVisible(proposed != null);
        if (proposed != null) {
            setStatus("Vorschlag der KI: gestrichelt auf der Bühne · " + proposed.room().name());
        } else {
            setStatus(" ");
        }
        revalidate();
    }

    void setStatus(String text) {
        status.setText(text == null || text.isEmpty() ? " " : text);
        status.setToolTipText(text);
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
            for (Row row : new Row[]{side, depth, level, width, share}) {
                row.slider.setEnabled(there);
            }
            mute.setEnabled(there);
            lock.setEnabled(there);
            if (!there) {
                chosen.setText("Kein Instrument gewählt");
                where.setText("Eine Scheibe auf der Bühne anklicken");
                course.setText(" ");
                flat.setVisible(false);
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
            mute.setSelected(placement.muted());
            lock.setSelected(placement.locked());
            List<SoundSourcePlacement.LevelPoint> points = placement.dynamics();
            if (points.isEmpty()) {
                course.setText("Verlauf: gleichbleibend laut");
            } else {
                StringBuilder text = new StringBuilder("Verlauf: ");
                for (int i = 0; i < points.size() && i < 5; i++) {
                    text.append(i == 0 ? "" : " → ").append("T").append(Math.round(points.get(i).bar()) + 1).append(' ')
                            .append(StageView.decibels(points.get(i).db()).replace(" dB", ""));
                }
                course.setText(text + (points.size() > 5 ? " …" : " dB"));
            }
            course.setToolTipText("Wie sich die Lautstärke der Spur über die Takte ändert; die KI schlägt das mit vor");
            flat.setVisible(!points.isEmpty());
        } finally {
            filling = false;
        }
    }
}
