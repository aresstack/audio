package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.ModelLineup;
import com.aresstack.audio.studio.album.Album;
import com.aresstack.audio.studio.album.AlbumConcept;
import com.aresstack.audio.studio.album.AlbumFiles;
import com.aresstack.audio.studio.album.AlbumProducer;
import com.aresstack.audio.studio.album.AlbumProductionState;
import com.aresstack.audio.studio.album.AlbumProductionState.Status;
import com.aresstack.audio.studio.album.AlbumRoles;
import com.aresstack.audio.studio.album.AlbumTrack;
import com.aresstack.audio.studio.album.AutopilotPieceProduction;
import com.aresstack.audio.studio.album.ChatAlbumPlanner;
import com.aresstack.audio.studio.album.LimitedChatModel;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * An album from its concept to its mixed pieces, with a model of a test: the AI plans the list of
 * titles, and every title becomes an ordinary piece by the very pipeline of a single piece (the
 * autopilot of the window), with the length and the meter the list asks for.
 */
public final class AlbumEndToEndTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** Answers the album plan and the proposal of the players itself, plan and notes of a piece as the fake model does. */
    private static final class Model implements ChatModel {
        final FakeChatModel piece = new FakeChatModel("C", "G", "Am", "F");
        final AtomicInteger atOnce = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final AtomicInteger proposals = new AtomicInteger();

        @Override
        public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) throws IOException {
            most.accumulateAndGet(atOnce.incrementAndGet(), Math::max);
            try {
                String answer;
                if (ChatAlbumPlanner.INSTRUCTION.equals(instruction)) {
                    answer = "{\"tracks\":[{\"title\":\"Christmas Overture\",\"mood\":\"festive\",\"tempoBpm\":120,"
                            + "\"key\":\"D major\",\"meter\":\"4/4\",\"instrumentation\":\"orchestra\",\"targetSeconds\":16},"
                            + "{\"title\":\"Winter Waltz\",\"mood\":\"lively\",\"tempoBpm\":90,\"key\":\"G major\","
                            + "\"meter\":\"3/4\",\"instrumentation\":\"strings\",\"targetSeconds\":16}]}";
                } else if (prompt.startsWith("Suggest an ORIGINAL instrumental arrangement")) {
                    proposals.incrementAndGet();
                    answer = "{\"tracks\":[{\"id\":\"lead\",\"role\":\"melody\",\"instrument\":\"Flute\",\"program\":73},"
                            + "{\"id\":\"low\",\"role\":\"bass\",\"instrument\":\"Bass\",\"program\":32}]}";
                } else {
                    return piece.generate(instruction, prompt, maxTokens, cache);
                }
                if (cache != null && Files.isRegularFile(cache)) {
                    return JsonParser.parseString(new String(Files.readAllBytes(cache), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                }
                if (cache != null) {
                    Files.createDirectories(cache.getParent());
                    Files.write(cache, answer.getBytes(StandardCharsets.UTF_8));
                }
                return JsonParser.parseString(answer).getAsJsonObject();
            } finally {
                atOnce.decrementAndGet();
            }
        }
    }

    @Test
    public void anAlbumIsPlannedAndEveryTitleBecomesAPieceOfTheLibrary() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        SongLibrary songs = new SongLibrary(library);
        AlbumFiles files = new AlbumFiles(library);
        Model model = new Model();
        // All models of the production share two permits: never more than two requests at once, over both pieces.
        Semaphore permits = new Semaphore(2, true);
        ModelLineup lineup = ModelLineup.of(new LimitedChatModel(model, permits));

        Album album = files.create("Christmas Memories", new AlbumConcept("A Christmas album", "orchestral", 2, 16));
        album = new ChatAlbumPlanner(lineup.model(AlbumRoles.ALBUM), 4096)
                .plan(album, files.folder(album.id()), line -> { });
        files.save(album);
        assertEquals(2, album.tracks().size());
        assertEquals("16 s × 120 BPM ÷ 240", 8, album.tracks().get(0).spec().bars());
        assertEquals("16 s × 90 BPM ÷ 180", 8, album.tracks().get(1).spec().bars());

        PieceAutopilot autopilot = new PieceAutopilot(new InstrumentRegistry(library.resolve("none.properties")),
                lineup, 4096, 8, PieceAutopilot.Rendering.BUILT_IN);
        AlbumProducer producer = new AlbumProducer(files, songs, new AutopilotPieceProduction(autopilot, songs), 2);
        AlbumProductionState state = producer.start(album.id(), (now, at) -> { }).await();

        Album produced = files.album(album.id()).orElseThrow();
        for (AlbumTrack track : produced.tracks()) {
            assertEquals(track.title() + ": " + state.of(track.id()).reason(), Status.DONE, state.status(track.id()));
            Path piece = library.resolve(track.piece());
            assertTrue(Files.isRegularFile(piece.resolve("arrangement.tsv")));
            assertTrue(Files.isRegularFile(piece.resolve(StageMixService.MIX_FILE)));
            assertTrue("the stage the AI proposed is kept with the piece",
                    Files.isRegularFile(piece.resolve(SpatialSceneStore.FILE_NAME)));
            assertEquals("composed with what the list asks for", track.spec(), GemmaMusicComposer.specOf(piece));
            assertEquals(2, new TrackArrangementStore().load(piece).tracks().size());
            assertTrue("the piece is in the model, as after composing in the window",
                    Files.isRegularFile(piece.resolve("composition.json")));
            // The mix is as long as the piece and its reverb tail: within the tolerance of the list.
            double seconds = state.of(track.id()).seconds();
            assertTrue(track.title() + " lasts " + seconds, seconds >= 16 && seconds < 16 + 8);
        }
        assertEquals("3/4", GemmaMusicComposer.specOf(library.resolve(produced.tracks().get(1).piece())).meter());
        assertEquals(2, songs.songs().size());
        assertEquals(2, model.proposals.get());
        assertTrue("requests of both pieces together stay within the shared limit: " + model.most.get(),
                model.most.get() <= 2);

        // Started again, nothing is asked and nothing is made: the album is finished.
        int asked = model.piece.prompts.size();
        producer.start(album.id(), (now, at) -> { }).await();
        assertEquals(asked, model.piece.prompts.size());
        assertEquals(2, model.proposals.get());
    }
}
