package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.application.CompositionSpec;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Chooses the advanced settings of a single piece that the user left automatic (length, tempo,
 * key, meter) from its description, as the plan of an album does for its titles: the piece is
 * planned as an album of one given title, with the settings the user set kept. No second way of
 * planning: the same planner, the same rules.
 */
public final class PieceSpecPlanner {
    /** The settings planning may choose. */
    public static final List<String> SETTINGS = List.of(AlbumTrack.LENGTH, AlbumTrack.BPM, AlbumTrack.KEY,
            AlbumTrack.METER);
    /** The name of the one title the piece is planned as. */
    static final String TITLE = "Dieses Stück";
    /** How long a piece is when neither the user nor the plan says otherwise: as long as an album piece at most. */
    static final int USUAL_SECONDS = AlbumConcept.DEFAULT_MAX_SECONDS;
    /** Told to the plan with the description, so that a length off the usual one is a choice. */
    static final String LENGTH_GUIDANCE = "One single piece. Choose its length (targetSeconds) from the description: "
            + "usually about " + USUAL_SECONDS + " seconds; clearly shorter or longer only when the description or "
            + "the kind of music asks for it (a jingle, an intro, an extended DJ mix, a long ambient piece).";

    private PieceSpecPlanner() {
    }

    /**
     * The spec a piece is composed with: what the user set, and for the rest what the plan says.
     * Without anything automatic, {@code shown} as it is, without asking.
     *
     * @param shown what the fields show; its values count where {@code fixed} names them
     * @param fixed the settings the user set ({@link AlbumTrack#LENGTH} for the bars, ...)
     * @param cache where the answer of the plan is kept, or {@code null}
     */
    public static CompositionSpec plan(ChatModel model, int maxTokens, String description, CompositionSpec shown,
                                       Set<String> fixed, Path cache, Progress progress) throws IOException {
        if (fixed.containsAll(SETTINGS)) {
            return shown;
        }
        Set<String> kept = new TreeSet<String>(fixed);
        kept.retainAll(SETTINGS);
        // The length the piece has when the plan names none: what the user set, else the usual one.
        int seconds = kept.contains(AlbumTrack.LENGTH) ? (int) Math.round(shown.seconds()) : USUAL_SECONDS;
        AlbumTrack piece = new AlbumTrack("t1", TITLE, "", shown.bpm(), shown.key().name(),
                shown.numerator() + "/" + shown.denominator(), "", "", seconds, "", true, "", kept);
        // The plan may choose any length from 10 s to an hour when the description asks for a short
        // or a long piece; without one it is the usual length (not the middle of the range: that was
        // half an hour, 925 bars of techno at 123 BPM).
        Album album = new Album("piece", TITLE, new AlbumConcept(description, LENGTH_GUIDANCE, 1,
                AlbumConcept.MIN_SECONDS, AlbumConcept.MAX_SECONDS), List.of(piece));
        progress.update("KI wählt aus der Beschreibung, was automatisch ist (Länge, Tempo, Tonart, Taktart) ...");
        AlbumTrack planned = new ChatAlbumPlanner(model, maxTokens).plan(album, cache, progress).tracks().get(0);
        return of(planned, shown, kept);
    }

    /** The spec of a planned title, with the bars the user set if the length is theirs. */
    static CompositionSpec of(AlbumTrack planned, CompositionSpec shown, Set<String> fixed) {
        CompositionSpec spec = planned.spec();
        int bars = fixed.contains(AlbumTrack.LENGTH) ? shown.bars() : spec.bars();
        return CompositionSpec.of(bars, spec.bpm(), spec.key(), spec.numerator(), spec.denominator())
                .withSnapToHarmony(shown.snapToHarmony());
    }
}
