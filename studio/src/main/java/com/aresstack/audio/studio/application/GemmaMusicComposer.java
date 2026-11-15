package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.progress.TrackProgress;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Plan a song once, then compose any validated arrangement of voices concurrently. */
public final class GemmaMusicComposer {
    private static final String[] CHORDS = {"C", "G", "Am", "F", "C", "G", "F", "G"};
    private final KipitzChatClient client;
    private final int maxTokens;
    private final int maxParallel;

    public GemmaMusicComposer(KipitzChatClient client) {
        this(client, 16384, 32);
    }

    public GemmaMusicComposer(KipitzChatClient client, int maxTokens) {
        this(client, maxTokens, 32);
    }

    public GemmaMusicComposer(KipitzChatClient client, int maxTokens, int maxParallel) {
        if (maxTokens < 256 || maxParallel < 1 || maxParallel > 1024) {
            throw new IllegalArgumentException("Use at least 256 tokens and 1–1024 parallel requests.");
        }
        this.client = client;
        this.maxTokens = maxTokens;
        this.maxParallel = maxParallel;
    }

    public Path compose(String description, Path directory, Progress progress) throws IOException {
        return compose(description, directory, TrackArrangement.defaults(), progress);
    }

    public Path compose(String description, Path directory, TrackArrangement tracks,
                        Progress progress) throws IOException {
        Files.createDirectories(directory);
        progress.update("Composition plan ...");
        JsonObject plan = plan(description, tracks, directory);
        Map<String, JsonObject> responses = generateTracks(tracks, plan, directory, progress);
        return assemble(tracks, responses, directory, progress);
    }

    private JsonObject plan(String description, TrackArrangement tracks, Path directory)
            throws IOException {
        StringBuilder instruments = new StringBuilder();
        for (MusicTrack track : tracks.tracks()) {
            instruments.append(track.id()).append(": ").append(track.instrument())
                    .append(" (").append(track.role()).append("); ");
        }
        String prompt = "Compose ORIGINAL short instrumental game music for: " + description
                + "\nInstrumentation: " + instruments
                + "\nReturn JSON with title, mood, tempoBpm=108, key=\"C major\", "
                + "timeSignature=\"4/4\", bars=8, "
                + "chords=[\"C\",\"G\",\"Am\",\"F\",\"C\",\"G\",\"F\",\"G\"], arrangementNotes."
                + "\nKeep these exact musical constraints. No notes yet. Return only JSON.";
        JsonObject plan = client.generate("Return valid JSON only, no Markdown.", prompt,
                Math.min(2048, maxTokens), directory.resolve("composition-plan.json"));
        validatePlan(plan);
        return plan;
    }

