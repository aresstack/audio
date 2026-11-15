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
 * live playback: plays the WAV stems of a folder (or the files given) on the sound card and moves
 * the first track while it plays, in three ways: (1) dragged in a slow circle, a new place every
 * 16 ms as from the mouse; (2) jumps to the corners that glide on the stage for 600 ms; (3) for
 * comparison, hard jumps with only the 20 ms channel glide. Then it prints how much of its time
 * the audio thread needed and whether a block came late.
 *
 * <pre>
 * java -cp audio-studio.jar com.aresstack.audio.studio.mix.LiveStageCheck &lt;folder or WAV files&gt; [seconds] [room] [loop] [puffer=frames]
 * </pre>
 *
 * The room is one of {@code studio}, {@code chamber} (default), {@code hall}, {@code cathedral};
 * {@code loop} (or {@code schleife}) plays the music over and over without a gap, for short stems;
 * {@code puffer=<frames>} sets the line buffer. At the end it also prints how often the sound card
 * ran empty, which a late block alone does not show.
 */
public final class LiveStageCheck {
    private LiveStageCheck() {
    }

    /** When the check ends, by the clock. */
    private static long until;

    /** Whether the check goes on: playing, and its time not yet over. */
    private static boolean running(LivePlayback playback) {
        return playback.state() == LivePlayback.State.PLAYING && System.nanoTime() < until;
    }

    private static void pause(LivePlayback playback, long millis) throws InterruptedException {
        for (long waited = 0; waited < millis && running(playback); waited += 50) {
            Thread.sleep(50);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Aufruf: LiveStageCheck <Ordner mit WAV-Stems oder WAV-Dateien> [Sekunden] [studio|chamber|hall|cathedral] [schleife] [puffer=4096]");
            System.exit(2);
        }
        List<Path> files = new ArrayList<Path>();
        double seconds = 25;
        AcousticRoom room = AcousticRoom.CHAMBER;
        boolean loop = false;
        int buffer = com.aresstack.audio.core.line.LineSink.DEFAULT_BUFFER_FRAMES;
        for (String arg : args) {
            Path path = Paths.get(arg);
            if (Files.isDirectory(path)) {
                try (Stream<Path> listed = Files.list(path)) {
                    listed.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav"))
                            .sorted().forEach(files::add);
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            } else if (arg.toLowerCase(Locale.ROOT).startsWith("puffer=")) {
                buffer = Integer.parseInt(arg.substring("puffer=".length()));
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
             LivePlayback playback = stage.playOnLine(LiveStage.master(Float.NaN, 0), buffer)) {
            // a loop plays the music over and over without a gap; the room rings from the end into the start
            stage.loop(loop);
            playback.play();
            until = System.nanoTime() + (long) (seconds * 1e9);
            SoundSourcePlacement home = placements.get(0);
            double[][] corners = {{-1, 0.1}, {1, 0.1}, {1, 0.9}, {-1, 0.9}};
            while (running(playback)) {
                // 1. Dragging: a new place every 16 ms, as the mouse delivers it, once around the stage in 6 s.
                System.out.printf(Locale.GERMANY, "%6.2f s  1) Ziehen: die erste Spur kreist langsam über die Bühne%n",
                        playback.heardSeconds());
                long started = System.nanoTime();
                while (System.nanoTime() - started < 6_000_000_000L && running(playback)) {
                    double turn = (System.nanoTime() - started) / 6e9 * 2 * Math.PI;
                    stage.move(0, home.at(-Math.cos(turn), 0.5 - 0.4 * Math.sin(turn)));
                    Thread.sleep(16);
                }
                // 2. Jumps that glide on the stage for 600 ms.
                for (int i = 0; i < corners.length && running(playback); i++) {
                    double[] corner = corners[i];
                    System.out.printf(Locale.GERMANY, "%6.2f s  2) Gleitsprung (600 ms) nach x=%+.0f, Tiefe=%.1f%n",
                            playback.heardSeconds(), corner[0], corner[1]);
                    stage.glide(0, home.at(corner[0], corner[1]), 600);
                    pause(playback, 2000);
                }
                // 3. For comparison: hard jumps (the channel glides only 20 ms).
                for (int i = 0; i < corners.length && running(playback); i++) {
                    double[] corner = corners[i];
                    System.out.printf(Locale.GERMANY, "%6.2f s  3) harter Sprung (20 ms) nach x=%+.0f, Tiefe=%.1f%n",
                            playback.heardSeconds(), corner[0], corner[1]);
                    stage.move(0, home.at(corner[0], corner[1]));
                    pause(playback, 2000);
                }
            }
            playback.pause();
            System.out.printf(Locale.GERMANY, "Blöcke: %d, mittlere Last: %.1f %%, längster Block: %.2f ms von %.2f ms, zu spät: %d%n",
                    playback.blocks(), playback.averageLoad() * 100, playback.worstBlockNanos() / 1e6,
                    playback.blockBudgetNanos() / 1e6, playback.overruns());
            System.out.printf(Locale.GERMANY, "Leitungspuffer: %d Frames, leergelaufen: %d, knappster Füllstand: %d Frames%n",
                    buffer, playback.underruns(), playback.lowestBufferedFrames());
            if (playback.failure() != null) {
                playback.failure().printStackTrace();
            }
        }
    }
}
