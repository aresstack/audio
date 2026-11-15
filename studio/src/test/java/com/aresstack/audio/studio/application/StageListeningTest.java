package com.aresstack.audio.studio.application;

import com.aresstack.audio.core.conv.ImpulseResponse;
import com.aresstack.audio.studio.hrtf.HrtfLibrary;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.mix.StageSound;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * How the user wants to hear the stage (work package D4) is kept in the settings and turned into
 * the sound of the stage from files on the disk; what cannot be read is named and replaced by
 * what is built in.
 */
public final class StageListeningTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void theChoiceIsKeptInTheSettingsAndTheUsualStageIsTheOneAsBefore() {
        Properties settings = new Properties();
        Assert.assertEquals(StageListening.USUAL, StageListening.from(settings));
        StageListening chosen = StageListening.USUAL.onHeadphones(true).withConvolution(true)
                .withImpulseFile(" /rooms/church.wav ").withHrtfFolder("/heads/kemar");
        chosen.into(settings);
        Assert.assertEquals(chosen, StageListening.from(settings));
        Assert.assertEquals("/rooms/church.wav", StageListening.from(settings).impulseFile());

        // Nothing chosen and nothing downloaded: exactly the stage as before, with no problem.
        StageListening.Loaded usual = StageListening.USUAL.load(new HrtfLibrary(temporary.getRoot().toPath()));
        Assert.assertEquals(StageSound.LOUDSPEAKERS, usual.sound());
        Assert.assertTrue(usual.problems().isEmpty());
        Assert.assertEquals(StageSound.LOUDSPEAKERS, StageListening.USUAL.load(null).sound());
    }

    @Test
    public void headphonesAndAConvolvedRoomAreSwitchedAndTheHeadSaysWhereItComesFrom() {
        HrtfLibrary none = new HrtfLibrary(temporary.getRoot().toPath().resolve("no-hrtf"));
        StageListening.Loaded heard = StageListening.USUAL.onHeadphones(true).withConvolution(true).load(none);
        Assert.assertTrue(heard.sound().headphones());
        Assert.assertTrue(heard.sound().convolution());
        Assert.assertNull("no set downloaded: the built-in head model", heard.sound().hrirs());
        Assert.assertNull(heard.sound().impulseResponse());
        Assert.assertFalse(heard.head().isBlank());
        Assert.assertEquals(heard.sound().head().source(), heard.head());
        Assert.assertTrue(heard.room().contains("Faltungshall"));
        Assert.assertTrue(heard.problems().isEmpty());
    }

    @Test
    public void anImpulseResponseOfOnesOwnIsReadAndAFileThatCannotBeReadIsNamed() throws Exception {
        float[] ring = new float[SpatialStemMixer.RATE / 2];
        for (int i = 0; i < ring.length; i++) {
            ring[i] = (float) (0.5 * Math.exp(-6.0 * i / ring.length) * ((i * 7919) % 2 == 0 ? 1 : -1));
        }
        Path file = temporary.newFolder().toPath().resolve("room.wav");
        byte[] bytes = new byte[ring.length * 4];
        for (int i = 0; i < ring.length; i++) {
            short sample = (short) Math.round(ring[i] * 32767);
            for (int side = 0; side < 2; side++) {
                bytes[i * 4 + side * 2] = (byte) sample;
                bytes[i * 4 + side * 2 + 1] = (byte) (sample >> 8);
            }
        }
        javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(SpatialStemMixer.RATE, 16, 2,
                true, false);
        javax.sound.sampled.AudioSystem.write(new javax.sound.sampled.AudioInputStream(
                new java.io.ByteArrayInputStream(bytes), format, ring.length),
                javax.sound.sampled.AudioFileFormat.Type.WAVE, file.toFile());
        StageListening.Loaded heard = StageListening.USUAL.withConvolution(true).withImpulseFile(file.toString())
                .load(null);
        Assert.assertTrue(heard.problems().toString(), heard.problems().isEmpty());
        Assert.assertNotNull(heard.sound().impulseResponse());
        Assert.assertEquals(0.5, heard.sound().impulseResponse().seconds(), 0.01);
        Assert.assertTrue(heard.room().contains("room.wav"));

        // Without the switch the file is kept but not heard.
        Assert.assertNull(StageListening.USUAL.withImpulseFile(file.toString()).load(null).sound().impulseResponse());

        StageListening.Loaded missing = StageListening.USUAL.withConvolution(true).withImpulseFile("/nowhere/x.wav")
                .withHrtfFolder("/nowhere/heads").onHeadphones(true).load(null);
        Assert.assertEquals(2, missing.problems().size());
        Assert.assertTrue("the stage still sounds: the room from its numbers, the head model",
                missing.sound().convolution() && missing.sound().impulseResponse() == null
                        && missing.sound().hrirs() == null);
    }

    @Test
    public void aResponseMeasuredAtAnotherRateIsBroughtToTheRateOfTheMix() {
        float[] click = new float[48000];
        click[0] = 1;
        click[24000] = 0.5f;
        ImpulseResponse at = StageListening.atMixRate(new ImpulseResponse(click, click.clone(), 48000));
        Assert.assertEquals(SpatialStemMixer.RATE, at.sampleRate());
        Assert.assertEquals(1.0, at.seconds(), 0.001);
        int half = SpatialStemMixer.RATE / 2;
        float most = 0;
        for (int i = half - 2; i <= half + 2; i++) {
            most = Math.max(most, Math.abs(at.left()[i]));
        }
        Assert.assertTrue("the second click is still half a second in", most > 0.3);
        ImpulseResponse same = new ImpulseResponse(new float[]{1, 0}, new float[]{1, 0}, SpatialStemMixer.RATE);
        Assert.assertSame(same, StageListening.atMixRate(same));
    }
}
