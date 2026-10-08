package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.stream.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fixture plumbing and simulated response contracts only. Never a real-model accuracy measurement. */
class ContextualComparisonReviewTest {
    record Sample(String id, String domain, String decision, String category, String target, String targetType,
                  List<String> lines, String reason, String alternative, String missing) {}
    record Dataset(int version, String status, List<Sample> cases) {}

    static Dataset dataset() {
        try (var input = ContextualComparisonReviewTest.class.getResourceAsStream("/evals/contextual-comparison.json")) {
            return JsonMapper.builder().build().readValue(Objects.requireNonNull(input), Dataset.class);
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    static Stream<Sample> samples() { return dataset().cases().stream(); }
    private AnalysisContext context(Sample s) {
        var video = Video.builder().filename("synthetic.mp4").build();
        return new AnalysisContext(video, ContentGenre.GENERAL, IntStream.range(0, s.lines().size())
                .mapToObj(i -> new TranscriptSegment(video, i * 2000, i * 2000 + 1000, s.lines().get(i))).toList(), null);
    }
    private LlmDecision pass(String text, int index) {
        return new LlmDecision("stt-index-" + index, "PASS", text,
                "이 구간은 연결 평가의 상황 설명이며 개별 경고와 묶음 평가는 별도로 기록합니다.",
                null, null, null, null, null, List.of());
    }
    private LlmDecision assessment(Sample s, boolean inventedTarget) {
        var evidence = new ArrayList<>(List.of(
                new LlmEvidence("stt-index-1", s.lines().get(1), "PRIMARY"),
                new LlmEvidence("stt-index-0", s.lines().get(0), "CONTEXT")));
        if (s.target() != null) evidence.add(new LlmEvidence("stt-index-0",
                inventedTarget ? "원문에 없는 대상" : s.target(), "TARGET"));
        return new LlmDecision("stt-index-1", s.decision(), s.lines().get(1), s.reason(),
                s.category(), s.target(), s.category() == null ? null : 0.5, null, null,
                s.missing() == null ? List.of() : List.of(s.missing()), evidence,
                s.targetType(), s.target() == null ? null : "CONTEXTUAL",
                s.target() == null ? null : "첫 발언이 지칭한 대상을 뒤의 대체 행동 평가에 연결합니다.", s.alternative());
    }
    private Result run(Sample s, boolean inventedTarget) {
        var c = context(s);
        var client = mock(OpenAiClient.class);
        var unit = DialogueReview.plan(TextReviewBatchPlanner.plan(c.reviewInput(), TimelineEventType.SPEECH, 3).get(0)).units().get(0);
        var relation = "UNCERTAIN".equals(s.decision()) ? "INSUFFICIENT_CONTEXT"
                : "REVIEW_REQUIRED".equals(s.decision()) ? "SAME_TARGET_CONNECTED" : "NO_CONNECTED_EVALUATION";
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenReturn(Optional.of(new LlmResult(
                List.of(pass(s.lines().get(0), 0), pass(s.lines().get(1), 1)),
                List.of(new DialogueReview.LlmDecision(unit.unitId(), relation, assessment(s, inventedTarget))))));
        var analyzer = new SpeechReviewAnalyzer(client);
        analyzer.analyze(c);
        verify(client, times(2)).completeAsJson(argThat(prompt -> prompt.contains(ContextualComparisonPolicy.PROMPT)),
                anyString(), eq(LlmResult.class)); // Independent segment/unit calls; no extra comparison judge.
        return analyzer.consumeReviewResult(c).orElseThrow();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("samples")
    void simulatedCrossDomainDecisionsKeepUnitAndSegmentScopeSeparate(Sample s) {
        var r = run(s, false);
        assertThat(r.diagnostics().segments()).allSatisfy(t -> assertThat(t.state()).isEqualTo(ReviewDiagnostics.State.PASS));
        assertThat(r.diagnostics().dialogue().invalidAttempts()).isZero();
        var trace = r.diagnostics().dialogue().units().get(0);
        assertThat(trace.state().name()).isEqualTo(s.decision());
        if ("REVIEW_REQUIRED".equals(s.decision())) {
            assertThat(r.findings()).singleElement().satisfies(f -> {
                assertThat(f.getTarget()).isEqualTo(s.target());
                assertThat(f.getCategory().name()).isEqualTo(s.category());
                assertThat(f.getStartMs()).isEqualTo(2000);
                assertThat(f.getReason()).contains(s.alternative(), s.lines().get(0));
            });
        } else {
            assertThat(r.findings()).isEmpty();
            if (s.missing() != null) assertThat(trace.missingInformation()).contains(s.missing());
        }
    }

    @Test void inventedTargetStillCannotPassExistingRawQuoteValidation() {
        var s = dataset().cases().get(0);
        var r = run(s, true);
        assertThat(r.findings()).isEmpty();
        assertThat(r.diagnostics().dialogue().invalidAttempts()).isEqualTo(1);
        assertThat(r.diagnostics().dialogue().units().get(0).failure()).isEqualTo("QUOTE_NOT_IN_RAW");
        assertThat(r.diagnostics().toString()).doesNotContain("원문에 없는 대상");
    }

    @Test void developmentFixturesAreExplicitlyNotAHeldOutAccuracyBenchmark() {
        var d = dataset();
        assertThat(d.status()).isEqualTo("synthetic_development_not_holdout");
        assertThat(d.cases()).hasSize(14);
        assertThat(d.cases().stream().map(Sample::id)).doesNotHaveDuplicates();
        assertThat(d.cases().stream().map(Sample::domain).distinct()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(d.cases().stream().map(Sample::decision).distinct()).containsExactlyInAnyOrder("PASS", "REVIEW_REQUIRED", "UNCERTAIN");
        assertThat(ContextualComparisonPolicy.PROMPT).doesNotContain("피식", "영양", "롯데리아", "stt-");
    }
}
