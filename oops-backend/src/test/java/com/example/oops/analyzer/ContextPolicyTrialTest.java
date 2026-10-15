package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static org.assertj.core.api.Assertions.*;

class ContextPolicyTrialTest {
    @Test void fixedStudyHasFourReviewedFlowsAndEightUnlabeledProbes() {
        assertThat(ContextPolicyTrial.specs()).hasSize(12);
        assertThat(ContextPolicyTrial.specs().stream().filter(s->s.expected()!=null)).hasSize(4);
        assertThat(ContextPolicyTrial.specs().stream().filter(s->s.expected()==null))
                .allSatisfy(s->assertThat(s.labelStatus()).isEqualTo("PENDING_HUMAN_REVIEW"));
        assertThat(ContextPolicyTrial.specs().stream().map(ContextPolicyTrial.Spec::id).distinct()).hasSize(12);
    }
    @Test void reconstructedRequestKeepsLabelsOutAndUsesProductionValidator() {
        var f=new CandidateReviewEngineTest();var context=f.context("이 가게를 소개해요","저는 다른 메뉴가 좋아요","그렇군요");
        var spec=new ContextPolicyTrial.Spec("synthetic",0,1,"TARGET_TREATMENT",List.of(0,1),"PENDING_HUMAN_REVIEW",null);
        var task=ContextPolicyTrial.capture(spec,context,null);var body=JSON.readTree(task.input());
        assertThat(body.has("expected")).isFalse();assertThat(body.has("labelStatus")).isFalse();
        assertThat(task.comparisonSystem()).isEqualTo(ContextJudgmentPolicy.replacePolicy(task.baselineSystem()));
        assertThat(body.get("candidates").get(0).has("hypothesisNotEvidence")).isFalse();
        assertThat(ContextPolicyTrial.assess(task,null).get(0).get("accepted")).isEqualTo(false);
        var pass=f.linkedPass("stt-index-1",context.transcript().get(1).getText(),"stt-index-0",context.transcript().get(0).getText());
        assertThat(ContextPolicyTrial.assess(task,new VerificationResult(List.of(new Verification("candidate-1",pass)))).get(0).get("accepted"))
                .isEqualTo(true);
        assertThat(ContextPolicyTrial.assess(task,new VerificationResult(List.of(new Verification("other-candidate",pass)))).get(0).get("accepted"))
                .isEqualTo(false);
    }
}
