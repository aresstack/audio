package com.aresstack.audio.studio.ai;

import java.util.Objects;

/**
 * A task of the AI that may get a model of its own, for example planning or writing notes. A
 * role that is not set falls back to another role, and the first roles fall back to the one
 * model the studio always had ({@code model} in the settings). Roles are open: an album planner
 * later registers one more without anything else changing.
 *
 * @param key      the name in the settings ({@code model.<key>}), lowercase ASCII
 * @param label    the German name shown to the user
 * @param purpose  one line on what the role does, shown under its choice
 * @param fallback the role whose model is taken while this one is not set, {@code null} for the
 *                 studio's base model
 * @param visible  whether the composition page shows its choice; the others are in the extended
 *                 assignment only
 */
public record ModelRole(String key, String label, String purpose, String fallback, boolean visible) {
    public ModelRole {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(purpose, "purpose");
        if (key == null || !key.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("A role key is lowercase ASCII: " + key);
        }
        if (key.equals(fallback)) {
            throw new IllegalArgumentException("A role cannot fall back to itself: " + key);
        }
    }

    /** The key in {@code studio.properties}. */
    public String settingKey() {
        return "model." + key;
    }
}
