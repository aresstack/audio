package com.aresstack.audio.studio.application;

import com.aresstack.audio.core.conv.ImpulseResponse;
import com.aresstack.audio.core.spatial.HrirMeasurements;
import com.aresstack.audio.core.spatial.HrirSet;
import com.aresstack.audio.core.wav.ImpulseResponseFile;
import com.aresstack.audio.studio.hrtf.HrtfDataset;
import com.aresstack.audio.studio.hrtf.HrtfLibrary;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.mix.StageSound;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * How the user wants to hear the stage (work package D4), as the studio keeps it in its settings:
 * loudspeakers or headphones, algorithmic or convolved room, an impulse response and a set of head
 * responses of their own. {@link #load} turns the choice into the {@link StageSound} the mixer and
 * the live stage take. It reads files from the disk only; nothing here ever asks the network.
 *
 * @param headphones  binaural for headphones ("Kopfhörer (3D)")
 * @param convolution the room rings by convolution ("Faltungshall")
 * @param impulseFile a WAV file with the response of a room, or empty for one made from the room's numbers
 * @param hrtfFolder  a folder with a measured set in the layout of MIT KEMAR, or empty for the set
 *                    the user downloaded, else the built-in head model
 */
public record StageListening(boolean headphones, boolean convolution, String impulseFile, String hrtfFolder) {
    /** Loudspeakers and the algorithmic room: the stage as it has always sounded. */
    public static final StageListening USUAL = new StageListening(false, false, "", "");

    private static final String HEADPHONES = "stageHeadphones";
    private static final String CONVOLUTION = "stageConvolution";
    private static final String IMPULSE = "stageImpulseResponse";
    private static final String HRTF = "stageHrtfFolder";
    private static final int TAPS = 128;

    public StageListening {
        impulseFile = impulseFile == null ? "" : impulseFile.trim();
        hrtfFolder = hrtfFolder == null ? "" : hrtfFolder.trim();
    }

    public StageListening onHeadphones(boolean wanted) {
        return new StageListening(wanted, convolution, impulseFile, hrtfFolder);
    }

    public StageListening withConvolution(boolean wanted) {
        return new StageListening(headphones, wanted, impulseFile, hrtfFolder);
    }

    public StageListening withImpulseFile(String file) {
        return new StageListening(headphones, convolution, file, hrtfFolder);
    }

    public StageListening withHrtfFolder(String folder) {
        return new StageListening(headphones, convolution, impulseFile, folder);
    }

    /** The choice kept in the settings of the studio; the usual stage if nothing is kept. */
    public static StageListening from(Properties settings) {
        return new StageListening("true".equals(settings.getProperty(HEADPHONES)),
                "true".equals(settings.getProperty(CONVOLUTION)), settings.getProperty(IMPULSE, ""),
                settings.getProperty(HRTF, ""));
    }

    /** Writes the choice into the settings of the studio. */
    public void into(Properties settings) {
        settings.setProperty(HEADPHONES, String.valueOf(headphones));
        settings.setProperty(CONVOLUTION, String.valueOf(convolution));
        settings.setProperty(IMPULSE, impulseFile);
        settings.setProperty(HRTF, hrtfFolder);
    }

    /**
     * What came of a choice.
     *
     * @param sound    what the mixer and the live stage take
     * @param head     where the head responses come from ({@link HrirSet#source()}), with the authors
     *                 of a measured set; said to the user on headphones
     * @param room     what the room rings with, in words
     * @param problems what could not be read, in words for the user; the sound then falls back to
     *                 what is built in
     */
    public record Loaded(StageSound sound, String head, String room, List<String> problems) {
    }

    /**
     * Reads what the choice names from the disk. A file that cannot be read is named in
     * {@link Loaded#problems()} and replaced by what is built in, so the stage always sounds.
     * Reads files: call it off the event thread.
     *
     * @param library the head responses the user downloaded, or {@code null} for none
     */
    public Loaded load(HrtfLibrary library) {
        List<String> problems = new ArrayList<String>();
        StageSound sound = StageSound.LOUDSPEAKERS.onHeadphones(headphones).withConvolution(convolution);
        String room = convolution ? "Faltungshall aus den Zahlen des Raums" : "algorithmischer Raum";
        if (convolution && !impulseFile.isEmpty()) {
            try {
                Path file = Paths.get(impulseFile);
                if (!Files.isRegularFile(file)) {
                    throw new java.io.IOException("Datei nicht gefunden");
                }
                ImpulseResponse response = atMixRate(ImpulseResponseFile.read(file));
                sound = sound.withImpulseResponse(response);
                room = "Faltungshall mit " + file.getFileName() + String.format(java.util.Locale.GERMANY,
                        " (%.1f s)", response.seconds());
            } catch (Exception unreadable) {
                problems.add("Die Impulsantwort " + impulseFile + " ließ sich nicht lesen (" + unreadable.getMessage()
                        + "); der Raum klingt aus seinen Zahlen.");
            }
        }
        if (!hrtfFolder.isEmpty()) {
            try {
                sound = sound.withHrirs(HrirMeasurements.readMitKemar(Paths.get(hrtfFolder), TAPS));
            } catch (Exception unreadable) {
                problems.add("Der HRTF-Ordner " + hrtfFolder + " ließ sich nicht lesen (" + unreadable.getMessage()
                        + "); es gilt der geladene Satz oder das Kopfmodell.");
            }
        }
        if (sound.hrirs() == null && library != null) {
            try {
                sound = library.onStage(sound, HrtfDataset.MIT_KEMAR);
            } catch (RuntimeException unreadable) {
                problems.add("Der geladene HRTF-Satz ließ sich nicht lesen; es gilt das Kopfmodell.");
            }
        }
        return new Loaded(sound, sound.head().source(), room, List.copyOf(problems));
    }

    /** The response at the rate of the mix; a response measured at another rate is brought there. */
    static ImpulseResponse atMixRate(ImpulseResponse response) {
        int rate = SpatialStemMixer.RATE;
        if (response.sampleRate() == rate) {
            return response;
        }
        double step = response.sampleRate() / (double) rate;
        int frames = Math.max(1, (int) Math.floor((response.length() - 1) / step) + 1);
        float[] left = new float[frames];
        float[] right = new float[frames];
        float[] fromLeft = response.left();
        float[] fromRight = response.right();
        for (int i = 0; i < frames; i++) {
            double at = i * step;
            int before = (int) at;
            int after = Math.min(fromLeft.length - 1, before + 1);
            float share = (float) (at - before);
            left[i] = fromLeft[before] + (fromLeft[after] - fromLeft[before]) * share;
            right[i] = fromRight[before] + (fromRight[after] - fromRight[before]) * share;
        }
        // The same energy per second as before, so that the room stays as loud.
        return new ImpulseResponse(left, right, rate).scaled(Math.sqrt(step));
    }
}
