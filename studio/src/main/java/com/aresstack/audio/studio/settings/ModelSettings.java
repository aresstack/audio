package com.aresstack.audio.studio.settings;

import com.aresstack.audio.studio.ai.ModelAssignment;
import com.aresstack.audio.studio.ai.ModelRole;
import com.aresstack.audio.studio.ai.ModelRoles;
import java.util.Properties;

/**
 * The model of each role in {@code studio.properties}:
 *
 * <pre>
 * model=google/gemma-4-31B-it
 * model.planning=gpt-oss:20b
 * model.composition=google/gemma-4-31B-it
 * </pre>
 *
 * {@code model} stays as it was and is the base: a role without an entry takes its fallback
 * role's model and in the end this one. Settings of before therefore mean exactly what they
 * meant, Gemma for everything.
 */
public final class ModelSettings {
    private ModelSettings() {
    }

    /** The assignment the settings describe; missing entries fall back as described above. */
    public static ModelAssignment read(Properties settings) {
        String base = settings.getProperty("model", "");
        ModelAssignment assignment = ModelAssignment.single(base.isBlank() ? AiDefaults.MODEL : base.trim());
        for (ModelRole role : ModelRoles.all()) {
            String own = settings.getProperty(role.settingKey());
            if (own != null && !own.isBlank()) {
                assignment = assignment.with(role, own);
            }
        }
        return assignment;
    }

    /**
     * Writes an assignment into settings: the base as {@code model}, an entry for each role with
     * a model of its own, none for a role that follows another.
     */
    public static void write(Properties settings, ModelAssignment assignment) {
        settings.setProperty("model", assignment.base());
        for (ModelRole role : ModelRoles.all()) {
            String own = assignment.ownModel(role);
            if (own == null) {
                settings.remove(role.settingKey());
            } else {
                settings.setProperty(role.settingKey(), own);
            }
        }
    }
}
