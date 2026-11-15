package com.aresstack.audio.studio.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Run an external sampler with a bounded lifetime and captured diagnostic output. */
public final class ExternalProcessRunner {
    /** Runs with the shortest time {@link RenderLimits#STANDARD} gives any sampler. */
    public void execute(List<String> command, Path outputFile) throws IOException, InterruptedException {
        execute(command, outputFile, RenderLimits.STANDARD.minProcess());
    }

    /** Runs and stops the process once it has taken longer than the timeout. */
    public void execute(List<String> command, Path outputFile, Duration timeout)
            throws IOException, InterruptedException {
        Path logFile = outputFile.resolveSibling(outputFile.getFileName().toString() + ".log");
        Files.createDirectories(outputFile.toAbsolutePath().getParent());
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(logFile.toFile()).start();
        } catch (IOException unavailable) {
            throw new SamplerUnavailableException(command.get(0), unavailable);
        }
        boolean completed;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException cancelled) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw cancelled;
        }
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("Sampler exceeded its time limit of " + timeout.toSeconds() + " s; see " + logFile);
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(outputFile)
                || Files.size(outputFile) < 44) {
            String reason = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
            if (reason.length() > 4000) {
                reason = reason.substring(reason.length() - 4000);
            }
            throw new IOException("Sampler failed (exit " + process.exitValue() + "): " + reason);
        }
    }
}
