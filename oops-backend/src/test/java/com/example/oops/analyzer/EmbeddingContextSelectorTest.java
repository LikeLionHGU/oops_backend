package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** A2-T: frozen query/document vectors, cosine to the target's query vector, greedy under B. Shape only. */
class EmbeddingContextSelectorTest {
    private static final int LARGE = 1_000_000;
    private static final double R = Math.sqrt(0.5);

    private static ReviewInput.Segment speech(int index, long startMs, String text) {
        return new ReviewInput.Segment("stt-index-" + index, TimelineEventType.SPEECH, startMs, startMs + 1500, text, null);
    }

    private final ReviewInput transcript = new ReviewInput(List.of(
            speech(0, 0, "근처 잡담"),
            speech(1, 2_000, "대상 발언"),
            speech(2, 4_000, "다른 근처 잡담"),
            speech(3, 400_000, "멀리 있는 관련 발언"),
            speech(4, 500_000, "조금 관련된 발언")));

    private Map<String, List<Double>> vectors() {
        return Map.of(
                "stt-index-0", List.of(0.0, 1.0, 0.0),
                "stt-index-1", List.of(1.0, 0.0, 0.0),
                "stt-index-2", List.of(0.0, 0.0, 1.0),
                "stt-index-3", List.of(1.0, 0.0, 0.0),
                "stt-index-4", List.of(R, R, 0.0));
    }

    private EmbeddingContextSelector.EmbeddingFile file(ReviewInput t, Map<String, List<Double>> documents,
                                                          Map<String, List<Double>> queries) {
        return new EmbeddingContextSelector.EmbeddingFile(EmbeddingContextSelector.FORMAT, "BAAI/bge-m3", "rev123",
                "artifacts-sha", EmbeddingContextSelector.transcriptSha256(t), "target-raw-text-v1", 3, "float32", true,
                Map.of("python", "3.13.9", "transformers", "x"), documents, queries);
    }

    private EmbeddingContextSelector.EmbeddingFile file(ReviewInput t, Map<String, List<Double>> documents) {
        return file(t, documents, Map.of("stt-index-1", List.of(1.0, 0.0, 0.0)));
    }

    private EmbeddingContextSelector selector(EmbeddingContextSelector.EmbeddingFile f, int budget) {
        return new EmbeddingContextSelector(new EmbeddingContextSelector.Loaded(f, "file-sha"), new ContextTokenBudget(budget));
    }

    private EmbeddingContextSelector selector(int budget) {
        return selector(file(transcript, vectors()), budget);
    }

    private int tokens(ReviewInput.Segment target, int... context) {
        List<ReviewInput.Segment> raw = new ArrayList<>(List.of(target));
        Arrays.stream(context).forEach(i -> raw.add(transcript.segments().get(i)));
        return new ContextTokenBudget(LARGE).measure(new ExperimentalBatchRendering(transcript, Set.of(target.id())), raw).contextTokens();
    }

    @Test void ranksEverySegmentByCosineThenTimeDistanceThenTranscriptOrder() {
        var target = transcript.segments().get(1);
        assertThat(selector(LARGE).ranking(target, transcript)).extracting(EmbeddingContextSelector.Scored::segmentId)
                .containsExactly("stt-index-3", "stt-index-4", "stt-index-0", "stt-index-2");
    }

    @Test void picksTheDistantSimilarSegmentOverNearbyChatterUnderBudget() {
        var target = transcript.segments().get(1);
        var selection = selector(tokens(target, 3)).select(target, transcript);
        assertThat(selection.contextSegmentIds()).containsExactly("stt-index-3");
        var all = selector(LARGE).select(target, transcript);
        assertThat(all.contextSegmentIds()).containsExactly("stt-index-0", "stt-index-2", "stt-index-3", "stt-index-4");
        assertThatCode(() -> all.toBatch(target, transcript)).doesNotThrowAnyException();
        assertThat(selector(1).select(target, transcript).contextSegmentIds()).isEmpty();
    }

    @Test void rejectsVectorsFrozenForAnotherTranscript() {
        var other = new ReviewInput(List.of(speech(0, 0, "근처 잡담"), speech(1, 2_000, "바뀐 발언"),
                speech(2, 4_000, "다른 근처 잡담"), speech(3, 400_000, "멀리 있는 관련 발언"), speech(4, 500_000, "조금 관련된 발언")));
        assertThatThrownBy(() -> selector(LARGE).select(other.segments().get(1), other))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transcript");
    }

