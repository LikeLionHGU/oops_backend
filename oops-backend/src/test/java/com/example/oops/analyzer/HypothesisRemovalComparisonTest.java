package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;

class HypothesisRemovalComparisonTest {
    UtteranceDirectionTrial.Plan plan() throws Exception {
        var source=CompactRelationsComparison.plan(new CompactRelationsComparisonTest().source(),"test");
        return HypothesisRemovalComparison.plan(source,"test");
    }
    @Test void onlyHypothesisFieldIsDeletedAndBothSystemsAndOtherInputsStayFixed() throws Exception {
        var p=plan();assertThat(UtteranceDirectionTrial.schedule(p)).hasSize(56);
        assertThat(UtteranceDirectionTrial.maxAttempts(p)).isEqualTo(56);
        assertThat(UtteranceDirectionTrial.reservationEstimate(p)).isLessThan(.50);
        UtteranceDirectionTrial.consent(p,"run-hypothesis-removal",HypothesisRemovalComparison.CONSENT);
        assertThatThrownBy(() -> UtteranceDirectionTrial.consent(p,"run-hypothesis-removal",CompactRelationsComparison.CONSENT)).hasMessageContaining("fresh cost/text");
        for(var t:p.tasks()) {
            assertThat(t.baselineSystem()).isEqualTo(t.comparisonSystem());
            var original=JSON.readTree(t.input());var removed=JSON.readTree(UtteranceDirectionTrial.requestInput(p,t,true));
            assertThat(removed.at("/candidates/0").has("hypothesisNotEvidence")).isFalse();
            var restored=(tools.jackson.databind.node.ObjectNode)removed.at("/candidates/0");
            restored.set("hypothesisNotEvidence",original.at("/candidates/0/hypothesisNotEvidence"));
            assertThat(removed).isEqualTo(original);
            assertThat(UtteranceDirectionTrial.requestInput(p,t,false)).isEqualTo(t.input());
        }
    }
    @Test void replayUsesSameV3ValidatorOnBothArmsAndChecksActualDeletedRequest() throws Exception {
        var p=plan();var saved=JSON.createObjectNode();saved.put("schemaVersion",p.schemaVersion());
        saved.put("state","COMPLETE");saved.put("httpAttempts",56);var rows=saved.putArray("results");
        for(var slot:UtteranceDirectionTrial.schedule(p)) {
            var t=slot.task();boolean removed=slot.arm().equals("COMPARISON");
            String input=UtteranceDirectionTrial.requestInput(p,t,removed);
            var actual=new UtteranceDirectionTrial.Task(t.id(),t.labelStatus(),t.expectedDecision(),null,null,t.baselineSystem(),t.comparisonSystem(),input);
            var response=new CompactRelationsComparisonTest().response(actual,true);
            var row=rows.addObject();row.put("caseId",t.id());row.put("repeat",slot.repeat());row.put("arm",slot.arm());
            row.put("labelStatus",t.labelStatus());row.set("expectedDecision",JSON.valueToTree(t.expectedDecision()));
            row.set("assessment",JSON.valueToTree(UtteranceDirectionTrial.assess(p,actual,response,removed)));
            var call=row.putArray("calls").addObject();call.put("system",t.baselineSystem());call.set("input",JSON.readTree(input));
            call.putNull("failureCode");call.set("output",JSON.valueToTree(response));
        }
        assertThat(OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved)).containsEntry("historicalContractDrift",0);
        ((tools.jackson.databind.node.ObjectNode)rows.get(1).at("/calls/0/input/candidates/0")).put("hypothesisNotEvidence","再混入");
        assertThatThrownBy(() -> OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved)).hasMessageContaining("request/label mismatch");
    }
}
