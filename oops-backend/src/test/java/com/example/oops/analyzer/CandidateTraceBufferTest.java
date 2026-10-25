package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateTraceBufferTest {
    @Test void terminalOutcomesAreNotEvictedByBudgetSkipNoise() {
        var buffer = new CandidateTraceBuffer(4);
        var first = new CandidateReviewDiagnostics.Trace("selected-1", "s1", "TARGET_TREATMENT", "PASS");
        var second = new CandidateReviewDiagnostics.Trace("selected-2", "s2", "EXPRESSION_CONTENT", "REVIEW_REQUIRED");
        buffer.outcome(first);
        buffer.outcome(second);
        for (int index = 0; index < 10; index++) {
            buffer.budgetSkipped(new CandidateReviewDiagnostics.Trace("skipped-" + index, "s" + index,
                    "TARGET_TREATMENT", "BUDGET_SKIPPED"));
        }

        assertThat(buffer.snapshot()).containsExactly(first, second,
                new CandidateReviewDiagnostics.Trace("skipped-0", "s0", "TARGET_TREATMENT", "BUDGET_SKIPPED"),
                new CandidateReviewDiagnostics.Trace("skipped-1", "s1", "TARGET_TREATMENT", "BUDGET_SKIPPED"));
        assertThat(buffer.totalCount()).isEqualTo(12);
        assertThat(buffer.truncated()).isTrue();
    }

    @Test void auxiliaryDiagnosticsFollowOutcomesAndBudgetSkips() {
        var buffer = new CandidateTraceBuffer(4);
        var outcome = new CandidateReviewDiagnostics.Trace("selected", "s1", "TARGET_TREATMENT", "PASS");
        var auxiliary = new CandidateReviewDiagnostics.Trace("proposal", "s2", "TARGET_TREATMENT", "INVALID_PROPOSAL");
        var skipped = new CandidateReviewDiagnostics.Trace("skipped", "s3", "TARGET_TREATMENT", "BUDGET_SKIPPED");
        buffer.budgetSkipped(skipped);
        buffer.auxiliary(auxiliary);
        buffer.outcome(outcome);

        assertThat(buffer.snapshot()).containsExactly(outcome, auxiliary, skipped);
        assertThat(buffer.truncated()).isFalse();
    }
}
