package com.example.oops.analyzer;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * A3-T: candidates are exactly the A2-T top-K from the same frozen vector file; only their order
 * changes, using cross-encoder scores frozen offline (tools/rerank_segments.py). The difference
 * from A2-T is therefore reranking alone. Ties go to the closer segment, then transcript order.
 * Greedy under budget B; segments outside the top-K are never selected. No model runs in Java.
 */
final class RerankContextSelector implements ContextSelector {
    static final String FORMAT = "rerank-scores-v1";
    private static final String POLICY = "rerank-topk-v1";
    private static final String SCORE_TYPE = "raw-logit";

    record CandidateSource(String vectorFileSha256, int topK) {}

    record ScoreFile(String format, String modelId, String revision, String modelArtifactsHash, String transcriptSha256,
                     String rerankInputVersion, String scoreType, Map<String, String> generationLibraryVersions,
                     CandidateSource candidateSource, Map<String, Map<String, Double>> scores) {}

    record Loaded(ScoreFile file, String scoreFileSha256) {}

    record Scored(String segmentId, double score) {}

    private final EmbeddingContextSelector retrieval;
    private final ScoreFile file;
    private final String scoreFileSha256;
    private final int topK;
    private final ContextTokenBudget budget;

    RerankContextSelector(EmbeddingContextSelector retrieval, Loaded loaded, int topK, ContextTokenBudget budget) {
        this.retrieval = Objects.requireNonNull(retrieval);
        if (topK < 1) throw new IllegalArgumentException("topK must be positive");
        this.topK = topK;
        this.file = validate(loaded.file(), retrieval, topK);
        this.scoreFileSha256 = required(loaded.scoreFileSha256(), "scoreFileSha256");
        this.budget = Objects.requireNonNull(budget);
    }

    static Loaded load(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            return new Loaded(JsonMapper.builder().build().readValue(bytes, ScoreFile.class),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String id() {
        return "A3-T:" + POLICY + ":K=" + topK + ":retrieval=" + retrieval.modelRef() + ":vectors=" + retrieval.vectorFileSha256()
                + ":reranker=" + file.modelId() + "@" + file.revision() + ":input=" + file.rerankInputVersion()
                + ":scores=" + scoreFileSha256 + ":" + ContextTokenBudget.COUNTER_ID + ":B=" + budget.budget();
    }

    @Override
    public ContextSelection select(ReviewInput.Segment target, ReviewInput transcript) {
        var rendering = new ExperimentalBatchRendering(transcript, Set.of(target.id()));
        List<ReviewInput.Segment> chosen = new ArrayList<>();
        for (var scored : ranking(target, transcript)) {
            var candidate = transcript.find(scored.segmentId()).orElseThrow();
            List<ReviewInput.Segment> raw = new ArrayList<>(chosen);
            raw.add(target);
            raw.add(candidate);
            if (!budget.measure(rendering, raw).exceeded()) chosen.add(candidate);
        }
        var order = transcript.segments();
        var ids = chosen.stream().sorted(Comparator.comparingInt(order::indexOf)).map(ReviewInput.Segment::id).toList();
        return new ContextSelection(ids, id());
    }

    /** The A2-T top-K, reordered by reranker score. */
    List<Scored> ranking(ReviewInput.Segment target, ReviewInput transcript) {
        var candidates = retrieval.ranking(target, transcript).stream().limit(topK)
                .map(EmbeddingContextSelector.Scored::segmentId).toList();
        var targetScores = file.scores().get(target.id());
        if (targetScores == null) throw new IllegalArgumentException("No reranker scores for target: " + target.id());
        if (!targetScores.keySet().equals(new HashSet<>(candidates)))
            throw new IllegalArgumentException("Reranker scores do not match the A2 top-K for target: " + target.id());
        var order = transcript.segments();
        Map<String, ReviewInput.Segment> byId = new HashMap<>();
        order.forEach(s -> byId.put(s.id(), s));
        return candidates.stream().map(id -> new Scored(id, targetScores.get(id)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparingLong(s -> EmbeddingContextSelector.distance(target, byId.get(s.segmentId())))
                        .thenComparingInt(s -> order.indexOf(byId.get(s.segmentId()))))
                .toList();
    }

    private static ScoreFile validate(ScoreFile f, EmbeddingContextSelector retrieval, int topK) {
        Objects.requireNonNull(f);
        if (!FORMAT.equals(f.format())) throw new IllegalArgumentException("Unsupported score file format: " + f.format());
        required(f.modelId(), "modelId");
        required(f.revision(), "revision");
        required(f.modelArtifactsHash(), "modelArtifactsHash");
        required(f.rerankInputVersion(), "rerankInputVersion");
        if (!SCORE_TYPE.equals(f.scoreType())) throw new IllegalArgumentException("scoreType must be " + SCORE_TYPE);
        if (f.generationLibraryVersions() == null || f.generationLibraryVersions().isEmpty())
            throw new IllegalArgumentException("generationLibraryVersions required");
        if (!retrieval.transcriptSha256().equals(f.transcriptSha256()))
            throw new IllegalArgumentException("Scores and vectors were frozen for different transcripts");
        var source = f.candidateSource();
        if (source == null || !retrieval.vectorFileSha256().equals(source.vectorFileSha256()) || source.topK() != topK)
            throw new IllegalArgumentException("Scores were not built from this A2 vector file and K");
        if (f.scores() == null) throw new IllegalArgumentException("scores required");
        f.scores().forEach((target, row) -> row.forEach((id, score) -> {
            if (score == null || !Double.isFinite(score)) throw new IllegalArgumentException("Non-finite score: " + target + "/" + id);
        }));
        return f;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value;
    }
}
