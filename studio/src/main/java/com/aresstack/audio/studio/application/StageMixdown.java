package com.aresstack.audio.studio.application;

import com.aresstack.audio.core.dsp.LookaheadLimiter;
import com.aresstack.audio.core.mix.ExportNormalization;
import com.aresstack.audio.studio.album.AlbumExporter;
import com.aresstack.audio.studio.mix.MixLevel;
import com.aresstack.audio.studio.stage.SpatialScene;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The mixdown of a piece for an album: the piece is mixed on its own stage as it is saved, and
 * the file is brought to the target loudness (work package D1b: fixed level structure without
 * make-up gain, limiter at −1 dBFS, loudness normalization of the written file). The limiter
 * only catches peaks; how loud the file is as a whole is set by the normalization, so the
 * dynamics within the piece stay. Nothing of the piece is changed.
 */
public final class StageMixdown implements AlbumExporter.Mixdown {
    private final StageMixService service = new StageMixService();

    @Override
    public void mix(Path pieceFolder, Path target, int bits, double lufs) throws Exception {
        Path arrangement = pieceFolder.resolve("arrangement.tsv");
        if (!Files.isRegularFile(arrangement)) {
            throw new IOException("Das Stück ist noch nicht komponiert.");
        }
        SpatialScene scene = service.scene(arrangement, pieceFolder);
        MixLevel level = MixLevel.fixed(0, scene.masterDb(), LookaheadLimiter.Settings.DEFAULT,
                ExportNormalization.loudness(lufs));
        service.export(arrangement, pieceFolder, scene, target, bits, level);
    }
}
