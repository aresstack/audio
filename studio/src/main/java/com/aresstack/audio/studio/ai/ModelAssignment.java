package com.aresstack.audio.studio.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Which model answers for which role: an unchangeable snapshot, taken when a production starts,
 * so that changing the choice while it runs only concerns the next one.
 *
 * <p>A role without a model of its own takes its fallback role's model, and at the end of the
 * chain the base model (the studio's single {@code model} of before). An assignment that only
 * names the base model therefore behaves exactly like the studio did with one model.
 */
public final class ModelAssignment {
    private final String base;
    private final Map<String, String> own;

    private ModelAssignment(String base, Map<String, String> own) {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("The base model is missing.");
        }
        this.base = base.trim();
        this.own = Map.copyOf(own);
    }

    /** Every role answered by one model. */
    public static ModelAssignment single(String model) {
        return new ModelAssignment(model, Map.of());
    }

    /** The model every role falls back to in the end. */
    public String base() {
        return base;
    }

    /** The same roles, with another base model. */
    public ModelAssignment withBase(String model) {
        return new ModelAssignment(model, own);
    }

    /** The same, with a model of its own for a role; {@code null} or blank lets the role follow again. */
    public ModelAssignment with(ModelRole role, String model) {
        Map<String, String> changed = new LinkedHashMap<>(own);
        if (model == null || model.isBlank()) {
            changed.remove(role.key());
        } else {
            changed.put(role.key(), model.trim());
        }
        return new ModelAssignment(base, changed);
    }

    /** The model set for exactly this role, or {@code null} when it follows another. */
    public String ownModel(ModelRole role) {
        return own.get(role.key());
    }

    /** The model that answers for a role. */
    public String modelFor(ModelRole role) {
        ModelRole current = Objects.requireNonNull(role, "role");
        for (int depth = 0; depth < 32 && current != null; depth++) {
            String model = own.get(current.key());
            if (model != null) {
                return model;
            }
            current = current.fallback() == null ? null : ModelRoles.byKey(current.fallback());
        }
        return base;
    }

    /** The roles with a model of their own, by key. */
    public Map<String, String> ownModels() {
        return own;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModelAssignment that && base.equals(that.base) && own.equals(that.own);
    }

    @Override
    public int hashCode() {
        return Objects.hash(base, own);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (ModelRole role : ModelRoles.all()) {
            text.append(text.length() == 0 ? "" : ", ").append(role.label()).append(": ").append(modelFor(role));
        }
        return text.toString();
    }
}
