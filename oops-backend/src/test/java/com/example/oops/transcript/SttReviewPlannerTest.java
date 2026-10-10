package com.example.oops.transcript;

import com.example.oops.analyzer.*;
import com.example.oops.domain.*;
import com.example.oops.service.ReviewDiagnosticsStore;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class SttReviewPlannerTest {
    final SttReviewPlanner planner = new SttReviewPlanner();
    ReviewInput input(int count) {
        var lines = new ArrayList<ReviewInput.Segment>();
        for (int i = 0; i < count; i++) lines.add(new ReviewInput.Segment("s" + i, TimelineEventType.SPEECH,
                i * 4000L, i * 4000L + 2000, "잘못 들렸을 수 있는 원문 " + i, null));
        return new ReviewInput(lines);
    }
    ReviewDiagnosticsStore.Snapshot snapshot(ReviewInput input, String missing, boolean related, ReviewEvaluation.Decision decision) {
        var segments = input.segments().stream().map(s -> new ReviewDiagnostics.SegmentTrace(s.id(), s.type(), s.startMs(), s.endMs(),
                ReviewDiagnostics.State.UNCERTAIN, List.of(new ReviewDiagnostics.DecisionTrace(decision, "가상 진단", null, null,
                List.of(missing), List.of(), false)), List.of(), 0, false)).toList();
        var traces = related ? List.of(new CandidateReviewDiagnostics.Trace("c1", "s0", "TARGET_TREATMENT", "UNCERTAIN",
                0L, 40000L, input.segments().stream().map(ReviewInput.Segment::id).toList(), true, false, null))
                : List.<CandidateReviewDiagnostics.Trace>of();
        var pipeline = new CandidateReviewDiagnostics("test", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false,
                traces, List.of(), List.of(), null, List.of(), 0, List.of(), 0);
        var diagnostics = new ReviewDiagnostics("speech-review", AnalyzerStatus.PARTIAL, segments.size(), false, Map.of(), segments, null, pipeline);
        return new ReviewDiagnosticsStore.Snapshot(1L, 1L, Instant.now(), "test", List.of(diagnostics), "test", Map.of());
    }
    @Test void preservesRawAndTimeWithoutExecutingRecognition() {
        var input = input(1);
        var out = planner.plan(input, 10000, snapshot(input, "전사 오류로 단어 의미 불명", true, ReviewEvaluation.Decision.UNCERTAIN));
        assertThat(out.executionEnabled()).isFalse(); assertThat(out.additionalCalls()).isZero();
        assertThat(out.items()).singleElement().satisfies(i -> {
            assertThat(i.rawText()).isEqualTo(input.segments().get(0).text());
            assertThat(i.startMs()).isZero(); assertThat(i.endMs()).isEqualTo(2000);
            assertThat(i.clipStartMs()).isZero(); assertThat(i.clipEndMs()).isEqualTo(3000);
            assertThat(i.candidateIds()).containsExactly("c1");
        });
    }
    @Test void tasteContextMissingAndPassTyposAreNotAutoSelected() {
        var input = input(1);
        assertThat(planner.plan(input, 10000, snapshot(input, "대상의 신원과 앞 문맥 부족", true, ReviewEvaluation.Decision.UNCERTAIN)).items()).isEmpty();
        assertThat(planner.plan(input, 10000, snapshot(input, "전사 오류", true, ReviewEvaluation.Decision.PASS)).items()).isEmpty();
    }
    @Test void unresolvedSpeechWithoutCandidateLinkIsDeferred() {
        var input = input(1);
        assertThat(planner.plan(input, 10000, snapshot(input, "음성 재확인 필요", false, ReviewEvaluation.Decision.UNCERTAIN)).items())
                .singleElement().satisfies(i -> {
                    assertThat(i.state()).isEqualTo("DEFERRED_NO_CANDIDATE_LINK"); assertThat(i.clipStartMs()).isNull();
                });
    }
    @Test void selectionBudgetAndVideoBoundariesAreRespected() {
        var input = input(5);
        var out = planner.plan(input, 18000, snapshot(input, "오인식 여부 확인", true, ReviewEvaluation.Decision.UNCERTAIN));
        assertThat(out.items().stream().filter(i -> i.clipStartMs() != null)).hasSize(3);
        assertThat(out.plannedMs()).isLessThanOrEqualTo(20000);
        assertThat(out.items()).allSatisfy(i -> { if (i.clipEndMs() != null) assertThat(i.clipEndMs()).isLessThanOrEqualTo(18000); });
    }
    @Test void expiredDiagnosticIsUnavailableNotNoErrors() {
        assertThat(planner.plan(input(1), 10000, null).state()).isEqualTo("DIAGNOSTICS_UNAVAILABLE");
    }
    @Test void differentSourceSnapshotCannotBeUsedForAudioPlan() {
        var input = input(1);
        var other = new ReviewInput(List.of(new ReviewInput.Segment("s0", TimelineEventType.SPEECH, 1, 2000, "다른 원문", null)));
        assertThat(planner.plan(other, 10000, snapshot(input, "전사 오류", true, ReviewEvaluation.Decision.UNCERTAIN)).state())
                .isEqualTo("SOURCE_SNAPSHOT_MISMATCH");
    }
}
