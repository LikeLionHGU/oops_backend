package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;

class SourceRelationsComparisonTest {
    @Test void replayUsesEachVersionsValidatorWithoutHistoricalMutation() throws Exception {
        var p=SourceRelationsComparison.plan(new SourceUnderstandingComparisonTest().plan(),"test");
        var saved=JSON.createObjectNode();saved.put("schemaVersion",p.schemaVersion());
        saved.put("state","COMPLETE");saved.put("httpAttempts",57);var rows=saved.putArray("results");
        for(var slot:UtteranceDirectionTrial.schedule(p)) {
            var t=slot.task();boolean comparison=slot.arm().equals("COMPARISON");
            var response=new SourceUnderstandingComparisonTest().response(t,true);
            if(comparison) {
                var v=response.verifications().get(0);var act=v.sourceUnderstanding().acts().get(0);
                var reading=new SourceUnderstanding(v.sourceUnderstanding().acts(),List.of(new SourceUnderstanding.RelationReading(
                        act.segmentId(),act.actType(),"NONE",null,"OTHER",List.of(act.segmentId()),"NOT_APPLICABLE",List.of())));
                response=new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(v.candidateId(),v.assessment(),null,reading)));
            }
            var row=rows.addObject();row.put("caseId",t.id());row.put("repeat",slot.repeat());row.put("arm",slot.arm());
            row.put("labelStatus",t.labelStatus());row.set("expectedDecision",JSON.valueToTree(t.expectedDecision()));
            row.set("assessment",JSON.valueToTree(UtteranceDirectionTrial.assess(p,t,response,comparison)));
            var call=row.putArray("calls").addObject();call.put("system",comparison?t.comparisonSystem():t.baselineSystem());
            call.set("input",JSON.readTree(t.input()));call.putNull("failureCode");call.set("output",JSON.valueToTree(response));
        }
        assertThat(OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved))
                .containsEntry("historicalContractDrift",0).containsEntry("providerCalls",0);
        var output=(tools.jackson.databind.node.ObjectNode)rows.get(2).at("/calls/0/output/verifications/0/sourceUnderstanding");
        output.remove("relations");
        assertThatThrownBy(() -> OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved)).hasMessageContaining("replay drift");
    }

    @Test void frozenInputsAndVersionSpecificValidationWithFreshConsent() throws Exception {
        var source=new SourceUnderstandingComparisonTest().plan();
        var p=SourceRelationsComparison.plan(source,"test");
        assertThat(UtteranceDirectionTrial.schedule(p)).hasSize(57);
        assertThat(UtteranceDirectionTrial.reservationEstimate(p)).isLessThan(.50);
        UtteranceDirectionTrial.consent(p,"run-relations-v2",SourceRelationsComparison.CONSENT);
        assertThatThrownBy(() -> UtteranceDirectionTrial.consent(p,"run-relations-v2",SourceUnderstandingComparison.CONSENT)).hasMessageContaining("fresh cost/text");
        for(int i=0;i<14;i++) {
            assertThat(p.tasks().get(i).input()).isEqualTo(source.tasks().get(i).input());
            assertThat(p.tasks().get(i).baselineSystem()).isEqualTo(source.tasks().get(i).comparisonSystem());
            String baseline=p.tasks().get(i).baselineSystem(), comparison=p.tasks().get(i).comparisonSystem();
            assertThat(comparison).contains("\"relations\"");
            assertThat(comparison.substring(comparison.indexOf("# 대상 필드 형식 예시")))
                    .isEqualTo(baseline.substring(baseline.indexOf("# 대상 필드 형식 예시")));
        }
        var t=UtteranceDirectionTrial.schedule(p).get(0).task();
        var v1=new SourceUnderstandingComparisonTest().response(t,true);
        assertThat(UtteranceDirectionTrial.assess(p,t,v1,false)).containsEntry("accepted",true);
        assertThat(UtteranceDirectionTrial.assess(p,t,v1,true)).containsEntry("failureCode","SOURCE_RELATION_READINGS_REQUIRED");
        var v=v1.verifications().get(0);String id=JSON.readTree(t.input()).at("/candidates/0/anchorId").asText();
        var reading=new SourceUnderstanding(v.sourceUnderstanding().acts(),List.of(new SourceUnderstanding.RelationReading(
                id,"OTHER","NONE",null,"OTHER",List.of(id),"NOT_APPLICABLE",List.of())));
        var v2=new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(v.candidateId(),v.assessment(),null,reading)));
        assertThat(UtteranceDirectionTrial.assess(p,t,v2,true)).containsEntry("accepted",true);
        assertThat(UtteranceDirectionTrial.assess(p,t,v2,false)).containsEntry("failureCode","UNEXPECTED_SOURCE_RELATION_READINGS");
    }
}
