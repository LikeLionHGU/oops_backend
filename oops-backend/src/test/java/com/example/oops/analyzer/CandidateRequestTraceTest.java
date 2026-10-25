package com.example.oops.analyzer;

import com.example.oops.service.ReviewRequestTraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.List;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CandidateRequestTraceTest {
    @Test void retainsActualGroupedCandidatesWithoutAddingCallsOrChangingFindings() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0", "stt-index-1"), f.proposal("stt-index-0", "그 집"), f.proposal("stt-index-1", "그 집"));
        f.verification(new Verification("candidate-1", f.assessment("stt-index-0", "PASS", "그 집")),
                new Verification("candidate-2", f.assessment("stt-index-1", "PASS", "그 집")));
        var env = new MockEnvironment().withProperty("server.address", "127.0.0.1")
                .withProperty("oops.analysis.review-request-trace-token", "x".repeat(32)); env.setActiveProfiles("local");
        var store = new ReviewRequestTraceStore(true, env);
        try (var trace = store.begin(1L)) {
            var result = run(f.client, f.context("그 집 메뉴", "그 집 선택지"), 24, null, null, null, trace);
            assertThat(result.findings()).isEmpty();
            assertThat(result.diagnostics().candidatePipeline().verificationCalls()).isOne();
        }
        var snapshot = store.find(1L).orElseThrow();
        assertThat(snapshot.requests()).extracting(ReviewRequestTraceStore.Entry::stage)
                .contains("CANDIDATE_DISCOVERY", "CANDIDATE_VERIFICATION");
        var entry = snapshot.requests().stream().filter(e -> e.stage().equals("CANDIDATE_VERIFICATION")).findFirst().orElseThrow();
        assertThat(entry.input()).contains("candidate-1", "candidate-2", "hypothesisNotEvidence", "proposedEvidence", "segmentIds");
        assertThat(entry.system()).isEqualTo(VERIFICATION_PROMPT);
        assertThat(entry.parsedResponse()).contains("PASS");
        verify(f.client, times(1)).completeAsJson(eq(entry.system()), eq(entry.input()), eq(VerificationResult.class));
    }
    @Test void tracesDiscoveryRepairAsItsOwnStage() {
        var f = new CandidateReviewEngineTest();
        var empty = new Proposal("stt-index-0", "TARGET_TREATMENT", "후보 근거를 복구해야 하는 가설입니다.", List.of());
        when(f.client.completeAsJson(anyString(), anyString(), eq(Discovery.class)))
                .thenReturn(java.util.Optional.of(new Discovery(List.of("stt-index-0"), List.of(empty), List.of(), false)));
        when(f.client.completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class)))
                .thenReturn(java.util.Optional.of(new ProposalRepairResult(empty)));
        var env = new MockEnvironment().withProperty("server.address", "127.0.0.1")
                .withProperty("oops.analysis.review-request-trace-token", "x".repeat(32)); env.setActiveProfiles("local");
        var store = new ReviewRequestTraceStore(true, env);
        try (var trace = store.begin(2L)) {
            run(f.client, f.context("그 집 음식 이야기", "메뉴 설명"), 24, null, null, null, trace);
        }
        assertThat(store.find(2L).orElseThrow().requests()).extracting(ReviewRequestTraceStore.Entry::stage)
                .contains("CANDIDATE_DISCOVERY", "CANDIDATE_DISCOVERY_REPAIR");
    }
}
