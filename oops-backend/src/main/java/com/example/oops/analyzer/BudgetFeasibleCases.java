package com.example.oops.analyzer;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Pre-registered budget-feasible case set for CONTROLLED analysis. A case is feasible when its gold
 * context plus evidence (the A4-T input) renders within budget B. Computed from gold labels and the
 * frozen transcripts only, before any run; written once and frozen with its digest.
 */
final class BudgetFeasibleCases {
    static final String FORMAT = "budget-feasible-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Entry(Long videoId, String targetId, int goldContextTokens, boolean feasible) {}

    record Registry(String format, String counterId, int budget, String labelVersion, List<Entry> entries) {
        Registry {
            entries = List.copyOf(entries);
        }

        Set<ExperimentAggregator.CaseRef> feasibleCases() {
            Set<ExperimentAggregator.CaseRef> cases = new HashSet<>();
            entries.stream().filter(Entry::feasible).forEach(e -> cases.add(new ExperimentAggregator.CaseRef(e.videoId(), e.targetId())));
            return Set.copyOf(cases);
        }
    }

    private BudgetFeasibleCases() {
    }

    static Registry compute(Map<Long, ReviewInput> transcripts, Map<Long, Map<String, OracleContextSelector.OracleLabel>> labels,
                            String labelVersion, ContextTokenBudget budget) {
        List<Entry> entries = new ArrayList<>();
        for (Long videoId : new TreeSet<>(labels.keySet())) {
            var transcript = transcripts.get(videoId);
            if (transcript == null) throw new IllegalArgumentException("No transcript for video: " + videoId);
            var videoLabels = labels.get(videoId);
            var oracle = new OracleContextSelector(videoLabels, labelVersion, budget);
            for (String targetId : videoLabels.keySet())
                if (transcript.find(targetId).isEmpty()) throw new IllegalArgumentException("Target outside transcript: " + videoId + "/" + targetId);
            for (var target : transcript.segments()) {
                if (!videoLabels.containsKey(target.id())) continue;
                var m = oracle.measure(target, transcript);
                entries.add(new Entry(videoId, target.id(), m.contextTokens(), !m.exceeded()));
            }
        }
        return new Registry(FORMAT, ContextTokenBudget.COUNTER_ID, budget.budget(), labelVersion, entries);
    }

    /** Write once; returns the file digest to record in the analysis plan. */
    static String write(Registry registry, Path path) {
        byte[] bytes = JSON.writeValueAsString(registry).getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("Budget-feasible registry already exists: " + path, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Registry load(Path path) {
        try {
            var registry = JSON.readValue(Files.readAllBytes(path), Registry.class);
            if (!FORMAT.equals(registry.format())) throw new IllegalArgumentException("Unsupported format: " + registry.format());
            return registry;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