    private Map<String, JsonObject> generateTracks(TrackArrangement tracks, JsonObject plan,
                                                    Path directory, Progress progress)
            throws IOException {
        int threads = Math.min(maxParallel, tracks.tracks().size());
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CompletionService<TrackResponse> completion =
                new ExecutorCompletionService<TrackResponse>(executor);
        List<Future<TrackResponse>> pending = new ArrayList<Future<TrackResponse>>();
        progress.update("Composing " + tracks.tracks().size() + " tracks, " + threads + " at the same time ...");
        try {
            for (MusicTrack track : tracks.tracks()) {
                TrackProgress.tell(progress, track.id(), TrackProgress.Stage.COMPOSE, TrackProgress.State.WAITING, "");
            }
            for (MusicTrack track : tracks.tracks()) {
                final MusicTrack target = track;
                pending.add(completion.submit(new Callable<TrackResponse>() {
                    @Override
                    public TrackResponse call() throws IOException {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("Composition cancelled.");
                        }
                        progress.update("Composing " + target.id() + " ("
                                + target.instrument() + ") ...");
                        TrackProgress.tell(progress, target.id(), TrackProgress.Stage.COMPOSE, TrackProgress.State.WORKING, "");
                        try {
                            String prompt = promptFor(target, plan);
                            Path cached = cachedResponse(directory, target, prompt);
                            boolean known = Files.isRegularFile(cached);
                            JsonObject answer = client.generate(
                                    "Compose original instrumental music. Return valid JSON only.",
                                    prompt, maxTokens, cached);
                            validateResponse(target, answer);
                            TrackProgress.tell(progress, target.id(), TrackProgress.Stage.COMPOSE, TrackProgress.State.DONE,
                                    answer.getAsJsonArray("notes").size() + (known ? " notes, cached" : " notes"));
                            return new TrackResponse(target.id(), answer);
                        } catch (IOException failed) {
                            TrackProgress.tell(progress, target.id(), TrackProgress.Stage.COMPOSE, TrackProgress.State.FAILED, String.valueOf(failed.getMessage()));
                            throw failed;
                        } catch (RuntimeException failed) {
                            TrackProgress.tell(progress, target.id(), TrackProgress.Stage.COMPOSE, TrackProgress.State.FAILED, String.valueOf(failed));
                            throw failed;
                        }
                    }
                }));
            }
            Map<String, JsonObject> responses = new HashMap<String, JsonObject>();
            for (int i = 0; i < pending.size(); i++) {
                TrackResponse generated = completion.take().get();
                responses.put(generated.id, generated.response);
                progress.update("Finished " + generated.id + " ("
                        + generated.response.getAsJsonArray("notes").size() + " notes)");
            }
            return responses;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            cancel(pending);
            client.cancel();
            throw new IOException("Composition cancelled.", cancelled);
        } catch (ExecutionException failed) {
            cancel(pending);
            client.cancel();
            Throwable cause = failed.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("One instrument generation failed: " + cause, cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void cancel(List<Future<TrackResponse>> tasks) {
        for (Future<TrackResponse> task : tasks) {
            task.cancel(true);
        }
    }

    private static String promptFor(MusicTrack track, JsonObject plan) {
        return "Compose the independent " + track.id() + " track (" + track.instrument()
                + "; role=" + track.role() + "; zero-based General MIDI program="
                + track.program() + ") for an ORIGINAL 8-bar instrumental song. "
                + "4/4, 108 BPM, C major, 32 quarter-note beats, chords by bar: "
                + "C | G | Am | F | C | G | F | G. "
                + "Arrangement: " + plan.get("arrangementNotes").getAsString()
                + "\n" + rules(track.role())
                + "\nReturn exactly one JSON object with keys part, program, notes. "
                + "Set part=\"" + track.id() + "\" and program=" + track.program() + ". "
                + "Every note needs numeric beat,duration,pitch,velocity; "
                + "0 <= beat < 32, 0.25 <= duration <= 4, beat+duration <= 32. "
                + "Align to multiples of 0.25. Use absolute song beats and explicitly list "
                + "the notes. Compose this track as part of the shared arrangement; no Markdown.";
    }

    private static String rules(String role) {
        if (MusicTrack.MELODY.equals(role)) {
            return "Create a memorable, expressive motif with 8–55 notes, pitches 60..88.";
        }
        if (MusicTrack.HARMONY.equals(role)) {
            return "Write chord tones with elegant voice leading, pitches 48..76, 8–60 notes.";
        }
        if (MusicTrack.BASS.equals(role)) {
            return "Write roots, fifths and bass movements, pitches 36..60, 8–48 notes.";
        }
        if (MusicTrack.DRUMS.equals(role)) {
            return "Write 16–90 GM drum hits using pitches 36 kick, 38 snare, 42 closed "
                    + "hi-hat and 46 open hi-hat; do not use pitched notes.";
        }
        return "Write 8–64 contrasting decorative notes suited to this instrument, "
                + "using C-major pitches 48..88 and the shared harmonic progression.";
    }

    private static void validateResponse(MusicTrack track, JsonObject response) throws IOException {
        try {
            if (!track.id().equals(response.get("part").getAsString())
                    || track.program() != response.get("program").getAsInt()) {
                throw new IOException("Model returned an unexpected ID or program for " + track.id()
                        + "; keep cached JSON for diagnostics.");
            }
            JsonArray notes = response.getAsJsonArray("notes");
            if (notes == null || notes.size() < 1 || notes.size() > 256) {
                throw new IOException("Unsupported note count for " + track.id());
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Malformed notes for " + track.id(), invalid);
        }
    }

    private static Path cachedResponse(Path directory, MusicTrack track, String prompt)
            throws IOException {
        if (track.hasLegacyCacheIdentity()) {
            return directory.resolve(track.id() + ".json");
        }
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] digest = hash.digest(prompt.getBytes(StandardCharsets.UTF_8));
            StringBuilder shortHash = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                shortHash.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
            }
            return directory.resolve(track.id() + "-" + shortHash + ".json");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable.", impossible);
        }
    }

