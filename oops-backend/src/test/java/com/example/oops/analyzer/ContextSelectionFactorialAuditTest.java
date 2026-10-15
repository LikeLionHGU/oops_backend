package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** Synthetic source responses; no provider calls and no semantic-label accuracy assertions. */
class ContextSelectionFactorialAuditTest {
    record Fixture(ContextRoutingProbePlan.Plan plan,String sha,tools.jackson.databind.JsonNode saved) {}
    Fixture fixture() throws Exception {
        var f=new ContextExampleSelectionTest(); var archive=new ContextRelationRetrievalTest().archive();
        List<ContextRetrievalContrastAudit.Probe> probes=new ArrayList<>();
        for(int pair=0;pair<4;pair++) for(String kind:ContextRetrievalContrastAudit.KINDS.stream().sorted().toList())
            probes.add(new ContextRetrievalContrastAudit.Probe("p"+pair+"-"+kind,"pair-"+pair,kind,"source-0","graphic-body-metaphor",
                    "current-1",f.query("무관한 새로운 대화"),new SourceRelations(List.of(),List.of(),List.of(),List.of("current-1"),List.of())));
        String registry=JSON.writeValueAsString(Map.of("schemaVersion","context-routing-keys-1","keys",List.of(
                new ContextMechanismRouter.Key("graphic-body-metaphor","신체 이미지 검색"),
                new ContextMechanismRouter.Key("local-life-devaluation","지역 생활 선택 검색"))));
        var probePlan=new ContextRetrievalContrastAudit.Plan("context-retrieval-probes-1","DRAFT",false,false,probes);
        var p=ContextRoutingProbePlan.prepare(JSON.writeValueAsBytes(probePlan),JSON.writeValueAsBytes(archive),registry.getBytes(StandardCharsets.UTF_8));
        String sha=ReferenceOnlyTrial.sha(JSON.writeValueAsBytes(p)); var library=f.library(archive,2,3000,"");
        var byProbe=new HashMap<String,ContextRetrievalContrastAudit.Probe>(); probes.forEach(q -> byProbe.put(q.id(),q));
        var response=new ContextMechanismRouter.Response(List.of(new ContextMechanismRouter.Route("graphic-body-metaphor",List.of("current-1"))));
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var task:p.tasks()) rows.add(Map.of("requestKey",task.requestKey(),"probeId",task.probeId(),"kind",byProbe.get(task.probeId()).kind(),
                "repeat",task.repeat(),"inputSha256",task.inputSha256(),"usage",Map.of("calls",1),"response",response,
                "outcome",ContextRoutingProbePlan.replay(byProbe.get(task.probeId()),library,registry,response)));
        return new Fixture(p,sha,JSON.valueToTree(Map.of("schemaVersion",ContextRoutingProbeExecution.SCHEMA,"state","COMPLETE",
                "planSha256",sha,"httpAttempts",48,"results",rows)));
    }
    @Test void reproducesAllBaselineRowsBeforeProducingExactlyFourArms() throws Exception {
        var f=fixture(); var analysis=ContextSelectionFactorialAudit.audit(f.plan(),f.sha(),f.saved());
        assertThat(analysis.rows()).hasSize(192); assertThat(analysis.summary()).hasSize(16);
        assertThat(analysis.rows().stream().filter(r -> r.arm().equals("S2_NEWKEY_ON")))
                .allMatch(r -> !r.referenceChanged() && r.payloadDeltaFromBaseline()==0 && r.addedExamples().isEmpty() && r.removedExamples().isEmpty());
        assertThat(analysis.summary()).allMatch(s -> s.requests()==12 && s.expectedSelected()==12);
    }
    @Test void refusesChangedBaselineRatherThanCallingItAnExperimentImprovement() throws Exception {
        var f=fixture();
        var outcome=(tools.jackson.databind.node.ObjectNode)f.saved().path("results").get(0).path("outcome");
        outcome.put("afterSelectedRank",99);
        assertThatThrownBy(() -> ContextSelectionFactorialAudit.audit(f.plan(),f.sha(),f.saved()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reproduce");
    }
    @Test void refusesDuplicateSourceRequestsAndDifferentPlanHash() throws Exception {
        var f=fixture(); var rows=(tools.jackson.databind.node.ArrayNode)f.saved().path("results"); rows.set(1,rows.get(0).deepCopy());
        assertThatThrownBy(() -> ContextSelectionFactorialAudit.audit(f.plan(),f.sha(),f.saved())).isInstanceOf(IllegalArgumentException.class);
        var clean=fixture();
        assertThatThrownBy(() -> ContextSelectionFactorialAudit.audit(clean.plan(),"a".repeat(64),clean.saved())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void countAndCoverageRewardHaveIndependentObservableEffectsAndDefaultIsUnchanged() {
        var f=new ContextReferenceBalanceTest();
        var matches=List.of(f.match(0,5,"a"),f.match(1,5,"a"),f.match(2,5,"b")); var hints=Set.of("a","b");
        var defaults=ContextReferenceBalance.select(matches,hints,2,false,items -> true);
        assertThat(ContextReferenceBalance.select(matches,hints,2,false,items -> true,true)).isEqualTo(defaults);
        assertThat(defaults.selected()).extracting(ReviewGuidelineLibrary.ContextExample::id).containsExactly("example-0","example-2");
        var off=ContextReferenceBalance.select(matches,hints,2,false,items -> true,false);
        assertThat(off.selected()).extracting(ReviewGuidelineLibrary.ContextExample::id).containsExactly("example-0","example-1");
        assertThat(off.trace().uncoveredKeys()).containsExactly("b");
        assertThat(off.trace().choices()).allMatch(c -> c.newKeyBonus()==0 && c.hintBonus()==.15);
        assertThat(ContextReferenceBalance.select(matches,hints,3,false,items -> true,true).selected())
                .extracting(ReviewGuidelineLibrary.ContextExample::id).containsExactly("example-0","example-2","example-1");
        assertThat(ContextReferenceBalance.select(matches,hints,3,false,items -> true,false).selected())
                .extracting(ReviewGuidelineLibrary.ContextExample::id).containsExactly("example-0","example-1","example-2");
    }
    @Test void thirdSlotCannotBypassWholeExampleSizeBudgetInEitherBonusArm() {
        var f=new ContextReferenceBalanceTest();
        var matches=List.of(f.match(0,5,"a"),f.match(1,5,"a"),f.match(2,5,"b"));
        for(boolean reward:List.of(true,false)) {
            var p=ContextReferenceBalance.select(matches,Set.of("a","b"),3,false,items -> items.size()<=2,reward);
            assertThat(p.selected()).hasSize(2); assertThat(p.trace().sizeOmissions()).isEqualTo(1);
            assertThat(p.trace().omissions()).allMatch(o -> o.reason().equals("SIZE_LIMIT"));
        }
    }
}
