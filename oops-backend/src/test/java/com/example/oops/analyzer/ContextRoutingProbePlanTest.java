package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.oops.analyzer.ContextRetrievalContrastAudit.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** All responses are scripted: tests verify contracts, never claim measured model accuracy. */
class ContextRoutingProbePlanTest {
    private final ContextExampleSelectionTest f=new ContextExampleSelectionTest();
    private final ReviewGuidelineLibrary.Archive archive=new ContextRelationRetrievalTest().archive();
    private final String registry=JSON.writeValueAsString(Map.of("schemaVersion","context-routing-keys-1","keys",List.of(
            new ContextMechanismRouter.Key("graphic-body-metaphor","신체 이미지 검색"),
            new ContextMechanismRouter.Key("local-life-devaluation","지역 생활 선택 검색"))));
    private Probe probe(String kind) {
        return new Probe(kind,"pair",kind,"source-0","graphic-body-metaphor","current-1",f.query("완전히 새로운 표현"),
                new SourceRelations(List.of(),List.of(),List.of(),List.of("current-1"),List.of()));
    }
    private ContextRoutingProbePlan.Plan prepare() throws Exception {
        var probes=new ContextRetrievalContrastAudit.Plan("context-retrieval-probes-1","DRAFT",false,false,
                List.of(probe("NEAR_REVIEW"),probe("PARAPHRASE_REVIEW"),probe("NEAR_NORMAL"),probe("PARAPHRASE_NORMAL")));
        return ContextRoutingProbePlan.prepare(JSON.writeValueAsBytes(probes),JSON.writeValueAsBytes(archive),registry.getBytes(StandardCharsets.UTF_8));
    }
    @Test void freezesActualLogicalRouterInputWithoutExpectedKeysLabelsRolesOrCaseIds() throws Exception {
        var p=prepare(); ContextRoutingProbePlan.validate(p);
        assertThat(p.authorizationState()).isEqualTo(ContextRoutingProbePlan.AUTH);
        assertThat(p.tasks()).hasSize(12);
        assertThat(p.tasks().stream().map(ContextRoutingProbePlan.Task::requestKey).distinct()).hasSize(12);
        for(var task:p.tasks()) {
            var input=JSON.readTree(task.input());
            assertThat(input.size()).isEqualTo(2); assertThat(input.has("keys") && input.has("raw")).isTrue();
            assertThat(input.get("keys").size()).isEqualTo(2); // Entire available registry, not the oracle answer only.
            assertThat(input.get("raw").get(0).path("text").asText()).isEqualTo("완전히 새로운 표현");
            assertThat(task.input()).doesNotContain("NEAR_REVIEW","NORMAL","manualKey","pairId","expectedExampleId","roles","source-0");
            assertThat(task.system()).isEqualTo(ContextMechanismRouter.PROMPT);
        }
    }
    @Test void rejectsApprovalPromotionAndRehashedAnswerLeakInFrozenRequest() throws Exception {
        var p=prepare(); var tree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p);
        tree.put("authorizationState","APPROVED");
        var promoted=JSON.treeToValue(tree,ContextRoutingProbePlan.Plan.class);
        assertThatThrownBy(() -> ContextRoutingProbePlan.validate(promoted))
                .isInstanceOf(IllegalArgumentException.class);
        tree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p);
        var task=(tools.jackson.databind.node.ObjectNode)tree.path("tasks").get(0);
        var input=(tools.jackson.databind.node.ObjectNode)JSON.readTree(task.path("input").asText());
        input.put("manualKey","graphic-body-metaphor"); String changed=JSON.writeValueAsString(input);
        task.put("input",changed); task.put("inputSha256",ReviewGuidelineLibrary.retrievalHash(changed));
        var modified=JSON.treeToValue(tree,ContextRoutingProbePlan.Plan.class);
        assertThatThrownBy(() -> ContextRoutingProbePlan.validate(modified)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void separatesKeyProposalPassingAndReferenceRecoveryWithoutAWarningDecision() {
        var response=new ContextMechanismRouter.Response(List.of(new ContextMechanismRouter.Route("graphic-body-metaphor",List.of("current-1"))));
        for(String kind:List.of("PARAPHRASE_REVIEW","PARAPHRASE_NORMAL")) {
            var result=ContextRoutingProbePlan.replay(probe(kind),f.library(archive,2,3000,""),registry,response);
            assertThat(result.router().state()).isEqualTo("ROUTED");
            assertThat(result.expectedKeyProposed() && result.expectedKeyPassed()).isTrue();
            assertThat(result.beforeSelectedRank()).isZero(); assertThat(result.afterSelectedRank()).isEqualTo(1);
        }
    }
    @Test void unknownIdsEmptyAndMissingResponsesKeepOriginalReferences() {
        for(var response:Arrays.asList(null,new ContextMechanismRouter.Response(List.of()),
                new ContextMechanismRouter.Response(List.of(new ContextMechanismRouter.Route("graphic-body-metaphor",List.of("invented")))))) {
            var result=ContextRoutingProbePlan.replay(probe("PARAPHRASE_REVIEW"),f.library(archive,2,3000,""),registry,response);
            assertThat(result.expectedKeyProposed() || result.expectedKeyPassed()).isFalse();
            assertThat(result.afterSelection().exampleIds()).isEqualTo(result.beforeSelection().exampleIds());
            assertThat(result.router().state()).isIn("EMPTY","NO_RESPONSE","INVALID_RESPONSE");
        }
    }
    @Test void validKeyOnUnrelatedLineIsNotPassedToTheControlledCandidate() {
        var q=probe("PARAPHRASE_REVIEW"); var raw=new ArrayList<>(q.raw());
        raw.add(new ReviewInput.Segment("noise",com.example.oops.domain.TimelineEventType.SPEECH,3000,4000,"별개 화제",null));
        var mixed=new Probe(q.id(),q.pairId(),q.kind(),q.expectedExampleId(),q.manualKey(),q.anchorId(),raw,q.roles());
        var response=new ContextMechanismRouter.Response(List.of(new ContextMechanismRouter.Route(q.manualKey(),List.of("noise"))));
        var result=ContextRoutingProbePlan.replay(mixed,f.library(archive,2,3000,""),registry,response);
        assertThat(result.expectedKeyProposed()).isTrue(); assertThat(result.expectedKeyPassed()).isFalse();
        assertThat(result.link().droppedKeys()).containsExactly(q.manualKey());
        assertThat(result.afterSelectedRank()).isZero();
    }
    @Test void freezesSourceHashesAndRejectsMissingRequiredRegistryKey() throws Exception {
        var p=prepare(); var tree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p);
        tree.put("runtimeSha256","a".repeat(64)); var changed=JSON.treeToValue(tree,ContextRoutingProbePlan.Plan.class);
        assertThatThrownBy(() -> ContextRoutingProbePlan.validate(changed)).isInstanceOf(IllegalArgumentException.class);
        String onlyOther=JSON.writeValueAsString(Map.of("schemaVersion","context-routing-keys-1","keys",List.of(
                new ContextMechanismRouter.Key("local-life-devaluation","지역 생활 선택 검색"))));
        assertThatThrownBy(() -> ContextRoutingProbePlan.prepare(p.probesText().getBytes(StandardCharsets.UTF_8),
                p.runtimeText().getBytes(StandardCharsets.UTF_8),onlyOther.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
