package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class SourceUnderstandingComparisonTest {
    @TempDir Path temporary;
    UtteranceDirectionTrial.Plan plan() throws Exception {
        return SourceUnderstandingComparison.plan(SituationFirstComparison.plan(new EvidenceContextComparisonTest().plan(),"test"),"test");
    }
    CandidateReviewEngine.VerificationResult response(UtteranceDirectionTrial.Task t,boolean enabled) {
        var input=JSON.readTree(t.input());var c=input.at("/candidates/0");String id=c.path("anchorId").asText();
        String text=null;for(var s:input.path("raw")) if(id.equals(s.path("id").asText())) text=s.path("text").asText();
        var d=new CandidateReviewEngineTest().assessment(id,"PASS",text);
        var reading=enabled?new SourceUnderstanding(List.of(new SourceUnderstanding.ActReading(id,"OTHER",List.of()))):null;
        return new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(c.path("candidateId").asText(),d,null,reading)));
    }

    @Test void scheduleHasOneUnlabeledSmokeAndFiftySixPairedCallsWithOriginalInputs() throws Exception {
        var source=SituationFirstComparison.plan(new EvidenceContextComparisonTest().plan(),"test");
        var p=SourceUnderstandingComparison.plan(source,"test");var slots=UtteranceDirectionTrial.schedule(p);
        assertThat(slots).hasSize(57);assertThat(UtteranceDirectionTrial.maxAttempts(p)).isEqualTo(57);
        assertThat(slots.get(0).repeat()).isZero();assertThat(slots.get(0).task().id()).isEqualTo(SourceUnderstandingComparison.SMOKE_ID);
        assertThat(slots.get(0).task().expectedDecision()).isNull();
        assertThat(slots.stream().filter(s -> s.repeat()>0 && s.arm().equals("BASELINE")).count()).isEqualTo(28);
        assertThat(slots.stream().filter(s -> s.repeat()>0 && s.arm().equals("COMPARISON")).count()).isEqualTo(28);
        for(int i=0;i<14;i++) {
            var t=p.tasks().get(i);var old=source.tasks().get(i);
            assertThat(t.input()).isEqualTo(old.input());assertThat(t.baselineSystem()).isEqualTo(old.comparisonSystem());
            assertThat(t.comparisonSystem()).isEqualTo(SourceUnderstandingContract.prompt(old.baselineSystem()));
            assertThat(UtteranceDirectionTrial.requestInput(p,t,true)).isEqualTo(t.input());
        }
        assertThat(UtteranceDirectionTrial.reservationEstimate(p)).isLessThan(.50);
        UtteranceDirectionTrial.consent(p,"run-understanding",SourceUnderstandingComparison.CONSENT);
        assertThatThrownBy(() -> UtteranceDirectionTrial.consent(p,"run-understanding",SituationFirstComparison.CONSENT))
                .hasMessageContaining("fresh cost/text");
    }

    @Test void smokeGateRejectsMissingReadingAndCapStopsBeforeFiftyEighthAttempt() throws Exception {
        var p=plan();var slot=UtteranceDirectionTrial.schedule(p).get(0);
        var bad=UtteranceDirectionTrial.assess(slot.task(),response(slot.task(),false),null,true);
        assertThat(bad).containsEntry("failureCode","SOURCE_UNDERSTANDING_REQUIRED");
        assertThatThrownBy(() -> SourceUnderstandingComparison.gate(slot,bad)).hasMessageContaining("stop before main comparison");
        var good=UtteranceDirectionTrial.assess(slot.task(),response(slot.task(),true),null,true);
        SourceUnderstandingComparison.gate(slot,good);
        var budget=new StructuredSearchBoundaryTrial.Budget(temporary,.50);
        for(int i=0;i<57;i++) UtteranceDirectionTrial.reserve(budget,0,57);
        assertThatThrownBy(() -> UtteranceDirectionTrial.reserve(budget,0,57)).hasMessageContaining("57 HTTP attempt cap");
        assertThat(budget.attempts).isEqualTo(57);
    }

    @Test void replayAppliesOnContractAndKeepsCompatibilityOutOfMainRows() throws Exception {
        var p=plan();var saved=JSON.createObjectNode();saved.put("schemaVersion",p.schemaVersion());
        saved.put("state","COMPLETE");saved.put("httpAttempts",57);var rows=saved.putArray("results");
        for(var slot:UtteranceDirectionTrial.schedule(p)) {
            var t=slot.task();boolean enabled=slot.arm().equals("COMPARISON");var response=response(t,enabled);
            var row=rows.addObject();row.put("caseId",t.id());row.put("repeat",slot.repeat());row.put("arm",slot.arm());
            row.put("labelStatus",t.labelStatus());row.set("expectedDecision",JSON.valueToTree(t.expectedDecision()));
            row.set("assessment",JSON.valueToTree(UtteranceDirectionTrial.assess(t,response,null,enabled)));
            var call=row.putArray("calls").addObject();call.put("system",enabled?t.comparisonSystem():t.baselineSystem());
            call.set("input",JSON.readTree(t.input()));call.putNull("failureCode");call.set("output",JSON.valueToTree(response));
        }
        var report=OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved);
        assertThat(report).containsEntry("historicalContractDrift",0).containsEntry("providerCalls",0);
        var audited=(List<?>)report.get("rows");assertThat(audited).hasSize(57);
        assertThat(((Map<?,?>)audited.get(0)).get("compatibilityOnly")).isEqualTo(true);
        // Missing sourceUnderstanding in an ON response must not silently use the old validator.
        var output=(tools.jackson.databind.node.ObjectNode)rows.get(2).at("/calls/0/output/verifications/0");
        output.remove("sourceUnderstanding");
        assertThatThrownBy(() -> OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved)).hasMessageContaining("replay drift");
    }
}
