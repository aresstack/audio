package com.aresstack.audio.studio.architecture;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Rules of the architecture (docs/architecture/2026-11-architekturpruefung.md, 2.1) that the
 * compiler does not check: dependencies only point down.
 */
public final class PackageDependencyTest {
    private static final Path ROOT = Paths.get("src", "main", "java", "com", "aresstack", "audio", "studio");

    /** F7: the stage no longer imports the application, so the two have no cycle. */
    @Test
    public void theStageDependsOnNothingAboveIt() throws IOException {
        assertEquals(List.of(), referencesFrom("stage", "studio.application", "studio.ui", "studio.ai", "studio.project"));
    }

    /** The project files (import, export, storage) know no window and no AI. */
    @Test
    public void theProjectPackageKnowsNoWindowAndNoAi() throws IOException {
        assertEquals(List.of(), referencesFrom("project", "studio.ui", "studio.ai", "studio.application", "javax.swing"));
    }

    /** S1: the score is notation only; it knows no window, no AI, no application and no project files. */
    @Test
    public void theScoreKnowsNothingAboveIt() throws IOException {
        assertEquals(List.of(), referencesFrom("score", "studio.ui", "studio.ai", "studio.application",
                "studio.project", "javax.swing"));
    }

    /** C1: planners, generators and the context of the AI know no window, no use case and no project files. */
    @Test
    public void theAiKnowsNoWindowNoUseCaseAndNoProjectFiles() throws IOException {
        assertEquals(List.of(), referencesFrom("ai", "studio.ui", "studio.application", "studio.project", "javax.swing"));
    }

    private static List<String> referencesFrom(String pkg, String... forbidden) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve(pkg))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                for (String name : forbidden) {
                    String reference = name.startsWith("javax.") ? name + "." : "com.aresstack.audio." + name + ".";
                    if (text.contains(reference)) {
                        found.add(ROOT.relativize(file) + " refers to " + name);
                    }
                }
            }
        }
        return found;
    }
}
