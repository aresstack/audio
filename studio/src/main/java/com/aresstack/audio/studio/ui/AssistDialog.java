package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.ai.ToolChat;
import com.aresstack.audio.studio.assist.AssistChat;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * The chat of the AI help of one field. The AI may read what is around and writes only this
 * field; every change it makes can be taken back. The conversation stays when the dialog is
 * closed and is only forgotten by "Verlauf löschen".
 */
final class AssistDialog extends JDialog {
    private final Assistant.Field field;
    private final AssistChat chat;
    private final Assistant.Connection connection;
    private final JTextArea transcript = new JTextArea();
    private final JTextArea input = new JTextArea(4, 40);
    private final JComboBox<String> model = new JComboBox<String>();
    private final JButton send = new DeckWidgets.HeadKey("Senden");
    private final JButton undo = new DeckWidgets.HeadKey("Rückgängig");
    private final JButton clear = new DeckWidgets.HeadKey("Verlauf löschen");
    /** How the field was before each answer of the AI, the last on top. */
    private final Deque<Runnable> before = new ArrayDeque<Runnable>();
    private boolean asking;

    AssistDialog(Window owner, Assistant.Field field, AssistChat chat, Assistant.Connection connection) {
        super(owner, "KI-Hilfe: " + field.label(), ModalityType.MODELESS);
        this.field = field;
        this.chat = chat;
        this.connection = connection;
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.setBackground(AudioStudioTheme.MODULE);

        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        head.setOpaque(false);
        JLabel modelLabel = new JLabel("Modell");
        modelLabel.setForeground(AudioStudioTheme.TEXT_DIM);
        head.add(modelLabel);
        model.setEditable(true);
        for (String name : connection.models()) {
            model.addItem(name);
        }
        String first = connection.defaultModel();
        if (first != null && !first.isBlank()) {
            model.setSelectedItem(first);
        }
        model.setToolTipText("Das Chat-Modell dieser Hilfe; es muss Werkzeuge aufrufen können");
        head.add(model);
        head.add(undo);
        head.add(clear);
        undo.setToolTipText("Nimmt die letzte Änderung der KI an diesem Feld zurück");
        clear.setToolTipText("Vergisst das Gespräch dieses Feldes");
        content.add(head, BorderLayout.NORTH);

        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setBackground(AudioStudioTheme.WELL);
        transcript.setForeground(AudioStudioTheme.TEXT);
        transcript.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane shown = new JScrollPane(transcript);
        shown.setPreferredSize(new Dimension(640, 420));
        content.add(shown, BorderLayout.CENTER);

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setToolTipText("Was die KI für dieses Feld tun soll; Strg+Enter sendet. Ein langer Text darf "
                + "hineinkopiert werden.");
        JPanel foot = new JPanel(new BorderLayout(8, 0));
        foot.setOpaque(false);
        foot.add(new JScrollPane(input), BorderLayout.CENTER);
        JPanel keys = new JPanel(new BorderLayout());
        keys.setOpaque(false);
        keys.add(send, BorderLayout.SOUTH);
        foot.add(keys, BorderLayout.EAST);
        content.add(foot, BorderLayout.SOUTH);
        setContentPane(content);

        send.addActionListener(event -> send());
        undo.addActionListener(event -> undo());
        clear.addActionListener(event -> clear());
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "send");
        input.getActionMap().put("send", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                send();
            }
        });
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        showConversation();
        keys();
        pack();
        setLocationRelativeTo(owner);
    }

    /** Writes the conversation so far into the window. */
    private void showConversation() {
        StringBuilder text = new StringBuilder();
        for (JsonObject message : chat.messages()) {
            String role = message.has("role") ? message.get("role").getAsString() : "";
            String said = message.has("content") && message.get("content").isJsonPrimitive()
                    ? message.get("content").getAsString() : "";
            if ("user".equals(role)) {
                text.append("Du: ").append(said).append("\n\n");
            } else if ("assistant".equals(role) && !said.isBlank()) {
                text.append("KI: ").append(said).append("\n\n");
            } else if ("tool".equals(role) && !said.startsWith("{") && !said.startsWith("[")) {
                text.append("    ✓ ").append(firstLine(said)).append('\n');
            }
        }
        if (text.length() == 0) {
            text.append("Sag der KI, was in „").append(field.label()).append("“ stehen soll, oder kopier einen Text "
                    + "hinein, aus dem sie es machen soll. Sie kann nur dieses Feld ändern; „Rückgängig“ nimmt eine "
                    + "Änderung zurück.\n\n");
        }
        transcript.setText(text.toString());
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }

    private void send() {
        String text = input.getText().strip();
        if (text.isEmpty() || asking) {
            return;
        }
        String chosen = model.getSelectedItem() == null ? "" : model.getSelectedItem().toString().trim();
        final ToolChat ai;
        try {
            ai = connection.connect(chosen);
        } catch (RuntimeException refused) {
            transcript.append("Fehler: " + refused.getMessage() + "\n\n");
            return;
        }
        before.push(field.snapshot());
        asking = true;
        keys();
        input.setText("");
        transcript.append("Du: " + text + "\n\n");
        transcript.append("KI denkt nach …\n");
        Thread worker = new Thread(() -> {
            String answer;
            try {
                answer = chat.send(text, ai, field.system(), field.tools(), connection.maxTokens(),
                        line -> SwingUtilities.invokeLater(() -> transcript.append("    ✓ " + line + "\n")));
            } catch (Exception failed) {
                answer = null;
                String why = failed.getMessage() == null ? failed.toString() : failed.getMessage();
                SwingUtilities.invokeLater(() -> {
                    transcript.append("Fehler: " + why + "\n\n");
                    connection.log("WARN: KI-Hilfe „" + field.label() + "“: " + why);
                });
            }
            final String said = answer;
            SwingUtilities.invokeLater(() -> {
                asking = false;
                if (said != null) {
                    showConversation();
                }
                keys();
            });
        }, "assist-" + Assistant.safe(field.key()));
        worker.setDaemon(true);
        worker.start();
    }

    private void undo() {
        if (!before.isEmpty() && !asking) {
            before.pop().run();
            transcript.append("(Die letzte Änderung der KI ist zurückgenommen.)\n\n");
            keys();
        }
    }

    private void clear() {
        if (asking) {
            return;
        }
        try {
            chat.clear();
        } catch (java.io.IOException failed) {
            transcript.append("Fehler: " + failed.getMessage() + "\n\n");
        }
        showConversation();
    }

    private void keys() {
        send.setEnabled(!asking);
        clear.setEnabled(!asking);
        undo.setEnabled(!asking && !before.isEmpty());
        model.setEnabled(!asking);
    }

    private static String firstLine(String text) {
        String line = text.strip();
        int end = line.indexOf('\n');
        line = end < 0 ? line : line.substring(0, end);
        return line.length() > 160 ? line.substring(0, 160) + " …" : line;
    }
}
