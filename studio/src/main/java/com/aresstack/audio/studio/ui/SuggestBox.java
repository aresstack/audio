package com.aresstack.audio.studio.ui;

import java.awt.Component;
import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.ComboBoxEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JTextField;

/**
 * A text field with a list of proposals to open: the user types as in any field, and the arrow
 * shows what was proposed for it. Filling the list never changes what the user has typed; only
 * choosing an entry does.
 */
final class SuggestBox extends JComboBox<String> {
    private final JTextField field;
    private boolean filling;

    SuggestBox(JTextField field) {
        this.field = field;
        setEditable(true);
        setMaximumRowCount(12);
        setEditor(new ComboBoxEditor() {
            @Override
            public Component getEditorComponent() {
                return field;
            }

            @Override
            public void setItem(Object item) {
                if (!filling && item != null) {
                    field.setText(item.toString());
                }
            }

            @Override
            public Object getItem() {
                return field.getText();
            }

            @Override
            public void selectAll() {
                field.selectAll();
            }

            @Override
            public void addActionListener(ActionListener listener) {
                field.addActionListener(listener);
            }

            @Override
            public void removeActionListener(ActionListener listener) {
                field.removeActionListener(listener);
            }
        });
        // A long proposal must not make the page wider; the list shows it in full.
        setPrototypeDisplayValue("A proposal of usual length");
    }

    /** Shows these proposals in the list; what stands in the field stays. */
    void proposals(List<String> proposals) {
        String typed = field.getText();
        int caret = field.getCaretPosition();
        filling = true;
        try {
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>();
            for (String proposal : proposals) {
                model.addElement(proposal);
            }
            model.setSelectedItem(null);
            setModel(model);
        } finally {
            filling = false;
        }
        if (!typed.equals(field.getText())) {
            field.setText(typed);
            field.setCaretPosition(Math.min(caret, typed.length()));
        }
    }

    /** The proposals in the list, the newest first. */
    List<String> proposals() {
        List<String> shown = new java.util.ArrayList<String>();
        for (int i = 0; i < getItemCount(); i++) {
            shown.add(getItemAt(i));
        }
        return shown;
    }
}
