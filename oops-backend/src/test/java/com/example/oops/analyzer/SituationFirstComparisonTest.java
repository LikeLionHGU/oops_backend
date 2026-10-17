package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class SituationFirstComparisonTest {
    @Test void preservesOriginalInputAndLabelsAndRequiresFreshConsentForFiftySixCalls() throws Exception {
        var source=new EvidenceContextComparisonTest().plan();
        var plan=SituationFirstComparison.plan(source,"test");
        assertThat(UtteranceDirectionTrial.maxAttempts(plan)).isEqualTo(56);
        assertThat(UtteranceDirectionTrial.reservationEstimate(plan)).isLessThan(.50);
        for(int i=0;i<14;i++) {
            var t=plan.tasks().get(i);var old=source.tasks().get(i);
            assertThat(t.input()).isEqualTo(old.input());
            assertThat(t.expectedDecision()).isEqualTo(old.expectedDecision());
            assertThat(t.comparisonSystem()).isEqualTo(SituationFirstVerification.prompt(old.baselineSystem()));
            assertThat(UtteranceDirectionTrial.requestInput(plan,t,true)).isEqualTo(t.input());
        }
        UtteranceDirectionTrial.consent(plan,"run-situation-first",SituationFirstComparison.CONSENT);
        assertThatThrownBy(() -> UtteranceDirectionTrial.consent(plan,"run-situation-first",EvidenceContextComparison.CONSENT))
                .hasMessageContaining("fresh cost/text");
    }

    @Test void completeReplayChecksBothSystemsAndRejectsChangedInput() throws Exception {
        var p=SituationFirstComparison.plan(new EvidenceContextComparisonTest().plan(),"test");
        var saved=JSON.createObjectNode();saved.put("schemaVersion",p.schemaVersion());saved.put("state","COMPLETE");saved.put("httpAttempts",56);
        var rows=saved.putArray("results");
        for(int repeat=1;repeat<=2;repeat++) for(var t:p.tasks()) for(String arm:UtteranceDirectionTrial.arms(p,t,repeat)) {
            var response=new CandidateReviewEngine.VerificationResult(List.of());var row=rows.addObject();
            row.put("caseId",t.id());row.put("repeat",repeat);row.put("arm",arm);row.put("labelStatus",t.labelStatus());
            row.set("expectedDecision",JSON.valueToTree(t.expectedDecision()));row.set("assessment",JSON.valueToTree(UtteranceDirectionTrial.assess(t,response)));
            var call=row.putArray("calls").addObject();call.put("system",arm.equals("BASELINE")?t.baselineSystem():t.comparisonSystem());
            call.set("input",JSON.readTree(t.input()));call.putNull("failureCode");call.set("output",JSON.valueToTree(response));
        }
        assertThat(OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved)).containsEntry("historicalContractDrift",0);
        ((tools.jackson.databind.node.ObjectNode)rows.get(1).path("calls").get(0).path("input")).put("extra","changed");
        assertThatThrownBy(() -> OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(p),saved)).hasMessageContaining("request/label mismatch");
    }
}
