package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;

class CompactRelationsComparisonTest {
    UtteranceDirectionTrial.Plan source() throws Exception {
        return SourceRelationsComparison.plan(new SourceUnderstandingComparisonTest().plan(),"test");
    }
    CandidateReviewEngine.VerificationResult response(UtteranceDirectionTrial.Task t,boolean compact) {
        var v=new SourceUnderstandingComparisonTest().response(t,true).verifications().get(0);
        var a=v.sourceUnderstanding().acts().get(0);
        var r=new SourceUnderstanding(compact?null:v.sourceUnderstanding().acts(),List.of(new SourceUnderstanding.RelationReading(
                a.segmentId(),a.actType(),"NONE",null,"OTHER",List.of(a.segmentId()),"NOT_APPLICABLE",List.of())));
        return new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(v.candidateId(),v.assessment(),null,r)));
    }
    @Test void fixedInputsEquivalentPromptConstructionAndFreshVersionedValidation() throws Exception {
        var source=source();var p=CompactRelationsComparison.plan(source,"test");
        assertThat(UtteranceDirectionTrial.schedule(p)).hasSize(57);
        assertThat(UtteranceDirectionTrial.reservationEstimate(p)).isLessThan(.50);
        UtteranceDirectionTrial.consent(p,"run-relations-v3",CompactRelationsComparison.CONSENT);
        assertThatThrownBy(() -> UtteranceDirectionTrial.consent(p,"run-relations-v3",SourceRelationsComparison.CONSENT)).hasMessageContaining("fresh cost/text");
        for(int i=0;i<14;i++) {
            var t=p.tasks().get(i);var old=source.tasks().get(i);
            assertThat(t.input()).isEqualTo(old.input());assertThat(t.baselineSystem()).isEqualTo(old.comparisonSystem());
            assertThat(t.comparisonSystem()).isEqualTo(SourceUnderstandingCompact.fromV1Prompt(old.baselineSystem()));
        }
        var t=UtteranceDirectionTrial.schedule(p).get(0).task();
        assertThat(UtteranceDirectionTrial.assess(p,t,response(t,false),false)).containsEntry("accepted",true);
        assertThat(UtteranceDirectionTrial.assess(p,t,response(t,true),true)).containsEntry("accepted",true);
        assertThat(UtteranceDirectionTrial.assess(p,t,response(t,false),true)).containsEntry("failureCode","UNEXPECTED_SOURCE_UNDERSTANDING_ACTS");
        assertThat(UtteranceDirectionTrial.assess(p,t,response(t,true),false)).containsEntry("failureCode","SOURCE_UNDERSTANDING_ACTS");
        assertThatThrownBy(() -> SourceUnderstandingCompact.fromV2Prompt(t.comparisonSystem())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void offlineReplayKeepsSmokeSeparateAndDoesNotDowngradeCompactOutput() throws Exception {
        var p=CompactRelationsComparison.plan(source(),"test");var saved=JSON.createObjectNode();
        saved.put("schemaVersion",p.schemaVersion());saved.put("state","COMPLETE");saved.put("httpAttempts",57);var rows=saved.putArray("results");
        for(var slot:UtteranceDirectionTrial.schedule(p)) {
            var t=slot.task();boolean compact=slot.arm().equals("COMPARISON");var response=response(t,compact);
            var row=rows.addObject();row.put("caseId",t.id());row.put("repeat",slot.repeat());row.put("arm",slot.arm());
            row.put("labelStatus",t.labelStatus());row.set("expectedDecision",JSON.valueToTree(t.expectedDecision()));
            row.set("assessment",JSON.valueToTree(UtteranceDirectionTrial.assess(p,t,response,compact)));
            var call=row.putArray("calls").addObject();call.put("system",compact?t.comparisonSystem():t.baselineSystem());
            call.set("input",JSON.readTree(t.input()));call.putNull("failureCode");call.set("output",JSON.valueToTree(response));
        }
        var report=OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved);
        assertThat(report).containsEntry("providerCalls",0).containsEntry("historicalContractDrift",0);
        var output=(tools.jackson.databind.node.ObjectNode)rows.get(2).at("/calls/0/output/verifications/0/sourceUnderstanding");
        output.putArray("acts");
        assertThatThrownBy(() -> OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved)).hasMessageContaining("replay drift");
    }
}
