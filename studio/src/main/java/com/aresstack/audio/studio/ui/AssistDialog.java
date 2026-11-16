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
 *
 * <p>It looks like a chat: what the user wrote stands at the right in the colour of the desk,
 * what the AI answered at the left, what it did to the field as small lines between them.
 */
final class AssistDialog extends JDialog {
    /** Who a line of the conversation is from, which decides where it stands and how it looks. */
    private enum Voice {
        USER, AI, STEP, NOTE, ERROR
    }

    private final Assistant.Field field;
    private final AssistChat chat;
    private final Assistant.Connection connection;
    private final Transcript transcript = new Transcript();
    private final JScrollPane scroll = new JScrollPane(transcript, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private final JTextArea input = new JTextArea(3, 40) {
        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().isEmpty()) {
                java.awt.Graphics2D g = DeckWidgets.smooth(graphics);
                g.setColor(AudioStudioTheme.TEXT_FAINT);
                g.setFont(getFont());
                java.awt.Insets in = getInsets();
                g.drawString("Nachricht an die KI …", in.left, in.top + g.getFontMetrics().getAscent());
                g.dispose();
            }
        }
    };
    private final JComboBox<String> model = new JComboBox<String>();
    private final JButton send = new DeckWidgets.IconKey(DeckWidgets.IconKey.Sign.SEND, "Senden (Enter)");
    private final JButton undo = new DeckWidgets.HeadKey("Rückgängig");
    private final JButton clear = new DeckWidgets.HeadKey("Verlauf löschen");
    /** How the field was before each answer of the AI, the last on top. */
    private final Deque<Runnable> before = new ArrayDeque<Runnable>();
    /** The bubble that says the AI is at work, while it is; its dots move. */
    private Bubble thinking;
    private final javax.swing.Timer dots = new javax.swing.Timer(380, event -> {
        if (thinking != null) {
            String now = thinking.text();
            thinking.say(now.length() >= 5 ? "·" : now + " ·");
            transcript.revalidate();
            transcript.repaint();
        }
    });
    private boolean asking;

    AssistDialog(Window owner, Assistant.Field field, AssistChat chat, Assistant.Connection connection) {
        super(owner, "KI-Hilfe: " + field.label(), ModalityType.MODELESS);
        this.field = field;
        this.chat = chat;
        this.connection = connection;
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        content.setBackground(AudioStudioTheme.MODULE);

        // The head: what the chat is for at the left, the model and the two keys at the right.
        JPanel head = new JPanel(new BorderLayout(12, 0));
        head.setOpaque(false);
        JPanel named = new JPanel(new BorderLayout(0, 3));
        named.setOpaque(false);
        JLabel kind = DeckWidgets.lettering("KI-Hilfe");
        kind.setForeground(AudioStudioTheme.ACCENT);
        JLabel name = new JLabel(field.label());
        name.setForeground(AudioStudioTheme.TEXT);
        name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD, 17f));
        named.add(kind, BorderLayout.NORTH);
        named.add(name, BorderLayout.CENTER);
        head.add(named, BorderLayout.WEST);
        JPanel keys = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        keys.setOpaque(false);
        model.setEditable(true);
        for (String each : connection.models()) {
            model.addItem(each);
        }
        String first = connection.defaultModel();
        if (first != null && !first.isBlank()) {
            model.setSelectedItem(first);
        }
        model.setToolTipText("Das Chat-Modell dieser Hilfe; es muss Werkzeuge aufrufen können");
        model.setPreferredSize(new Dimension(230, 28));
        keys.add(model);
        keys.add(undo);
        keys.add(clear);
        undo.setToolTipText("Nimmt die letzte Änderung der KI an diesem Feld zurück");
        clear.setToolTipText("Vergisst das Gespräch dieses Feldes");
        JPanel keysLow = new JPanel(new BorderLayout());
        keysLow.setOpaque(false);
        keysLow.add(keys, BorderLayout.SOUTH);
        head.add(keysLow, BorderLayout.EAST);
        content.add(head, BorderLayout.NORTH);

        scroll.setBorder(BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));
        scroll.getViewport().setBackground(AudioStudioTheme.WELL);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.setPreferredSize(new Dimension(700, 460));
        content.add(scroll, BorderLayout.CENTER);

        // The foot: the box to write in with the key that sends, and under it how to send.
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setOpaque(false);
        input.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        input.setForeground(AudioStudioTheme.TEXT);
        input.setCaretColor(AudioStudioTheme.TEXT);
        input.setToolTipText("Was die KI für dieses Feld tun soll. Ein langer Text darf hineinkopiert werden.");
        JScrollPane typed = new JScrollPane(input, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        typed.setBorder(BorderFactory.createEmptyBorder());
        typed.setOpaque(false);
        typed.getViewport().setOpaque(false);
        JPanel box = new JPanel(new BorderLayout(6, 0)) {
            @Override
            protected void paintComponent(java.awt.Graphics graphics) {
                java.awt.Graphics2D g = DeckWidgets.smooth(graphics);
                g.setColor(AudioStudioTheme.WELL);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g.setColor(input.isFocusOwner() ? AudioStudioTheme.ACCENT : AudioStudioTheme.STROKE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g.dispose();
            }
        };
        box.setOpaque(false);
        box.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 8));
        box.add(typed, BorderLayout.CENTER);
        JPanel sendLow = new JPanel(new BorderLayout());
        sendLow.setOpaque(false);
        sendLow.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        sendLow.add(send, BorderLayout.SOUTH);
        box.add(sendLow, BorderLayout.EAST);
        input.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent event) {
                box.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                box.repaint();
            }
        });
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                input.repaint();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                input.repaint();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
            }
        });
        JLabel how = new JLabel("Enter sendet · Umschalt+Enter beginnt eine neue Zeile · die KI ändert nur dieses Feld");
        how.setForeground(AudioStudioTheme.TEXT_FAINT);
        how.setFont(how.getFont().deriveFont(11.5f));
        JPanel foot = new JPanel(new BorderLayout(0, 6));
        foot.setOpaque(false);
        foot.add(box, BorderLayout.CENTER);
        foot.add(how, BorderLayout.SOUTH);
        content.add(foot, BorderLayout.SOUTH);
        setContentPane(content);

        send.addActionListener(event -> send());
        undo.addActionListener(event -> undo());
        clear.addActionListener(event -> clear());
        AbstractAction sending = new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                send();
            }
        };
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "send");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
        input.getActionMap().put("send", sending);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        showConversation();
        keys();
        pack();
        setMinimumSize(new Dimension(520, 420));
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent event) {
                input.requestFocusInWindow();
            }
        });
    }

    /** One thing said in the conversation, drawn as a bubble; its text can be marked and copied. */
    private static final class Bubble extends JPanel {
        private final Voice voice;
        private final JTextArea words = new JTextArea();

        Bubble(Voice voice, String text) {
            super(null);
            this.voice = voice;
            setOpaque(false);
            words.setEditable(false);
            words.setLineWrap(true);
            words.setWrapStyleWord(true);
            words.setOpaque(false);
            words.setBorder(null);
            boolean small = voice == Voice.STEP || voice == Voice.NOTE;
            words.setFont(words.getFont().deriveFont(small ? 12f : 13.5f));
            words.setForeground(voice == Voice.STEP ? AudioStudioTheme.GREEN
                    : voice == Voice.NOTE ? AudioStudioTheme.TEXT_DIM
                    : voice == Voice.ERROR ? AudioStudioTheme.RED : AudioStudioTheme.TEXT);
            add(words);
            say(text);
        }

        void say(String text) {
            words.setText(text);
        }

        String text() {
            return words.getText();
        }

        private boolean framed() {
            return voice == Voice.USER || voice == Voice.AI || voice == Voice.ERROR;
        }

        private int padX() {
            return framed() ? 12 : 4;
        }

        private int padY() {
            return framed() ? 8 : 1;
        }

        /** The size of the bubble when it may be at most this wide: as wide as its longest line needs. */
        Dimension sizeFor(int most) {
            java.awt.FontMetrics metrics = words.getFontMetrics(words.getFont());
            int longest = 0;
            for (String line : words.getText().split("\n", -1)) {
                longest = Math.max(longest, metrics.stringWidth(line));
            }
            int inner = Math.max(24, Math.min(most - 2 * padX(), longest + 6));
            words.setSize(inner, Short.MAX_VALUE);
            int height = words.getPreferredSize().height;
            return new Dimension(inner + 2 * padX(), height + 2 * padY());
        }

        @Override
        public void doLayout() {
            words.setBounds(padX(), padY(), getWidth() - 2 * padX(), getHeight() - 2 * padY());
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            if (!framed()) {
                return;
            }
            java.awt.Graphics2D g = DeckWidgets.smooth(graphics);
            java.awt.Color fill = voice == Voice.USER ? AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 70)
                    : voice == Voice.ERROR ? AudioStudioTheme.withAlpha(AudioStudioTheme.RED, 40) : AudioStudioTheme.RAISED;
            java.awt.Color line = voice == Voice.USER ? AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 150)
                    : voice == Voice.ERROR ? AudioStudioTheme.withAlpha(AudioStudioTheme.RED, 130) : AudioStudioTheme.STROKE;
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.setColor(line);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.dispose();
        }
    }

    /** The conversation: bubbles one under the other, the user's at the right, as wide as the window allows. */
    private static final class Transcript extends JPanel implements javax.swing.Scrollable {
        private static final int EDGE = 14;

        Transcript() {
            super(null);
            setBackground(AudioStudioTheme.WELL);
        }

        private int width() {
            return getParent() == null || getParent().getWidth() == 0 ? 700 : getParent().getWidth();
        }

        private int layout(boolean place) {
            int width = width();
            int most = Math.max(160, Math.min(620, (int) ((width - 2 * EDGE) * 0.8)));
            int y = EDGE;
            Bubble above = null;
            for (java.awt.Component each : getComponents()) {
                Bubble bubble = (Bubble) each;
                boolean wide = bubble.voice == Voice.NOTE;
                Dimension size = bubble.sizeFor(wide ? width - 2 * EDGE : most);
                if (above != null) {
                    y += bubble.voice == Voice.STEP && above.voice == Voice.STEP ? 2 : 10;
                }
                if (place) {
                    int x = bubble.voice == Voice.USER ? width - EDGE - size.width
                            : wide ? (width - size.width) / 2 : EDGE;
                    bubble.setBounds(x, y, size.width, size.height);
                }
                y += size.height;
                above = bubble;
            }
            return y + EDGE;
        }

        @Override
        public void doLayout() {
            layout(true);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(width(), layout(false));
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
            return Math.max(24, visible.height - 48);
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

    /** Puts a line at the end of the conversation (before the bubble of the AI at work) and shows it. */
    private Bubble say(Voice voice, String text) {
        Bubble bubble = new Bubble(voice, text);
        int at = thinking == null ? -1 : transcript.getComponentZOrder(thinking);
        transcript.add(bubble, at);
        toEnd();
        return bubble;
    }

    private void toEnd() {
        transcript.revalidate();
        transcript.repaint();
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
            javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        }));
    }

    private void atWork(boolean now) {
        if (thinking != null) {
            transcript.remove(thinking);
            thinking = null;
        }
        if (now) {
            thinking = new Bubble(Voice.AI, "·");
            transcript.add(thinking);
            dots.start();
        } else {
            dots.stop();
        }
        toEnd();
    }

    /** Writes the conversation so far into the window. */
    private void showConversation() {
        transcript.removeAll();
        thinking = null;
        boolean any = false;
        for (JsonObject message : chat.messages()) {
            String role = message.has("role") ? message.get("role").getAsString() : "";
            String said = message.has("content") && message.get("content").isJsonPrimitive()
                    ? message.get("content").getAsString() : "";
            if ("user".equals(role)) {
                transcript.add(new Bubble(Voice.USER, said.strip()));
                any = true;
            } else if ("assistant".equals(role) && !said.isBlank()) {
                transcript.add(new Bubble(Voice.AI, said.strip()));
                any = true;
            } else if ("tool".equals(role) && !said.startsWith("{") && !said.startsWith("[")) {
                transcript.add(new Bubble(Voice.STEP, "✓ " + firstLine(said)));
            }
        }
        if (!any) {
            transcript.add(new Bubble(Voice.NOTE, "Sag der KI, was in „" + field.label() + "“ stehen soll, oder kopier "
                    + "einen Text hinein, aus dem sie es machen soll.\nSie kann nur dieses Feld ändern; „Rückgängig“ "
                    + "nimmt eine Änderung zurück."));
        }
        toEnd();
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
            say(Voice.ERROR, refused.getMessage());
            return;
        }
        before.push(field.snapshot());
        asking = true;
        keys();
        input.setText("");
        if (chat.messages().isEmpty()) {
            transcript.removeAll(); // the first word takes the place of the hint
        }
        say(Voice.USER, text);
        atWork(true);
        Thread worker = new Thread(() -> {
            String answer;
            try {
                answer = chat.send(text, ai, field.system(), field.tools(), connection.maxTokens(),
                        line -> SwingUtilities.invokeLater(() -> say(Voice.STEP, "✓ " + line)));
            } catch (Exception failed) {
                answer = null;
                String why = failed.getMessage() == null ? failed.toString() : failed.getMessage();
                SwingUtilities.invokeLater(() -> {
                    atWork(false);
                    say(Voice.ERROR, why);
                    connection.log("WARN: KI-Hilfe „" + field.label() + "“: " + why);
                });
            }
            final String said = answer;
            SwingUtilities.invokeLater(() -> {
                asking = false;
                if (said != null) {
                    atWork(false);
                    showConversation();
                }
                keys();
                input.requestFocusInWindow();
            });
        }, "assist-" + Assistant.safe(field.key()));
        worker.setDaemon(true);
        worker.start();
    }

    private void undo() {
        if (!before.isEmpty() && !asking) {
            before.pop().run();
            say(Voice.NOTE, "Die letzte Änderung der KI ist zurückgenommen.");
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
            say(Voice.ERROR, failed.getMessage());
            return;
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
