package com.aresstack.audio.studio.album;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a list of titles in what the user wrote about an album: a table whose rows begin with a
 * number (as a Markdown table "| 01 | Title | direction |" or tab separated, as pasted from a
 * spreadsheet), or a numbered list ("1. Title – direction"). Each title may come with a few words
 * on how it sounds. Headings ("## 1. ...") are no titles.
 */
public final class TitleList {
    /** One title of the list and what the user said about how it sounds; the words may be empty. */
    public record Entry(String title, String direction) {
    }

    /** A list shorter than this is no list of titles but a numbered remark. */
    static final int LEAST = 2;
    private static final Pattern NUMBER = Pattern.compile("\\d{1,2}\\.?");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d{1,2})\\s*[.):]\\s*(.+)$");
    private static final Pattern SPLIT = Pattern.compile("\\s+[–—-]\\s+|:\\s+|\\t+");

    private TitleList() {
    }

    /** The titles the text lists, in their order; empty if it lists none. */
    public static List<Entry> in(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Entry> table = new ArrayList<Entry>();
        List<Entry> numbered = new ArrayList<Entry>();
        List<Entry> run = new ArrayList<Entry>();
        for (String raw : text.split("\\R")) {
            String line = plain(raw);
            List<String> cells = cells(line);
            if (cells.size() >= 2 && NUMBER.matcher(cells.get(0)).matches() && !cells.get(1).isEmpty()) {
                table.add(new Entry(cells.get(1), String.join(", ", cells.subList(2, cells.size())
                        .stream().filter(cell -> !cell.isEmpty()).toList())));
                continue;
            }
            Matcher item = line.startsWith("#") ? null : NUMBERED.matcher(line);
            if (item != null && item.matches()) {
                String[] parts = SPLIT.split(item.group(2).trim(), 2);
                run.add(new Entry(clean(parts[0]), parts.length > 1 ? clean(parts[1]) : ""));
            } else if (!line.isBlank()) {
                // a numbered list ends where other text begins; blank lines may stand between its items
                if (run.size() >= LEAST && numbered.isEmpty()) {
                    numbered.addAll(run);
                }
                run.clear();
            }
        }
        if (run.size() >= LEAST && numbered.isEmpty()) {
            numbered.addAll(run);
        }
        List<Entry> found = table.size() >= LEAST ? table : numbered;
        found.removeIf(entry -> entry.title().isEmpty());
        return found.size() >= LEAST ? List.copyOf(found) : List.of();
    }

    /** The cells of a table row: "| a | b |" or "a\tb"; one cell for a line that is no row. */
    private static List<String> cells(String line) {
        String row = line.trim();
        List<String> cells = new ArrayList<String>();
        if (row.startsWith("|")) {
            row = row.substring(1);
            if (row.endsWith("|")) {
                row = row.substring(0, row.length() - 1);
            }
            for (String cell : row.split("\\|", -1)) {
                cells.add(clean(cell));
            }
        } else if (row.contains("\t")) {
            for (String cell : row.split("\t", -1)) {
                cells.add(clean(cell));
            }
        } else {
            cells.add(row);
        }
        return cells;
    }

    /** The line without Markdown emphasis. */
    private static String plain(String line) {
        return line.replace("**", "").replace("__", "");
    }

    private static String clean(String text) {
        String cleaned = text.trim();
        if (cleaned.length() >= 2 && (cleaned.startsWith("\"") && cleaned.endsWith("\"")
                || cleaned.startsWith("„") && cleaned.endsWith("“"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }
}
