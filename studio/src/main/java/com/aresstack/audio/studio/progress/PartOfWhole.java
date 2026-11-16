package com.aresstack.audio.studio.progress;

import java.util.List;

/**
 * The progress of one step of a longer work (a portion of a long piece): everything is passed on
 * as it is, but how far the notes are is told as a share of the whole, so that the bar under the
 * status line goes on over all steps instead of starting anew with each.
 */
public final class PartOfWhole implements SketchProgress {
    private final SketchProgress whole;
    private final double from;
    private final double to;

    private PartOfWhole(SketchProgress whole, double from, double to) {
        this.whole = whole;
        this.from = from;
        this.to = to;
    }

    /**
     * The progress of a step that covers the share {@code from} to {@code to} (0 to 1) of the
     * whole; {@code progress} itself if it does not show a sketch.
     */
    public static Progress of(Progress progress, double from, double to) {
        if (!(progress instanceof SketchProgress)) {
            return progress;
        }
        double start = Math.max(0, Math.min(1, from));
        return new PartOfWhole((SketchProgress) progress, start, Math.max(start, Math.min(1, to)));
    }

    @Override
    public void done(double share) {
        whole.done(from + Math.max(0, Math.min(1, share)) * (to - from));
    }

    @Override
    public void update(String message) {
        whole.update(message);
    }

    @Override
    public void detail(String message) {
        whole.detail(message);
    }

    @Override
    public void track(String trackId, Stage stage, State state, String detail) {
        whole.track(trackId, stage, state, detail);
    }

    @Override
    public void sketch(List<String> arrangement) {
        whole.sketch(arrangement);
    }

    @Override
    public void arriving(long characters) {
        whole.arriving(characters);
    }

    @Override
    public void preparing(String what, long characters) {
        whole.preparing(what, characters);
    }

    @Override
    public void planned(List<String> chords) {
        whole.planned(chords);
    }

    @Override
    public void form(List<FormSection> sections) {
        whole.form(sections);
    }

    @Override
    public void instruments(List<String[]> chosen) {
        whole.instruments(chosen);
    }
}
