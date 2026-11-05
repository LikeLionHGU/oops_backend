package com.example.oops.analyzer;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * A2-T: dense retrieval from a frozen vector file produced offline (tools/embed_segments.py).
 * Query = the target's precomputed query vector; documents = every other segment of the same frozen
 * transcript. Cosine on normalized vectors, ties to the closer segment then transcript order.
 * Greedy under budget B; a ranked segment that does not fit is skipped, not truncated.
 * No model runs in Java.
 */
final class EmbeddingContextSelector implements ContextSelector {
    static final String FORMAT = "segment-embeddings-v1";
    private static final String POLICY = "dense-cosine-v1";
    private static final String DTYPE = "float32";
    private static final double NORM_TOLERANCE = 1e-3;

    record EmbeddingFile(String format, String modelId, String revision, String modelArtifactsHash, String transcriptSha256,
                         String embeddingInputVersion, int vectorDimension, String vectorDtype, boolean normalized,
                         Map<String, String> generationLibraryVersions,
                         Map<String, List<Double>> documentVectors, Map<String, List<Double>> queryVectors) {}

    record Loaded(EmbeddingFile file, String vectorFileSha256) {}

    record Scored(String segmentId, double score) {}

    private final EmbeddingFile file;
    private final String vectorFileSha256;
    private final ContextTokenBudget budget;
    private final Map<String, double[]> documents;
    private final Map<String, double[]> queries;

    EmbeddingContextSelector(Loaded loaded, ContextTokenBudget budget) {
        this.file = validate(loaded.file());
        this.vectorFileSha256 = required(loaded.vectorFileSha256(), "vectorFileSha256");
        this.budget = Objects.requireNonNull(budget);
        this.documents = vectors(file.documentVectors(), file.vectorDimension());
        this.queries = vectors(file.queryVectors(), file.vectorDimension());
    }

    static Loaded load(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            return new Loaded(JsonMapper.builder().build().readValue(bytes, EmbeddingFile.class), sha256(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** SHA-256 over id NUL text NUL for every segment in transcript order; mirrored by the Python embedder. */
    static String transcriptSha256(ReviewInput transcript) {
        var joined = new StringBuilder();
        for (var s : transcript.segments()) joined.append(s.id()).append('\0').append(s.text()).append('\0');
        return sha256(joined.toString().getBytes(StandardCharsets.UTF_8));
    }

    String id() {
        return "A2-T:" + POLICY + ":model=" + file.modelId() + "@" + file.revision() + ":input=" + file.embeddingInputVersion()
                + ":vectors=" + vectorFileSha256 + ":" + ContextTokenBudget.COUNTER_ID + ":B=" + budget.budget();
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

    /** Every non-target segment, most similar first. */
    List<Scored> ranking(ReviewInput.Segment target, ReviewInput transcript) {
        if (!transcriptSha256(transcript).equals(file.transcriptSha256()))
            throw new IllegalArgumentException("Vectors were frozen for another transcript");
        var order = transcript.segments();
        if (!order.contains(target)) throw new IllegalArgumentException("Target outside transcript: " + target.id());
        double[] query = queries.get(target.id());
        if (query == null) throw new IllegalArgumentException("No query vector for target: " + target.id());
        Map<String, ReviewInput.Segment> byId = new HashMap<>();
        List<Scored> scored = new ArrayList<>();
        for (var s : order) {
            byId.put(s.id(), s);
            if (s.id().equals(target.id())) continue;
            double[] document = documents.get(s.id());
            if (document == null) throw new IllegalArgumentException("No document vector for segment: " + s.id());
            scored.add(new Scored(s.id(), dot(query, document)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingLong(s -> distance(target, byId.get(s.segmentId())))
                .thenComparingInt(s -> order.indexOf(byId.get(s.segmentId()))));
        return List.copyOf(scored);
    }

    private static EmbeddingFile validate(EmbeddingFile f) {
        Objects.requireNonNull(f);
        if (!FORMAT.equals(f.format())) throw new IllegalArgumentException("Unsupported embedding file format: " + f.format());
        required(f.modelId(), "modelId");
        required(f.revision(), "revision");
        required(f.modelArtifactsHash(), "modelArtifactsHash");
        required(f.transcriptSha256(), "transcriptSha256");
        required(f.embeddingInputVersion(), "embeddingInputVersion");
        if (f.vectorDimension() < 1) throw new IllegalArgumentException("vectorDimension must be positive");
        if (!DTYPE.equals(f.vectorDtype())) throw new IllegalArgumentException("vectorDtype must be " + DTYPE);
        if (!f.normalized()) throw new IllegalArgumentException("Vectors must be normalized for cosine");
        if (f.generationLibraryVersions() == null || f.generationLibraryVersions().isEmpty())
            throw new IllegalArgumentException("generationLibraryVersions required");
        if (f.documentVectors() == null || f.queryVectors() == null) throw new IllegalArgumentException("Vectors required");
        return f;
    }

    private static Map<String, double[]> vectors(Map<String, List<Double>> raw, int dimension) {
        Map<String, double[]> result = new HashMap<>();
        raw.forEach((id, values) -> {
            if (values == null || values.size() != dimension) throw new IllegalArgumentException("Wrong vector dimension: " + id);
            double[] v = values.stream().mapToDouble(d -> {
                if (d == null || !Double.isFinite(d)) throw new IllegalArgumentException("Non-finite vector value: " + id);
                return d;
            }).toArray();
            if (Math.abs(Math.sqrt(dot(v, v)) - 1) > NORM_TOLERANCE) throw new IllegalArgumentException("Vector not normalized: " + id);
            result.put(id, v);
        });
        return Map.copyOf(result);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value;
    }

    private static double dot(double[] a, double[] b) {
        double total = 0;
        for (int i = 0; i < a.length; i++) total += a[i] * b[i];
        return total;
    }

    private static long distance(ReviewInput.Segment target, ReviewInput.Segment other) {
        return other.startMs() >= target.endMs() ? other.startMs() - target.endMs()
                : Math.max(0, target.startMs() - other.endMs());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
