package com.aresstack.audio.studio.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The chat models of one production: one per model name, made when a role first needs it and
 * shared by all roles with the same model. Cancelling the lineup cancels every model it made, so
 * a production that plans with one model and composes with another stops as a whole.
 */
public final class ModelLineup {
    private final ModelAssignment assignment;
    private final Function<String, ChatModel> factory;
    private final Map<String, ChatModel> made = new LinkedHashMap<>();
    private boolean cancelled;

    /**
     * @param assignment which model answers for which role, fixed for this lineup
     * @param factory    makes the chat model of a model name, for example a KIPITZ client
     */
    public ModelLineup(ModelAssignment assignment, Function<String, ChatModel> factory) {
        this.assignment = Objects.requireNonNull(assignment, "assignment");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /** A lineup in which one chat model answers for every role, as in tests. */
    public static ModelLineup of(ChatModel model) {
        return new ModelLineup(ModelAssignment.single("default"), name -> model);
    }

    public ModelAssignment assignment() {
        return assignment;
    }

    /** The chat model of a role. */
    public ChatModel model(ModelRole role) {
        String name = assignment.modelFor(role);
        ChatModel model;
        boolean late;
        synchronized (this) {
            model = made.get(name);
            if (model == null) {
                model = Objects.requireNonNull(factory.apply(name), "factory gave no model for " + name);
                made.put(name, model);
            }
            late = cancelled;
        }
        if (late) {
            model.cancel(); // made after cancelling: it must not start anything either
        }
        return model;
    }

    /** Stops every request of every model of this lineup. */
    public void cancel() {
        List<ChatModel> running;
        synchronized (this) {
            cancelled = true;
            running = new ArrayList<>(made.values());
        }
        for (ChatModel model : running) {
            model.cancel();
        }
    }

    /** The model names this lineup has used so far. */
    public synchronized List<String> usedModels() {
        return List.copyOf(made.keySet());
    }
}
