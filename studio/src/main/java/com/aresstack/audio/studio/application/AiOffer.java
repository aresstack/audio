package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A proposal of the AI as the window offers it, whatever was asked for (a new voice, a passage
 * written anew, a longer piece): it can be heard in a folder of its own, generated again, taken
 * or thrown away. The saved piece changes only when it is taken.
 */
public interface AiOffer {
    /** What the AI did, in a sentence for the user. */
    String what();

    /** What is worth knowing before deciding: the plan of the AI, how much was repaired. */
    List<String> notes();

    /** The piece as the proposal would make it. */
    Composition proposed();

    /**
     * Writes the preview folder of the open proposal: the proposed piece with the stage of the
     * piece and the stems it already has, so that only what is new is rendered there.
     */
    Path preview() throws IOException;

    /** The part to hear on its own beside the whole piece, or {@code null} if there is none. */
    String soloPartId();

    /** What the key that plays {@link #soloPartId()} alone is called. */
    default String soloName() {
        return "Nur das Neue anhören";
    }

    /**
     * The second of the proposed piece from which listening starts: 0 for a change within the
     * piece, a little before the first new bar where the proposal makes the piece longer.
     */
    default double listenFromSecond() {
        return 0;
    }

    /** The General MIDI programs of parts the proposal adds, by part: the studio's table does not know them yet. */
    Map<String, Integer> newPrograms();

    /**
     * What the proposal takes from the original and cannot give back, one line each: the user has
     * to see every line and say yes to it before the proposal can be taken ({@link #confirm}).
     * Empty for most proposals.
     */
    default List<String> toConfirm() {
        return List.of();
    }

    /** The user saw {@link #toConfirm()} and agrees; without it, {@link #accept} refuses such a proposal. */
    default void confirm() {
    }

    /** Has the AI do it again, differently; the open proposal is replaced. */
    void again(Progress progress) throws Exception;

    /** Takes the proposal into the piece: an undoable step and a revision. */
    ChangeSet accept() throws IOException;

    /** Throws the proposal away; the piece is as it was. */
    void discard() throws IOException;
}
