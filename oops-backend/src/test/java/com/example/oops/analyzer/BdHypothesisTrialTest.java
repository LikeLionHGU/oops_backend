package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static org.assertj.core.api.Assertions.*;

class BdHypothesisTrialTest {
    private JsonNode input(int count) {
        var input=JSON.createObjectNode(); input.put("promptRevision",CandidateReviewEngine.REVISION);
        input.putArray("raw").addObject().put("id","stt-index-0").put("text","원문").put("startMs",0).put("endMs",1500);
        var candidates=input.putArray("candidates");
        for(int i=1;i<=count;i++) {
            var c=candidates.addObject(); c.put("candidateId","candidate-"+i); c.put("anchorId","stt-index-0");
            c.put("axis",i==1?"TARGET_TREATMENT":"EXPRESSION_CONTENT"); c.put("hypothesisNotEvidence","가설");
            c.putArray("segmentIds").add("stt-index-0"); c.putArray("proposedEvidence").addObject().put("segmentId","stt-index-0").put("quote","원문");
        }
        return input;
    }
    @Test void sourcePairsKeepDGroupAndRejectUnexpectedInputChange() {
        var source=JSON.createObjectNode(); var rows=source.putArray("results");
        var plan=JSON.createObjectNode(); plan.put("schemaVersion","bd-hypothesis-regression-draft-1");
        plan.put("revision",CandidateReviewEngine.REVISION); plan.put("executionEnabled",false); var pairs=plan.putArray("pairs");
        for(int repeat=1;repeat<=2;repeat++) for(String caseId:List.of("P-B-development-excerpt","P-D-development-excerpt")) {
            boolean d=caseId.startsWith("P-D"); var row=rows.addObject(); row.put("caseId",caseId); row.put("repeat",repeat);
            var calls=row.putArray("calls"); calls.addNull();
            for(int index=1;index<=(d?1:2);index++) {
                var raw=input(d?2:1); var call=calls.addObject(); call.put("system",CandidateReviewEngine.verificationPrompt(0));
                call.set("input",raw); call.putObject("output").putArray("verifications");
                var pair=pairs.addObject(); pair.put("caseId",caseId); pair.put("sourceRepeat",repeat); pair.put("sourceCallIndex",index);
                pair.put("candidateCount",d?2:1); var arms=pair.putArray("arms");
                var with=arms.addObject(); with.put("id","WITH_HYPOTHESIS"); with.set("input",raw);
                var without=arms.addObject(); without.put("id","WITHOUT_HYPOTHESIS"); without.set("input",CandidateHypothesisTrial.noHypothesis(raw));
            }
        }
        var tasks=BdHypothesisTrial.tasks(plan,source);
        assertThat(tasks).hasSize(12);
        assertThat(tasks.stream().filter(t->t.caseId().startsWith("P-D"))).allSatisfy(t->assertThat(t.input().get("candidates").size()).isEqualTo(2));
        ((tools.jackson.databind.node.ObjectNode)pairs.get(0).get("arms").get(1).get("input")).put("unexpected",true);
        assertThatThrownBy(()->BdHypothesisTrial.tasks(plan,source)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingOrDuplicateGroupMemberCannotBecomeAcceptedPass() {
        var task=new BdHypothesisTrial.Task("P-D-development-excerpt",1,1,"WITH_HYPOTHESIS","system",input(2));
        var d=new CandidateReviewEngineTest().assessment("stt-index-0","PASS","원문");
        var one=new CandidateReviewEngine.Verification("candidate-1",d); var two=new CandidateReviewEngine.Verification("candidate-2",d);
        for(var bad:List.of(new CandidateReviewEngine.VerificationResult(List.of(one)),new CandidateReviewEngine.VerificationResult(List.of(one,one))))
            assertThat(BdHypothesisTrial.validate(task,bad)).allSatisfy(r->{assertThat(r.get("accepted")).isEqualTo(false); assertThat(r.get("failureCode")).isEqualTo("TRIAL_RESPONSE_SHAPE");});
        assertThat(BdHypothesisTrial.validate(task,new CandidateReviewEngine.VerificationResult(List.of(one,two))))
                .hasSize(2).allSatisfy(r->assertThat(r.get("accepted")).isEqualTo(true));
    }
}
