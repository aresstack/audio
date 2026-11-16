package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.progress.Progress;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Puts the finished pieces of an album together: one WAV file for each title, numbered in the
 * order of the album, a cue sheet that names these files in their order, so that a player plays
 * the album from it, and the cover as {@code cover.png}, where players such as VLC look for it.
 * If wanted, the whole album is written as one WAV file as well, with a pause between the titles.
 * Every piece is brought to the same target loudness, which changes only how loud the piece is as
 * a whole: what is quiet and loud within a piece stays. Exporting writes into the chosen folder
 * only and changes no piece.
 */
public final class AlbumExporter {
    /** Mixes one piece down to a file. The album does not know how. */
    public interface Mixdown {
        /**
         * Writes the mix of the piece in a folder to a WAV file at this loudness.
         *
         * @param lufs the integrated loudness to reach, in LUFS; the peaks stay below −1 dBFS,
         *             so a piece with high peaks may come out a little quieter
         */
        void mix(Path pieceFolder, Path target, int bits, double lufs) throws Exception;
    }

    /** Where the cover of an album comes from. The album does not know how it is made. */
    public interface Cover {
        /**
         * The cover as the bytes of a PNG file; {@code null} if the album has none. It may fail:
         * the export then lays a placeholder beside the titles and goes on.
         */
        byte[] png(Album album) throws Exception;
    }

    /**
     * @param lufs         the loudness every title is brought to
     * @param bits         16 or 24
     * @param continuous   whether the whole album is written as one file as well
     * @param pauseSeconds the pause between two titles in that file
     */
    public record Options(double lufs, int bits, boolean continuous, double pauseSeconds) {
        /** −16 LUFS, 24 bits, the titles as single files only; two seconds between them if they are joined. */
        public static final Options STANDARD = new Options(-16, 24, false, 2);

        public Options {
            if (!(lufs <= -5 && lufs >= -40)) {
                throw new IllegalArgumentException("Die Ziellautheit liegt zwischen −40 und −5 LUFS: " + lufs);
            }
            if (bits != 16 && bits != 24) {
                throw new IllegalArgumentException("16 oder 24 Bit: " + bits);
            }
            if (!(pauseSeconds >= 0 && pauseSeconds <= 30)) {
                throw new IllegalArgumentException("Die Pause liegt zwischen 0 und 30 Sekunden: " + pauseSeconds);
            }
        }
    }

    /** One exported title: its file, its length, and where it begins in the file of the whole album. */
    public record Exported(AlbumTrack track, Path file, double seconds, double startSeconds) {
    }

    /**
     * What was written.
     *
     * @param titles    the titles in the order of the album
     * @param whole     the file of the whole album, or {@code null}
     * @param cueSheet  the cue sheet over the files of the titles
     * @param cover     the cover beside the titles, {@code cover.png}
     * @param coverNote why the cover is a placeholder, or {@code null} if it is the album's own
     * @param skipped   why a title is not in the export, one line each
     */
    public record Result(Path folder, List<Exported> titles, Path whole, Path cueSheet, Path list, Path cover,
                         String coverNote, List<String> skipped) {
    }

    private final Path library;
    private final Mixdown mixdown;
    private final Cover cover;

    /** @param library the root of the library of pieces */
    public AlbumExporter(Path library, Mixdown mixdown) {
        this(library, mixdown, null);
    }

    /** @param cover where the cover of the album comes from; {@code null}: every album gets the placeholder */
    public AlbumExporter(Path library, Mixdown mixdown, Cover cover) {
        this.library = library;
        this.mixdown = mixdown;
        this.cover = cover;
    }

