package com.example.oops.analyzer;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class CurrentRequestCounterevidenceTrialTest {
    @Test void cannotReusePreviousConsentOrChangedPlan() {
        assertThatThrownBy(() -> CurrentRequestCounterevidenceTrial.authorized(new byte[0],new byte[0],new byte[0],new byte[0],
                CurrentRequestCounterevidenceTrial.PLAN_SHA,DefaultVerificationTrial.CONSENT)).hasMessageContaining("Fresh exact-plan");
        assertThatThrownBy(() -> CurrentRequestCounterevidenceTrial.authorized(new byte[0],new byte[0],new byte[0],new byte[0],
                CurrentRequestCounterevidenceTrial.PLAN_SHA,CurrentRequestCounterevidenceTrial.CONSENT)).hasMessageContaining("Fresh exact-plan");
    }
    @Test void reservesOnlyFortyEightAttemptsAndDoesNotRefundFailedCalls(@TempDir Path output) throws Exception {
        var budget=new StructuredSearchBoundaryTrial.Budget(output,.50);
        for(int i=0;i<48;i++) DefaultVerificationTrial.reserveBounded(budget,0,48);
        double reserved=budget.reserved;
        assertThatThrownBy(() -> DefaultVerificationTrial.reserveBounded(budget,0,48)).hasMessageContaining("HTTP attempt cap");
        assertThat(budget.attempts).isEqualTo(48);assertThat(budget.reserved).isEqualTo(reserved);assertThat(budget.exhausted).isTrue();
    }
    @Test void replaysAllThreeRepeatsAndKeepsRealIssuesInOneActualScope() throws Exception {
        var f=new CurrentRequestCounterevidencePlanTest().fixture();
        var plan=CurrentRequestCounterevidencePlan.build(f.source(),f.inputs(),f.labels(),"s","i","l");
        var saved=JSON.createObjectNode();saved.put("state","COMPLETE");saved.put("httpAttempts",48);
        saved.put("planSha256",CurrentRequestCounterevidenceTrial.PLAN_SHA);var rows=saved.putArray("results");
        for(var r:plan.requests()) {
            var response=new CandidateReviewEngine.VerificationResult(List.of());
            if(r.caseId().equals("R03") && r.repeat()==3 && r.arm().equals("BASELINE")) {
                var t=new UtteranceDirectionTrial.Task(r.caseId(),r.labelStatus(),null,null,null,r.system(),r.system(),r.input());
                var v=new CompactRelationsComparisonTest().response(t,true).verifications().get(0);
                response=new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(v.candidateId(),v.assessment())));
            }
            var row=rows.addObject();row.put("caseId",r.caseId());row.put("arm",r.arm());row.put("repeat",r.repeat());
            row.put("labelStatus",r.labelStatus());row.put("expectedDecision",r.expectedDecision());
            row.set("assessment",JSON.valueToTree(DefaultVerificationPlan.assess(r,response)));
            var call=row.putArray("calls").addObject();call.put("kind","VerificationResult");call.put("system",r.system());
            call.set("input",JSON.readTree(r.input()));call.set("output",JSON.valueToTree(response));call.putNull("failureCode");
        }
        var report=DefaultVerificationTrial.auditRequests(plan.requests(),CurrentRequestCounterevidenceTrial.PLAN_SHA,3,saved);
        assertThat(report).containsEntry("providerCalls",0).containsEntry("historicalContractDrift",0)
                .containsEntry("decisionOrContractRepeatDisagreements",1L);
        assertThat(JSON.valueToTree(report).at("/totals/BASELINE:ACTUAL_APPROVED/responses").asInt()).isEqualTo(6);
        ((tools.jackson.databind.node.ObjectNode)rows.get(0)).put("expectedDecision","REVIEW_REQUIRED");
        assertThatThrownBy(() -> DefaultVerificationTrial.auditRequests(plan.requests(),CurrentRequestCounterevidenceTrial.PLAN_SHA,3,saved))
                .hasMessageContaining("mismatch");
    }
}
