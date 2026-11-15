package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentCatalog;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.instruments.network.AudioProxyRoutes;
import com.aresstack.audio.studio.application.GemmaMusicComposer;
import com.aresstack.audio.studio.application.StudioMusicRenderer;
import com.aresstack.audio.studio.keepass.AudioKeePassService;
import com.aresstack.audio.studio.progress.Progress;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Launch the desktop studio and check its entire local rendering pipeline without a desktop. */
public final class AudioStudioMain {
    private AudioStudioMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && "--smoke-test".equals(arguments[0])) {
            verifyHeadlessStartup(arguments.length >= 2
                    ? java.nio.file.Paths.get(arguments[1]) : null);
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Audio Studio requires a graphical desktop.");
        }
        AudioStudioTheme.install();
        final InstrumentRegistry registry = InstrumentRegistry.standard();
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                new AudioStudioFrame(registry).setVisible(true);
            }
        });
    }

    private static void verifyHeadlessStartup(Path optionalSoundFont) throws Exception {
        // Verify the desktop theme and all runtime dependencies on Java 8, without opening a window.
        AudioStudioTheme.install();
        Class.forName("com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassRpcLink");
        java.util.Properties proxySettings = new java.util.Properties();
        proxySettings.setProperty("proxyMode", "DISABLED");
        AudioProxyRoutes proxy = new AudioProxyRoutes(proxySettings);
        if (proxy.proxyFor(java.net.URI.create("https://example.org")) != java.net.Proxy.NO_PROXY) {
            throw new IllegalStateException("The proxy-disabled smoke test attempted networking.");
        }
        if (InstrumentCatalog.standard().size() < 9) {
            throw new IllegalStateException("Instrument catalog is incomplete.");
        }
        Path sandbox = Files.createTempDirectory("audio-studio-smoke-");
        try {
            if (AudioKeePassService.isPaired(sandbox)) {
                throw new IllegalStateException("A fresh temporary profile cannot be KeePass-paired.");
            }
            InstrumentRegistry registry = new InstrumentRegistry(sandbox.resolve("libraries.properties"));
            verifyDirectoryImport(registry, sandbox);
            Path tsv = copyBundledComposition(sandbox);
            verifyPieceModel(sandbox, tsv);
            verifyModelRendering(registry, sandbox);
            verifyVoiceAddition(registry, sandbox);
            verifyRegionRework(registry, sandbox);
            verifyExtension(registry, sandbox);
            Map<String, InstrumentLibrary> assignments =
                    new HashMap<String, InstrumentLibrary>();
            if (optionalSoundFont != null) {
                InstrumentLibrary generalUser = InstrumentCatalog.standard().get(0);
                registry.importLocation(generalUser, optionalSoundFont);
                for (InstrumentLibrary installed : registry.installedInstruments()) {
                    if (installed.id().startsWith("generaluser::")) {
                        assignments.put("melody", installed);
                        break;
                    }
                }
                if (!assignments.containsKey("melody")) {
                    throw new IllegalStateException("Failed to register the test SoundFont.");
                }
            }
            Path mixed = new StudioMusicRenderer(registry).render(
                    tsv, sandbox.resolve("render"), assignments, "fluidsynth", "sfizz_render",
                    new Progress() {
                        @Override
                        public void update(String message) {
                            System.out.println(message);
                        }
                    });
            if (!Files.isRegularFile(mixed) || Files.size(mixed) < 1000
                    || !Files.isRegularFile(mixed.getParent().resolve("arrangement.mid"))) {
                throw new IllegalStateException("The studio did not produce MIDI and mixed WAV.");
            }
            try (AudioInputStream stream = AudioSystem.getAudioInputStream(mixed.toFile())) {
                if (stream.getFrameLength() < 44100) {
                    throw new IllegalStateException("The mixed WAV is unexpectedly short.");
                }
            }
            System.out.println("Studio smoke test passed: SFZ directory import, piece model, model rendering, voice addition, region rework, piece extension, "
                    + (optionalSoundFont == null ? "Java fallback" : "GeneralUser sampled melody")
                    + ", MIDI export and WAV mix.");
        } finally {
            removeTree(sandbox);
        }
    }

    private static void verifyDirectoryImport(InstrumentRegistry registry, Path sandbox)
            throws Exception {
        Path folder = Files.createDirectories(sandbox.resolve("sfz-library"));
        Files.write(folder.resolve("choir.sfz"), new byte[]{'<', 'g', 'r', 'o', 'u', 'p', '>'});
        Files.write(folder.resolve("brass.sfz"), new byte[]{'<', 'g', 'r', 'o', 'u', 'p', '>'});
        Files.write(folder.resolve("ignored.txt"), new byte[]{'x'});
        int imported = registry.importLocation(InstrumentCatalog.standard().get(8), folder);
        if (imported != 2 || registry.installedInstruments().size() != 2) {
            throw new IllegalStateException("Importing two SFZ presets should yield two instruments.");
        }
        InstrumentRegistry reloaded = new InstrumentRegistry(sandbox.resolve("libraries.properties"));
        if (reloaded.installedInstruments().size() != 2) {
            throw new IllegalStateException("Imported presets were not persisted.");
        }
    }

    /**
     * Opening the bundled piece takes it into the model, as opening any piece from before the
     * model does: its arrangement stays byte for byte, in place and in the folder "legacy".
     */
    private static void verifyPieceModel(Path folder, Path arrangement) throws Exception {
        byte[] written = Files.readAllBytes(arrangement);
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> session =
                com.aresstack.audio.studio.application.ProjectSession.open(folder);
        if (!session.isPresent() || session.get().composition().tracks().isEmpty()
                || !Files.isRegularFile(folder.resolve("composition.json"))) {
            throw new IllegalStateException("The bundled piece was not taken into the model.");
        }
        if (!java.util.Arrays.equals(written, Files.readAllBytes(arrangement))
                || !java.util.Arrays.equals(written, Files.readAllBytes(folder.resolve("legacy").resolve("arrangement.tsv")))) {
            throw new IllegalStateException("Opening the bundled piece changed its arrangement.");
        }
        byte[] model = Files.readAllBytes(folder.resolve("composition.json"));
        com.aresstack.audio.studio.application.ProjectSession again =
                com.aresstack.audio.studio.application.ProjectSession.open(folder).get();
        if (again.externalChanges() || again.synchronize()
                || !java.util.Arrays.equals(model, Files.readAllBytes(folder.resolve("composition.json")))) {
            throw new IllegalStateException("A piece that was not touched changed when it was opened again.");
        }
        System.out.println("Piece model: " + session.get().composition().tracks().size() + " tracks, "
                + session.get().composition().parts().size() + " parts taken over; old files kept.");
    }

    /**
     * The bundled piece is rendered from its model and mixed on its stage; rendering it again
     * renders nothing, because every stem is in the cache.
     */
    private static void verifyModelRendering(InstrumentRegistry registry, Path folder) throws Exception {
        com.aresstack.audio.studio.application.ProjectSession session =
                com.aresstack.audio.studio.application.ProjectSession.open(folder).get();
        com.aresstack.audio.studio.application.PieceRendering rendering =
                new com.aresstack.audio.studio.application.PieceRendering(registry);
        java.util.Map<String, Integer> programs = new HashMap<String, Integer>();
        com.aresstack.audio.studio.application.PieceRendering.Outcome first = rendering.render(session, null,
                com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine.GERVILL, "fluidsynth",
                "sfizz_render", programs, message -> { });
        if (!Files.isRegularFile(first.mix()) || Files.size(first.mix()) < 1000
                || first.stems().rendered().size() != session.composition().parts().size()
                || !Files.isRegularFile(folder.resolve("arrangement.mid"))) {
            throw new IllegalStateException("The piece was not rendered from its model.");
        }
        com.aresstack.audio.studio.application.PieceRendering.Outcome second = rendering.render(session, null,
                com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine.GERVILL, "fluidsynth",
                "sfizz_render", programs, message -> { });
        if (!second.stems().rendered().isEmpty()) {
            throw new IllegalStateException("An unchanged piece was rendered again: " + second.stems().rendered());
        }
        System.out.println("Model rendering: " + first.stems().rendered().size() + " stems rendered, then "
                + second.stems().reused().size() + " taken from the cache.");
    }

    /**
     * A voice is added to the bundled piece by a model that answers without network: the proposal
     * is rendered in its preview folder, where only the new voice is rendered and the others come
     * from the stems of the piece; taken, it is part of the piece, and nothing is rendered twice.
     */
    private static void verifyVoiceAddition(InstrumentRegistry registry, Path folder) throws Exception {
        com.aresstack.audio.studio.application.ProjectSession session =
                com.aresstack.audio.studio.application.ProjectSession.open(folder).get();
        int partsBefore = session.composition().parts().size();
        com.aresstack.audio.studio.ai.ChatModel model = (instruction, prompt, maxTokens, cache) -> {
            String part = quoted(prompt, "part");
            String section = quoted(prompt, "section");
            return com.google.gson.JsonParser.parseString("{\"part\":\"" + part + "\",\"section\":\"" + section
                    + "\",\"notes\":[{\"bar\":1,\"beat\":2,\"duration\":2,\"pitch\":72,\"velocity\":90},"
                    + "{\"bar\":2,\"beat\":0,\"duration\":1,\"pitch\":74,\"velocity\":85}]}").getAsJsonObject();
        };
        com.aresstack.audio.studio.application.VoiceOffer offer = com.aresstack.audio.studio.application.VoiceOffer.start(
                session, com.aresstack.audio.studio.ai.VoiceComposer.with(model),
                com.aresstack.audio.studio.ai.AddVoiceRequest.of("Trompete", "Trumpet", 56, "melody"), message -> { });
        String added = offer.soloPartId();
        if (session.composition().parts().size() != partsBefore) {
            throw new IllegalStateException("A proposed voice changed the piece before it was taken.");
        }
        com.aresstack.audio.studio.application.PieceRendering rendering =
                new com.aresstack.audio.studio.application.PieceRendering(registry);
        java.util.Map<String, Integer> programs = new HashMap<String, Integer>(offer.newPrograms());
        com.aresstack.audio.studio.application.PieceRendering.Outcome heard = rendering.render(offer.preview(),
                offer.proposed(), null, com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine.GERVILL,
                "fluidsynth", "sfizz_render", programs, message -> { });
        if (!heard.stems().rendered().equals(java.util.List.of(added)) || heard.stems().reused().size() != partsBefore
                || !Files.isRegularFile(heard.mix()) || !Files.isRegularFile(heard.stems().stems().get(added))) {
            throw new IllegalStateException("The preview of the new voice rendered " + heard.stems().rendered()
                    + " instead of only the new voice.");
        }
        offer.accept();
        if (session.composition().part(added) == null || session.composition().parts().size() != partsBefore + 1) {
            throw new IllegalStateException("The new voice was not taken into the piece.");
        }
        com.aresstack.audio.studio.application.PieceRendering.Outcome after = rendering.render(session, null,
                com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine.GERVILL, "fluidsynth",
                "sfizz_render", programs, message -> { });
        if (!after.stems().rendered().isEmpty()) {
            throw new IllegalStateException("After taking the voice, parts were rendered again: "
                    + after.stems().rendered());
        }
        System.out.println("Voice addition: proposed, only the new voice rendered, taken, nothing rendered twice.");
    }

    /**
     * The first two bars of one part of the bundled piece are written anew by a model that answers
     * without network: in the preview only that part is rendered, the proposal changes no other
     * part, and after it is taken nothing is rendered twice.
     */
    private static void verifyRegionRework(InstrumentRegistry registry, Path folder) throws Exception {
        com.aresstack.audio.studio.application.ProjectSession session =
                com.aresstack.audio.studio.application.ProjectSession.open(folder).get();
        com.aresstack.audio.composition.Composition before = session.composition();
        String part = before.parts().get(0).id();
        com.aresstack.audio.studio.ai.ChatModel model = (instruction, prompt, maxTokens, cache) ->
                com.google.gson.JsonParser.parseString("{\"part\":\"" + quoted(prompt, "part") + "\",\"section\":\""
                        + quoted(prompt, "section") + "\",\"notes\":[{\"bar\":1,\"beat\":0,\"duration\":2,\"pitch\":76,"
                        + "\"velocity\":90},{\"bar\":2,\"beat\":1,\"duration\":1,\"pitch\":79,\"velocity\":85}]}")
                        .getAsJsonObject();
        com.aresstack.audio.studio.application.RegionOffer offer = com.aresstack.audio.studio.application.RegionOffer.start(
                session, com.aresstack.audio.studio.ai.RegionComposer.with(model),
                com.aresstack.audio.studio.ai.RegionRequest.replace(
                        com.aresstack.audio.composition.edit.Selection.bars(before, java.util.List.of(part), 1, 2), ""),
                java.util.List.of(part), 1, 2, message -> { });
        for (com.aresstack.audio.composition.Part other : before.parts()) {
            if (!other.id().equals(part) && !before.notesOf(other.id()).equals(offer.proposed().notesOf(other.id()))) {
                throw new IllegalStateException("The rewritten passage changed another part: " + other.id());
            }
        }
        com.aresstack.audio.studio.application.PieceRendering rendering =
                new com.aresstack.audio.studio.application.PieceRendering(registry);
        java.util.Map<String, Integer> programs = new HashMap<String, Integer>();
        com.aresstack.audio.studio.application.PieceRendering.Outcome heard = rendering.render(offer.preview(),
                offer.proposed(), null, com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine.GERVILL,
                "fluidsynth", "sfizz_render", programs, message -> { });
        if (!heard.stems().rendered().equals(java.util.List.of(part))) {
            throw new IllegalStateException("The preview of the passage rendered " + heard.stems().rendered()
                    + " instead of only " + part + ".");
        }
        offer.accept();
        com.aresstack.audio.studio.application.PieceRendering.Outcome after = rendering.render(session, null,
                com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine.GERVILL, "fluidsynth",
                "sfizz_render", programs, message -> { });
        if (!after.stems().rendered().isEmpty() || session.composition().equals(before)) {
            throw new IllegalStateException("The passage was not taken, or parts were rendered again: "
                    + after.stems().rendered());
        }
        System.out.println("Region rework: proposed, only the rewritten part rendered, taken, nothing rendered twice.");
    }

    /**
     * The piece is made longer with a model that answers a plan of one section and two notes per
     * part: the proposal leaves the bars there were alone, is heard, taken, and the piece is four
     * bars longer.
     */
    private static void verifyExtension(InstrumentRegistry registry, Path folder) throws Exception {
        com.aresstack.audio.studio.application.ProjectSession session =
                com.aresstack.audio.studio.application.ProjectSession.open(folder).get();
        com.aresstack.audio.composition.Composition before = session.composition();
        long oldEnd = 0;
        for (com.aresstack.audio.composition.Part part : before.parts()) {
            for (com.aresstack.audio.composition.Composition.PlacedNote note : before.notesOf(part.id())) {
                oldEnd = Math.max(oldEnd, note.tick() + note.note().length());
            }
        }
        final long end = oldEnd;
        StringBuilder all = new StringBuilder();
        for (com.aresstack.audio.composition.Part part : before.parts()) {
            all.append(all.length() == 0 ? "" : ",").append('"').append(part.id()).append('"');
        }
        final String plan = "{\"sections\":[{\"name\":\"Coda\",\"function\":\"outro\",\"bars\":4,\"intensity\":0.4,"
                + "\"chords\":[],\"idea\":\"close\",\"parts\":[" + all + "]}],\"newParts\":[],\"motifs\":\"\","
                + "\"arc\":\"close\"}";
        com.aresstack.audio.studio.ai.ChatModel model = (instruction, prompt, maxTokens, cache) ->
                com.google.gson.JsonParser.parseString(
                        instruction.equals(com.aresstack.audio.studio.ai.ChatContinuationPlanner.INSTRUCTION) ? plan
                                : "{\"part\":\"" + quoted(prompt, "part") + "\",\"section\":\"" + quoted(prompt, "section")
                                + "\",\"notes\":[{\"bar\":1,\"beat\":0,\"duration\":2,\"pitch\":60,\"velocity\":80},"
                                + "{\"bar\":2,\"beat\":0,\"duration\":2,\"pitch\":62,\"velocity\":80}]}").getAsJsonObject();
        com.aresstack.audio.studio.application.ExtensionOffer offer =
                com.aresstack.audio.studio.application.ExtensionOffer.start(session,
                        com.aresstack.audio.studio.ai.ContinuationComposer.with(model),
                        com.aresstack.audio.studio.ai.ContinuationRequest.of(4,
                                com.aresstack.audio.studio.ai.ContinuationPreset.STANDALONE,
                                com.aresstack.audio.studio.ai.ContinuationEnding.CLOSED), message -> { });
        for (com.aresstack.audio.composition.Part part : before.parts()) {
            java.util.List<com.aresstack.audio.composition.Note> old = new java.util.ArrayList<>();
            for (com.aresstack.audio.composition.Composition.PlacedNote note : before.notesOf(part.id())) {
                old.add(note.note());
            }
            java.util.List<com.aresstack.audio.composition.Note> kept = new java.util.ArrayList<>();
            for (com.aresstack.audio.composition.Composition.PlacedNote note : offer.proposed().notesOf(part.id())) {
                if (old.contains(note.note())) {
                    kept.add(note.note());
                }
            }
            if (!kept.equals(old)) {
                throw new IllegalStateException("The continuation changed the bars there were: " + part.id());
            }
        }
        if (offer.listenFromSecond() <= 0 || offer.notes().isEmpty()) {
            throw new IllegalStateException("The continuation does not say its plan or where to listen from.");
        }
        com.aresstack.audio.studio.application.PieceRendering rendering =
                new com.aresstack.audio.studio.application.PieceRendering(registry);
        java.util.Map<String, Integer> programs = new HashMap<String, Integer>(offer.newPrograms());
        rendering.render(offer.preview(), offer.proposed(), null,
                com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine.GERVILL, "fluidsynth",
                "sfizz_render", programs, message -> { });
        offer.accept();
        com.aresstack.audio.studio.application.PieceRendering.Outcome after = rendering.render(session, null,
                com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine.GERVILL, "fluidsynth",
                "sfizz_render", programs, message -> { });
        int bars = session.composition().timeMap().barsCovering(end);
        int now = bars;
        for (com.aresstack.audio.composition.Section section : session.composition().sections()) {
            now = Math.max(now, session.composition().timeMap().barsCovering(section.startTick() + section.lengthTicks()));
        }
        if (now != bars + 4 || !after.stems().rendered().isEmpty()) {
            throw new IllegalStateException("The piece is not four bars longer (" + bars + " to " + now
                    + "), or parts were rendered again: " + after.stems().rendered());
        }
        System.out.println("Piece extension: planned, proposed, heard, taken, four bars longer, nothing rendered twice.");
    }

    /** The value between {@code "<key>":"} and the next quote in a prompt. */
    private static String quoted(String prompt, String key) {
        String marker = "\"" + key + "\":\"";
        int from = prompt.lastIndexOf(marker);
        if (from < 0) {
            return "";
        }
        from += marker.length();
        return prompt.substring(from, prompt.indexOf('"', from));
    }

    private static Path copyBundledComposition(Path directory) throws Exception {
        Path output = directory.resolve("arrangement.tsv");
        try (InputStream input = AudioStudioMain.class.getResourceAsStream("/district-dawn.tsv")) {
            if (input == null) {
                throw new IllegalStateException("Original Gemma demo is not bundled.");
            }
            Files.copy(input, output);
        }
        return output;
    }

    private static void removeTree(Path directory) throws Exception {
        try (Stream<Path> children = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) children.sorted(Collections.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}
