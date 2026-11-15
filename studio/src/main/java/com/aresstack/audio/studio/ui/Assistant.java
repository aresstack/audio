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

    /** The endings a score the AI writes may have: text formats the template reader takes. */
    static final java.util.Set<String> SCORE_FORMATS = java.util.Set.of("abc", "ly", "musicxml", "krn");
    /** The most characters of a template the AI is given to read. */
    static final int SCORE_READ_LIMIT = 200_000;

    /**
     * The help of a score template: the AI writes notes with the user, from a description,
     * chords, a melody in words or any notation, and keeps them as the template of a piece or of
     * a title of an album, which is then made from them instead of being composed freely.
     *
     * @param folder     the folder whose template it writes (of a piece, or of a title); {@code null}
     *                   while there is none, read on the event thread
     * @param afterWrite what the page does after the template changed, on the event thread
     */
    static Field scoreField(String label, String key, Supplier<Path> folder, Supplier<String> context,
                            Runnable afterWrite) {
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
                return "You write the notes of a score template with the user of a music studio. The user may give a "
                        + "description, chords, a melody in words, ABC, LilyPond or anything else; make real notes of "
                        + "it, faithful to what is given, and invent only what the user leaves open. Write the score "
                        + "in ABC notation unless the user wants another format: headers X:, T:, M:, L:, Q:, K:, one "
                        + "voice per part (V: with a name of the instrument, e.g. V:1 name=\"Violin\"), every bar "
                        + "complete. Keep it with write_score, always the whole score; it replaces the template "
                        + "before. Read what is there with read_score and read_context. Talk with the user in their "
                        + "language and say in a few sentences what you wrote. You can change nothing else.";
            }

            @Override
            public List<AssistTool> tools() {
                return List.of(
                        AssistTool.reading("read_score", "Reads the template kept now, if it is text.",
                                arguments -> onScreen(() -> {
                                    Path where = folder.get();
                                    java.util.Optional<Path> kept = where == null ? java.util.Optional.empty()
                                            : com.aresstack.audio.studio.application.ScoreTemplates.of(where);
                                    if (kept.isEmpty()) {
                                        return "Es gibt noch keine Notenvorlage.";
                                    }
                                    String name = kept.get().getFileName().toString();
                                    if (!SCORE_FORMATS.contains(ending(name))) {
                                        return "Die Vorlage ist die Datei „" + name + "“ (kein Text zum Lesen).";
                                    }
                                    try {
                                        String text = java.nio.file.Files.readString(kept.get(),
                                                java.nio.charset.StandardCharsets.UTF_8);
                                        return "Datei „" + name + "“:\n" + (text.length() > SCORE_READ_LIMIT
                                                ? text.substring(0, SCORE_READ_LIMIT) + "\n…" : text);
                                    } catch (java.io.IOException unreadable) {
                                        return "Die Vorlage ließ sich nicht lesen: " + unreadable.getMessage();
                                    }
                                })),
                        AssistTool.reading("read_context", "Reads what the piece or title is meant to be.",
                                arguments -> onScreen(context)),
                        AssistTool.of("write_score", "Keeps the whole score as the template, in place of the one "
                                        + "before. format: abc (default), ly, musicxml or krn.",
                                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},"
                                        + "\"format\":{\"type\":\"string\"},\"name\":{\"type\":\"string\","
                                        + "\"description\":\"a short file name without ending\"}},"
                                        + "\"required\":[\"text\"]}",
                                arguments -> {
                                    String text = arguments.has("text") ? arguments.get("text").getAsString() : "";
                                    if (text.isBlank()) {
                                        throw new IllegalArgumentException("Die Noten sind leer.");
                                    }
                                    String format = arguments.has("format")
                                            ? arguments.get("format").getAsString().trim().toLowerCase(java.util.Locale.ROOT)
                                            : "abc";
                                    String ending = SCORE_FORMATS.contains(format) ? format : "abc";
                                    String name = arguments.has("name") ? safe(arguments.get("name").getAsString())
                                            : "ki-noten";
                                    return onScreen(() -> {
                                        Path where = folder.get();
                                        if (where == null) {
                                            throw new IllegalStateException("Es ist kein Stück oder Titel gewählt, "
                                                    + "zu dem die Noten gehören.");
                                        }
                                        try {
                                            keep(where, name + "." + ending,
                                                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                        } catch (java.io.IOException failed) {
                                            throw new IllegalStateException(failed.getMessage(), failed);
                                        }
                                        if (afterWrite != null) {
                                            afterWrite.run();
                                        }
                                        return "Die Notenvorlage „" + name + "." + ending + "“ ist gespeichert ("
                                                + text.length() + " Zeichen).";
                                    });
                                }));
            }

            @Override
            public Runnable snapshot() {
                Path where = folder.get();
                java.util.Optional<Path> kept = where == null ? java.util.Optional.empty()
                        : com.aresstack.audio.studio.application.ScoreTemplates.of(where);
                byte[] bytes = null;
                String name = null;
                if (kept.isPresent()) {
                    try {
                        bytes = java.nio.file.Files.readAllBytes(kept.get());
                        name = kept.get().getFileName().toString();
                    } catch (java.io.IOException unreadable) {
                        return () -> { };
                    }
                }
                final byte[] was = bytes;
                final String wasName = name;
                return () -> {
                    if (where == null) {
                        return;
                    }
                    try {
                        if (was == null) {
                            com.aresstack.audio.studio.application.ScoreTemplates.remove(where);
                        } else {
                            keep(where, wasName, was);
                        }
                    } catch (java.io.IOException failed) {
                        // the template stays as the AI left it
                    }
                    if (afterWrite != null) {
                        afterWrite.run();
                    }
                };
            }
        };
    }

    /** Keeps these bytes under this name as the template of the folder, in place of the one before. */
    static void keep(Path folder, String name, byte[] bytes) throws java.io.IOException {
        Path temporary = java.nio.file.Files.createTempDirectory("ki-noten");
        try {
            Path file = temporary.resolve(name);
            java.nio.file.Files.write(file, bytes);
            com.aresstack.audio.studio.application.ScoreTemplates.put(folder, file);
            java.nio.file.Files.deleteIfExists(file);
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
        }
    }

    private static String ending(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
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
