package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** Authored request fixtures, not independently reviewed semantic labels. No provider calls. */
class EvidenceContextComparisonTest {
    @TempDir Path temporary;
    String input() {
        var node=JSON.createObjectNode();var raw=node.putArray("raw");
        raw.addObject().put("id",EvidenceContextComparison.CONTEXT_ID).put("text","그 말의 뜻은 무엇인가요?").put("startMs",0).put("endMs",1500);
        raw.addObject().put("id","groq-v4-166").put("text","그 말의 뜻을 풀이합니다.").put("startMs",2000).put("endMs",3500);
        var c=node.putArray("candidates").addObject();c.put("candidateId","candidate-1");c.put("anchorId","groq-v4-166");
        c.put("axis","TARGET_TREATMENT");c.put("hypothesisNotEvidence","변경하지 않는 원래 가설");
        c.putArray("segmentIds").add(EvidenceContextComparison.CONTEXT_ID).add("groq-v4-166");
        c.putArray("proposedEvidence").addObject().put("segmentId","groq-v4-166").put("quote","그 말의 뜻을 풀이합니다.");
        return node.toString();
    }

    @Test void prependsOnlyAnExistingAllowedSourceWithoutChangingHypothesisRawOrOtherEvidence() {
        String original=input();var before=JSON.readTree(original);
        var changed=JSON.readTree(EvidenceContextComparison.input("groq-v4-166",original));
        var evidence=changed.at("/candidates/0/proposedEvidence");
        assertThat(evidence.size()).isEqualTo(2);
        assertThat(evidence.get(0).path("segmentId").asText()).isEqualTo(EvidenceContextComparison.CONTEXT_ID);
        assertThat(evidence.get(0).path("quote").asText()).isEqualTo(before.path("raw").get(0).path("text").asText());
        assertThat(evidence.get(1)).isEqualTo(before.at("/candidates/0/proposedEvidence/0"));
        ((tools.jackson.databind.node.ObjectNode)changed.path("candidates").get(0))
                .set("proposedEvidence",before.at("/candidates/0/proposedEvidence"));
        assertThat(changed).isEqualTo(before);assertThat(JSON.readTree(original)).isEqualTo(before);
        assertThat(EvidenceContextComparison.input("unchanged-control",original)).isEqualTo(original);
    }

    @Test void missingPermissionOrAlreadyProposedContextFailsClosed() {
        var missing=(tools.jackson.databind.node.ObjectNode)JSON.readTree(input());
        ((tools.jackson.databind.node.ObjectNode)missing.path("candidates").get(0)).putArray("segmentIds").add("groq-v4-166");
        assertThatThrownBy(() -> EvidenceContextComparison.input("groq-v4-166",missing.toString())).hasMessageContaining("allowed context");
        String once=EvidenceContextComparison.input("groq-v4-166",input());
        assertThatThrownBy(() -> EvidenceContextComparison.input("groq-v4-166",once)).hasMessageContaining("already proposed");
    }

    UtteranceDirectionTrial.Plan plan() throws Exception {
        var s=new NeutralHypothesisComparisonTest().source();
        var source=NeutralHypothesisComparison.prepare(s.plan(),s.results());
        var tasks=source.tasks().stream().map(t -> new UtteranceDirectionTrial.Task(t.id(),t.labelStatus(),t.expectedDecision(),null,null,
                t.baselineSystem(),t.baselineSystem(),EvidenceContextComparison.TARGETS.contains(t.id())?input():t.input())).toList();
        return new UtteranceDirectionTrial.Plan(EvidenceContextComparison.VERSION,source.model(),2,.50,"test-source",source.runtimeSha256(),
                source.normalFixtureSha256(),source.relationFixtureSha256(),tasks);
    }

