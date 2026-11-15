package com.aresstack.audio.studio.performance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The form a plan names for a new piece: its sections in order, each with a name, what it does and
 * how many bars it has. Read leniently, as everything a model writes: sections that say no length
 * are left out, what goes beyond the piece is cut, and what is missing at the end joins the last
 * section. A plan that names no section gives the piece one section over all its bars.
 */
public final class PlanForm {
    /** The name of the one section of a piece whose plan names none. */
    public static final String WHOLE = "A";

    private PlanForm() {
    }

    /**
     * A section of the plan.
     *
     * @param function what it does in the form, or {@code null}
     * @param bars     at least one
     */
    public record Named(String name, String function, int bars) {
    }

    /** The sections of a whole plan: they cover exactly {@code bars} bars. */
    public static List<Named> read(JsonObject plan, int bars) {
        List<Named> sections = seen(plan, bars);
        int covered = sections.stream().mapToInt(Named::bars).sum();
        if (sections.isEmpty()) {
            sections.add(new Named(WHOLE, null, Math.max(1, bars)));
        } else if (covered < bars) {
            Named last = sections.remove(sections.size() - 1);
            sections.add(new Named(last.name(), last.function(), last.bars() + bars - covered));
        }
        return sections;
    }

    /** The sections a plan names as far as it is written: none if it has named none yet. */
    public static List<Named> seen(JsonObject plan, int bars) {
        List<Named> sections = new ArrayList<>();
        if (plan == null || !plan.has("sections") || !plan.get("sections").isJsonArray()) {
            return sections;
        }
        int covered = 0;
        for (JsonElement element : plan.getAsJsonArray("sections")) {
            if (covered >= bars) {
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject given = element.getAsJsonObject();
            int length;
            try {
                length = (int) Math.round(given.get("bars").getAsDouble());
            } catch (RuntimeException unsaid) {
                continue;
            }
            if (length < 1) {
                continue;
            }
            length = Math.min(length, bars - covered);
            String name = text(given, "name");
            sections.add(new Named(name == null || name.isBlank() ? "Abschnitt " + (sections.size() + 1) : name.strip(),
                    text(given, "function"), length));
            covered += length;
        }
        return sections;
    }

    private static String text(JsonObject given, String field) {
        JsonElement value = given.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
