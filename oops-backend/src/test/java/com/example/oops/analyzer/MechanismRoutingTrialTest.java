package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static com.example.oops.analyzer.MechanismRoutingTrial.*;
import static org.assertj.core.api.Assertions.*;

class MechanismRoutingTrialTest {
    Plan plan() throws Exception {
        var tasks = IDS.stream().map(id -> Map.of("spec",Map.of("id",id),"input",JSON.writeValueAsString(Map.of(
                "raw",List.of(Map.of("id","stt-index-9","text","합성 검증 대화","startMs",1000,"endMs",2500)),
                "candidates",List.of(Map.of("forbiddenFixedCandidate",true)))))).toList();
        return prepare(JSON.writeValueAsBytes(Map.of("tasks",tasks)),
                JSON.writeValueAsBytes(new ContextUnitRoutingTest().units()),
                JSON.writeValueAsBytes(Map.of("schemaVersion","context-routing-keys-1","keys",List.of(
                        new ContextMechanismRouter.Key("graphic-body-metaphor","신체 비유 검색")))));
    }
    @Test void planPreservesSourceIdsButDoesNotReuseFixedCandidatesOrTextGoldForBanter() throws Exception {
        var p = plan();
        assertThat(p.conditions()).hasSize(4);
        assertThat(p.conditions().get(0).raw().get(0).id()).isEqualTo("stt-index-9");
        assertThat(context(p.conditions().get(0)).reviewInput().segments()).isEqualTo(p.conditions().get(0).raw());
        assertThat(p.conditions().get(3).labelStatus()).contains("UNREVIEWED");
        assertThat(JSON.writeValueAsString(p)).doesNotContain("forbiddenFixedCandidate","proposedEvidence");
    }
    @Test void snapshotTamperingIsRejectedBeforeAnyProviderRequest() throws Exception {
        var p = plan();
        var modified = new Plan(p.schemaVersion(),p.scope(),p.repeats(),p.approvedEstimatedUsd(),p.model(),p.sourcePlanSha256(),
                p.runtimeText()+" ",p.runtimeSha256(),p.registryText(),p.registrySha256(),p.conditions());
        assertThatThrownBy(() -> validate(modified)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hash");
    }
    @Test void networkBoundingPreservesRouting1024AndCapsOtherCallsAt8000() {
        for (int limit : List.of(1024,8000,16000)) {
            var body = JSON.writeValueAsBytes(Map.of("max_completion_tokens",limit,"model","gpt-6-luna"));
            assertThat(JSON.readTree(boundedBody(body)).path("max_completion_tokens").asInt()).isEqualTo(Math.min(limit,8000));
        }
        assertThat(JSON.readTree(boundedBody(JSON.writeValueAsBytes(Map.of("model","gpt-6-luna")))).path("max_completion_tokens").asInt())
                .isEqualTo(8000);
    }
    @Test void routingLibraryUsesExactlyTheFrozenV6Text() throws Exception {
        var p = plan(); var library = library(p);
        assertThat(library.contextUnitArchive()).isTrue();
        assertThat(library.routingKeys()).containsExactlyInAnyOrder("graphic-body-metaphor","local-life-devaluation");
        assertThatThrownBy(() -> resources(p.runtimeText()).getResource("file:/unexpected.json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