    @Test void scheduleHasEightPairedExcerptCallsAndTwentyFourUnchangedControlCalls() throws Exception {
        var p=plan();UtteranceDirectionTrial.validate(p);var slots=new ArrayList<String>();int intervention=0,controls=0;
        for(int repeat=1;repeat<=2;repeat++) for(var t:p.tasks()) for(String arm:UtteranceDirectionTrial.arms(p,t,repeat)) {
            slots.add(t.id()+"/"+repeat+"/"+arm);
            if(EvidenceContextComparison.TARGETS.contains(t.id())) intervention++;else {controls++;assertThat(arm).isEqualTo("BASELINE");}
        }
        assertThat(slots).hasSize(32).doesNotHaveDuplicates();assertThat(intervention).isEqualTo(8);assertThat(controls).isEqualTo(24);
        assertThat(UtteranceDirectionTrial.maxAttempts(p)).isEqualTo(32);
        assertThat(UtteranceDirectionTrial.arms(p,p.tasks().get(0),2)).containsExactly("COMPARISON","BASELINE");
        assertThat(UtteranceDirectionTrial.reservationEstimate(p)).isPositive().isLessThan(.50);
    }

    @Test void freshConsentAndThirtyTwoAttemptLimitRemainSeparateFromOldTrials() throws Exception {
        var p=plan();UtteranceDirectionTrial.consent(p,"run-evidence-context",EvidenceContextComparison.CONSENT);
        assertThatThrownBy(() -> UtteranceDirectionTrial.consent(p,"run-evidence-context",NeutralHypothesisComparison.CONSENT))
                .hasMessageContaining("fresh cost/text");
        var budget=new StructuredSearchBoundaryTrial.Budget(temporary,.50);
        for(int i=0;i<32;i++) UtteranceDirectionTrial.reserve(budget,0,32);
        assertThat(JSON.readTree(Files.readString(temporary.resolve("reservation.json"))).path("maxHttpAttempts").asInt()).isEqualTo(32);
        assertThatThrownBy(() -> UtteranceDirectionTrial.reserve(budget,0,32)).hasMessageContaining("32 HTTP attempt cap");
        assertThat(budget.attempts).isEqualTo(32);
    }

    @Test void replayUsesTheAddedEvidenceAndDoesNotInventComparisonControlRows() throws Exception {
        var p=plan();var saved=JSON.createObjectNode();saved.put("schemaVersion",p.schemaVersion());
        saved.put("state","COMPLETE");saved.put("httpAttempts",32);var rows=saved.putArray("results");
        for(int repeat=1;repeat<=2;repeat++) for(var t:p.tasks()) for(String arm:UtteranceDirectionTrial.arms(p,t,repeat)) {
            String input=UtteranceDirectionTrial.requestInput(p,t,arm.equals("COMPARISON"));
            var actual=new UtteranceDirectionTrial.Task(t.id(),t.labelStatus(),t.expectedDecision(),null,null,t.baselineSystem(),t.comparisonSystem(),input);
            var response=new CandidateReviewEngine.VerificationResult(List.of());var row=rows.addObject();
            row.put("caseId",t.id());row.put("repeat",repeat);row.put("arm",arm);row.put("labelStatus",t.labelStatus());
            row.set("expectedDecision",JSON.valueToTree(t.expectedDecision()));row.set("assessment",JSON.valueToTree(UtteranceDirectionTrial.assess(actual,response)));
            var call=row.putArray("calls").addObject();call.put("system",t.baselineSystem());call.set("input",JSON.readTree(input));
            call.putNull("failureCode");call.set("output",JSON.valueToTree(response));
        }
        var report=OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved);
        assertThat(report).containsEntry("historicalContractDrift",0).containsEntry("providerCalls",0);
        assertThat((List<?>)report.get("rows")).hasSize(32);
        var call=(tools.jackson.databind.node.ObjectNode)saved.path("results").get(1).path("calls").get(0);
        call.set("input",JSON.readTree(p.tasks().get(0).input()));
        assertThatThrownBy(() -> OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved)).hasMessageContaining("request/label mismatch");
    }
}
