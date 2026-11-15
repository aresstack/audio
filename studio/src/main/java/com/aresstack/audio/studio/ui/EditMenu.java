package com.aresstack.audio.studio.ui;

/** What the entries "Rückgängig" and "Wiederholen" say: the change they would take back or do again. */
final class EditMenu {
    record Labels(String undo, boolean canUndo, String redo, boolean canRedo) {
    }

    private EditMenu() {
    }

    static Labels labels(boolean canUndo, String undoDescription, boolean canRedo, String redoDescription) {
        return new Labels(label("Rückgängig", canUndo, undoDescription), canUndo,
                label("Wiederholen", canRedo, redoDescription), canRedo);
    }

    private static String label(String verb, boolean can, String what) {
        if (!can || what == null || what.trim().isEmpty()) {
            return verb;
        }
        String shown = what.trim();
        return verb + ": " + (shown.length() > 60 ? shown.substring(0, 59) + "…" : shown);
    }
}
