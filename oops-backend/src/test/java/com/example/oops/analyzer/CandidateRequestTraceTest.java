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
        var entry = store.find(1L).orElseThrow().requests().get(0);
        assertThat(entry.input()).contains("candidate-1", "candidate-2", "hypothesisNotEvidence", "proposedEvidence", "segmentIds");
        assertThat(entry.system()).isEqualTo(VERIFICATION_PROMPT);
        assertThat(entry.parsedResponse()).contains("PASS");
        verify(f.client, times(1)).completeAsJson(eq(entry.system()), eq(entry.input()), eq(VerificationResult.class));
    }
}
