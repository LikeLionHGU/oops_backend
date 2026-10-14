package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;

class CandidateHypothesisTrialTest {
    private ObjectNode plan() {
        var plan=JSON.createObjectNode(); plan.put("schemaVersion","c-verification-ablation-draft-1");
        plan.put("revision",CandidateReviewEngine.REVISION); plan.put("executionEnabled",false);
        var input=JSON.createObjectNode(); input.put("promptRevision",CandidateReviewEngine.REVISION);
        input.putArray("raw").addObject().put("id","stt-index-0").put("text","원문").put("startMs",0).put("endMs",1500);
        var c=input.putArray("candidates").addObject(); c.put("candidateId","candidate-1"); c.put("anchorId","stt-index-0");
        c.put("axis","TARGET_TREATMENT"); c.put("hypothesisNotEvidence","가설 1"); c.putArray("segmentIds").add("stt-index-0");
        c.putArray("proposedEvidence").addObject().put("segmentId","stt-index-0").put("quote","원문");
        var arms=plan.putArray("arms");
        for(String id:new String[]{"WITH_HYPOTHESIS_1","WITH_HYPOTHESIS_2","WITHOUT_HYPOTHESIS"}) {
            var a=arms.addObject(); a.put("id",id); var request=a.putObject("request");
            request.put("system",CandidateReviewEngine.verificationPrompt(0));
            var variant=input.deepCopy(); ((ObjectNode)variant.get("candidates").get(0)).put("hypothesisNotEvidence",id);
            request.set("input",id.equals("WITHOUT_HYPOTHESIS")?CandidateHypothesisTrial.noHypothesis(variant):variant);
        }
        return plan;
    }
    @Test void onlyHypothesisMayDifferAndNormalLabelsStayOutsideRequests() throws Exception {
        var suite=TextBoundaryTrial.readSuite(Path.of("src/test/resources/evals/text-boundaries-37.json"));
        var tasks=CandidateHypothesisTrial.tasks(plan(),suite);
        assertThat(tasks).hasSize(15);
        assertThat(tasks.stream().mapToInt(CandidateHypothesisTrial.Task::repeats).sum()).isEqualTo(33);
        for(var t:tasks) assertThat(JSON.writeValueAsString(t.input())).doesNotContain("expected","previousParsedDecision","armId");
        var corrupt=plan(); ((ObjectNode)corrupt.get("arms").get(1).get("request").get("input").get("raw").get(0)).put("text","changed");
        assertThatThrownBy(()->CandidateHypothesisTrial.tasks(corrupt,suite)).isInstanceOf(IllegalArgumentException.class);
        var live=plan(); live.put("executionEnabled",true);
        assertThatThrownBy(()->CandidateHypothesisTrial.tasks(live,suite)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingOutputCannotBecomePassAndAcceptedResponsesUseProductionValidator() throws Exception {
        var suite=TextBoundaryTrial.readSuite(Path.of("src/test/resources/evals/text-boundaries-37.json"));
        var t=CandidateHypothesisTrial.tasks(plan(),suite).get(0);
        assertThat(CandidateHypothesisTrial.validate(t,null).failureCode()).isEqualTo("TRIAL_RESPONSE_SHAPE");
        var d=new CandidateReviewEngineTest().assessment("stt-index-0","PASS","原文");
        var result=new CandidateReviewEngine.VerificationResult(java.util.List.of(new CandidateReviewEngine.Verification("candidate-1",d)));
        assertThat(CandidateHypothesisTrial.validate(t,result).observation()).isNotNull();
        var wrong=new CandidateReviewEngine.VerificationResult(java.util.List.of(new CandidateReviewEngine.Verification("unknown",d)));
        assertThat(CandidateHypothesisTrial.validate(t,wrong).observation()).isNull();
    }
}
