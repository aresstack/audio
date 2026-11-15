package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.application.PieceAutopilot;
import com.aresstack.audio.studio.application.PieceModel;
import com.aresstack.audio.studio.application.SongLibrary;
import com.aresstack.audio.studio.application.StageMixService;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Path;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * The pieces of an album made by the pipeline of a single piece: the autopilot (the AI proposes
 * the players and the stage and composes, the tracks are rendered) and then the mix on the stage.
 * This is the only class of the albums that knows that pipeline.
 */
public final class AutopilotPieceProduction implements PieceProduction {
    private final PieceAutopilot autopilot;
    private final SongLibrary songs;

    /**
     * @param autopilot with the models of this production; to limit the requests of all pieces
     *                  together, its lineup hands out {@link LimitedChatModel}s that share permits
     */
    public AutopilotPieceProduction(PieceAutopilot autopilot, SongLibrary songs) {
        this.autopilot = autopilot;
        this.songs = songs;
    }

    @Override
    public ProducedPiece produce(PieceOrder order, Progress progress) throws Exception {
        StringBuilder warnings = new StringBuilder();
        PieceModel model = new PieceModel(line -> {
            if (line.startsWith("WARN")) {
                warnings.append(line).append('\n');
            }
        });
        model.open(order.folder());
        PieceAutopilot.Result result = autopilot.run(order.description(), order.folder(), order.spec(), null, songs,
                model, progress, null);
        Path mix = new StageMixService().mix(result.arrangement(), order.folder(), progress);
        return new ProducedPiece(order.folder(), mix, seconds(mix));
    }

    @Override
    public void cancel() {
        autopilot.cancel();
    }

    /** How long a WAV file is, in seconds. */
    static double seconds(Path wav) throws IOException {
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(wav.toFile())) {
            return audio.getFrameLength() / (double) audio.getFormat().getFrameRate();
        } catch (javax.sound.sampled.UnsupportedAudioFileException notAudio) {
            throw new IOException("Keine WAV-Datei: " + wav.getFileName(), notAudio);
        }
    }
}
