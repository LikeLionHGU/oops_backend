package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline contract regressions, not a model accuracy benchmark. */
class TargetGroundingRepairTest {
    @Test void missingProposalAnchorIsDiagnosedWithoutInventingLocationOrCrashing() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), new Proposal(null, "TARGET_TREATMENT", "문맥 검토 가설",
                List.of(new Quote("stt-index-0", "그 집"))));
        var r = run(f.client, f.context("그 집 메뉴 이야기"), 24);
        assertThat(r.findings()).isEmpty();
        assertThat(r.diagnostics().candidatePipeline().candidates()).singleElement().satisfies(t -> {
            assertThat(t.anchorId()).isNull();
            assertThat(t.contextStartMs()).isNull();
            assertThat(t.failureCode()).isEqualTo("PROPOSAL_ANCHOR_NOT_REVIEWED");
        });
    }
    LlmDecision missingTargetEvidence(LlmDecision d) {
        return new LlmDecision(d.segmentId(), d.decision(), d.evidenceText(), d.reason(), d.category(),
                d.target(), d.score(), d.context(), d.reading(), d.missingInformation(),
                d.evidence().stream().filter(e -> !"TARGET".equals(e.role())).toList(),
                d.targetType(), d.targetRelation(), d.targetReason(), d.alternativeInterpretation(), d.targetMention());
    }
    @Test void targetFailureCanRecoverToPassWithoutCopyingOldAssessment() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1",
                        missingTargetEvidence(f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집")))))))
                .thenAnswer(invocation -> {
                    assertThat((String) invocation.getArgument(0)).contains(TARGET_REPAIR_PROMPT).doesNotContain(ID_REPAIR_PROMPT);
                    assertThat((String) invocation.getArgument(1)).contains("TARGET_EVIDENCE_REQUIRED")
                            .doesNotContain("alternativeInterpretation", "MOCKERY");
                    return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",
                            f.assessment("stt-index-0", "PASS", "그 집")))));
                });
        var r = run(f.client, f.context("그 집 메뉴는 내 취향이 아니야"), 24);
        assertThat(r.findings()).isEmpty();
        assertThat(r.diagnostics().candidatePipeline().repairs()).singleElement().satisfies(t -> {
            assertThat(t.initialFailureCode()).isEqualTo("TARGET_EVIDENCE_REQUIRED");
            assertThat(t.state()).isEqualTo("RECOVERED");
        });
        assertThat(r.diagnostics().candidatePipeline().candidates()).singleElement()
                .extracting(CandidateReviewDiagnostics.Trace::state).isEqualTo("PASS");
    }
    @Test void validTargetRecoveryCanPublishButRepeatedFailureNeverDoes() {
        for (boolean recover : List.of(true, false)) {
            var f = new CandidateReviewEngineTest();
            f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
            var good = f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집");
            var bad = missingTargetEvidence(good);
            when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                    .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1", bad)))),
                            Optional.of(new VerificationResult(List.of(new Verification("candidate-1", recover ? good : bad)))));
            var r = run(f.client, f.context("그 집 이용자는 수준이 낮아"), 24);
            assertThat(r.findings()).hasSize(recover ? 1 : 0);
            assertThat(r.diagnostics().candidatePipeline().repairCalls()).isOne();
            assertThat(r.diagnostics().candidatePipeline().verificationFailed()).isEqualTo(recover ? 0 : 1);
            verify(f.client, times(2)).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
        }
    }
    @Test void repairCannotPublishFabricatedRawQuote() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        var bad = missingTargetEvidence(f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집"));
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1", bad)))),
                        Optional.of(new VerificationResult(List.of(new Verification("candidate-1",
                                f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집은 허구 문장"))))));
        var r = run(f.client, f.context("그 집 이용자는 수준이 낮아"), 24);
        assertThat(r.findings()).isEmpty();
        assertThat(r.diagnostics().candidatePipeline().repairs()).singleElement()
                .extracting(CandidateReviewDiagnostics.RepairTrace::finalFailureCode).isEqualTo("DECISION_QUOTE_NOT_IN_RAW");
    }
    @Test void invalidLateProposalKeepsLocationAndQuoteFailureRatherThanDisappearing() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0", "stt-index-1"), f.proposal("stt-index-1", "전사에 없는 문장"));
        var r = run(f.client, f.context("그 집 메뉴가 다양하지 않네", "오늘은 정해진 메뉴만 판매합니다"), 24);
        assertThat(r.findings()).isEmpty();
        assertThat(r.diagnostics().candidatePipeline().candidates()).singleElement().satisfies(t -> {
            assertThat(t.anchorId()).isEqualTo("stt-index-1");
            assertThat(t.contextStartMs()).isEqualTo(2000);
            assertThat(t.failureCode()).isEqualTo("PROPOSAL_QUOTE_NOT_IN_RAW");
            assertThat(t.state()).isEqualTo("INVALID_PROPOSAL");
        });
        verify(f.client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void normalReviewAndSalesConditionsDoNotCreateRuleBasedCandidates() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0", "stt-index-1", "stt-index-2"));
        var r = run(f.client, f.context("음식이 별로고 돈이 아까웠다", "메뉴에 특색이 없다", "오늘은 정해진 메뉴만 판매합니다"), 24);
        assertThat(r.findings()).isEmpty();
        verify(f.client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
        assertThat(DISCOVERY_PROMPT).contains("평가와 뒤따르는 설명", "메뉴 불만·판매 조건 안내 자체는 후보 근거가 아니다")
                .doesNotContain("피식대학", "롯데리아", "메뉴가 의미가 없어");
    }
    @Test void visualRepairUsesImagesWithoutFallingBackToText() {
        var v = new VisualContextReviewerTest();
        var f = v.fixture;
        var proposals = List.of(f.proposal("stt-index-0", "그 집"),
                new Proposal("stt-index-0", "TARGET_TREATMENT", "독립적인 두 번째 비판 가설", List.of(new Quote("stt-index-0", "그 집"))));
        when(f.client.completeAsJson(anyString(), anyString(), eq(Discovery.class)))
                .thenReturn(Optional.of(new Discovery(List.of("stt-index-0"), List.of(), List.of(), false, proposals)));
        v.frames();
        when(f.client.completeWithImagesAsJson(anyString(), anyString(), anyList(), eq(VisualContextReviewer.Response.class)))
                .thenAnswer(invocation -> {
                    var tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
                    var good = f.assessment("stt-index-0", "PASS", "그 집");
                    boolean repair = tree.has("repair");
                    if (repair) assertThat((String) invocation.getArgument(0)).contains(TARGET_REPAIR_PROMPT);
                    return Optional.of(new VisualContextReviewer.Response(tree.get("candidateId").asText(),
                            repair ? good : missingTargetEvidence(f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집")),
                            List.of(new VisualContextReviewer.Observation("scene-0", "상점이 보인다")), "원문과 제공한 화면을 대조한다"));
                });
        var r = run(f.client, v.context(), 24, v.reviewer);
        assertThat(r.findings()).isEmpty();
        assertThat(r.diagnostics().candidatePipeline().repairCalls()).isEqualTo(2);
        assertThat(r.diagnostics().candidatePipeline().verificationCalls()).isEqualTo(4);
        assertThat(r.diagnostics().candidatePipeline().repairs()).allSatisfy(t -> assertThat(t.state()).isEqualTo("RECOVERED"));
        verify(f.client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void visualCannotExceedBudgetAlreadyConsumedByTextFailures() {
        var v = new VisualContextReviewerTest(); var f = v.fixture;
        var first = f.proposal("stt-index-0", "그 집");
        var second = new Proposal(first.anchorId(), first.axis(), "다른 텍스트 비판 가설", first.evidence());
        var visual = new Proposal(first.anchorId(), first.axis(), "장면 비교가 필요한 가설", first.evidence());
        when(f.client.completeAsJson(anyString(), anyString(), eq(Discovery.class)))
                .thenReturn(Optional.of(new Discovery(List.of("stt-index-0"), List.of(first, second), List.of(), false, List.of(visual))));
        var bad = missingTargetEvidence(f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집"));
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(invocation -> {
            var tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            List<Verification> results = new ArrayList<>();
            for (var c : tree.get("candidates")) results.add(new Verification(c.get("candidateId").asText(), bad));
            return Optional.of(new VerificationResult(results));
        });
        v.frames();
        when(f.client.completeWithImagesAsJson(anyString(), anyString(), anyList(), eq(VisualContextReviewer.Response.class)))
                .thenReturn(Optional.of(new VisualContextReviewer.Response("candidate-3", bad,
                        List.of(new VisualContextReviewer.Observation("scene-0", "상점이 보인다")), "실제 화면과 발언 대조")));
        var r = run(f.client, v.context(), 24, v.reviewer);
        var d = r.diagnostics().candidatePipeline();
        assertThat(r.findings()).isEmpty();
        assertThat(d.repairCalls()).isEqualTo(2);
        assertThat(d.verificationCalls()).isEqualTo(4);
        assertThat(d.verificationFailed()).isEqualTo(3);
        assertThat(d.repairs()).hasSize(3).last().extracting(CandidateReviewDiagnostics.RepairTrace::state)
                .isEqualTo("NOT_ATTEMPTED_BUDGET");
        verify(f.client, times(1)).completeWithImagesAsJson(anyString(), anyString(), anyList(), any());
    }
}
