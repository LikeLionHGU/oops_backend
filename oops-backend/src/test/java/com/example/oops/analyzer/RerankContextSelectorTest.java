package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** A3-T: reorder exactly the A2 top-K with frozen cross-encoder scores, greedy under B. Shape only. */
class RerankContextSelectorTest {
    private static final int LARGE = 1_000_000;
    private static final int K = 3;
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

    /** A2 order for stt-index-1 is 3, 4, 0, 2; top-3 is {3, 4, 0}. */
    private final EmbeddingContextSelector a2 = new EmbeddingContextSelector(new EmbeddingContextSelector.Loaded(
            new EmbeddingContextSelector.EmbeddingFile(EmbeddingContextSelector.FORMAT, "BAAI/bge-m3", "rev123", "artifacts",
                    EmbeddingContextSelector.transcriptSha256(transcript), "target-raw-text-v1", 3, "float32", true,
                    Map.of("python", "3.13"),
                    Map.of("stt-index-0", List.of(0.0, 1.0, 0.0), "stt-index-1", List.of(1.0, 0.0, 0.0),
                            "stt-index-2", List.of(0.0, 0.0, 1.0), "stt-index-3", List.of(1.0, 0.0, 0.0),
                            "stt-index-4", List.of(R, R, 0.0)),
                    Map.of("stt-index-1", List.of(1.0, 0.0, 0.0))), "vectors-sha"), new ContextTokenBudget(LARGE));

    private RerankContextSelector.ScoreFile scores(String vectorSha, int topK, Map<String, Map<String, Double>> scores) {
        return new RerankContextSelector.ScoreFile(RerankContextSelector.FORMAT, "BAAI/bge-reranker-v2-m3", "rerank-rev",
                "rerank-artifacts", EmbeddingContextSelector.transcriptSha256(transcript), "target-raw-text-v1", "raw-logit",
                Map.of("python", "3.13"), new RerankContextSelector.CandidateSource(vectorSha, topK), scores);
    }

    private Map<String, Map<String, Double>> goodScores() {
        return Map.of("stt-index-1", Map.of("stt-index-0", 5.0, "stt-index-3", 1.0, "stt-index-4", 3.0));
    }

    private RerankContextSelector selector(RerankContextSelector.ScoreFile file, int budget) {
        return new RerankContextSelector(a2, new RerankContextSelector.Loaded(file, "scores-sha"), K, new ContextTokenBudget(budget));
    }

    private RerankContextSelector selector(int budget) {
        return selector(scores("vectors-sha", K, goodScores()), budget);
    }

    private int tokens(ReviewInput.Segment target, int... context) {
        List<ReviewInput.Segment> raw = new ArrayList<>(List.of(target));
        Arrays.stream(context).forEach(i -> raw.add(transcript.segments().get(i)));
        return new ContextTokenBudget(LARGE).measure(new ExperimentalBatchRendering(transcript, Set.of(target.id())), raw).contextTokens();
    }

    private ReviewInput.Segment target() {
        return transcript.segments().get(1);
    }

    @Test void reordersOnlyTheA2TopKByRerankerScore() {
        assertThat(selector(LARGE).ranking(target(), transcript)).extracting(RerankContextSelector.Scored::segmentId)
                .containsExactly("stt-index-0", "stt-index-4", "stt-index-3");
        var all = selector(LARGE).select(target(), transcript);
        assertThat(all.contextSegmentIds()).containsExactly("stt-index-0", "stt-index-3", "stt-index-4");
        assertThatCode(() -> all.toBatch(target(), transcript)).doesNotThrowAnyException();
    }

    @Test void greedyFollowsRerankedOrderUnderBudget() {
        assertThat(selector(tokens(target(), 0)).select(target(), transcript).contextSegmentIds()).containsExactly("stt-index-0");
        assertThat(selector(1).select(target(), transcript).contextSegmentIds()).isEmpty();
    }

    @Test void tiesGoToTheCloserSegmentThenTranscriptOrder() {
        var tied = scores("vectors-sha", K, Map.of("stt-index-1", Map.of("stt-index-0", 1.0, "stt-index-3", 1.0, "stt-index-4", 1.0)));
        assertThat(selector(tied, LARGE).ranking(target(), transcript)).extracting(RerankContextSelector.Scored::segmentId)
                .containsExactly("stt-index-0", "stt-index-3", "stt-index-4");
    }

    @Test void rejectsScoresThatDoNotCoverExactlyTheA2Candidates() {
        var otherVectors = scores("other-vectors", K, goodScores());
        assertThatThrownBy(() -> selector(otherVectors, LARGE)).isInstanceOf(IllegalArgumentException.class);
        var otherK = scores("vectors-sha", 2, goodScores());
        assertThatThrownBy(() -> selector(otherK, LARGE)).isInstanceOf(IllegalArgumentException.class);
        var wrongSet = scores("vectors-sha", K, Map.of("stt-index-1", Map.of("stt-index-0", 5.0, "stt-index-2", 1.0, "stt-index-4", 3.0)));
        assertThatThrownBy(() -> selector(wrongSet, LARGE).select(target(), transcript)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> selector(LARGE).select(transcript.segments().get(0), transcript))
                .isInstanceOf(IllegalArgumentException.class);
        var nonFinite = scores("vectors-sha", K, Map.of("stt-index-1", Map.of("stt-index-0", Double.NaN, "stt-index-3", 1.0, "stt-index-4", 3.0)));
        assertThatThrownBy(() -> selector(nonFinite, LARGE)).isInstanceOf(IllegalArgumentException.class);
        var good = scores("vectors-sha", K, goodScores());
        var badFormat = new RerankContextSelector.ScoreFile("other", good.modelId(), good.revision(), good.modelArtifactsHash(),
                good.transcriptSha256(), good.rerankInputVersion(), good.scoreType(), good.generationLibraryVersions(),
                good.candidateSource(), good.scores());
        assertThatThrownBy(() -> selector(badFormat, LARGE)).isInstanceOf(IllegalArgumentException.class);
        var otherTranscript = new RerankContextSelector.ScoreFile(good.format(), good.modelId(), good.revision(), good.modelArtifactsHash(),
                "other-transcript", good.rerankInputVersion(), good.scoreType(), good.generationLibraryVersions(),
                good.candidateSource(), good.scores());
        assertThatThrownBy(() -> selector(otherTranscript, LARGE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RerankContextSelector(a2, new RerankContextSelector.Loaded(good, "s"), 0, new ContextTokenBudget(LARGE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void loadsTheFrozenScoreFileAndRecordsBothStagesInTheId(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("scores.json");
        JsonMapper.builder().build().writeValue(path.toFile(), scores("vectors-sha", K, goodScores()));
        var loaded = RerankContextSelector.load(path);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        assertThat(loaded.scoreFileSha256()).isEqualTo(sha);
        var selector = new RerankContextSelector(a2, loaded, K, new ContextTokenBudget(64));
        assertThat(selector.id()).isEqualTo("A3-T:rerank-topk-v1:K=3:retrieval=BAAI/bge-m3@rev123:vectors=vectors-sha"
                + ":reranker=BAAI/bge-reranker-v2-m3@rerank-rev:input=target-raw-text-v1:scores=" + sha
                + ":" + ContextTokenBudget.COUNTER_ID + ":B=64");
    }
}
