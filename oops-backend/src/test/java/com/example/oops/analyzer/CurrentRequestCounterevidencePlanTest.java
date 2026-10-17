package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class CurrentRequestCounterevidencePlanTest {
    record Fixture(DefaultVerificationPlan.Plan source,tools.jackson.databind.node.ArrayNode inputs,
                   tools.jackson.databind.node.ArrayNode labels) {}
    Fixture fixture() throws Exception {
        var source=new DefaultVerificationPlanTest().plan();var inputs=JSON.createArrayNode();var labels=JSON.createArrayNode();
        for(String id:CurrentRequestCounterevidencePlan.IDS) {
            var r=source.requests().stream().filter(t -> t.caseId().equals(CurrentRequestCounterevidencePlan.SOURCES.get(id))
                    && t.arm().equals("KEPT") && t.repeat()==1).findFirst().orElseThrow();
            var input=JSON.readTree(r.input());var data=inputs.addObject();data.put("caseId",id);
            data.put("anchorId",input.at("/candidates/0/anchorId").asText());
            data.set("raw",CurrentRequestCounterevidencePlan.projected(input.path("raw")));
            labels.addObject().put("caseId",id).put("expectedDecision",Set.of("R04","R08").contains(id)?"REVIEW_REQUIRED":"PASS");
        }
        return new Fixture(source,inputs,labels);
    }
    @Test void replacesOneInterpretationParagraphWithoutChangingPolicyOutputOrReferences() {
        String before=CandidateReviewEngine.verificationPrompt(0)+"\nfrozen references";
        String after=CurrentRequestCounterevidencePlan.comparison(before);
        assertThat(after.replace(CurrentRequestCounterevidencePlan.NEW,CurrentRequestCounterevidencePlan.OLD)).isEqualTo(before);
        assertThat(after).contains("뜻풀이 해석과 구별되는", "뒤의 다른 행위만으로 앞 발언", "자동 PASS 처리하지 않으며");
        assertThat(after).doesNotContain("오펜무", "팬티", "groq-v4", "sourceUnderstanding", "reviewBasis");
        assertThat(after).isNotEqualTo(SituationFirstVerification.prompt(before));
        assertThatThrownBy(() -> CurrentRequestCounterevidencePlan.comparison(after)).hasMessageContaining("original interpretation");
    }
    @Test void freezesOnlyEightApprovedConditionsKeepingInputAndReferencesIdenticalAcrossArms() throws Exception {
        var f=fixture();var p=CurrentRequestCounterevidencePlan.build(f.source(),f.inputs(),f.labels(),"source","inputs","labels");
        assertThat(p.requests()).hasSize(48);assertThat(p.freshCostAndTextApprovalRequired()).isTrue();
        assertThat(p.reservationEstimatedUsd()).isLessThan(.50);assertThat(p.independentGold()).isFalse();
        for(int i=0;i<48;i+=2) {
            var a=p.requests().get(i);var b=p.requests().get(i+1);
            assertThat(a.caseId()).isEqualTo(b.caseId());assertThat(a.input()).isEqualTo(b.input());
            var base=a.arm().equals("BASELINE")?a:b;var modified=a.arm().equals("BASELINE")?b:a;
            assertThat(modified.system()).isEqualTo(CurrentRequestCounterevidencePlan.comparison(base.system()));
            assertThat(JSON.readTree(a.input()).at("/candidates/0").has("hypothesisNotEvidence")).isTrue();
            assertThat(JSON.readTree(a.input()).has("expectedDecision")).isFalse();
            assertThat(a.arm()).isEqualTo(a.repeat()%2==1?"BASELINE":"COUNTEREVIDENCE");
        }
        assertThat(p.requests().stream().filter(r -> "REVIEW_REQUIRED".equals(r.expectedDecision())).count()).isEqualTo(12);
    }
    @Test void newOfferWindowIsExpandedExplicitlyWithoutChangingAnchorOrProposedEvidence() throws Exception {
        var f=fixture();var r=f.source().requests().stream().filter(t -> t.caseId().equals("groq-v4-178")
                && t.arm().equals("KEPT") && t.repeat()==1).findFirst().orElseThrow();
        var old=JSON.readTree(r.input());var full=old.path("raw").deepCopy();
        ((tools.jackson.databind.node.ArrayNode)full).addObject().put("id","context-extra").put("text","extra synthetic context")
                .put("startMs",4000).put("endMs",5000);
        var updated=JSON.readTree(CurrentRequestCounterevidencePlan.fixedInput(r,full,full,true));
        assertThat(updated.at("/candidates/0/proposedEvidence")).isEqualTo(old.at("/candidates/0/proposedEvidence"));
        assertThat(updated.at("/candidates/0/anchorId")).isEqualTo(old.at("/candidates/0/anchorId"));
        assertThat(updated.path("raw")).isEqualTo(full);
        assertThat(updated.at("/candidates/0/segmentIds").toString()).contains("context-extra");
        assertThatThrownBy(() -> CurrentRequestCounterevidencePlan.fixedInput(r,full,full,false)).hasMessageContaining("approved full window");
    }
    @Test void changedLabelOrRawCannotBeEvaluatedAsFrozenApprovedInput() throws Exception {
        var f=fixture();((tools.jackson.databind.node.ObjectNode)f.labels().get(0)).put("expectedDecision","REVIEW_REQUIRED");
        assertThatThrownBy(() -> CurrentRequestCounterevidencePlan.build(f.source(),f.inputs(),f.labels(),"s","i","l"))
                .hasMessageContaining("Approved decision");
        var changed=fixture();((tools.jackson.databind.node.ObjectNode)changed.inputs().get(0).path("raw").get(0)).put("text","changed");
        assertThatThrownBy(() -> CurrentRequestCounterevidencePlan.build(changed.source(),changed.inputs(),changed.labels(),"s","i","l"))
                .hasMessageContaining("approved full window");
        assertThatThrownBy(() -> CurrentRequestCounterevidencePlan.prepare(new byte[0],new byte[0],new byte[0]))
                .hasMessageContaining("Frozen approved sources");
    }
}