    /**
     * Exports the album into a folder. A title without a piece, or whose piece cannot be mixed,
     * is left out and named in the result; the others are exported.
     *
     * @throws IOException if nothing at all could be exported
     */
    public Result export(Album album, Path folder, Options options, Progress progress) throws Exception {
        Files.createDirectories(folder);
        List<Exported> titles = new ArrayList<Exported>();
        List<String> skipped = new ArrayList<String>();
        int digits = album.tracks().size() > 99 ? 3 : 2;
        double start = 0;
        for (int i = 0; i < album.tracks().size(); i++) {
            AlbumTrack track = album.tracks().get(i);
            String number = String.format(Locale.ROOT, "%0" + digits + "d", i + 1);
            if (!track.hasPiece() || !Files.isDirectory(library.resolve(track.piece()))) {
                skipped.add(number + " " + track.title() + ": Das Stück gibt es noch nicht.");
                continue;
            }
            progress.update("Exportiere " + number + " · " + track.title() + " ...");
            Path target = folder.resolve(number + " - " + fileName(track.title()) + ".wav");
            Path fresh = folder.resolve(target.getFileName() + ".part");
            try {
                mixdown.mix(library.resolve(track.piece()), fresh, options.bits(), options.lufs());
                double seconds = seconds(fresh);
                Files.move(fresh, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                titles.add(new Exported(track, target, seconds, start));
                start += seconds + options.pauseSeconds();
            } catch (Exception failed) {
                skipped.add(number + " " + track.title() + ": " + (failed.getMessage() == null
                        ? failed.getClass().getSimpleName() : failed.getMessage()));
            } finally {
                Files.deleteIfExists(fresh);
            }
        }
        if (titles.isEmpty()) {
            throw new IOException("Kein Titel des Albums ließ sich exportieren."
                    + (skipped.isEmpty() ? "" : " " + String.join(" ", skipped)));
        }
        Path whole = null;
        if (options.continuous()) {
            progress.update("Füge das Album zu einer Datei zusammen ...");
            whole = folder.resolve(fileName(album.title()) + ".wav");
            join(titles, options.pauseSeconds(), whole);
        }
        Path cue = folder.resolve(fileName(album.title()) + ".cue");
        Files.write(cue, cueSheet(album, titles).getBytes(StandardCharsets.UTF_8));
        Path list = folder.resolve("album.txt");
        Files.write(list, list(album, titles, skipped, options).getBytes(StandardCharsets.UTF_8));
        // The cover comes last and never stops the export: without one of its own the album gets a placeholder.
        progress.update("Lege das Cover dazu ...");
        String coverNote = null;
        byte[] picture = null;
        try {
            picture = cover == null ? null : cover.png(album);
            if (picture == null) {
                coverNote = "Das Album hat kein Cover: ein Platzhalter liegt als cover.png dabei.";
            }
        } catch (Exception | Error failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            coverNote = "Das Cover ließ sich nicht erzeugen (" + (failed.getMessage() == null
                    ? failed.getClass().getSimpleName() : failed.getMessage())
                    + "): ein Platzhalter liegt als cover.png dabei.";
        }
        Path coverFile = folder.resolve(AlbumCover.PNG);
        try {
            Files.write(coverFile, picture == null ? AlbumCover.placeholder(album.title()) : picture);
        } catch (IOException | RuntimeException unwritten) {
            coverNote = "cover.png ließ sich nicht schreiben: " + unwritten.getMessage();
            coverFile = null;
        }
        progress.update("Album exportiert: " + titles.size() + " Titel nach " + folder);
        return new Result(folder, Collections.unmodifiableList(titles), whole, cue, list, coverFile, coverNote,
                Collections.unmodifiableList(skipped));
    }

    /** A title as a file name: without the signs a file system refuses. */
    static String fileName(String title) {
        String clean = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        clean = clean.replaceAll("[. ]+$", "");
        if (clean.length() > 80) {
            clean = clean.substring(0, 80).trim();
        }
        return clean.isEmpty() ? "Titel" : clean;
    }

    static double seconds(Path wav) throws IOException {
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(wav.toFile())) {
            return audio.getFrameLength() / (double) audio.getFormat().getFrameRate();
        } catch (UnsupportedAudioFileException notAudio) {
            throw new IOException("Keine WAV-Datei: " + wav.getFileName(), notAudio);
        }
    }

