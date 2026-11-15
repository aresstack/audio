package com.aresstack.audio.studio.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * The notes of an answer that is still being written: every note object of the list
 * {@code "notes"} that is already whole. What comes after the last whole note is left out, so
 * the text may break off anywhere. Only for looking at a piece while it is composed; the piece
 * itself is made from the whole answer.
 */
public final class PartialNotes {
    private PartialNotes() {
    }

    /** The whole note objects in the text so far, in the order they were written. */
    public static JsonArray read(CharSequence text) {
        JsonArray notes = new JsonArray();
        String written = text.toString();
        int key = written.indexOf("\"notes\"");
        int open = key < 0 ? -1 : written.indexOf('[', key);
        if (open < 0) {
            return notes;
        }
        int depth = 0;
        int begin = -1;
        boolean quoted = false;
        for (int i = open + 1; i < written.length(); i++) {
            char c = written.charAt(i);
            if (quoted) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == '{') {
                if (depth++ == 0) {
                    begin = i;
                }
            } else if (c == '}') {
                if (depth > 0 && --depth == 0) {
                    try {
                        JsonElement note = JsonParser.parseString(written.substring(begin, i + 1));
                        if (note.isJsonObject()) {
                            notes.add(note);
                        }
                    } catch (RuntimeException unreadable) {
                        // not a note to show; the whole answer is checked when it is there
                    }
                }
            } else if (c == ']' && depth == 0) {
                break; // the end of the list
            }
        }
        return notes;
    }
}
