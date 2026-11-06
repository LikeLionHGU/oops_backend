package com.example.oops.analyzer;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

/**
 * Write-once directory per run ID: manifest.json plus results.jsonl, one line per
 * (condition, repeat, target), appended and flushed as each target finishes.
 */
final class ExperimentResultStore implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MANIFEST = "manifest.json", RESULTS = "results.jsonl";

    record Manifest(String runId, Instant createdAt, Map<String, String> metadata) {}

    private final Path runDir;
    private final String runId;
    private final Set<String> written = new HashSet<>();

    private ExperimentResultStore(Path runDir, String runId) {
        this.runDir = runDir;
        this.runId = runId;
    }

    static ExperimentResultStore create(Path runDir, String runId, Map<String, String> metadata) {
        if (runId == null || !runId.matches("[A-Za-z0-9._-]{1,64}") || runId.startsWith("."))
            throw new IllegalArgumentException("Invalid run ID: " + runId);
        try {
            if (runDir.getParent() != null) Files.createDirectories(runDir.getParent());
            Files.createDirectory(runDir);
            Files.writeString(runDir.resolve(MANIFEST), JSON.writeValueAsString(new Manifest(runId, Instant.now(), Map.copyOf(metadata))),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.createFile(runDir.resolve(RESULTS));
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("Run directory already exists: " + runDir, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ExperimentResultStore(runDir, runId);
    }

    String runId() {
        return runId;
    }

    void append(ContextExperimentRunner.TargetRecord record) {
        if (!runId.equals(record.runId())) throw new IllegalArgumentException("Record belongs to another run: " + record.runId());
        if (!written.add(key(record.conditionId(), record.repeat(), record.targetId())))
            throw new IllegalArgumentException("Duplicate record: " + record.conditionId() + "/" + record.repeat() + "/" + record.targetId());
        try {
            Files.writeString(runDir.resolve(RESULTS), JSON.writeValueAsString(record) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean contains(String conditionId, int repeat, String targetId) {
        return written.contains(key(conditionId, repeat, targetId));
    }

    static List<ContextExperimentRunner.TargetRecord> read(Path runDir) {
        try {
            return Files.readAllLines(runDir.resolve(RESULTS), StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank())
                    .map(l -> JSON.readValue(l, ContextExperimentRunner.TargetRecord.class)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String key(String conditionId, int repeat, String targetId) {
        return conditionId + "\0" + repeat + "\0" + targetId;
    }

    @Override
    public void close() {
    }
}
