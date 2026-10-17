package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** No API calls; fixture completion is orchestration evidence, not meaning accuracy. */
class NeutralHypothesisComparisonTest {
    record Source(byte[] plan,byte[] results) {}
    Source source() throws Exception {
        var i=new DirectTargetWritingComparisonTest().inputs();
        var plan=UtteranceDirectionTrial.directPlan(i.factorial(),i.history());
        var bytes=JSON.writeValueAsBytes(plan);var saved=JSON.createObjectNode();
        saved.put("schemaVersion",DirectTargetWritingComparison.VERSION);saved.put("state","COMPLETE");saved.put("httpAttempts",56);
        saved.put("planSha256",ReferenceOnlyTrial.sha(bytes));var rows=saved.putArray("results");
        for(int repeat=1;repeat<=2;repeat++) for(var t:plan.tasks())
            for(String arm:repeat==1?java.util.List.of("BASELINE","COMPARISON"):java.util.List.of("COMPARISON","BASELINE")) {
                var response=new CandidateReviewEngine.VerificationResult(java.util.List.of());
                var row=rows.addObject();row.put("caseId",t.id());row.put("repeat",repeat);row.put("arm",arm);row.put("labelStatus",t.labelStatus());
                row.set("expectedDecision",JSON.valueToTree(t.expectedDecision()));row.set("assessment",JSON.valueToTree(UtteranceDirectionTrial.assess(t,response)));
                var call=row.putArray("calls").addObject();call.put("system",arm.equals("BASELINE")?t.baselineSystem():t.comparisonSystem());
                call.set("input",JSON.readTree(t.input()));call.putNull("failureCode");call.set("output",JSON.valueToTree(response));
            }
        return new Source(bytes,JSON.writeValueAsBytes(saved));
    }

    @Test void changesExactlyOneCandidateFieldWithoutMutatingOriginalOrLeakingDecisionLabels() {
        var original=JSON.createObjectNode();original.putArray("raw").addObject().put("text","원문 속 같은 가설 문장");
        original.put("other","원문 속 같은 가설 문장");
        var candidate=original.putArray("candidates").addObject();candidate.put("hypothesisNotEvidence","원문 속 같은 가설 문장");
        candidate.putArray("proposedEvidence").addObject().put("segmentId","s");var before=original.deepCopy();
        var changed=JSON.readTree(NeutralHypothesisComparison.input(original.toString()));
        assertThat(changed.at("/candidates/0/hypothesisNotEvidence").asText()).isEqualTo(NeutralHypothesisComparison.NEUTRAL);
        ((tools.jackson.databind.node.ObjectNode)changed.path("candidates").get(0)).put("hypothesisNotEvidence","원문 속 같은 가설 문장");
        assertThat(changed).isEqualTo(original);assertThat(original).isEqualTo(before);
        assertThat(NeutralHypothesisComparison.NEUTRAL).doesNotContain("PASS","REVIEW_REQUIRED","정상","성적");
        assertThatThrownBy(() -> NeutralHypothesisComparison.input("{\"candidates\":[]}")).hasMessageContaining("One frozen");
        assertThatThrownBy(() -> NeutralHypothesisComparison.input("{\"candidates\":[{\"hypothesisNotEvidence\":\"\"}]}")).hasMessageContaining("non-neutral");
    }

    @Test void systemsAreIdenticalAndInputsDifferOnlyInHypothesisForAllFourteenConditions() throws Exception {
        var s=source();var plan=NeutralHypothesisComparison.prepare(s.plan(),s.results());
        assertThat(plan.tasks()).hasSize(14);assertThat(plan.repeats()*plan.tasks().size()*2).isEqualTo(56);
        assertThat(plan.tasks().stream().filter(t -> t.expectedDecision()!=null).count()).isEqualTo(8);
        assertThat(UtteranceDirectionTrial.reservationEstimate(plan)).isPositive().isLessThan(.50);
        for(var t:plan.tasks()) {
            assertThat(t.baselineSystem()).isEqualTo(t.comparisonSystem());
            var before=JSON.readTree(UtteranceDirectionTrial.requestInput(plan,t,false));
            var after=JSON.readTree(UtteranceDirectionTrial.requestInput(plan,t,true));
            assertThat(after).isNotEqualTo(before);
            ((tools.jackson.databind.node.ObjectNode)after.path("candidates").get(0))
                    .set("hypothesisNotEvidence",before.at("/candidates/0/hypothesisNotEvidence"));
            assertThat(after).isEqualTo(before);
        }
    }

    @Test void priorConsentChangedSourceAndSystemEditsAreRejected() throws Exception {
        var s=source();var plan=NeutralHypothesisComparison.prepare(s.plan(),s.results());
        UtteranceDirectionTrial.consent(plan,"run-neutral-hypothesis",NeutralHypothesisComparison.CONSENT);
        assertThatThrownBy(() -> UtteranceDirectionTrial.consent(plan,"run-neutral-hypothesis",DirectTargetWritingComparison.CONSENT))
                .hasMessageContaining("fresh cost/text");
        assertThatThrownBy(() -> UtteranceDirectionTrial.consent(plan,"run-direct-writing",NeutralHypothesisComparison.CONSENT))
                .hasMessageContaining("fresh cost/text");
        var results=(tools.jackson.databind.node.ObjectNode)JSON.readTree(s.results());results.put("planSha256","0".repeat(64));
        assertThatThrownBy(() -> NeutralHypothesisComparison.prepare(s.plan(),JSON.writeValueAsBytes(results))).hasMessageContaining("source/result hash");
        var tree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(plan);
        ((tools.jackson.databind.node.ObjectNode)tree.path("tasks").get(0)).put("comparisonSystem","changed policy");
        var changed=JSON.treeToValue(tree,UtteranceDirectionTrial.Plan.class);
        assertThatThrownBy(() -> UtteranceDirectionTrial.validate(changed)).hasMessageContaining("Comparison changes only");
    }
}