    @Test void usesTheFrozenQueryVectorNotTheTargetsDocumentVector() {
        var target = transcript.segments().get(1);
        var queryTowardChatter = file(transcript, vectors(), Map.of("stt-index-1", List.of(0.0, 1.0, 0.0)));
        assertThat(selector(queryTowardChatter, LARGE).ranking(target, transcript)).extracting(EmbeddingContextSelector.Scored::segmentId)
                .startsWith("stt-index-0", "stt-index-4");
    }

    @Test void rejectsMissingVectorsForTheTranscriptOrTheTarget() {
        var missing = new HashMap<>(vectors());
        missing.remove("stt-index-4");
        assertThatThrownBy(() -> selector(file(transcript, missing), LARGE).select(transcript.segments().get(1), transcript))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> selector(LARGE).select(transcript.segments().get(0), transcript))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("query");
    }

    @Test void rejectsFilesThatBreakTheReproducibilityContract() {
        var wrongDimension = new HashMap<>(vectors());
        wrongDimension.put("stt-index-4", List.of(1.0, 0.0));
        assertThatThrownBy(() -> selector(file(transcript, wrongDimension), LARGE)).isInstanceOf(IllegalArgumentException.class);
        var unnormalized = new HashMap<>(vectors());
        unnormalized.put("stt-index-4", List.of(2.0, 0.0, 0.0));
        assertThatThrownBy(() -> selector(file(transcript, unnormalized), LARGE)).isInstanceOf(IllegalArgumentException.class);
        var good = file(transcript, vectors());
        List<EmbeddingContextSelector.EmbeddingFile> broken = List.of(
                new EmbeddingContextSelector.EmbeddingFile("other", good.modelId(), good.revision(), good.modelArtifactsHash(),
                        good.transcriptSha256(), good.embeddingInputVersion(), 3, "float32", true, good.generationLibraryVersions(),
                        good.documentVectors(), good.queryVectors()),
                new EmbeddingContextSelector.EmbeddingFile(good.format(), good.modelId(), " ", good.modelArtifactsHash(),
                        good.transcriptSha256(), good.embeddingInputVersion(), 3, "float32", true, good.generationLibraryVersions(),
                        good.documentVectors(), good.queryVectors()),
                new EmbeddingContextSelector.EmbeddingFile(good.format(), good.modelId(), good.revision(), good.modelArtifactsHash(),
                        good.transcriptSha256(), good.embeddingInputVersion(), 3, "float16", true, good.generationLibraryVersions(),
                        good.documentVectors(), good.queryVectors()),
                new EmbeddingContextSelector.EmbeddingFile(good.format(), good.modelId(), good.revision(), good.modelArtifactsHash(),
                        good.transcriptSha256(), good.embeddingInputVersion(), 3, "float32", false, good.generationLibraryVersions(),
                        good.documentVectors(), good.queryVectors()),
                new EmbeddingContextSelector.EmbeddingFile(good.format(), good.modelId(), good.revision(), good.modelArtifactsHash(),
                        good.transcriptSha256(), good.embeddingInputVersion(), 3, "float32", true, Map.of(),
                        good.documentVectors(), good.queryVectors()));
        assertThat(broken).allSatisfy(f -> assertThatThrownBy(() -> selector(f, LARGE)).isInstanceOf(IllegalArgumentException.class));
    }

    @Test void loadsTheFrozenJsonFileAndRecordsItsDigestInTheId(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("embeddings.json");
        JsonMapper.builder().build().writeValue(path.toFile(), file(transcript, vectors()));
        var file = EmbeddingContextSelector.load(path);
        String fileSha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.nio.file.Files.readAllBytes(path)));
        assertThat(file.vectorFileSha256()).isEqualTo(fileSha);
        var loaded = new EmbeddingContextSelector(file, new ContextTokenBudget(64));
        assertThat(loaded.id()).isEqualTo("A2-T:dense-cosine-v1:model=BAAI/bge-m3@rev123:input=target-raw-text-v1:vectors="
                + fileSha + ":" + ContextTokenBudget.COUNTER_ID + ":B=64");
        assertThat(loaded.select(transcript.segments().get(1), transcript).selectorId()).isEqualTo(loaded.id());
    }

    /** The Python embedder must produce the same digest; tools/tests/test_embed_segments.py pins this value. */
    @Test void transcriptDigestIsStableAcrossLanguages() {
        var t = new ReviewInput(List.of(speech(0, 0, "안녕"), speech(1, 2_000, "hello")));
        assertThat(EmbeddingContextSelector.transcriptSha256(t))
                .isEqualTo("a9a6035dc95ffece838e2b11be3d705199216fbcbb47f4121a8e8bad264278c8");
    }
}
