package com.aresstack.audio.studio.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Run an external sampler with a bounded lifetime and captured diagnostic output. */
public final class ExternalProcessRunner {
    public void execute(List<String> command, Path outputFile) throws IOException, InterruptedException {
        Path logFile = outputFile.resolveSibling(outputFile.getFileName().toString() + ".log");
        Files.createDirectories(outputFile.toAbsolutePath().getParent());
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(logFile.toFile()).start();
        boolean completed;
        try {
            completed = process.waitFor(5, TimeUnit.MINUTES);
        } catch (InterruptedException cancelled) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw cancelled;
        }
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("Sampler exceeded five-minute timeout; see " + logFile);
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
