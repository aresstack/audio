package com.aresstack.audio.studio.render;

import com.aresstack.audio.composition.Asset;
import com.aresstack.audio.composition.AudioClip;
import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.stream.LinearResampler;
import com.aresstack.audio.core.wav.WavFileSink;
import com.aresstack.audio.core.wav.WavFileSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The sound of an audio lane as one stem (work package B3): its clips laid out on the time line of
 * the piece, each from the sample its start tick falls on, as long as the clip says, with its gain
 * and its fades. The mixer then treats the stem like the stem of a part: it goes through the lane's
 * own channel strip, so a sound effect stands on the stage like any other source (E3).
 *
 * <p>Clips are read block by block, each file once from front to back; a file of another rate is
 * converted to {@link AudioClip#SAMPLE_RATE}. A clip whose file is missing stays silent and is named
 * in the warnings. Clips that overlap (the format allows it; the editor does not yet) are added.
 */
public final class AudioTrackStem {
    /** The sample rate of the stem and of the lengths and fades of audio clips. */
    public static final int RATE = AudioClip.SAMPLE_RATE;
    private static final int BLOCK = 1024;

    private AudioTrackStem() {
    }

    /** The first sample of a clip in the stem: where its start tick lies in time. */
    public static long firstSample(Composition piece, AudioClip clip) {
        return Math.round(piece.timeMap().seconds(clip.startTick()) * RATE);
    }

    /** Where an asset's file lies: relative to the folder of the piece, or as written if absolute. */
    public static Path file(Path folder, Asset asset) {
        Path path = Path.of(asset.path());
        return path.isAbsolute() ? path : folder.resolve(path);
    }

    /**
     * Writes the stem of an audio lane.
     *
     * @return what could not be played (missing or unreadable files); empty if all was played
     */
    public static List<String> write(Composition piece, Path folder, Track track, Path output) throws IOException {
        List<Playing> clips = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long total = 0;
        for (Clip clip : track.clips()) {
            if (clip instanceof AudioClip audio) {
                Playing playing = new Playing(audio, firstSample(piece, audio));
                total = Math.max(total, playing.first + audio.lengthSamples());
                Asset asset = piece.asset(audio.assetId());
                if (asset == null || !Files.isRegularFile(file(folder, asset))) {
                    warnings.add("Audio-Clip " + audio.id() + ": Datei fehlt"
                            + (asset == null ? "" : " (" + asset.path() + ")"));
                    continue;
                }
                playing.file = file(folder, asset);
                clips.add(playing);
            }
        }
        clips.sort(Comparator.comparingLong(p -> p.first));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path fresh = output.toAbsolutePath().resolveSibling(output.getFileName() + "." + System.nanoTime() + ".part");
        AudioBlock block = new AudioBlock(BLOCK);
        AudioBlock read = new AudioBlock(BLOCK);
        try {
            try (WavFileSink sink = new WavFileSink(fresh, RATE, 24, BLOCK)) {
                long frames = Math.max(1, total);
                for (long at = 0; at < frames; at += BLOCK) {
                    int count = (int) Math.min(BLOCK, frames - at);
                    block.setFrames(count);
                    block.clear();
                    for (Playing clip : clips) {
                        try {
                            clip.addInto(block, read, at, count);
                        } catch (IOException unreadable) {
                            warnings.add("Audio-Clip " + clip.clip.id() + ": " + unreadable.getMessage());
                            clip.close();
                            clip.failed = true;
                        }
                    }
                    sink.write(block);
                }
            } finally {
                for (Playing clip : clips) {
                    clip.close();
                }
            }
            Files.move(fresh, output, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(fresh);
        }
        return warnings;
    }

    /** One clip while the stem is written: its file, opened when its first sample comes. */
    private static final class Playing {
        final AudioClip clip;
        final long first;
        final double gain;
        Path file;
        AudioSource source;
        long played;
        boolean ended;
        boolean failed;

        Playing(AudioClip clip, long first) {
            this.clip = clip;
            this.first = first;
            this.gain = Math.pow(10, clip.gainDb() / 20.0);
        }

        /** Adds what of the clip falls into the frames {@code at .. at + count} of the stem. */
        void addInto(AudioBlock block, AudioBlock read, long at, int count) throws IOException {
            long end = first + clip.lengthSamples();
            if (failed || ended || at + count <= first || at >= end) {
                return;
            }
            if (source == null) {
                WavFileSource wav = new WavFileSource(file);
                source = LinearResampler.at(wav, wav.sampleRate(), RATE);
                source.prepare(RATE, BLOCK);
            }
            int from = (int) Math.max(0, first - at);
            int wanted = (int) Math.min(count - from, end - (at + from));
            read.setFrames(wanted);
            int got = source.read(read);
            for (int i = 0; i < got; i++) {
                long inClip = played + i;
                double level = gain * fade(inClip);
                block.left[from + i] += (float) (read.left[i] * level);
                block.right[from + i] += (float) (read.right[i] * level);
            }
            played += wanted;
            if (got < wanted || played >= clip.lengthSamples()) {
                ended = true;
                close();
            }
        }

        private double fade(long inClip) {
            double level = 1;
            if (clip.fadeInSamples() > 0 && inClip < clip.fadeInSamples()) {
                level = inClip / (double) clip.fadeInSamples();
            }
            long left = clip.lengthSamples() - inClip;
            if (clip.fadeOutSamples() > 0 && left <= clip.fadeOutSamples()) {
                level = Math.min(level, Math.max(0, (left - 1) / (double) clip.fadeOutSamples()));
            }
            return level;
        }

        void close() throws IOException {
            if (source != null) {
                source.close();
                source = null;
            }
        }
    }
}
