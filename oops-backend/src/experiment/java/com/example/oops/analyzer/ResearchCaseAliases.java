package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads Git-ignored case aliases so tracked tests never carry real incident identifiers. */
final class ResearchCaseAliases {
    private static final Path DATASET = Path.of("../datasets/controversy");
    private static final JsonNode VALUES = load();

    private ResearchCaseAliases() {}

    private static JsonNode load() {
        Path file = DATASET.resolve("research/local-case-aliases.json");
        if (!Files.isRegularFile(file)) return ReviewGuidelineLibrary.JSON.createObjectNode();
        try {
            return ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(file));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot read local case aliases", ex);
        }
    }

    static String value(String key) { return VALUES.path(key).asText(); }

    static String value(String key, String fallback) {
        String found = value(key);
        return found.isBlank() ? fallback : found;
    }

    /** Resolves to the dataset directory when unset, which file assumptions treat as absent. */
    static Path datasetFile(String key) { return DATASET.resolve(value(key)); }

    static boolean containsIncidentReference(String prompt) {
        List<String> prefixes = new ArrayList<>();
        VALUES.path("forbiddenReferencePrefixes").forEach(row -> prefixes.add(row.asText()));
        if (prefixes.isEmpty()) throw new IllegalStateException("Local case aliases required for incident guard");
        return prefixes.stream().anyMatch(prompt::contains);
    }
}
