package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.edit.ChangeSet;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The editor's way into the open piece (work package B1): it applies one {@link NoteEdits.NoteEdit}
 * at a time through {@link ProjectSession#apply}, keeps what was copied, and undoes and redoes
 * through the session's history. Every change goes through the edit core and is saved at once, so
 * a change by hand survives the next change of the AI to another part (A10).
 *
 * <p>Nothing here knows Swing. The window asks, and gets back what to say and which notes are
 * selected now.
 */
public final class PieceEditing {
    /** What became of an action of the editor. */
    public record Outcome(boolean changed, String said, List<NoteEdits.NoteRef> selected, ChangeSet changes) {
        public Outcome {
            said = said == null ? "" : said;
            selected = List.copyOf(selected == null ? List.of() : selected);
        }

        static Outcome nothing(String said) {
            return new Outcome(false, said, List.of(), null);
        }
    }

    private final Supplier<Optional<ProjectSession>> piece;
    private final Supplier<String> ids;
    private NoteEdits.Clipboard clipboard = new NoteEdits.Clipboard(List.of());

    /** @param piece the open piece, empty when it has no model (then nothing can be edited) */
    public PieceEditing(Supplier<Optional<ProjectSession>> piece) {
        this(piece, Ids.random());
    }

    PieceEditing(Supplier<Optional<ProjectSession>> piece, Supplier<String> ids) {
        this.piece = piece;
        this.ids = ids;
    }

    /** The piece as the editor shows it, if there is a model to edit. */
    public Optional<Composition> composition() {
        return piece.get().map(ProjectSession::composition);
    }

    public boolean canEdit() {
        return piece.get().isPresent();
    }

    /**
     * Makes an edit from the piece as it is now and applies it: one undoable step. An edit that
     * changes nothing is not applied; one the edit core refuses changes nothing and says why.
     */
    public Outcome apply(Function<Composition, NoteEdits.NoteEdit> making) {
        Optional<ProjectSession> open = piece.get();
        if (open.isEmpty()) {
            return Outcome.nothing("Dieses Stück hat noch kein Werkmodell; erst komponieren oder öffnen.");
        }
        ProjectSession session = open.get();
        NoteEdits.NoteEdit edit = making.apply(session.composition());
        if (edit.isEmpty()) {
            return Outcome.nothing("");
        }
        try {
            ChangeSet changes = session.apply(edit.patch(), edit.scope());
            return new Outcome(true, edit.patch().description(), edit.selected(), changes);
        } catch (EditRefusedException refused) {
            return Outcome.nothing("Nicht möglich: " + refused.violations());
        } catch (IOException | RuntimeException failed) {
            return Outcome.nothing("Die Änderung ließ sich nicht speichern: " + failed.getMessage());
        }
    }

    public Outcome move(Collection<NoteEdits.NoteRef> notes, long ticks, int semitones) {
        return apply(piece -> NoteEdits.move(piece, notes, ticks, semitones));
    }

    public Outcome resize(Collection<NoteEdits.NoteRef> notes, long ticks, long shortest) {
        return apply(piece -> NoteEdits.resize(piece, notes, ticks, shortest));
    }

    public Outcome insert(String trackId, long tick, int pitch, long length) {
        return apply(piece -> NoteEdits.insert(piece, trackId, tick, pitch, length, ids));
    }

    public Outcome delete(Collection<NoteEdits.NoteRef> notes) {
        return apply(piece -> NoteEdits.delete(piece, notes));
    }

    public Outcome quantize(Collection<NoteEdits.NoteRef> notes, long grid) {
        return apply(piece -> NoteEdits.quantize(piece, notes, grid));
    }

    /** Keeps the notes for pasting; returns how many were copied. */
    public int copy(Collection<NoteEdits.NoteRef> notes) {
        Optional<Composition> shown = composition();
        if (shown.isEmpty()) {
            return 0;
        }
        NoteEdits.Clipboard copied = NoteEdits.copy(shown.get(), notes);
        if (!copied.isEmpty()) {
            clipboard = copied;
        }
        return copied.entries().size();
    }

    /** Copies the notes and deletes them, as one undoable step. */
    public Outcome cut(Collection<NoteEdits.NoteRef> notes) {
        if (copy(notes) == 0) {
            return Outcome.nothing("");
        }
        return delete(notes);
    }

    public boolean canPaste() {
        return !clipboard.isEmpty();
    }

    public Outcome paste(String trackId, long atTick) {
        NoteEdits.Clipboard pasting = clipboard;
        return apply(piece -> NoteEdits.paste(piece, pasting, trackId, atTick, ids));
    }

    public boolean canUndo() {
        return piece.get().map(ProjectSession::canUndo).orElse(false);
    }

    public boolean canRedo() {
        return piece.get().map(ProjectSession::canRedo).orElse(false);
    }

    /** Takes back the last change of the piece, whoever made it. */
    public Outcome undo() {
        return step(false);
    }

    /** Does again what was last taken back. */
    public Outcome redo() {
        return step(true);
    }

    private Outcome step(boolean again) {
        Optional<ProjectSession> open = piece.get();
        if (open.isEmpty() || !(again ? open.get().canRedo() : open.get().canUndo())) {
            return Outcome.nothing("");
        }
        ProjectSession session = open.get();
        String what = again ? session.redoDescription() : session.undoDescription();
        try {
            ChangeSet changes = again ? session.redo() : session.undo();
            return new Outcome(true, (again ? "Wiederholt: " : "Rückgängig: ") + what, List.of(), changes);
        } catch (IOException | RuntimeException failed) {
            return Outcome.nothing("Ließ sich nicht " + (again ? "wiederholen" : "rückgängig machen") + ": "
                    + failed.getMessage());
        }
    }
}
