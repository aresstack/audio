package com.aresstack.audio.studio.ai;

import java.util.function.Consumer;

/**
 * Who looks at an answer while it is written, for the request the current thread makes next.
 * A composer sets it around the call of a planner or generator; the planners and generators that
 * ask a model hand it to {@link ChatModel#generate(String, String, int, java.nio.file.Path, Consumer)}.
 * Looking never changes the answer.
 */
public final class Listening {
    private static final ThreadLocal<Consumer<String>> CURRENT = new ThreadLocal<>();

    private Listening() {
    }

    /** Ends the looking when closed. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** From now until the scope is closed, {@code heard} gets the text of the answers so far; {@code null} for nobody. */
    public static Scope to(Consumer<String> heard) {
        final Consumer<String> before = CURRENT.get();
        CURRENT.set(heard);
        return () -> {
            if (before == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(before);
            }
        };
    }

    /** Who looks at the next answer of this thread, or {@code null}. */
    public static Consumer<String> current() {
        return CURRENT.get();
    }
}
