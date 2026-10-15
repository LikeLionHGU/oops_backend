package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;

class CandidateWindowDiagnosticsTest {
    private Proposal proposal(String... ids) {
        return new Proposal("anchor","EXPRESSION_CONTENT","test hypothesis",
                java.util.Arrays.stream(ids).map(id -> new Quote(id,"raw")).toList());
    }
    private ReviewUnit window(String... ids) {
        return new ReviewUnit("anchor",List.of(ids),0,30000,false,ReviewUnit.View.CENTRED);
    }
    @Test void detectsUnionMembershipWithoutASinglePermittedWindow() {
        var trace=inspectWindows(proposal("anchor","before","after"),
                List.of(window("before","anchor"),window("anchor","after")));
        assertThat(trace.detail()).isEqualTo("EVIDENCE_SPANS_DIFFERENT_WINDOWS");
        assertThat(trace.evidenceOutsideAllWindows()).isZero();
        assertThat(trace.minimumMissingEvidence()).isEqualTo(1);
        assertThat(trace.matchingWindowCount()).isZero();
    }
    @Test void distinguishesOutsideEvidenceMissingAnchorPermissionAndValidWindow() {
        assertThat(inspectWindows(proposal("anchor","far"),List.of(window("anchor"))).detail())
                .isEqualTo("EVIDENCE_OUTSIDE_ALL_WINDOWS");
        assertThat(inspectWindows(proposal("anchor"),List.of()).detail()).isEqualTo("NO_ANCHOR_WINDOW");
        assertThat(inspectWindows(proposal("anchor","after"),List.of(window("anchor","after"))).detail())
                .isEqualTo("MATCHED");
    }
}
