package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Key;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.golden.Golden;
import com.aresstack.audio.studio.project.LegacyImport;
import com.aresstack.audio.studio.project.MidiExport;
import com.aresstack.audio.studio.project.TsvExport;
import com.aresstack.audio.studio.score.Score;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * F2 acceptance (architecture review 7.2, scenarios A1 and A14): length, tempo, key and meter are
 * free, and MIDI, score and audio of a piece agree with what was asked for.
 */
public final class ParametricCompositionTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** A1: 24 bars, 96 BPM, D minor. */
    @Test
    public void a1TwentyFourBarsAt96BpmInDMinor() throws Exception {
        CompositionSpec spec = CompositionSpec.of(24, 96, Key.parse("d-Moll"), 4, 4);
        Path dir = folder.newFolder("hafen").toPath();
        FakeChatModel model = new FakeChatModel("Dm", "Gm", "A7", "Dm", "Bb", "Gm", "A7", "Dm");
        List<String> messages = new ArrayList<>();
        Path tsv = new GemmaMusicComposer(model).compose("Nebliger Hafen", dir, TrackArrangement.defaults(), spec,
                messages::add);

        // Composed in three sections of eight bars per track, each after the first told how the last one ended.
        assertEquals(1 + 4 * 3, model.prompts.size());
        String plan = model.prompts.get(0);
        assertTrue(plan, plan.contains("tempoBpm=96, key=\"D minor\", timeSignature=\"4/4\", bars=24"));
        assertTrue(model.prompts.stream().anyMatch(p -> p.contains("bars 9–16 of the song")
                && p.contains("ended like this")));
        assertTrue(model.prompts.stream().anyMatch(p -> p.contains("bars 17–24") && p.contains("bring the song to an end")));
        assertTrue(model.prompts.stream().noneMatch(p -> p.contains("C major") || p.contains("108 BPM")));
        assertEquals(spec, GemmaMusicComposer.specOf(dir));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        assertEquals("tempo\t96", lines.get(0));
        assertEquals("key\tD\tminor", lines.get(1));
        assertTrue(lines.get(2).startsWith("track\t"));
        // Every note was a correct note of D minor: the normalizer changed none.
        assertEquals(List.of("part\tnote\tfield\toriginal\tcorrected\treason"),
                Files.readAllLines(dir.resolve("note-corrections.tsv"), StandardCharsets.UTF_8));

        // MIDI: tempo, meter and key events; the notes end in the last of the 24 bars.
        Path out = folder.newFolder("hafen-render").toPath();
        Golden.renderReference(tsv, out);
        Sequence midi = MidiSystem.getSequence(out.resolve("arrangement.mid").toFile());
        assertEquals(625000, tempoMicros(midi));
        assertArrayEquals(new byte[]{4, 2, 24, 8}, meta(midi, 0x58));
        assertArrayEquals(new byte[]{-1, 1}, meta(midi, 0x59));
        long last = lastNoteOff(midi);
        assertTrue("ends in bar 24: " + last, last > 23 * 1920L && last <= 24 * 1920L);

        // Audio: as long as the piece (24 bars of 2.5 s and the tail), and sounding in the last bar.
        assertWave(out.resolve("arrangement.wav"), 24 * 4 * 60.0 / 96);
        assertWave(out.resolve("melody.wav"), 24 * 4 * 60.0 / 96);

        // Score: 24 bars, one flat, B flat written as B flat, C sharp with its sharp.
        Score score = Score.read(tsv);
        assertEquals(24, score.bars());
        assertEquals(-1, score.keySignature());
        assertEquals(16, score.barLength());
        Score.Staff harmony = staff(score, "harmony");
        Score.Event a7 = harmony.bar(2).get(0);
        assertEquals("C#4", spelled(a7, 1));
        assertEquals(Score.Accidental.SHARP, accidental(a7, 1));
        Score.Event bFlat = harmony.bar(4).get(0);
        assertEquals("Bb3", spelled(bFlat, 10));
        assertEquals("in the key signature, no sign", Score.Accidental.NONE, accidental(bFlat, 10));

        // Model, MIDI export and TSV export agree with the file the composer wrote.
        Composition composition = LegacyImport.read(dir).orElseThrow().composition();
        assertEquals(Key.parse("D minor"), composition.timeMap().keyAt(0));
        assertEquals(96.0, composition.timeMap().tempoAt(0), 0);
        assertEquals(24 * 1920L, composition.endTick());
        assertArrayEquals(Files.readAllBytes(out.resolve("arrangement.mid")), MidiExport.bytes(composition));
        assertEquals(TsvExport.canonical(ArrangementFile.read(tsv)), TsvExport.lines(composition));
        assertEquals(lines, TsvExport.lines(composition));
    }

    /** Taktzahl, Tempo, Tonart, Taktart → MIDI length, meta events, audio length and score bars. */
    @Test
    public void lengthTempoKeyAndMeterReachMidiAudioAndScore() throws Exception {
        Object[][] cases = {
                {12, 72, "F# major", 3, 4, new String[]{"F#", "B", "C#7", "F#"}},
                {10, 140, "Eb minor", 6, 8, new String[]{"Ebm", "Abm", "Bb7", "Ebm"}},
                {9, 50, "A major", 5, 4, new String[]{"A", "D", "E7", "A"}},
                {17, 180, "C major", 4, 4, new String[]{"C", "Am", "F", "G7"}},
                {6, 400, "G minor", 7, 8, new String[]{"Gm", "Cm", "D7", "Gm"}},
                // Shorter than the old eight bars, in the key and meter of the old pieces.
                {1, 120, "C major", 4, 4, new String[]{"C"}},
                {4, 120, "C major", 4, 4, new String[]{"C", "F", "G7", "C"}},
                {7, 120, "C major", 4, 4, new String[]{"C", "F", "G7", "C"}},
                {8, 100, "C major", 4, 4, new String[]{"C", "F", "G7", "C"}},
        };
        for (Object[] c : cases) {
            int bars = (Integer) c[0];
            int bpm = (Integer) c[1];
            Key key = Key.parse((String) c[2]);
            int numerator = (Integer) c[3];
            int denominator = (Integer) c[4];
            String name = c[2] + " " + numerator + "/" + denominator;
            CompositionSpec spec = CompositionSpec.of(bars, bpm, key, numerator, denominator);
            Path dir = folder.newFolder().toPath();
            Path tsv = new GemmaMusicComposer(new FakeChatModel((String[]) c[5]))
                    .compose("Test", dir, TrackArrangement.defaults(), spec, message -> { });
            Path out = folder.newFolder().toPath();
            Golden.renderReference(tsv, out);
            Sequence midi = MidiSystem.getSequence(out.resolve("arrangement.mid").toFile());
            assertEquals(name, 60000000 / bpm, tempoMicros(midi));
            assertArrayEquals(name, new byte[]{(byte) numerator, (byte) Integer.numberOfTrailingZeros(denominator), 24, 8},
                    meta(midi, 0x58));
            if (key.equals(Key.C_MAJOR)) {
                assertTrue(name, meta(midi, 0x59) == null);
            } else {
                assertArrayEquals(name, new byte[]{(byte) key.fifths(), (byte) (key.mode() == Key.Mode.MINOR ? 1 : 0)},
                        meta(midi, 0x59));
            }
            long barTicks = 1920L * numerator / denominator;
            long last = lastNoteOff(midi);
            assertTrue(name + ": " + last, last > (bars - 1) * barTicks && last <= bars * barTicks);
            assertWave(out.resolve("arrangement.wav"), bars * numerator * 4.0 / denominator * 60.0 / bpm);

            Score score = Score.read(tsv);
            assertEquals(name, bars, score.bars());
            assertEquals(name, numerator * 16 / denominator, score.barLength());
            assertEquals(name, key.fifths(), score.keySignature());
            for (Score.Staff staff : score.staves()) {
                for (int bar = 0; bar < bars; bar++) {
                    int length = 0;
                    for (Score.Event event : staff.bar(bar)) {
                        length += event.length();
                    }
                    assertEquals(name + " " + staff.trackId() + " bar " + (bar + 1), score.barLength(), length);
                }
            }
            Composition composition = LegacyImport.read(dir).orElseThrow().composition();
            assertEquals(name, bars * barTicks, composition.endTick());
            assertArrayEquals(name, Files.readAllBytes(out.resolve("arrangement.mid")), MidiExport.bytes(composition));
            assertEquals(name, Files.readAllLines(tsv, StandardCharsets.UTF_8), TsvExport.lines(composition));
        }
    }

    /** A14: a twelve-minute piece goes through the reference renderer without a limit getting in the way. */
    @Test
    public void a14TwelveMinutesRenderInFull() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("tempo\t120");
        lines.add("track\tlead\t0\t0\tmelody");
        for (int bar = 0; bar < 360; bar++) {
            lines.add("note\tlead\t" + bar * 1920 + "\t1920\t" + (60 + bar % 12) + "\t90");
        }
        Path tsv = folder.newFile("long.tsv").toPath();
        Files.write(tsv, lines, StandardCharsets.UTF_8);
        Path out = folder.newFolder("long").toPath();
        Golden.renderReference(tsv, out);
        assertWave(out.resolve("lead.wav"), 720);
        assertEquals(360, Score.read(tsv).bars());
        com.aresstack.audio.studio.render.RenderLimits.STANDARD.checkPiece(720, "A14");
    }

    /** Files from before F2 have no bars line and still sound eight bars long, however short their notes. */
    @Test
    public void legacyFilesStayEightBarsLong() throws Exception {
        for (int bars : new int[]{2, 8}) {
            Path tsv = folder.newFile("legacy-" + bars + ".tsv").toPath();
            Files.write(tsv, List.of("tempo\t120", "track\tlead\t0\t0\tmelody",
                    "note\tlead\t" + (bars - 1) * 1920 + "\t1920\t60\t90"), StandardCharsets.UTF_8);
            Path out = folder.newFolder("legacy-out-" + bars).toPath();
            Golden.renderReference(tsv, out);
            try (AudioInputStream audio = AudioSystem.getAudioInputStream(out.resolve("lead.wav").toFile())) {
                assertEquals("eight bars of 2 s and the tail", (long) Math.ceil(16.75 * 22050), audio.getFrameLength());
            }
        }
        assertEquals("a new two-bar piece says its length", List.of("tempo\t120", "bars\t2"),
                CompositionSpec.of(2, 120, Key.C_MAJOR, 4, 4).headerLines(2));
        assertEquals("eight bars need no line, as before", List.of("tempo\t120"),
                CompositionSpec.of(8, 120, Key.C_MAJOR, 4, 4).headerLines(8));
    }

    /** A sync from 4/4 C major to 3/4 D minor at an unchanged 120 BPM takes the new meter and key. */
    @Test
    public void aChangedMeterOrKeyInTheFileIsTakenIntoTheModel() throws Exception {
        Path dir = folder.newFolder().toPath();
        Files.write(dir.resolve("arrangement.tsv"), List.of("tempo\t120", "track\tlead\t0\t0\tmelody",
                "note\tlead\t0\t1920\t60\t90"), StandardCharsets.UTF_8);
        ProjectSession session = ProjectSession.open(dir).orElseThrow();
        assertEquals(Key.C_MAJOR, session.composition().timeMap().keyAt(0));
        Files.write(dir.resolve("arrangement.tsv"), List.of("tempo\t120", "meter\t3\t4", "key\tD\tminor",
                "track\tlead\t0\t0\tmelody", "note\tlead\t0\t1440\t62\t90"), StandardCharsets.UTF_8);
        assertTrue(session.synchronize());
        assertEquals(Key.parse("D minor"), session.composition().timeMap().keyAt(0));
        assertEquals(3, session.composition().timeMap().meterAt(0).numerator());
    }

    @Test
    public void aPieceOfTheLegacySpecIsAskedForAsBefore() throws Exception {
        assertTrue(CompositionSpec.legacy().isLegacy());
        assertEquals(List.of("tempo\t108"), CompositionSpec.legacy().headerLines(8));
        assertEquals(1, CompositionSpec.legacy().sections().size());
        assertFalse(CompositionSpec.of(8, 108, Key.C_MAJOR, 4, 4).isLegacy());
    }

    @Test
    public void aPlannedPieceKeepsItsSpec() throws Exception {
        CompositionSpec spec = CompositionSpec.of(10, 90, Key.parse("E minor"), 3, 4);
        Path dir = folder.newFolder().toPath();
        new GemmaMusicComposer(new FakeChatModel("Em", "Am", "B7", "Em"))
                .compose("x", dir, TrackArrangement.defaults(), spec, message -> { });
        assertEquals(spec, GemmaMusicComposer.specOf(dir));
        try {
            new GemmaMusicComposer(new FakeChatModel("C")).compose("x", dir, TrackArrangement.defaults(),
                    CompositionSpec.of(12, 90, Key.parse("E minor"), 3, 4), message -> { });
            throw new AssertionError("a planned piece must not be composed against another spec");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("10 Takte"));
        }
        // Composing again without a spec takes the planned one and the cached answers.
        FakeChatModel again = new FakeChatModel("C");
        new GemmaMusicComposer(again).compose("x", dir, TrackArrangement.defaults(), message -> { });
        assertEquals(0, again.prompts.size());
    }

    @Test
    public void sectionsAreEightBarsAndTheLastTakesAShortRest() {
        assertEquals("[0+16]", sections(CompositionSpec.of(16, 100, Key.C_MAJOR, 4, 4)));
        assertEquals("[0+8, 8+8, 16+8]", sections(CompositionSpec.of(24, 100, Key.C_MAJOR, 4, 4)));
        assertEquals("[0+8, 8+9]", sections(CompositionSpec.of(17, 100, Key.C_MAJOR, 4, 4)));
        assertEquals("[0+8, 8+8, 16+4]", sections(CompositionSpec.of(20, 100, Key.C_MAJOR, 4, 4)));
    }

    private static String sections(CompositionSpec spec) {
        List<String> parts = new ArrayList<>();
        for (int[] section : spec.sections()) {
            parts.add(section[0] + "+" + section[1]);
        }
        return parts.toString();
    }

    private static Score.Staff staff(Score score, String id) {
        return score.staves().stream().filter(s -> s.trackId().equals(id)).findFirst().orElseThrow();
    }

    private static String spelled(Score.Event event, int pitchClass) {
        int[] pitches = event.pitches();
        for (int i = 0; i < pitches.length; i++) {
            if (pitches[i] % 12 == pitchClass) {
                return event.spelling(i).name();
            }
        }
        throw new AssertionError("no pitch class " + pitchClass);
    }

    private static Score.Accidental accidental(Score.Event event, int pitchClass) {
        int[] pitches = event.pitches();
        for (int i = 0; i < pitches.length; i++) {
            if (pitches[i] % 12 == pitchClass) {
                return event.accidental(i);
            }
        }
        throw new AssertionError("no pitch class " + pitchClass);
    }

    private static int tempoMicros(Sequence midi) {
        byte[] data = meta(midi, 0x51);
        return ((data[0] & 255) << 16) | ((data[1] & 255) << 8) | (data[2] & 255);
    }

    private static byte[] meta(Sequence midi, int type) {
        for (Track track : midi.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof MetaMessage meta && meta.getType() == type) {
                    return meta.getData();
                }
            }
        }
        return null;
    }

    private static long lastNoteOff(Sequence midi) {
        long last = 0;
        for (Track track : midi.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage message && message.getCommand() == ShortMessage.NOTE_OFF) {
                    last = Math.max(last, event.getTick());
                }
            }
        }
        return last;
    }

    /** The WAV is as long as the piece plus the tail of 0.75 s, and not silent in the last bar or so. */
    private static void assertWave(Path wav, double pieceSeconds) throws Exception {
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(wav.toFile())) {
            float rate = audio.getFormat().getSampleRate();
            long frames = audio.getFrameLength();
            assertEquals(wav.getFileName().toString(), (long) Math.ceil((pieceSeconds + 0.75) * rate), frames);
            byte[] data = audio.readAllBytes();
            ByteBuffer samples = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int from = (int) ((pieceSeconds - Math.min(2.0, pieceSeconds / 4)) * rate);
            int to = (int) (pieceSeconds * rate);
            double energy = 0;
            for (int i = from; i < to; i++) {
                double s = samples.getShort(i * 2) / 32768.0;
                energy += s * s;
            }
            double rms = Math.sqrt(energy / (to - from));
            assertTrue(wav.getFileName() + " sounds at the end: rms " + rms, rms > 0.01);
        }
    }
}
