package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Default REVIEW_CASES_LOCATION (classpath:review-cases.json) ships no cases, so a production
 * reproduction (A0-prod-engine) with defaults sends the same requests as path B disabled.
 * An overridden location must be recorded with the run; this test does not cover one.
 */
class DefaultCaseLibraryIsolationTest {
    private static final RunOptions OPTIONS = new RunOptions(24, 8, false, new VerificationFeatures(false, false, false), false);

    private static ReviewCaseLibrary defaultLibrary() {
        var library = new ReviewCaseLibrary(new DefaultResourceLoader(), true, "classpath:review-cases.json", 2, 2400, "");
        library.load();
        return library;
    }

    private static List<String> discoveryRequest(ReviewCaseLibrary cases, List<ReviewCaseLibrary.Trace> traces) {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"));
        var result = CandidateReviewEngine.run(f.client, f.context("그 집은 정말 별로다"),
                new RunServices(null, cases, null, null, null), OPTIONS);
        traces.addAll(result.diagnostics().candidatePipeline().caseRetrieval());
        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client).completeAsJson(system.capture(), input.capture(), eq(Discovery.class));
        return List.of(system.getValue(), input.getValue());
    }

    @Test void defaultCaseArchiveIsEmptyAndLeavesRequestsIdenticalToPathBDisabled() {
        List<ReviewCaseLibrary.Trace> withDefault = new ArrayList<>();
        var defaultRequest = discoveryRequest(defaultLibrary(), withDefault);
        var disabledRequest = discoveryRequest(null, new ArrayList<>());
        assertThat(defaultRequest).isEqualTo(disabledRequest);
        assertThat(defaultRequest.get(1)).doesNotContain("referenceCases");
        assertThat(withDefault).singleElement().satisfies(t -> {
            assertThat(t.archiveVersion()).isEqualTo("2026-10-09-empty-1");
            assertThat(t.eligibleCases()).isZero();
            assertThat(t.caseIds()).isEmpty();
        });
    }
}
