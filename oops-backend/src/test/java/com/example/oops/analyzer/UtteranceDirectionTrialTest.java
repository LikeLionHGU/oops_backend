package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class UtteranceDirectionTrialTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    byte[] runtime() { return JSON.writeValueAsBytes(new ContextRelationRetrievalTest().archive()); }
    UtteranceDirectionTrial.Plan plan() throws Exception {
        var library=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(new String(runtime(),java.nio.charset.StandardCharsets.UTF_8)),
                true,"classpath:frozen.json",6000,3,3000,"",true);library.load();
        var calls=new ArrayList<Object>();
        for(int i=0;i<2;i++) {
            var task=UtteranceDirectionTrial.capture("seed","DRAFT",null,null,null,List.of("제가 질문합니다.","제가 보여드릴까요?"),i,library);
            String input=task.input().replace("stt-index-0","groq-v4-166").replace("stt-index-1","groq-v4-178");
            calls.add(Map.of("kind","VerificationResult","system",task.baselineSystem(),"input",JSON.readTree(input)));
        }
        var rows=new ArrayList<Object>();
        rows.add(Map.of("caseId","v4-intimate-question-groq","arm","SCOPED","repeat",1,"calls",calls));
        for(int i=1;i<32;i++) rows.add(Map.of("caseId","other-"+i));
        return UtteranceDirectionTrial.prepare(JSON.writeValueAsBytes(Map.of("schemaVersion","discovery-id-scope-results-1",
                "state","COMPLETE","results",rows)),runtime());
    }
    @Test void insertsOnlyDirectionGuidanceWithoutChangingBaselineOrOutputContract() {
        String before=CandidateReviewEngine.verificationPrompt(0)+"\n참고 원문";
        String after=UtteranceDirectionPolicy.comparison(before);
        assertThat(after.replace(UtteranceDirectionPolicy.GUIDANCE,"")).isEqualTo(before);
        assertThat(after).contains("자기 공개 제안을 타인에게 공개하라는 요구로", "용어 풀이·인용", "자동 정상은 아니고", "자동 논란은 아니다");
        assertThatThrownBy(() -> UtteranceDirectionPolicy.comparison("unknown prompt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UtteranceDirectionPolicy.comparison(after)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void freezesFourteenFixedInputsWithLabelsOutsideTheModelRequest() throws Exception {
        var p=plan();UtteranceDirectionTrial.validate(p);
        assertThat(p.tasks()).hasSize(14);assertThat(p.repeats()*2*p.tasks().size()).isEqualTo(56);
        assertThat(p.estimatedCapUsd()).isEqualTo(.50);
        assertThat(p.tasks().stream().filter(t -> t.id().startsWith("normal-")).toList()).hasSize(6)
                .allSatisfy(t -> assertThat(t.expectedDecision()).isEqualTo("PASS"));
        assertThat(p.tasks().stream().filter(t -> t.id().startsWith("relation-")).toList()).hasSize(6).allSatisfy(t -> {
            assertThat(t.expectedDecision()).isNull();assertThat(t.input()).doesNotContain(t.relationDraft(),"unsupportedInference",t.unsupportedInference());
        });
        assertThat(p.tasks()).allSatisfy(t -> {
            assertThat(t.comparisonSystem().replace(UtteranceDirectionPolicy.GUIDANCE,"")).isEqualTo(t.baselineSystem());
            assertThat(t.input()).doesNotContain("expectedDecision","labelStatus","relationDraft");
        });
    }
    @Test void changedGuidanceOrFixtureCannotRunAsTheFrozenPair() throws Exception {
        var p=plan();var tree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p);
        tree.put("relationFixtureSha256","0".repeat(64));
        var fixtureChanged=JSON.treeToValue(tree,UtteranceDirectionTrial.Plan.class);
        assertThatThrownBy(() -> UtteranceDirectionTrial.validate(fixtureChanged))
                .hasMessageContaining("Boundary fixtures changed");
        tree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p);
        ((tools.jackson.databind.node.ObjectNode)tree.path("tasks").get(0)).put("comparisonSystem","altered");
        var changed=JSON.treeToValue(tree,UtteranceDirectionTrial.Plan.class);
        assertThatThrownBy(() -> UtteranceDirectionTrial.validate(changed)).hasMessageContaining("Comparison changes only direction guidance");
        assertThat(UtteranceDirectionTrial.CONSENT).isNotEqualTo(StructuredSearchBoundaryTrial.SCOPE_CONSENT);
    }
    @Test void attemptsIncludingProviderRetriesCannotExceedFiftySix() {
        var budget=new StructuredSearchBoundaryTrial.Budget(temporary,.50);budget.attempts=56;
        assertThatThrownBy(() -> UtteranceDirectionTrial.reserve(budget,0)).hasMessageContaining("56 HTTP attempt cap");
        assertThat(budget.exhausted).isTrue();assertThat(budget.reserved).isZero();
    }
    @Test void basisComparisonChangesOnlyContractAndNeverAddsUnadoptedGuidance() throws Exception {
        var old=plan();var newer=UtteranceDirectionTrial.basisPlan(old);
        assertThat(newer.schemaVersion()).isEqualTo(UtteranceDirectionTrial.BASIS_REVISION);
        assertThat(UtteranceDirectionTrial.BASIS_CONSENT).isNotEqualTo(UtteranceDirectionTrial.CONSENT);
        assertThat(newer.sourceSha256()).isEqualTo(old.sourceSha256());
        assertThat(UtteranceDirectionTrial.reservationEstimate(newer)).isLessThanOrEqualTo(.50);
        for(int i=0;i<14;i++) {
            var t=newer.tasks().get(i);var original=old.tasks().get(i);
            assertThat(t.input()).isEqualTo(original.input());
            assertThat(t.baselineSystem()).isEqualTo(original.baselineSystem());
            assertThat(t.comparisonSystem()).isEqualTo(FinalReviewBasisContract.prompt(original.baselineSystem()));
            assertThat(t.comparisonSystem()).doesNotContain(UtteranceDirectionPolicy.GUIDANCE);
            assertThat(UtteranceDirectionTrial.requestInput(newer,t,false)).isEqualTo(t.input());
            var changed=(tools.jackson.databind.node.ObjectNode)JSON.readTree(UtteranceDirectionTrial.requestInput(newer,t,true));
            assertThat(changed.remove("verificationContract").asText()).isEqualTo(FinalReviewBasisContract.VERSION);
            assertThat(changed).isEqualTo(JSON.readTree(t.input()));
        }
    }
    @Test void basisAssessorUsesSelectedContractAndPreservesReturnedBasis() throws Exception {
        var t=UtteranceDirectionTrial.basisPlan(plan()).tasks().get(0);
        var input=JSON.readTree(t.input());var c=input.path("candidates").get(0);
        String anchor=c.path("anchorId").asText(),candidateId=c.path("candidateId").asText();
        var raw=CandidateHypothesisTrial.raw(input);
        String text=raw.segments().stream().filter(s -> s.id().equals(anchor)).findFirst().orElseThrow().text();
        var d=new TextReviewEngine.LlmDecision(anchor,"REVIEW_REQUIRED",text,"표현 자체에 대한 합성 검토 근거입니다.","SEXUAL",
                null,.5,null,null,List.of(),List.of(new TextReviewEngine.LlmEvidence(anchor,text,"PRIMARY")),null,null,null,
                "대상 평가와 별개인 표현 자체에 대한 합성 검토입니다.");
        var missing=new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(candidateId,d)));
        assertThat(UtteranceDirectionTrial.assess(t,missing,true)).containsEntry("failureCode","REVIEW_BASIS_REQUIRED");
        var provided=new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(candidateId,d,
                new CandidateReviewEngine.ReviewBasis("EXPRESSION_CONTENT","인용 표현 자체를 검토합니다.",List.of(anchor)))));
        assertThat(UtteranceDirectionTrial.assess(t,provided,false)).containsEntry("failureCode","UNEXPECTED_REVIEW_BASIS");
        assertThat(UtteranceDirectionTrial.assess(t,provided,true)).containsEntry("accepted",true);
    }
    @Test void basisPlanCannotMixOldGuidanceOrCarryContractInBaselineInput() throws Exception {
        var p=UtteranceDirectionTrial.basisPlan(plan());
        var tree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p);
        ((tools.jackson.databind.node.ObjectNode)tree.path("tasks").get(0)).put("comparisonSystem",p.tasks().get(0).baselineSystem());
        assertThatThrownBy(() -> UtteranceDirectionTrial.validate(JSON.treeToValue(tree,UtteranceDirectionTrial.Plan.class)))
                .hasMessageContaining("Comparison changes only");
        var inputTree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p);
        var first=(tools.jackson.databind.node.ObjectNode)inputTree.path("tasks").get(0);
        var input=(tools.jackson.databind.node.ObjectNode)JSON.readTree(first.path("input").asText());
        input.put("verificationContract",FinalReviewBasisContract.VERSION);first.put("input",input.toString());
        assertThatThrownBy(() -> UtteranceDirectionTrial.validate(JSON.treeToValue(inputTree,UtteranceDirectionTrial.Plan.class)))
                .hasMessageContaining("Fixed unlabeled verification input required");
    }
}
