package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.CompositionValidator;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.CompositionEditor;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.render.BuiltinStemRenderer;
import com.aresstack.audio.studio.render.PartMidi;
import com.aresstack.audio.studio.render.RenderLimits;
import com.aresstack.audio.studio.score.Score;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package B2: clips carry the part, not the lane. Piano in bars 1 to 4 and a flute in bars 5
 * to 8 on one lane are two clips with two parts: two stems, two places on the stage, two staves;
 * clips of a lane do not overlap (a rule of editor and validator, not of the format), and the
 * first part rings on past its clip's end (A7).
 */
public final class ClipEditingTest {
    private static final long BAR = 1920;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path folder;
    private ProjectSession session;
    private PieceEditing editing;

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        folder = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, folder.resolve(file.getFileName()));
            }
        }
        session = ProjectSession.open(folder).orElseThrow();
        editing = new PieceEditing(() -> Optional.of(session), counting());
    }

    private static Supplier<String> counting() {
        Iterator<Integer> numbers = Stream.iterate(1, n -> n + 1).iterator();
        return () -> "made" + numbers.next();
    }

    private Composition piece() {
        return session.composition();
    }

    private List<Clip> clipsOf(String trackId) {
        return piece().track(trackId).clips();
    }

    /** The melody cut at bar 5, its second half played by a flute: the case of A7. */
    private String fluteFromBarFive() {
        String first = clipsOf("melody").get(0).id();
        PieceEditing.Outcome split = editing.splitClip(first, 4 * BAR);
        assertTrue(split.said(), split.changed());
        String second = split.selectedClip();
        PieceEditing.Outcome flute = editing.playClipByNew(second,
                new ClipEdits.Instrument("Flute", 73, "builtin", null));
        assertTrue(flute.said(), flute.changed());
        return second;
    }

    private String fluteId() {
        for (Part part : piece().parts()) {
            if ("Flute".equals(part.name())) {
                return part.id();
            }
        }
        throw new AssertionError("no flute");
    }

    @Test
    public void twoClipsOnOneLaneArePlayedByTwoParts() throws Exception { // A7
        int partsBefore = piece().parts().size();
        String second = fluteFromBarFive();
        assertEquals(2, clipsOf("melody").size());
        assertEquals(partsBefore + 1, piece().parts().size());
        String flute = fluteId();
        MidiClip clip = (MidiClip) clipsOf("melody").get(1);
        assertEquals(second, clip.id());
        assertEquals(flute, clip.partId());
        assertEquals(List.of(), CompositionValidator.validate(piece()));

        // Two players on the stage, each with a strip of its own; the old files learn of the flute.
        assertNotNull(session.mix().channelOf(MixChannel.Owner.part("melody")));
        assertNotNull(session.mix().channelOf(MixChannel.Owner.part(flute)));
        assertNotNull(new SpatialSceneStore().load(folder).placement(flute));
        assertTrue(new TrackArrangementStore().load(folder).tracks().stream().anyMatch(t -> t.id().equals(flute)));

        // Two staves: the score writes each part on a staff of its own.
        Score score = Score.read(folder.resolve("arrangement.tsv"));
        assertEquals(partsBefore + 1, score.staves().size());

        // The notes before bar 5 belong to the melody, those after to the flute.
        for (Composition.PlacedNote note : piece().notesOf("melody")) {
            assertTrue(note.tick() < 4 * BAR);
        }
        assertFalse(piece().notesOf(flute).isEmpty());
        for (Composition.PlacedNote note : piece().notesOf(flute)) {
            assertTrue(note.tick() >= 4 * BAR);
        }
        assertEquals(piece(), ProjectSession.open(folder).orElseThrow().composition());
    }

    @Test
    public void twoPartsRenderTwoStemsAndTheFirstRingsOnPastItsClip() throws Exception { // A7
        fluteFromBarFive();
        String flute = fluteId();
        RenderService service = new RenderService(
                part -> new RenderService.Voicing(new BuiltinStemRenderer(), null, PartMidi.OWN_PROGRAM),
                RenderLimits.STANDARD);
        RenderService.Result result = service.render(session, message -> { });
        Path melody = result.stems().get("melody");
        Path flutes = result.stems().get(flute);
        assertNotNull(melody);
        assertNotNull(flutes);
        assertNotEquals(melody, flutes);

        // The melody's last note ends at the cut; its stem still sounds a little after it.
        long lastEnd = 0;
        for (Composition.PlacedNote note : piece().notesOf("melody")) {
            lastEnd = Math.max(lastEnd, note.endTick());
        }
        double cut = piece().timeMap().seconds(lastEnd);
        assertTrue("sound after the clip end", peak(melody, cut + 0.01, cut + 0.12) > 0.001);
    }

    @Test
    public void clipsOfALaneDoNotOverlapButTheFormatCanHoldIt() throws Exception { // A7, O2
        fluteFromBarFive();
        MidiClip first = (MidiClip) clipsOf("melody").get(0);
        MidiClip second = (MidiClip) clipsOf("melody").get(1);
        PieceEditing.Outcome refused = editing.moveClip(second.id(), 2 * BAR, null);
        assertFalse(refused.changed());
        assertTrue(refused.said(), refused.said().contains("überlappen"));

        // Past the editor: the validator refuses it too.
        Patch overlap = Patch.of("overlap", new EditOp.MoveClip(second.id(), null, 2 * BAR));
        assertFalse(CompositionEditor.apply(piece(), overlap, EditScope.everything()).applied());

        // The format stores clips with start and length, whatever they do.
        List<Clip> clips = List.of(first, new MidiClip(second.id(), 2 * BAR, second.lengthTicks(), second.partId(),
                second.notes()));
        List<Track> tracks = new ArrayList<>();
        for (Track track : piece().tracks()) {
            tracks.add(track.id().equals("melody") ? track.withClips(clips) : track);
        }
        Composition overlapping = piece().withTracks(tracks);
        Path other = temporary.newFolder("other").toPath();
        CompositionJson.save(other, overlapping);
        assertEquals(overlapping, CompositionJson.load(other));
        assertFalse(CompositionValidator.validate(overlapping).isEmpty());
    }

    @Test
    public void aClipMovesToAnotherLaneWithItsPlayer() {
        String first = clipsOf("melody").get(0).id();
        editing.splitClip(first, 4 * BAR);
        String second = clipsOf("melody").get(1).id();
        // Make room on the harmony lane: cut it and take its second half away.
        String harmony = clipsOf("harmony").get(0).id();
        editing.splitClip(harmony, 4 * BAR);
        editing.deleteClip(clipsOf("harmony").get(1).id());
        PieceEditing.Outcome moved = editing.moveClip(second, 4 * BAR, "harmony");
        assertTrue(moved.said(), moved.changed());
        MidiClip landed = (MidiClip) clipsOf("harmony").get(1);
        assertEquals(second, landed.id());
        assertEquals("the melody still plays it", "melody", landed.partId());
        assertEquals(List.of(), CompositionValidator.validate(piece()));
    }

    @Test
    public void copyDuplicateCreateDeleteAndUndo() {
        String first = clipsOf("melody").get(0).id();
        editing.splitClip(first, 4 * BAR);
        String second = clipsOf("melody").get(1).id();
        editing.deleteClip(second);
        assertEquals(1, clipsOf("melody").size());
        PieceEditing.Outcome copied = editing.copyClip(first, 4 * BAR, null);
        assertTrue(copied.said(), copied.changed());
        MidiClip original = (MidiClip) clipsOf("melody").get(0);
        MidiClip copy = (MidiClip) clipsOf("melody").get(1);
        assertEquals(4 * BAR, copy.startTick());
        assertEquals(original.notes().size(), copy.notes().size());
        assertNotEquals(original.notes().get(0).id(), copy.notes().get(0).id());
        assertEquals(List.of(), CompositionValidator.validate(piece()));

        Composition before = piece();
        editing.deleteClip(copy.id());
        PieceEditing.Outcome created = editing.createClip("melody", 4 * BAR, 2 * BAR);
        assertTrue(created.said(), created.changed());
        assertEquals(2 * BAR, ((MidiClip) clipsOf("melody").get(1)).lengthTicks());
        PieceEditing.Outcome note = editing.insert("melody", 5 * BAR, 72, 240);
        assertTrue("a new clip takes notes", note.changed());
        editing.undo();
        editing.undo();
        editing.undo();
        assertEquals(before, piece());
    }

    @Test
    public void aClipCanGoBackToItsLanesOwnPart() {
        String second = fluteFromBarFive();
        PieceEditing.Outcome back = editing.playClipBy(second, "melody");
        assertTrue(back.said(), back.changed());
        assertNull(((MidiClip) clipsOf("melody").get(1)).partId());
        for (Note note : ((MidiClip) clipsOf("melody").get(1)).notes()) {
            assertNotNull(piece().part("melody").voice(note.voiceId()));
        }
        assertEquals(List.of(), CompositionValidator.validate(piece()));
    }

    /** The highest level of a WAV between two seconds. */
    private static double peak(Path wav, double from, double to) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(
                new ByteArrayInputStream(Files.readAllBytes(wav)))) {
            float rate = in.getFormat().getSampleRate();
            int frame = in.getFormat().getFrameSize();
            int channels = in.getFormat().getChannels();
            byte[] all = in.readAllBytes();
            int start = (int) (from * rate);
            int end = Math.min(all.length / frame, (int) (to * rate));
            double peak = 0;
            for (int i = start; i < end; i++) {
                for (int c = 0; c < channels; c++) {
                    int at = i * frame + c * 2;
                    short sample = (short) ((all[at] & 0xFF) | (all[at + 1] << 8));
                    peak = Math.max(peak, Math.abs(sample / 32768.0));
                }
            }
            return peak;
        }
    }
}
