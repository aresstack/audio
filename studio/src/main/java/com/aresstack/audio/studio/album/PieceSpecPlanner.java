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
        AlbumTrack piece = new AlbumTrack("t1", TITLE, "", shown.bpm(), shown.key().name(),
                shown.numerator() + "/" + shown.denominator(), "", "", (int) Math.round(shown.seconds()), "", true, "",
                kept);
        // A piece chosen automatically is short to as long as an album piece at most (10 s to 6 min); a
        // longer one is set by the user. With the whole range up to an hour, an answer without a length
        // fell back to its middle: half an hour, 925 bars of techno at 123 BPM.
        Album album = new Album("piece", TITLE, new AlbumConcept(description, "", 1, AlbumConcept.MIN_SECONDS,
                AlbumConcept.DEFAULT_MAX_SECONDS), List.of(piece));
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
