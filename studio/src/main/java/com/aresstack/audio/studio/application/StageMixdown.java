package com.aresstack.audio.studio.application;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.dsp.LoudnessMeter;
import com.aresstack.audio.core.mix.ExportNormalization;
import com.aresstack.audio.studio.album.AlbumExporter;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The mixdown of a piece for an album. The piece is mixed on its own stage exactly as its own
 * export mixes it (files and exports keep the level they always had), and the finished mix is
 * then made louder or quieter as a whole until it has the loudness of the album: one factor for
 * the whole title, no limiter, so the dynamics within the piece stay as they are. The peaks stay
 * below −1 dBFS; a title with high peaks therefore comes out a little quieter than the target.
 * Nothing of the piece is changed.
 */
public final class StageMixdown implements AlbumExporter.Mixdown {
    private final StageMixService service = new StageMixService();

    @Override
    public void mix(Path pieceFolder, Path target, int bits, double lufs) throws Exception {
        Path arrangement = pieceFolder.resolve("arrangement.tsv");
        if (!Files.isRegularFile(arrangement)) {
            throw new IOException("Das Stück ist noch nicht komponiert.");
        }
        // Mixed with 24 bits first, so that the factor is not applied to a mix already rounded to 16.
        Path plain = Files.createTempFile(target.toAbsolutePath().getParent(), "title-", ".part");
        try {
            service.export(arrangement, pieceFolder, service.scene(arrangement, pieceFolder), plain, 24);
            SpatialStemMixer.Signal mixed = SpatialStemMixer.read(plain);
            float gain = (float) gain(mixed, lufs);
            for (int i = 0; i < mixed.left.length; i++) {
                mixed.left[i] *= gain;
                mixed.right[i] *= gain;
            }
            SpatialStemMixer.write(mixed, target, bits);
        } finally {
            Files.deleteIfExists(plain);
        }
    }

    /** The one factor that brings a finished mix to this loudness, with its peaks below −1 dBFS. */
    static double gain(SpatialStemMixer.Signal mixed, double lufs) {
        float peak = 0;
        for (int i = 0; i < mixed.left.length; i++) {
            peak = Math.max(peak, Math.max(Math.abs(mixed.left[i]), Math.abs(mixed.right[i])));
        }
        LoudnessMeter meter = new LoudnessMeter();
        meter.prepare(SpatialStemMixer.RATE, SpatialStemMixer.BLOCK);
        AudioBlock block = new AudioBlock(SpatialStemMixer.BLOCK);
        for (int at = 0; at < mixed.left.length; at += SpatialStemMixer.BLOCK) {
            int count = Math.min(SpatialStemMixer.BLOCK, mixed.left.length - at);
            block.setFrames(count);
            System.arraycopy(mixed.left, at, block.left, 0, count);
            System.arraycopy(mixed.right, at, block.right, 0, count);
            meter.process(block);
        }
        return ExportNormalization.loudness(lufs).gain(peak, meter::integrated);
    }
}
