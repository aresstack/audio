package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.edit.ChangeSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps the answers of the AI for a track in step with the model of the piece. The composer
 * takes a track from its kept answer ({@code <track>.json}, {@code <track>-<hash>.json}) when the
 * whole piece is composed again; so the answer in the folder of the piece has to be the one the
 * track of the model was written from. Taking a proposal puts a new answer there. When that step
 * is taken back, done again, or a revision is brought back, the answer has to go with it;
 * otherwise composing the whole piece would bring back a voice the user rejected (PO finding).
 *
 * <p>The shelf is the folder {@code answers/} of the piece: for a track and the notes it has, the
 * answers it was written from; an empty entry says that this state had none. A state the shelf
 * does not know (notes written by hand, for one) leaves the answers of the piece as they are.
 */
public final class AnswerShelf {
    static final String FOLDER = "answers";

    private AnswerShelf() {
    }

    /** Notes the answers the piece keeps for a track as those of the notes the track has in this state. */
    public static void keep(Path piece, Composition state, String trackId) throws IOException {
        Path entry = entry(piece, state, trackId);
        clear(entry);
        Files.createDirectories(entry);
        for (Path answer : answers(piece, trackId)) {
            Files.copy(answer, entry.resolve(answer.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * After a step that changed the piece: every track that sounds different gets the answers of
     * the notes it has now, if the shelf knows that state. Answers that were there and are not
     * those go to the folder "earlier", as when a proposal is taken.
     *
     * @return the tracks whose answers were exchanged
     */
    public static Set<String> follow(Path piece, Composition now, ChangeSet changes) throws IOException {
        Set<String> exchanged = new TreeSet<String>();
        if (changes == null) {
            return exchanged;
        }
        Set<String> touched = new TreeSet<String>(changes.changed());
        touched.addAll(changes.added());
        for (String trackId : touched) {
            if (now.part(trackId) == null) {
                continue;
            }
            Path entry = entry(piece, now, trackId);
            if (!Files.isDirectory(entry) || same(entry, answers(piece, trackId))) {
                continue;
            }
            for (Path stale : answers(piece, trackId)) {
                Path aside = Files.createDirectories(piece.resolve("earlier"));
                Files.move(stale, aside.resolve(System.currentTimeMillis() + "-" + stale.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            for (Path kept : list(entry)) {
                Files.copy(kept, piece.resolve(kept.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
            exchanged.add(trackId);
        }
        return exchanged;
    }

    /** The answers the piece keeps for a track. */
    static List<Path> answers(Path piece, String trackId) throws IOException {
        Pattern names = Pattern.compile(Pattern.quote(trackId) + "(-[0-9a-f]{12})?\\.json");
        List<Path> answers = new ArrayList<Path>();
        for (Path file : list(piece)) {
            if (names.matcher(file.getFileName().toString()).matches()) {
                answers.add(file);
            }
        }
        return answers;
    }

    private static boolean same(Path entry, List<Path> answers) throws IOException {
        List<Path> kept = list(entry);
        if (kept.size() != answers.size()) {
            return false;
        }
        for (Path answer : answers) {
            Path twin = entry.resolve(answer.getFileName());
            if (!Files.isRegularFile(twin) || !Arrays.equals(Files.readAllBytes(twin), Files.readAllBytes(answer))) {
                return false;
            }
        }
        return true;
    }

    private static List<Path> list(Path folder) throws IOException {
        List<Path> found = new ArrayList<Path>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                    if (Files.isRegularFile(file)) {
                        found.add(file);
                    }
                }
            }
        }
        return found;
    }

    private static void clear(Path entry) throws IOException {
        for (Path file : list(entry)) {
            Files.delete(file);
        }
    }

    /** Where the shelf keeps the answers of a track for the notes it has in a state. */
    private static Path entry(Path piece, Composition state, String trackId) throws IOException {
        StringBuilder notes = new StringBuilder();
        List<String> lines = new ArrayList<String>();
        for (Composition.PlacedNote placed : state.notesOf(trackId)) {
            lines.add(placed.tick() + " " + placed.note().length() + " " + placed.note().pitch() + " "
                    + placed.note().velocity());
        }
        java.util.Collections.sort(lines);
        for (String line : lines) {
            notes.append(line).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(notes.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                name.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
            }
            return piece.resolve(FOLDER).resolve(trackId.replaceAll("[^A-Za-z0-9_.-]", "_") + "-" + name);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable.", impossible);
        }
    }
}
