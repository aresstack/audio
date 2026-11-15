package com.aresstack.audio.studio.mix;

import com.aresstack.audio.core.live.LivePlayback;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A listening check of acceptance A13 on the machine it runs on, before the stage view is wired to
 * live playback: plays the WAV stems of a folder (or the files given) on the sound card and drags
 * the first track around the stage while it plays, then prints how much of its time the audio
 * thread needed and whether a block came late.
 *
 * <pre>
 * java -cp audio-studio.jar com.aresstack.audio.studio.mix.LiveStageCheck &lt;folder or WAV files&gt; [seconds] [room] [loop]
 * </pre>
 *
 * The room is one of {@code studio}, {@code chamber} (default), {@code hall}, {@code cathedral};
 * {@code loop} (or {@code schleife}) starts the piece again whenever it ends, for short stems.
 */
public final class LiveStageCheck {
    private LiveStageCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Aufruf: LiveStageCheck <Ordner mit WAV-Stems oder WAV-Dateien> [Sekunden] [studio|chamber|hall|cathedral] [schleife]");
            System.exit(2);
        }
        List<Path> files = new ArrayList<Path>();
        double seconds = 20;
        AcousticRoom room = AcousticRoom.CHAMBER;
        boolean loop = false;
        for (String arg : args) {
            Path path = Paths.get(arg);
            if (Files.isDirectory(path)) {
                try (Stream<Path> listed = Files.list(path)) {
                    listed.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav"))
                            .sorted().forEach(files::add);
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            } else if (arg.equalsIgnoreCase("loop") || arg.equalsIgnoreCase("schleife")) {
                loop = true;
            } else if (AcousticRoom.known(arg)) {
                room = AcousticRoom.of(arg);
            } else {
                seconds = Double.parseDouble(arg);
            }
        }
        if (files.isEmpty()) {
            System.err.println("Keine WAV-Dateien gefunden.");
            System.exit(2);
        }
        List<SoundSourcePlacement> placements = new ArrayList<SoundSourcePlacement>();
        for (int i = 0; i < files.size(); i++) {
            double x = files.size() == 1 ? 0 : -0.8 + 1.6 * i / (files.size() - 1);
            placements.add(new SoundSourcePlacement("spur-" + i, x, 0.3));
        }
        System.out.println("Lade " + files.size() + " Spuren, Raum: " + room.name() + " ...");
        try (LiveStage stage = LiveStage.load(files, placements, room, 2);
             LivePlayback playback = stage.playOnLine(LiveStage.master(MixLevel.fixed(0), Float.NaN))) {
            playback.play();
            System.out.println("Spielt. Die erste Spur wandert über die Bühne: links vorn, rechts vorn, rechts hinten, links hinten.");
            double[][] corners = {{-1, 0}, {1, 0}, {1, 1}, {-1, 1}};
            long until = System.nanoTime() + (long) (seconds * 1e9);
            int step = 0;
            while (System.nanoTime() < until) {
                LivePlayback.State state = playback.state();
                if (state == LivePlayback.State.ENDED && loop) {
                    playback.play();
                } else if (state != LivePlayback.State.PLAYING) {
                    break;
                }
                double[] corner = corners[step++ % corners.length];
                stage.move(0, placements.get(0).at(corner[0], corner[1]));
                System.out.printf(Locale.GERMANY, "%6.2f s  erste Spur bei x=%+.0f, Tiefe=%.0f%n", playback.heardSeconds(),
                        corner[0], corner[1]);
                // in steps of 50 ms, so that a loop starts again without a long gap
                for (int wait = 0; wait < 40 && System.nanoTime() < until; wait++) {
                    Thread.sleep(50);
                    if (loop && playback.state() == LivePlayback.State.ENDED) {
                        playback.play();
                    }
                }
            }
            playback.pause();
            System.out.printf(Locale.GERMANY, "Blöcke: %d, mittlere Last: %.1f %%, längster Block: %.2f ms von %.2f ms, zu spät: %d%n",
                    playback.blocks(), playback.averageLoad() * 100, playback.worstBlockNanos() / 1e6,
                    playback.blockBudgetNanos() / 1e6, playback.overruns());
            if (playback.failure() != null) {
                playback.failure().printStackTrace();
            }
        }
    }
}
