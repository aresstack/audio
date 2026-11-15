package com.aresstack.audio.studio.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sums up the lines about AI requests tried again: when many requests break off together, the
 * protocol says so in one line per model and cause, instead of one line for every request. Other
 * lines pass at once. Only on the event thread.
 */
final class TroubleDigest {
    /** How often what came together is said, in milliseconds. */
    static final int EVERY_MILLIS = 10_000;
    private static final Pattern RETRY = Pattern.compile(
            "^Verbindung zur KI: (.*) · Versuch (\\d+) in (.+) \\(([^()]*)\\)\\.$");

    private final Consumer<String> protocol;
    /** By model, then by cause with its numbers left out. */
    private final Map<String, Map<String, Cause>> waiting = new LinkedHashMap<String, Map<String, Cause>>();

    TroubleDigest(Consumer<String> protocol) {
        this.protocol = protocol;
    }

    /** A line about a request; one about a request tried again waits for the next summary. */
    void add(String line) {
        Matcher retry = line == null ? null : RETRY.matcher(line);
        if (retry == null || !retry.matches()) {
            protocol.accept(line);
            return;
        }
        String cause = retry.group(1);
        Cause known = waiting.computeIfAbsent(retry.group(4), model -> new LinkedHashMap<String, Cause>())
                .computeIfAbsent(cause.replaceAll("\\d+", "#"), key -> new Cause(cause));
        known.count++;
        int attempt = Integer.parseInt(retry.group(2));
        if (attempt > known.highest) {
            known.highest = attempt;
            known.longest = retry.group(3);
        }
        if (known.count == 1 || attempt < known.lowest) {
            known.lowest = attempt;
            known.shortest = retry.group(3);
        }
    }

    /** Says what came together since the last time, one line per model and cause. */
    void flush() {
        for (Map.Entry<String, Map<String, Cause>> model : waiting.entrySet()) {
            for (Cause cause : model.getValue().values()) {
                if (cause.count == 1) {
                    protocol.accept("Verbindung zur KI: " + cause.example + " · Versuch " + cause.highest + " in "
                            + cause.longest + " (" + model.getKey() + ").");
                } else {
                    protocol.accept("Verbindung zur KI (" + model.getKey() + "): " + cause.count
                            + " Anfragen gestört: " + cause.example + " · nächste Versuche in " + cause.shortest
                            + (cause.shortest.equals(cause.longest) ? "" : " bis " + cause.longest)
                            + " (Versuch " + cause.lowest + (cause.lowest == cause.highest ? "" : " bis "
                            + cause.highest) + ").");
                }
            }
        }
        waiting.clear();
    }

    private static final class Cause {
        final String example;
        int count;
        int lowest;
        int highest;
        String shortest = "";
        String longest = "";

        Cause(String example) {
            this.example = example;
        }
    }
}
