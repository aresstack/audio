package com.aresstack.audio.studio.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * A JSON object that is still being written, as far as it can be read: every value that is
 * already whole, in lists and objects that are closed here for the reading. A text, number or
 * name the answer breaks off in is left out. Only for showing an answer while it arrives; what
 * is done with the answer is decided on the whole of it.
 */
public final class PartialJson {
    private PartialJson() {
    }

    /** The object the text begins to write, or {@code null} if nothing of it can be read yet. */
    public static JsonObject read(CharSequence written) {
        String text = written.toString();
        int open = text.indexOf('{');
        if (open < 0) {
            return null;
        }
        Deque<Character> nesting = new ArrayDeque<>();
        Deque<Boolean> wantsKey = new ArrayDeque<>();
        int safe = -1;
        String closers = "";
        boolean quoted = false;
        boolean key = false;
        int bare = -1;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    quoted = false;
                    if (!key) {
                        safe = i + 1;
                        closers = closers(nesting);
                    }
                }
                continue;
            }
            boolean ends = c == ',' || c == ']' || c == '}' || Character.isWhitespace(c);
            if (bare >= 0 && ends) {
                bare = -1;
                safe = i;
                closers = closers(nesting);
            }
            if (c == '"') {
                quoted = true;
                key = !nesting.isEmpty() && nesting.peek() == '{' && wantsKey.peek();
            } else if (c == '{' || c == '[') {
                nesting.push(c);
                wantsKey.push(c == '{');
                safe = i + 1;
                closers = closers(nesting);
            } else if (c == '}' || c == ']') {
                if (nesting.isEmpty()) {
                    break;
                }
                nesting.pop();
                wantsKey.pop();
                safe = i + 1;
                closers = closers(nesting);
                if (nesting.isEmpty()) {
                    break; // the object is whole
                }
            } else if (c == ':') {
                if (!wantsKey.isEmpty()) {
                    wantsKey.pop();
                    wantsKey.push(false);
                }
            } else if (c == ',') {
                if (!nesting.isEmpty() && nesting.peek() == '{') {
                    wantsKey.pop();
                    wantsKey.push(true);
                }
            } else if (!ends && bare < 0) {
                bare = i;
            }
        }
        if (safe < 0) {
            return null;
        }
        try {
            JsonElement read = JsonParser.parseString(text.substring(open, safe) + closers);
            return read.isJsonObject() ? read.getAsJsonObject() : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String closers(Deque<Character> nesting) {
        StringBuilder closers = new StringBuilder();
        for (Iterator<Character> open = nesting.iterator(); open.hasNext(); ) {
            closers.append(open.next() == '{' ? '}' : ']');
        }
        return closers.toString();
    }
}
