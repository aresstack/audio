package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.ai.ModelAssignment;
import com.aresstack.audio.studio.ai.ModelRole;
import com.aresstack.audio.studio.ai.ModelRoles;
import com.aresstack.audio.studio.settings.AiDefaults;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * The models next to a prompt for the AI: one choice per role the page works with (planning and
 * composition beside the composition prompt; the album's model too on the album page), filled
 * from the refresh sign beside the heading, and the extended assignment of every role behind a button. The choice is
 * only a wish: a production takes it over when it starts.
 */
final class ModelChoicePanel extends JPanel {
    /** In the extended assignment: the role has no model of its own and follows another. */
    private static final String FOLLOWS = "";

    private final Runnable changed;
    private final Runnable loadModels;
    private final List<ModelRole> roles;
    private final List<String> known = new ArrayList<>();
    private final List<JComboBox<String>> boxes = new ArrayList<>();
    private final List<JLabel> purposes = new ArrayList<>();
    private ModelAssignment assignment = ModelAssignment.single(AiDefaults.MODEL);
    private boolean filling;

    /**
     * @param changed    told after the user chose another model
     * @param loadModels asks the service for its models; answers with {@link #setModels}
     */
    ModelChoicePanel(Runnable changed, Runnable loadModels) {
        this(ModelRoles.visible(), changed, loadModels);
    }

    /**
     * @param roles the roles this page lets the user choose a model for, in the order shown
     */
    ModelChoicePanel(List<ModelRole> roles, Runnable changed, Runnable loadModels) {
        super(new GridBagLayout());
        this.roles = List.copyOf(roles);
        this.changed = Objects.requireNonNull(changed);
        this.loadModels = Objects.requireNonNull(loadModels);
        setOpaque(false);
        GridBagConstraints at = new GridBagConstraints();
        at.gridx = 0;
        at.gridy = 0;
        at.gridwidth = 2;
        at.anchor = GridBagConstraints.WEST;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.weightx = 1;
        // The heading, and right beside it the sign that fetches the models of the service anew.
        load = new DeckWidgets.RefreshKey(
                "Modelle neu laden: die Modelle des eingestellten Dienstes in die Auswahl holen");
        load.addActionListener(event -> this.loadModels.run());
        JPanel title = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        title.setOpaque(false);
        title.add(DeckWidgets.lettering("KI-Modelle"));
        title.add(javax.swing.Box.createHorizontalStrut(8));
        title.add(load);
        // At the right end of the heading: the key to the table that gives every task its own model.
        extended = new DeckWidgets.HeadKey("Erweitert");
        extended.setToolTipText("Erweiterte Modellzuordnung: jeder KI-Aufgabe ein eigenes Modell geben");
        extended.addActionListener(event -> showExtended());
        JPanel head = new JPanel(new java.awt.BorderLayout(8, 0));
        head.setOpaque(false);
        head.add(title, java.awt.BorderLayout.WEST);
        head.add(extended, java.awt.BorderLayout.EAST);
        add(head, at);
        for (ModelRole role : this.roles) {
            JComboBox<String> box = box(role);
            boxes.add(box);
            at.gridy++;
            at.gridwidth = 1;
            at.weightx = 0;
            at.insets = new Insets(6, 0, 0, 8);
            // "Planungsmodell", "Kompositionsmodell", but "Albummodell".
            JLabel name = new JLabel(role.label()
                    + (role.label().endsWith("ung") || role.label().endsWith("ion") ? "smodell" : "modell"));
            name.setForeground(AudioStudioTheme.TEXT);
            add(name, at);
            at.gridx = 1;
            at.weightx = 1;
            at.insets = new Insets(6, 0, 0, 0);
            add(box, at);
            at.gridx = 1;
            at.gridy++;
            at.insets = new Insets(1, 2, 0, 0);
            JLabel purpose = small(role.purpose());
            purposes.add(purpose);
            add(purpose, at);
            at.gridx = 0;
        }
        refresh();
    }

    /** Locked while the models are at work (a track list is planned, an album produced): no choice then. */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (JComboBox<String> box : boxes) {
            box.setEnabled(enabled);
        }
        load.setEnabled(enabled);
        extended.setEnabled(enabled);
    }

    private final DeckWidgets.RefreshKey load;
    private final JButton extended;

    private JComboBox<String> box(ModelRole role) {
        JComboBox<String> box = new JComboBox<>();
        box.setEditable(true);
        box.setRenderer(new Labelled(null));
        box.setPreferredSize(new Dimension(260, 30));
        box.setToolTipText(role.purpose());
        box.addActionListener(event -> {
            if (filling) {
                return;
            }
            Object chosen = box.getSelectedItem();
            String name = chosen == null ? "" : chosen.toString().trim();
            // The model the role would get anyway (the base model, or that of the role it follows)
            // is no model of its own: the role goes on following.
            ModelAssignment following = assignment.with(role, null);
            assignment = name.isEmpty() || name.equals(following.modelFor(role)) ? following
                    : assignment.with(role, name);
            showOthers(box); // a role that follows this one shows its new model
            changed.run();
        });
        return box;
    }

    /** The choice of a visible role, for tests. */
    JComboBox<String> choice(ModelRole role) {
        return boxes.get(roles.indexOf(role));
    }

    /** The assignment as chosen. */
    ModelAssignment assignment() {
        return assignment;
    }

    /** Shows an assignment, as read from the settings. */
    void setAssignment(ModelAssignment assignment) {
        this.assignment = Objects.requireNonNull(assignment);
        refresh();
    }

    /** The base model (settings page) changed: roles that follow it show the new one. */
    void setBase(String base) {
        String model = base == null || base.isBlank() ? AiDefaults.MODEL : base.trim();
        if (!model.equals(assignment.base())) {
            assignment = assignment.withBase(model);
            refresh();
        }
    }

    /** The models the service offers. */
    void setModels(List<String> models) {
        known.clear();
        known.addAll(models);
        refresh();
    }

    private Set<String> offered() {
        Set<String> names = new LinkedHashSet<>();
        names.add(assignment.base());
        names.addAll(known);
        names.addAll(assignment.ownModels().values());
        return names;
    }

    private void refresh() {
        showOthers(null);
    }

    /** Shows the assignment in every choice but the one the user is just changing. */
    private void showOthers(JComboBox<String> changing) {
        filling = true;
        try {
            List<ModelRole> shown = roles;
            for (int i = 0; i < boxes.size(); i++) {
                JComboBox<String> box = boxes.get(i);
                if (box == changing) {
                    continue;
                }
                box.setModel(new DefaultComboBoxModel<>(offered().toArray(new String[0])));
                String model = assignment.modelFor(shown.get(i));
                box.setSelectedItem(model);
                purposes.get(i).setText(shown.get(i).purpose()
                        + (AiDefaults.MODEL.equals(model) ? " · Standard" : ""));
            }
        } finally {
            filling = false;
        }
    }

    /** Every role with its model; a role may follow another instead of having its own. */
    private void showExtended() {
        JPanel table = new JPanel(new GridBagLayout());
        GridBagConstraints at = new GridBagConstraints();
        at.anchor = GridBagConstraints.WEST;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.insets = new Insets(4, 0, 4, 10);
        List<ModelRole> roles = ModelRoles.all();
        List<JComboBox<String>> choices = new ArrayList<>();
        at.gridy = 0;
        for (ModelRole role : roles) {
            JComboBox<String> box = new JComboBox<>(); // not editable: the empty entry shows what it follows
            DefaultComboBoxModel<String> items = new DefaultComboBoxModel<>();
            items.addElement(FOLLOWS);
            for (String name : offered()) {
                items.addElement(name);
            }
            box.setModel(items);
            box.setRenderer(new Labelled(follows(role)));
            String own = assignment.ownModel(role);
            box.setSelectedItem(own == null ? FOLLOWS : own);
            box.setPreferredSize(new Dimension(380, 30));
            choices.add(box);
            at.gridx = 0;
            at.weightx = 0;
            JPanel name = new JPanel(new BorderLayout());
            name.setOpaque(false);
            name.add(new JLabel(role.label()), BorderLayout.NORTH);
            name.add(small(role.purpose()), BorderLayout.SOUTH);
            table.add(name, at);
            at.gridx = 1;
            at.weightx = 1;
            table.add(box, at);
            at.gridy++;
        }
        at.gridx = 0;
        at.gridwidth = 2;
        table.add(small("Leer: die Aufgabe nimmt das Modell der genannten Rolle. Gilt ab dem nächsten Auftrag."), at);
        int answer = JOptionPane.showConfirmDialog(this, table, "Erweiterte Modellzuordnung",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        ModelAssignment chosen = assignment;
        for (int i = 0; i < roles.size(); i++) {
            Object item = choices.get(i).getSelectedItem();
            String name = item == null ? "" : item.toString().trim();
            chosen = chosen.with(roles.get(i), name.isEmpty() ? null : name);
        }
        if (!chosen.equals(assignment)) {
            assignment = chosen;
            refresh();
            changed.run();
        }
    }

    /** "wie Planung (…)" for a role that follows another; "Basismodell (…)" for the first roles. */
    private String follows(ModelRole role) {
        ModelRole leader = role.fallback() == null ? null : ModelRoles.byKey(role.fallback());
        String model = leader == null ? assignment.base() : assignment.modelFor(leader);
        return (leader == null ? "Basismodell" : "wie " + leader.label()) + " (" + label(model) + ")";
    }

    private static String label(String model) {
        return AiDefaults.MODEL.equals(model) ? model + " (Standard)" : model;
    }

    private static JLabel small(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(AudioStudioTheme.TEXT_DIM);
        label.setFont(label.getFont().deriveFont(11.5f));
        label.setBorder(BorderFactory.createEmptyBorder());
        return label;
    }

    /** Shows the studio's default model as such, and the empty entry as what it follows. */
    private static final class Labelled extends DefaultListCellRenderer {
        private final String followsText;

        Labelled(String followsText) {
            this.followsText = followsText;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                      boolean focused) {
            String text = value == null ? "" : value.toString();
            String shown = text.isEmpty() && followsText != null ? followsText : label(text);
            return super.getListCellRendererComponent(list, shown, index, selected, focused);
        }
    }
}