    private static Path assemble(TrackArrangement tracks, Map<String, JsonObject> responses,
                                 Path directory, Progress progress) throws IOException {
        List<String> arrangement = new ArrayList<String>();
        arrangement.add("tempo\t108");
        CompositionNoteNormalizer normalizer = new CompositionNoteNormalizer(progress);
        for (MusicTrack track : tracks.tracks()) {
            JsonObject part = responses.get(track.id());
            if (part == null) {
                throw new IOException("Missing generated instrument: " + track.id());
            }
            int channel = tracks.channelFor(track.id());
            arrangement.add("track\t" + track.id() + "\t" + track.program() + "\t"
                    + channel + "\t" + track.role());
            int playable = 0;
            JsonArray notes = part.getAsJsonArray("notes");
            for (int index = 0; index < notes.size(); index++) {
                JsonElement element = notes.get(index);
                if (!element.isJsonObject()) {
                    progress.update("WARN: Ignoring non-object note in " + track.id());
                    continue;
                }
                CompositionNoteNormalizer.NormalizedNote note = normalizer.normalize(
                        element.getAsJsonObject(), track.id(), track.role(), index + 1);
                if (note == null) {
                    continue;
                }
                arrangement.add("note\t" + track.id() + "\t" + note.startTick()
                        + "\t" + note.durationTicks() + "\t" + note.pitch()
                        + "\t" + note.velocity());
                playable++;
            }
            if (playable == 0) {
                throw new IOException("No playable notes for " + track.id()
                        + ". The cached model response was preserved.");
            }
        }
        Path output = directory.resolve("arrangement.tsv");
        Files.write(output, arrangement, StandardCharsets.UTF_8);
        Files.write(directory.resolve("note-corrections.tsv"),
                normalizer.corrections(), StandardCharsets.UTF_8);
        progress.update("Created " + tracks.tracks().size() + " MIDI tracks; "
                + normalizer.correctionCount() + " note corrections. Cached JSON preserved.");
        return output;
    }

    private static void validatePlan(JsonObject plan) throws IOException {
        try {
            if (plan.get("tempoBpm").getAsInt() != 108 || plan.get("bars").getAsInt() != 8
                    || !"C major".equals(plan.get("key").getAsString())
                    || !"4/4".equals(plan.get("timeSignature").getAsString())) {
                throw new IOException("Model changed locked tempo, key or duration.");
            }
            JsonArray chords = plan.getAsJsonArray("chords");
            if (chords.size() != CHORDS.length) {
                throw new IOException("Expected eight chords.");
            }
            for (int i = 0; i < CHORDS.length; i++) {
                if (!CHORDS[i].equals(chords.get(i).getAsString())) {
                    throw new IOException("Unexpected chord at bar " + (i + 1));
                }
            }
        } catch (RuntimeException invalid) {
            throw new IOException("The cached or generated composition plan is invalid.", invalid);
        }
    }

    private static final class TrackResponse {
        private final String id;
        private final JsonObject response;

        private TrackResponse(String id, JsonObject response) {
            this.id = id;
            this.response = response;
        }
    }
}
