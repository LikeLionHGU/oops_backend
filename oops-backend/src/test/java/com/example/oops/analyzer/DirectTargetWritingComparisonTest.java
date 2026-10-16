package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.DirectTargetWritingComparison.*;
import static org.assertj.core.api.Assertions.*;

/** Authored orchestration fixtures only; no real API calls or semantic accuracy claims. */
class DirectTargetWritingComparisonTest {
    static String old(String current) {
        int end=current.indexOf("# 대상 필드 형식 예시");
        return current.substring(0,end).replace(NEW_NAME,OLD_NAME).replace(NEW_ORDER,OLD_ORDER)+current.substring(end);
    }
    record Inputs(byte[] factorial,byte[] history) {}
    Inputs inputs() throws Exception {
        var i=new VerificationFactorialPlanTest().inputs();
        var source=JSON.readValue(i.source(),UtteranceDirectionTrial.Plan.class);
        var tasks=source.tasks().stream().map(t -> {
            String before=old(t.baselineSystem());
            return new UtteranceDirectionTrial.Task(t.id(),t.labelStatus(),t.expectedDecision(),t.relationDraft(),t.unsupportedInference(),
                    before,FinalReviewBasisContract.prompt(before),t.input());
        }).toList();
        var history=new UtteranceDirectionTrial.Plan(source.schemaVersion(),source.model(),source.repeats(),source.estimatedCapUsd(),
                source.sourceSha256(),source.runtimeSha256(),source.normalFixtureSha256(),source.relationFixtureSha256(),tasks);
        byte[] bytes=JSON.writeValueAsBytes(history);
        return new Inputs(JSON.writeValueAsBytes(VerificationFactorialPlan.prepare(bytes,i.human(),i.mappings())),bytes);
    }
    @Test void twoEditsReproduceCurrentVerificationAndNeverRewriteQuotedReferences() {
        String current=CandidateReviewEngine.verificationPrompt(0);
        String refs="\n참고 원문:\n"+OLD_NAME+OLD_ORDER;
        assertThat(comparison(old(current)+refs)).isEqualTo(current+refs);
        assertThatThrownBy(() -> comparison(current)).hasMessageContaining("Exactly one");
        assertThatThrownBy(() -> comparison("unknown instructions")).hasMessageContaining("Known verification");
    }
    @Test void fourteenInputsStayFrozenAndOnlyEightCarryUserApprovedLabels() throws Exception {
        var i=inputs();var p=UtteranceDirectionTrial.directPlan(i.factorial(),i.history());
        var baseline=JSON.readValue(i.factorial(),VerificationFactorialPlan.Plan.class).requests().stream()
                .filter(r -> r.arm()==VerificationFactorialPlan.Arm.BASELINE).toList();
        assertThat(p.tasks()).hasSize(14);assertThat(p.repeats()*p.tasks().size()*2).isEqualTo(56);
        assertThat(p.tasks().stream().filter(t -> t.expectedDecision()!=null).count()).isEqualTo(8);
        assertThat(UtteranceDirectionTrial.reservationEstimate(p)).isPositive().isLessThan(.50);
        for(int j=0;j<14;j++) {
            var t=p.tasks().get(j);var b=baseline.get(j);
            assertThat(t.input()).isEqualTo(b.input());assertThat(t.baselineSystem()).isEqualTo(b.system());
            assertThat(UtteranceDirectionTrial.requestInput(p,t,true)).isEqualTo(t.input());
            assertThat(t.comparisonSystem()).isEqualTo(comparison(t.baselineSystem()));
            assertThat(t.input()).doesNotContain("expectedDecision","labelStatus");
        }
    }
    @Test void sourceMismatchAlteredInstructionsAndOldConsentCannotRun() throws Exception {
        var i=inputs();var p=UtteranceDirectionTrial.directPlan(i.factorial(),i.history());
        UtteranceDirectionTrial.consent(p,"run-direct-writing",CONSENT);
        assertThatThrownBy(() -> UtteranceDirectionTrial.consent(p,"run-direct-writing",UtteranceDirectionTrial.CONSENT))
                .hasMessageContaining("fresh cost/text approval");
        assertThatThrownBy(() -> UtteranceDirectionTrial.consent(p,"run",CONSENT)).hasMessageContaining("fresh cost/text approval");
        var altered=(tools.jackson.databind.node.ObjectNode)JSON.readTree(i.factorial());altered.put("sourcePlanSha256","0".repeat(64));
        assertThatThrownBy(() -> UtteranceDirectionTrial.directPlan(JSON.writeValueAsBytes(altered),i.history())).hasMessageContaining("source/history mismatch");
        var changedPlan=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p);
        ((tools.jackson.databind.node.ObjectNode)changedPlan.path("tasks").get(0)).put("comparisonSystem","changed policy");
        var changed=JSON.treeToValue(changedPlan,UtteranceDirectionTrial.Plan.class);
        assertThatThrownBy(() -> UtteranceDirectionTrial.validate(changed)).hasMessageContaining("Comparison changes only");
    }
}
