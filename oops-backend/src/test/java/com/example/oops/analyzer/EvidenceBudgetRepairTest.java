package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Quote-count contracts only, not real-model accuracy. */
class EvidenceBudgetRepairTest {
    private LlmDecision withEvidence(LlmDecision d, List<LlmEvidence> evidence) {
        return new LlmDecision(d.segmentId(), d.decision(), d.evidenceText(), d.reason(), d.category(),
                d.target(), d.score(), d.context(), d.reading(), d.missingInformation(), evidence,
                d.targetType(), d.targetRelation(), d.targetReason(), d.alternativeInterpretation(), d.targetMention());
    }
    private List<LlmEvidence> overflow(LlmDecision d, int count) {
        var result = new ArrayList<>(d.evidence());
        // Synthetic repeated source fragments exercise count routing, not meaningful evidence selection.
        for (int i = 0; result.size() < count; i++)
            result.add(new LlmEvidence("stt-index-0", "그 집", "CONTEXT"));
        return result;
    }
    @Test void smallActualQuoteOverflowIsNeverAcceptedOrTruncated() {
        var f = new CandidateReviewEngineTest();
        var input = f.context("그 집 이용자는 수준이 낮아");
        var good = f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집");
        var candidate = new Candidate("candidate-1", f.proposal("stt-index-0", "그 집"), input.reviewInput().segments(), false, false);
        var extra = withEvidence(good, overflow(good, 9));
        assertThat(validate(candidate, extra, input.reviewInput()).failureCode()).isEqualTo("EVIDENCE_BUDGET_EXCEEDED");
        assertThat(extra.evidence()).hasSize(9);
        assertThat(validate(candidate, good, input.reviewInput()).failureCode()).isNull();
        assertThat(validate(candidate, withEvidence(good, overflow(good, 13)), input.reviewInput()).failureCode()).isEqualTo("ASSESSMENT_SHAPE");
    }
    @Test void invalidQuoteOrRoleOverflowCannotUseBudgetRepairRoute() {
        var f = new CandidateReviewEngineTest();
        var input = f.context("그 집 이용자는 수준이 낮아");
        var good = f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집");
        var candidate = new Candidate("candidate-1", f.proposal("stt-index-0", "그 집"), input.reviewInput().segments(), false, false);
        for (var bad : Arrays.asList(new LlmEvidence("unknown", "그 집", "CONTEXT"),
                new LlmEvidence("stt-index-0", "원문에 없는 말", "CONTEXT"),
                new LlmEvidence("stt-index-0", "그 집", "UNKNOWN"), null)) {
            var evidence = overflow(good, 9); evidence.set(8, bad);
            assertThat(validate(candidate, withEvidence(good, evidence), input.reviewInput()).failureCode()).isEqualTo("ASSESSMENT_SHAPE");
        }
    }
    @Test void budgetRepairRejudgesOnceAndDoesNotForceWarning() {
        for (String decision : List.of("PASS", "REVIEW_REQUIRED")) {
            var f = new CandidateReviewEngineTest();
            var good = f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집");
            var extra = withEvidence(good, overflow(good, 9));
            f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
            when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1", extra)))))
                .thenAnswer(invocation -> {
                    assertThat((String) invocation.getArgument(0)).contains(EVIDENCE_BUDGET_REPAIR_PROMPT);
                    assertThat((String) invocation.getArgument(1)).contains("EVIDENCE_BUDGET_EXCEEDED");
                    return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",
                            f.assessment("stt-index-0", decision, "그 집")))));
                });
            var result = run(f.client, f.context("그 집 이용자는 수준이 낮아"), 24);
            assertThat(result.diagnostics().candidatePipeline().repairCalls()).isOne();
            assertThat(result.findings()).hasSize("PASS".equals(decision) ? 0 : 1);
        }
    }
    @Test void repeatedOverflowStaysUnverifiedWithoutAnotherRetry() {
        var f = new CandidateReviewEngineTest();
        var good = f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집");
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
            .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1", withEvidence(good, overflow(good, 9)))))));
        var result = run(f.client, f.context("그 집 이용자는 수준이 낮아"), 24);
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isOne();
        assertThat(result.diagnostics().candidatePipeline().verificationFailed()).isOne();
        verify(f.client, times(2)).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void threeOverflowsShareExistingTwoRepairBudget() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0", "stt-index-1", "stt-index-2"),
                f.proposal("stt-index-0", "그 집"), f.proposal("stt-index-1", "그 집"), f.proposal("stt-index-2", "그 집"));
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
            .thenAnswer(invocation -> {
                var request = tools.jackson.databind.json.JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
                var decisions = new ArrayList<Verification>();
                for (var candidate : request.get("candidates")) {
                    var good = f.assessment(candidate.get("anchorId").asText(), "REVIEW_REQUIRED", "그 집");
                    decisions.add(new Verification(candidate.get("candidateId").asText(),
                            request.has("repair") ? good : withEvidence(good, overflow(good, 9))));
                }
                return Optional.of(new VerificationResult(decisions));
            });
        var result = run(f.client, f.context("그 집 이용자는 수준이 낮아", "그 집 생활은 한심해", "그 집 사람들은 뒤떨어졌어"), 24);
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isEqualTo(2);
        assertThat(result.diagnostics().candidatePipeline().verificationFailed()).isOne();
        assertThat(result.findings()).hasSize(2);
    }
    @Test void primaryExamplesDoNotChangeJudgmentOrCreateBenchmarkRules() {
        assertThat(VERIFICATION_PROMPT).contains("판정 예시가 아니다", "의미가 가까워도 CONTEXTUAL", "복사하지 않는다");
        assertThat(VERIFICATION_PROMPT).doesNotContain("롯데리아", "피식대학", "영양");
        assertThat(repairPrompt("EVIDENCE_BUDGET_EXCEEDED")).isEqualTo(EVIDENCE_BUDGET_REPAIR_PROMPT);
    }
}
