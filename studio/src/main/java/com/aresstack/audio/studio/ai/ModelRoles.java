package com.aresstack.audio.studio.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The roles the studio knows (architecture: role based model choice). Two are shown on the
 * composition page, planning and composition; the others follow one of them until the user
 * gives them a model of their own in the extended assignment.
 */
public final class ModelRoles {
    /** Musical plan, structure, harmonies, tempo and form. */
    public static final ModelRole PLANNING = new ModelRole("planning", "Planung",
            "Musikalischer Plan, Struktur und Harmonien", null, true);
    /** The notes of each instrument track. */
    public static final ModelRole COMPOSITION = new ModelRole("composition", "Komposition",
            "Noten und Instrumentenspuren generieren", null, true);
    /** The proposed instruments and their places on the stage. */
    public static final ModelRole INSTRUMENTS = new ModelRole("instruments", "Instrumente",
            "Besetzung und Bühnenplätze vorschlagen", PLANNING.key(), false);
    /** Extending, inserting and reworking music that is there (C1 to C4 generators). */
    public static final ModelRole REWORK = new ModelRole("rework", "Erweitern und Umarbeiten",
            "Noten für neue Stimmen, Passagen und Fortsetzungen", COMPOSITION.key(), false);
    /** Plans of arrangement, orchestration and continuation (C1 to C4 planners). */
    public static final ModelRole ARRANGING = new ModelRole("arranging", "Arrangement",
            "Arrangement, Orchestrierung und Fortsetzung planen", PLANNING.key(), false);
    /** The AI mix proposal. */
    public static final ModelRole MIX = new ModelRole("mix", "Abmischung",
            "Pegel, Verläufe, Plätze und Raum vorschlagen", PLANNING.key(), false);

    private static final Map<String, ModelRole> ROLES = new LinkedHashMap<>();

    static {
        for (ModelRole role : List.of(PLANNING, COMPOSITION, INSTRUMENTS, REWORK, ARRANGING, MIX)) {
            ROLES.put(role.key(), role);
        }
    }

    private ModelRoles() {
    }

    /**
     * Adds a role, for example the album planner's. Registering the same role again does nothing;
     * another role under a known key, or one whose fallback is unknown, is refused.
     */
    public static synchronized ModelRole register(ModelRole role) {
        ModelRole known = ROLES.get(role.key());
        if (known != null) {
            if (!known.equals(role)) {
                throw new IllegalArgumentException("Another role is registered as " + role.key());
            }
            return known;
        }
        if (role.fallback() != null && !ROLES.containsKey(role.fallback())) {
            throw new IllegalArgumentException("Unknown fallback role " + role.fallback() + " for " + role.key());
        }
        ROLES.put(role.key(), role);
        return role;
    }

    /** All roles in the order they were registered. */
    public static synchronized List<ModelRole> all() {
        return List.copyOf(ROLES.values());
    }

    /** The roles the composition page shows. */
    public static synchronized List<ModelRole> visible() {
        List<ModelRole> shown = new ArrayList<>();
        for (ModelRole role : ROLES.values()) {
            if (role.visible()) {
                shown.add(role);
            }
        }
        return List.copyOf(shown);
    }

    /** The role of a key, or {@code null}. */
    public static synchronized ModelRole byKey(String key) {
        return ROLES.get(key);
    }
}