    /** Writes the titles one after the other into one WAV file, with silence between them. */
    private static void join(List<Exported> titles, double pauseSeconds, Path target) throws IOException {
        List<AudioInputStream> open = new ArrayList<AudioInputStream>();
        Path fresh = target.resolveSibling(target.getFileName() + ".part");
        try {
            AudioFormat format = null;
            long frames = 0;
            List<InputStream> parts = new ArrayList<InputStream>();
            for (int i = 0; i < titles.size(); i++) {
                AudioInputStream audio;
                try {
                    audio = AudioSystem.getAudioInputStream(titles.get(i).file().toFile());
                } catch (UnsupportedAudioFileException notAudio) {
                    throw new IOException("Keine WAV-Datei: " + titles.get(i).file().getFileName(), notAudio);
                }
                open.add(audio);
                if (format == null) {
                    format = audio.getFormat();
                } else if (!format.matches(audio.getFormat())) {
                    throw new IOException("Die Titel haben verschiedene Formate: " + format + " und "
                            + audio.getFormat());
                }
                parts.add(audio);
                frames += audio.getFrameLength();
                if (i < titles.size() - 1) {
                    long pause = Math.round(pauseSeconds * format.getFrameRate());
                    // Silence of signed PCM is zero bytes; a pause of up to 30 s fits in memory.
                    parts.add(new ByteArrayInputStream(new byte[(int) (pause * format.getFrameSize())]));
                    frames += pause;
                }
            }
            try (AudioInputStream joined = new AudioInputStream(
                    new SequenceInputStream(Collections.enumeration(parts)), format, frames)) {
                AudioSystem.write(joined, AudioFileFormat.Type.WAVE, fresh.toFile());
            }
            Files.move(fresh, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            for (AudioInputStream audio : open) {
                try {
                    audio.close();
                } catch (IOException ignored) {
                    // read to its end already
                }
            }
            Files.deleteIfExists(fresh);
        }
    }

    /**
     * The cue sheet of the album over the files of its titles: every title is a file of its own
     * that begins at its start. Written the way players read it best: UTF-8 with its mark at the
     * start, so that titles with umlauts are read right.
     */
    static String cueSheet(Album album, List<Exported> titles) {
        StringBuilder cue = new StringBuilder("\uFEFF");
        cue.append("TITLE \"").append(quoted(album.title())).append("\"\n");
        for (int i = 0; i < titles.size(); i++) {
            Exported title = titles.get(i);
            cue.append("FILE \"").append(quoted(title.file().getFileName().toString())).append("\" WAVE\n");
            cue.append(String.format(Locale.ROOT, "  TRACK %02d AUDIO\n", i + 1));
            cue.append("    TITLE \"").append(quoted(title.track().title())).append("\"\n");
            cue.append("    INDEX 01 00:00:00\n");
        }
        return cue.toString();
    }

    /** A text between the quotation marks of a cue sheet, which knows no way to write one inside. */
    private static String quoted(String text) {
        return text.replace('"', '\'').replaceAll("\\s+", " ").trim();
    }

    private static String list(Album album, List<Exported> titles, List<String> skipped, Options options) {
        StringBuilder text = new StringBuilder();
        text.append(album.title()).append('\n');
        if (!album.concept().description().isEmpty()) {
            text.append(album.concept().description()).append('\n');
        }
        text.append('\n');
        double sum = 0;
        for (Exported title : titles) {
            text.append(String.format(Locale.ROOT, "%s  %s%n", clock(title.seconds()),
                    title.file().getFileName().toString().replaceAll("\\.wav$", "")));
            sum += title.seconds();
        }
        text.append(String.format(Locale.ROOT, "%n%d Titel, %s, Ziellautheit %.1f LUFS, %d Bit%n", titles.size(),
                clock(sum), options.lufs(), options.bits()));
        for (String line : skipped) {
            text.append("Fehlt: ").append(line).append('\n');
        }
        return text.toString();
    }

    private static String clock(double seconds) {
        long whole = Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60);
    }
}
