package com.example.oops.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Current source locations for new plans; does not rewrite or accept historical plan hashes. */
final class ExperimentSources {
    private ExperimentSources() {}

    static Path analyzerFile(String filename) {
        if (filename == null || !filename.matches("[A-Za-z][A-Za-z0-9]*\\.java"))
            throw new IllegalArgumentException("Analyzer source filename required");
        var matches = List.of("src/experiment/java", "src/test/java").stream()
                .map(root -> Path.of(root, "com/example/oops/analyzer", filename))
                .filter(Files::isRegularFile).toList();
        if (matches.size() != 1) throw new IllegalStateException("Missing or ambiguous source: " + filename);
        return matches.get(0);
    }

    static void addImplementation(Map<String, String> hashes) throws Exception {
        try (var paths = Files.walk(Path.of("src/experiment/java"))) {
            for (var path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).sorted().toList())
                hashes.put(path.toString(), ReferenceOnlyTrial.sha(Files.readAllBytes(path)));
        }
        for (var path : List.of(Path.of("build.gradle"), Path.of("gradle/experiments.gradle")))
            hashes.put(path.toString(), ReferenceOnlyTrial.sha(Files.readAllBytes(path)));
    }
}
