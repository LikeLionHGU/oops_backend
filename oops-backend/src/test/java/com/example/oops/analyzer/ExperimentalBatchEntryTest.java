package com.example.oops.analyzer;

import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Experimental per-target batches: explicit PRIMARY/CONTEXT split, no page re-planning. Not accuracy. */
class ExperimentalBatchEntryTest {
    private static final long EIGHT_MINUTES_MS = 8 * 60_000L;
    private final CandidateReviewEngineTest f = new CandidateReviewEngineTest();

    private AnalysisContext context(long[] starts, String... lines) {
        List<TranscriptSegment> segments = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) segments.add(new TranscriptSegment(f.video, starts[i], starts[i] + 1500, lines[i]));
        return new AnalysisContext(f.video, ContentGenre.GENERAL, segments, List.of());
    }

    private AnalysisContext farContext() {
        return context(new long[]{0, 2_000, 4_000, EIGHT_MINUTES_MS, EIGHT_MINUTES_MS + 2_000},
                "그건 사실이 아니었어", "다른 이야기", "아무 말", "그 집은 정말 별로다", "다음 주제");
    }

    private TextReviewEngine.Result run(AnalysisContext c, ExperimentalBatch batch) {
        return runExperimental(f.client, c, new RunServices(null, null, null, null, null),
                new RunOptions(24, 8, false, new VerificationFeatures(false, false, false), false), batch);
    }

    private List<String> discoveryPrimaryIds() {
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client).completeAsJson(anyString(), captured.capture(), eq(Discovery.class));
        var tree = JsonMapper.builder().build().readTree(captured.getValue());
        List<String> ids = new ArrayList<>();
        tree.get("primaryIds").forEach(n -> ids.add(n.asText()));
        return ids;
    }

    @Test void onlyPrimaryIsJudgedAndContextNeverEntersStateOrDenominator() {
        f.discovery(List.of("stt-index-3"));
        var r = run(farContext(), new ExperimentalBatch(List.of("stt-index-3"), List.of("stt-index-0", "stt-index-1", "stt-index-4")));
        assertThat(discoveryPrimaryIds()).containsExactly("stt-index-3");
        assertThat(r.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(r.unassessedSegmentIds()).isEmpty();
        assertThat(r.diagnostics().segments()).extracting(ReviewDiagnostics.SegmentTrace::segmentId).containsExactly("stt-index-3");
        assertThat(r.diagnostics().segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.NO_CANDIDATE);
    }

    @Test void eightMinuteGapStaysInOneDiscoveryRequest() {
        f.discovery(List.of("stt-index-3"));
        run(farContext(), new ExperimentalBatch(List.of("stt-index-3"), List.of("stt-index-0")));
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client, times(1)).completeAsJson(anyString(), captured.capture(), eq(Discovery.class));
        assertThat(captured.getValue()).contains("그건 사실이 아니었어", "그 집은 정말 별로다");
    }

    @Test void farContextCanBeCitedAndReachesVerificationRaw() {
        var p = new Proposal("stt-index-3", "TARGET_TREATMENT", "멀리 떨어진 앞 발언과 현재 평가의 연결을 확인합니다.",
                List.of(new Quote("stt-index-3", "그 집"), new Quote("stt-index-0", "사실이 아니었어")));
        f.discovery(List.of("stt-index-3"), p);
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(invocation -> {
            var tree = JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            assertThat(tree.get("raw").toString()).contains("그건 사실이 아니었어", "그 집은 정말 별로다");
            return Optional.of(new VerificationResult(List.of(
                    new Verification("candidate-1", f.assessment("stt-index-3", "REVIEW_REQUIRED", "그 집")))));
        });
        var r = run(farContext(), new ExperimentalBatch(List.of("stt-index-3"), List.of("stt-index-0")));
        assertThat(r.diagnostics().candidatePipeline().invalidProposals()).isZero();
        assertThat(r.diagnostics().segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.REVIEW_REQUIRED);
        verify(f.client).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }

    @Test void transcriptSegmentNotSuppliedInBatchCannotBeEvidence() {
        var p = new Proposal("stt-index-3", "TARGET_TREATMENT", "제공되지 않은 구간과 현재 평가의 연결을 확인합니다.",
                List.of(new Quote("stt-index-3", "그 집"), new Quote("stt-index-1", "다른 이야기")));
        f.discovery(List.of("stt-index-3"), p);
        when(f.client.completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class))).thenReturn(Optional.empty());
        var r = run(farContext(), new ExperimentalBatch(List.of("stt-index-3"), List.of("stt-index-0")));
        assertThat(r.diagnostics().candidatePipeline().invalidProposals()).isPositive();
        assertThat(r.diagnostics().segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.NOT_RETURNED);
        verify(f.client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }

    @Test void contextIdInReviewedSegmentsInvalidatesWholeDiscoveryWithoutRetry() {
        f.discovery(List.of("stt-index-3", "stt-index-0"));
        var r = run(farContext(), new ExperimentalBatch(List.of("stt-index-3"), List.of("stt-index-0")));
        verify(f.client, times(1)).completeAsJson(anyString(), anyString(), eq(Discovery.class));
        verify(f.client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
        assertThat(r.diagnostics().segments()).extracting(ReviewDiagnostics.SegmentTrace::segmentId).containsExactly("stt-index-3");
        assertThat(r.diagnostics().segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.NOT_RETURNED);
        assertThat(r.unassessedSegmentIds()).containsExactly("stt-index-3");
    }

    @Test void rejectsMalformedBatchesAndUnsupportedServices() {
        var c = farContext();
        assertThatThrownBy(() -> run(c, new ExperimentalBatch(List.of(), List.of("stt-index-0"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(c, new ExperimentalBatch(List.of("stt-index-3"), List.of("stt-index-3"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(c, new ExperimentalBatch(List.of("stt-index-3"), List.of("missing"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runExperimental(f.client, c, new RunServices(mock(VisualContextReviewer.class), null, null, null, null),
                new RunOptions(24, 8, false, new VerificationFeatures(false, false, false), false),
                new ExperimentalBatch(List.of("stt-index-3"), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runExperimental(f.client, c, new RunServices(null, null, null, null, null),
                new RunOptions(24, 8, false, new VerificationFeatures(false, false, false), true),
                new ExperimentalBatch(List.of("stt-index-3"), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void experimentalRequestsCarryRolesGapMarkersAndContractAcrossDiscoveryAndVerification() {
        var p = new Proposal("stt-index-3", "TARGET_TREATMENT", "멀리 떨어진 앞 발언과 현재 평가의 연결을 확인합니다.",
                List.of(new Quote("stt-index-3", "그 집"), new Quote("stt-index-0", "사실이 아니었어")));
        f.discovery(List.of("stt-index-3"), p);
        f.verification(new Verification("candidate-1", f.assessment("stt-index-3", "PASS", "그 집")));
        var r = run(farContext(), new ExperimentalBatch(List.of("stt-index-3"), List.of("stt-index-0")));
        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client).completeAsJson(system.capture(), input.capture(), eq(Discovery.class));
        assertThat(system.getValue()).contains(ExperimentalBatchRendering.VERSION);
        assertThat(input.getValue()).contains("\"batchRole\":\"JUDGED\"", "\"batchRole\":\"CONTEXT_ONLY\"",
                "\"omittedBefore\"", ExperimentalBatchRendering.VERSION);
        var verifySystem = org.mockito.ArgumentCaptor.forClass(String.class);
        var verifyInput = org.mockito.ArgumentCaptor.forClass(String.class);
        // Includes contract repair when the mocked verdict is rejected: recovery must keep the same rendering.
        verify(f.client, atLeastOnce()).completeAsJson(verifySystem.capture(), verifyInput.capture(), eq(VerificationResult.class));
        assertThat(verifySystem.getAllValues()).allMatch(s -> s.contains(ExperimentalBatchRendering.VERSION));
        assertThat(verifyInput.getAllValues()).allMatch(s -> s.contains("\"batchRole\":\"JUDGED\"") && s.contains("\"omittedBefore\""));
        assertThat(r.diagnostics().candidatePipeline().revision()).contains(ExperimentalBatchRendering.VERSION);
    }

    @Test void productionRunRequestsStayFreeOfExperimentalRendering() {
        f.discovery(List.of("stt-index-0", "stt-index-1", "stt-index-2"));
        CandidateReviewEngine.run(f.client, farContext(), 24);
        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client, atLeastOnce()).completeAsJson(system.capture(), input.capture(), eq(Discovery.class));
        assertThat(system.getAllValues()).noneMatch(s -> s.contains(ExperimentalBatchRendering.VERSION));
        assertThat(input.getAllValues()).noneMatch(s -> s.contains("batchRole") || s.contains("omittedBefore"));
    }
}
