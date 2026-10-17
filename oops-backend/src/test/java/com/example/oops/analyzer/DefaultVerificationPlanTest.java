package com.example.oops.analyzer;

import java.util.*;
import org.junit.jupiter.api.Test;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class DefaultVerificationPlanTest {
    DefaultVerificationPlan.Plan plan() throws Exception {
        return DefaultVerificationPlan.plan(new HypothesisRemovalComparisonTest().plan(),"test");
    }
    @Test void preparesOnlyCurrentDefaultInstructionsWithFrozenReferencesAndNoAutomaticExecution() throws Exception {
        var source=new HypothesisRemovalComparisonTest().plan();var p=DefaultVerificationPlan.plan(source,"test");
        assertThat(p.requests()).hasSize(56);
        assertThat(p.freshApprovalRequired()).isTrue();assertThat(p.executionImplemented()).isFalse();
        assertThat(p.independentGold()).isFalse();assertThat(p.reservationEstimatedUsd()).isLessThan(.50);
        for(var r:p.requests()) {
            var original=source.tasks().stream().filter(t -> t.id().equals(r.caseId())).findFirst().orElseThrow();
            assertThat(r.system()).isEqualTo(CandidateReviewEngine.verificationPrompt(0)+"\n"+DefaultVerificationPlan.reference(original.baselineSystem()));
            assertThat(r.system()).doesNotContain("sourceUnderstanding", "# 상황 이해 → 비판 해석 → 정상 해석 대조", "reviewBasis");
            assertThat(r.expectedDecision()).isEqualTo(original.expectedDecision());
            assertThat(r.labelStatus()).isEqualTo(original.labelStatus());
        }
    }
    @Test void changesOnlyHypothesisBetweenArmsAndPreservesRawCandidateAndReferences() throws Exception {
        var p=plan();var source=new HypothesisRemovalComparisonTest().plan();
        for(var original:source.tasks()) for(int repeat=1;repeat<=2;repeat++) {
            int n=repeat;
            var rows=p.requests().stream().filter(r -> r.caseId().equals(original.id()) && r.repeat()==n).toList();
            assertThat(rows.stream().map(DefaultVerificationPlan.Request::arm).toList())
                    .isEqualTo(repeat==1?List.of("KEPT","REMOVED"):List.of("REMOVED","KEPT"));
            var kept=rows.stream().filter(r -> r.arm().equals("KEPT")).findFirst().orElseThrow();
            var removed=rows.stream().filter(r -> r.arm().equals("REMOVED")).findFirst().orElseThrow();
            var old=(tools.jackson.databind.node.ObjectNode)JSON.readTree(original.input());
            old.put("promptRevision",CandidateReviewEngine.REVISION);
            assertThat(JSON.readTree(kept.input())).isEqualTo(old);
            assertThat(removed.system()).isEqualTo(kept.system());
            assertThat(removed.input()).isEqualTo(HypothesisRemovalComparison.input(kept.input()));
            assertThat(JSON.readTree(removed.input()).has("expectedDecision")).isFalse();
        }
        assertThat(p.requests().stream().filter(r -> r.expectedDecision()!=null).count()).isEqualTo(32);
        assertThat(p.requests().stream().filter(r -> "REVIEW_REQUIRED".equals(r.expectedDecision())).count()).isEqualTo(8);
    }
    @Test void defaultValidationRejectsAuxiliaryOutputsRatherThanSilentlyIgnoringThem() throws Exception {
        var r=plan().requests().get(0);
        var t=new UtteranceDirectionTrial.Task(r.caseId(),r.labelStatus(),null,null,null,r.system(),r.system(),r.input());
        var v=new CompactRelationsComparisonTest().response(t,true).verifications().get(0);
        var clean=new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(v.candidateId(),v.assessment())));
        assertThat(DefaultVerificationPlan.assess(r,clean)).containsEntry("accepted",true);
        assertThat(DefaultVerificationPlan.assess(r,new CandidateReviewEngine.VerificationResult(List.of(v))))
                .containsEntry("failureCode","UNEXPECTED_SOURCE_UNDERSTANDING");
        var basis=new CandidateReviewEngine.ReviewBasis("TARGET_TREATMENT","unexpected",List.of());
        assertThat(DefaultVerificationPlan.assess(r,new CandidateReviewEngine.VerificationResult(List.of(
                new CandidateReviewEngine.Verification(v.candidateId(),v.assessment(),basis)))))
                .containsEntry("failureCode","UNEXPECTED_REVIEW_BASIS");
    }
    @Test void rejectsUnknownSourceAndMissingOrAmbiguousReferenceBoundaries() {
        assertThatThrownBy(() -> DefaultVerificationPlan.prepare("{}".getBytes())).hasMessageContaining("hash mismatch");
        assertThatThrownBy(() -> DefaultVerificationPlan.reference("no reference")).hasMessageContaining("boundary");
        String boundary=DefaultVerificationPlan.REFERENCE_BOUNDARY;
        assertThatThrownBy(() -> DefaultVerificationPlan.reference(boundary+boundary+"contextReference={}"))
                .hasMessageContaining("boundary");
    }
}
