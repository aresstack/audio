package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.ai.ToolChat;
import com.aresstack.audio.studio.assist.AssistChat;
import com.aresstack.audio.studio.assist.AssistTool;
import java.awt.Window;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

/**
 * The AI help of the fields: a key with the sparkle beside a field opens a chat whose AI may read
 * what is around and write only that one field. Each field keeps its conversation in a file of
 * its own, so that it is still known when the chat is opened again, until the user clears it.
 */
final class Assistant {
    /** The sign of AI help on the keys; a plainer star where the font has no sparkle. */
    static final String SPARKLE = "✨";
    static final String STAR = "✦";

    /** What the help needs from the window. */
    interface Connection {
        /** A chat model of this name that may call tools; throws with words for the user if it cannot. */
        ToolChat connect(String model);

        /** The models to choose from. */
        List<String> models();

        /** The model chosen first. */
        String defaultModel();

        /** How long an answer may be. */
        int maxTokens();

        /** The folder the conversations are kept in. */
        Path folder();

        void log(String line);
    }

    /** One field the help writes: its name, what the AI is told, its tools, and how to take a change back. */
    interface Field {
        /** The name shown, e.g. "Worum geht es?". */
        String label();

        /** The name of its conversation file, unique per field (and album or piece). */
        String key();

        /** What the AI is told about its task. */
        String system();

        /** The tools: reading ones and the one that writes this field. */
        List<AssistTool> tools();

        /** What the field holds now, restored by running it; called on the event thread. */
        Runnable snapshot();
    }

    private final Connection connection;
    private final Map<String, AssistDialog> open = new HashMap<String, AssistDialog>();

    Assistant(Connection connection) {
        this.connection = connection;
    }

    /** The sparkle key of a field, without text, with a tooltip. */
    JButton key(String tooltip, Supplier<Field> field) {
        JButton key = new DeckWidgets.HeadKey(SPARKLE);
        if (key.getFont().canDisplayUpTo(SPARKLE) >= 0) {
            key.setText(STAR);
        }
        key.setToolTipText(tooltip);
        key.addActionListener(event -> {
            Field chosen = field.get();
            if (chosen != null) {
                show(SwingUtilities.getWindowAncestor(key), chosen);
            }
        });
        return key;
    }

    /** Opens the chat of a field, or brings it to the front; its conversation is read from its file. */
    void show(Window owner, Field field) {
        AssistDialog shown = open.get(field.key());
        if (shown == null || !shown.isDisplayable()) {
            AssistChat chat = AssistChat.at(connection.folder().resolve(safe(field.key()) + ".json"));
            shown = new AssistDialog(owner, field, chat, connection);
            open.put(field.key(), shown);
        }
        shown.setVisible(true);
        shown.toFront();
    }

    /**
     * The help of a text field: it may read the field and what is around it, and write the field,
     * always as a whole.
     *
     * @param what       what belongs into the field, for the AI
     * @param context    what is around, read on the event thread
     * @param afterWrite what the page does after the field changed, on the event thread; may be {@code null}
     */
    static Field textField(String label, String key, javax.swing.text.JTextComponent text, String what,
                           Supplier<String> context, Runnable afterWrite) {
        return new Field() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public String key() {
                return key;
            }

            @Override
            public String system() {
                return "You help the user of a music studio to fill in the field \"" + label + "\": " + what
                        + " Talk with the user in their language. Read the field and what is around it with the "
                        + "tools when you need it. Change the field only with set_field, always with its whole new "
                        + "text; you cannot change anything else. After a change, say in one or two sentences what "
                        + "you wrote.";
            }

            @Override
            public List<AssistTool> tools() {
                return List.of(
                        AssistTool.reading("read_field", "Reads what the field \"" + label + "\" holds now.",
                                arguments -> onScreen(text::getText)),
                        AssistTool.reading("read_context", "Reads the other fields and the state around this field.",
                                arguments -> onScreen(context)),
                        AssistTool.of("set_field", "Replaces the whole text of the field \"" + label + "\".",
                                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
                                        + "\"required\":[\"text\"]}",
                                arguments -> {
                                    String written = arguments.has("text") ? arguments.get("text").getAsString() : "";
                                    onScreen(() -> {
                                        text.setText(written);
                                        text.setCaretPosition(0);
                                        if (afterWrite != null) {
                                            afterWrite.run();
                                        }
                                        return "";
                                    });
                                    return "Das Feld enthält jetzt " + written.length() + " Zeichen.";
                                }));
            }

            @Override
            public Runnable snapshot() {
                String was = text.getText();
                return () -> {
                    text.setText(was);
                    if (afterWrite != null) {
                        afterWrite.run();
                    }
                };
            }
        };
    }

    /** Runs this on the event thread and waits for its answer. */
    static String onScreen(Supplier<String> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.get();
        }
        String[] result = new String[1];
        RuntimeException[] failed = new RuntimeException[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = work.get();
            } catch (RuntimeException problem) {
                failed[0] = problem;
            }
        });
        if (failed[0] != null) {
            throw failed[0];
        }
        return result[0];
    }

    /** A file name made of a key: letters, digits, dots, hyphens and underscores only. */
    static String safe(String key) {
        String name = key.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isEmpty() || name.startsWith(".") ? "_" + name : name;
    }
}
