package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.VerificationFactorialPlan.*;
import static org.assertj.core.api.Assertions.*;

class VerificationFactorialPlanTest {
    record Inputs(byte[] source,byte[] human,byte[] mappings) {}
    Inputs inputs() throws Exception {
        var p=UtteranceDirectionTrial.basisPlan(new UtteranceDirectionTrialTest().plan());
        var selected=p.tasks().stream().filter(t -> t.id().startsWith("groq-") || t.id().startsWith("relation-")).toList();
        var cases=new ArrayList<Object>();var mappings=new ArrayList<Object>();
        for(int i=0;i<selected.size();i++) {
            var t=selected.get(i);String id="R%02d".formatted(i+1);
            cases.add(Map.of("reviewId",id,"reviewStatus","USER_APPROVED_DEVELOPMENT_LABEL","expectedDecision",
                    Set.of("relation-other-request","relation-refusal-not-acceptance").contains(t.id())?"REVIEW_REQUIRED":"PASS"));
            mappings.add(Map.of("reviewId",id,"sourceCaseId",t.id(),"inputSha256",ReferenceOnlyTrial.sha(t.input().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
        return new Inputs(JSON.writeValueAsBytes(p),JSON.writeValueAsBytes(Map.of("schemaVersion","utterance-human-review-form-1",
                "reviewStatus","USER_APPROVED_DEVELOPMENT_LABELS","blindPacketCanonicalSha256","a".repeat(64),"cases",cases)),
                JSON.writeValueAsBytes(Map.of("blindPacketCanonicalSha256","a".repeat(64),"cases",mappings)));
    }
    @Test void factorsAreIndependentAndHistoricalProductionPromptIsUnchanged() {
        String original=CandidateReviewEngine.verificationPrompt(0),snapshot=original;
        String base=system(original,Arm.BASELINE),guard=system(original,Arm.GUARD_ONLY);
        String contract=system(original,Arm.CONTRACT_ONLY),both=system(original,Arm.BOTH);
        assertThat(base).isEqualTo(original);
        assertThat(guard.replace(GUARD,"")).isEqualTo(base);
        assertThat(both.replace(GUARD,"")).isEqualTo(contract);
        assertThat(contract).doesNotContain(GUARD).contains("assessment 밖의 reviewBasis");
        assertThat(guard).doesNotContain("assessment 밖의 reviewBasis");
        assertThat(both).isNotEqualTo(FinalReviewBasisContract.prompt(original)); // guard placement is deliberately standardized.
        assertThat(CandidateReviewEngine.verificationPrompt(0)).isEqualTo(snapshot);
    }
    @Test void quotedGuardOrFakeHeaderInFrozenReferencesIsNotEdited() {
        String refs="\n고정 참고:\n"+GUARD+MARKER;
        String original=CandidateReviewEngine.verificationPrompt(0)+refs;
        for(var arm:Arm.values()) assertThat(system(original,arm)).endsWith(refs);
        assertThatThrownBy(() -> system("unknown "+MARKER,Arm.BASELINE)).hasMessageContaining("Known verification");
        assertThatThrownBy(() -> system(FinalReviewBasisContract.prompt(CandidateReviewEngine.verificationPrompt(0)),Arm.BOTH))
                .hasMessageContaining("Known verification axis rule");
    }
    @Test void judgmentFactorNeverChangesRawCandidatesOrContractMetadata() {
        String original="{\"raw\":[{\"id\":\"x\",\"text\":\"원문\"}],\"candidates\":[{\"anchorId\":\"x\"}]}";
        for(var arm:Arm.values()) {
            var actual=(tools.jackson.databind.node.ObjectNode)JSON.readTree(input(original,arm));
            if(arm.basis) assertThat(actual.remove("verificationContract").asText()).isEqualTo(FinalReviewBasisContract.VERSION);
            else assertThat(actual.has("verificationContract")).isFalse();
            assertThat(actual).isEqualTo(JSON.readTree(original));
        }
        assertThat(input(original,Arm.BASELINE)).isEqualTo(input(original,Arm.GUARD_ONLY));
        assertThat(input(original,Arm.CONTRACT_ONLY)).isEqualTo(input(original,Arm.BOTH));
        assertThatThrownBy(() -> input("{\"expectedDecision\":\"PASS\"}",Arm.BASELINE)).hasMessageContaining("Unlabeled");
    }
    @Test void freezesApprovedLabelsOutsideInputsAndLeavesOtherDraftLabelsUnapproved() throws Exception {
        var i=inputs();var p=prepare(i.source(),i.human(),i.mappings());
        assertThat(p.requests()).hasSize(56);assertThat(p.maxRequests()).isEqualTo(p.repeats()*p.requests().size()).isEqualTo(112);
        assertThat(p.requests().stream().filter(r -> r.expectedDecision()!=null).toList()).hasSize(32);
        assertThat(p.requests().stream().filter(r -> r.expectedDecision()==null).toList()).hasSize(24);
        assertThat(p.requests()).allSatisfy(r -> assertThat(r.input()).doesNotContain("expectedDecision","labelStatus","reviewId","USER_APPROVED"));
        assertThat(p.reservationEstimatedUsd()).isGreaterThan(0).isLessThan(1);
        assertThat(order(2)).containsExactly(Arm.BOTH,Arm.CONTRACT_ONLY,Arm.GUARD_ONLY,Arm.BASELINE);
        assertThatThrownBy(() -> order(3)).hasMessageContaining("Two repeats");
    }
    @Test void pendingApprovalOrMismatchedSourceCannotEnterThePlan() throws Exception {
        var i=inputs();var form=(tools.jackson.databind.node.ObjectNode)JSON.readTree(i.human());
        ((tools.jackson.databind.node.ObjectNode)form.path("cases").get(0)).put("reviewStatus","PENDING_HUMAN_REVIEW");
        assertThatThrownBy(() -> prepare(i.source(),JSON.writeValueAsBytes(form),i.mappings())).hasMessageContaining("No pending");
        var mappings=(tools.jackson.databind.node.ObjectNode)JSON.readTree(i.mappings());
        ((tools.jackson.databind.node.ObjectNode)mappings.path("cases").get(0)).put("inputSha256","b".repeat(64));
        assertThatThrownBy(() -> prepare(i.source(),i.human(),JSON.writeValueAsBytes(mappings))).hasMessageContaining("exact source input");
    }
    @Test void basisValidationDependsOnContractFactorNotJudgmentGuard() throws Exception {
        var i=inputs();var p=prepare(i.source(),i.human(),i.mappings());
        var r=p.requests().get(0);var c=JSON.readTree(r.input()).path("candidates").get(0);
        String anchor=c.path("anchorId").asText();var raw=CandidateHypothesisTrial.raw(JSON.readTree(r.input()));
        String text=raw.segments().stream().filter(s -> s.id().equals(anchor)).findFirst().orElseThrow().text();
        var d=new CandidateReviewEngineTest().assessment(anchor,"REVIEW_REQUIRED",text);
        var response=new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(c.path("candidateId").asText(),d)));
        for(var arm:List.of(Arm.CONTRACT_ONLY,Arm.BOTH)) {
            var selected=p.requests().stream().filter(x -> x.caseId().equals(r.caseId()) && x.arm()==arm).findFirst().orElseThrow();
            assertThat(assess(selected,response)).containsEntry("failureCode","REVIEW_BASIS_REQUIRED");
        }
        for(var arm:List.of(Arm.BASELINE,Arm.GUARD_ONLY)) {
            var selected=p.requests().stream().filter(x -> x.caseId().equals(r.caseId()) && x.arm()==arm).findFirst().orElseThrow();
            assertThat(assess(selected,response).get("failureCode")).isNotEqualTo("REVIEW_BASIS_REQUIRED");
        }
    }
}
