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
    /** The ways a model may be asked to think before it answers, as sent in {@code reasoning_effort}. */
    public static final java.util.List<String> EFFORTS = java.util.List.of("low", "medium", "high");

    private final String base;
    private final Map<String, String> own;
    /** How much each role's model thinks, by role key; a role without an entry lets its model decide. */
    private final Map<String, String> efforts;

    private ModelAssignment(String base, Map<String, String> own, Map<String, String> efforts) {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("The base model is missing.");
        }
        this.base = base.trim();
        this.own = Map.copyOf(own);
        this.efforts = Map.copyOf(efforts);
    }

    /** Every role answered by one model. */
    public static ModelAssignment single(String model) {
        return new ModelAssignment(model, Map.of(), Map.of());
    }

    /**
     * The same, with how much the role's model thinks before it answers: {@code low},
     * {@code medium} or {@code high}; anything else ("Standard") lets the model decide.
     */
    public ModelAssignment withEffort(ModelRole role, String effort) {
        Map<String, String> changed = new LinkedHashMap<>(efforts);
        String value = effort == null ? "" : effort.trim().toLowerCase(java.util.Locale.ROOT);
        if (EFFORTS.contains(value)) {
            changed.put(role.key(), value);
        } else {
            changed.remove(role.key());
        }
        return new ModelAssignment(base, own, changed);
    }

    /** How much the model of this role thinks, or {@code null} when the model decides. */
    public String effortFor(ModelRole role) {
        return efforts.get(role.key());
    }

    /** The roles with a way of thinking of their own, by key. */
    public Map<String, String> efforts() {
        return efforts;
    }

    /** The model every role falls back to in the end. */
    public String base() {
        return base;
    }

    /** The same roles, with another base model. */
    public ModelAssignment withBase(String model) {
        return new ModelAssignment(model, own, efforts);
    }

    /** The same, with a model of its own for a role; {@code null} or blank lets the role follow again. */
    public ModelAssignment with(ModelRole role, String model) {
        Map<String, String> changed = new LinkedHashMap<>(own);
        if (model == null || model.isBlank()) {
            changed.remove(role.key());
        } else {
            changed.put(role.key(), model.trim());
        }
        return new ModelAssignment(base, changed, efforts);
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
        return other instanceof ModelAssignment that && base.equals(that.base) && own.equals(that.own)
                && efforts.equals(that.efforts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(base, own, efforts);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (ModelRole role : ModelRoles.all()) {
            text.append(text.length() == 0 ? "" : ", ").append(role.label()).append(": ").append(modelFor(role));
            if (effortFor(role) != null) {
                text.append(" (Nachdenken ").append(effortFor(role)).append(')');
            }
        }
        return text.toString();
    }
}
