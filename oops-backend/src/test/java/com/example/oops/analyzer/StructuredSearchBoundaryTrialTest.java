package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.domain.TimelineEventType.SPEECH;
import static org.assertj.core.api.Assertions.*;

class StructuredSearchBoundaryTrialTest {
    @TempDir Path temporary;
    byte[] fixture() throws Exception {
        try(var in=getClass().getResourceAsStream("/structured-search-boundaries.json")) { return Objects.requireNonNull(in).readAllBytes(); }
    }
    StructuredSearchBoundaryTrial.Plan plan() throws Exception {
        var raw=List.of(new ReviewInput.Segment("stored-1",SPEECH,0,1500,"합성 저장 입력",null));
        var cases=List.of("v4-intimate-question-groq","v6-group-generalization-groq").stream()
                .map(id -> Map.of("id",id,"labelStatus","INDEPENDENT_REVIEW_PROVISIONAL_NOT_GOLD","source","groq","raw",raw)).toList();
        return StructuredSearchBoundaryTrial.prepare(JSON.writeValueAsBytes(Map.of("schemaVersion","groq-pipeline-audit-input-1","cases",cases)),
                JSON.writeValueAsBytes(new ContextRelationRetrievalTest().archive()),fixture());
    }
    @Test void freezesEightConditionsWithoutPromotingDraftsOrChangingRuntime() throws Exception {
        var p=plan(); StructuredSearchBoundaryTrial.validate(p);
        assertThat(p.conditions()).hasSize(8); assertThat(p.repeats()*2*p.conditions().size()).isEqualTo(32);
        assertThat(p.conditions().stream().filter(c -> c.id().startsWith("normal-")).toList()).hasSize(6)
                .allSatisfy(c -> { assertThat(c.expectedDecision()).isEqualTo("PASS"); assertThat(c.labelStatus()).contains("DRAFT","NOT_HUMAN_GOLD"); });
        assertThat(StructuredSearchBoundaryTrial.library(p,false).trace(SPEECH)).isEqualTo(StructuredSearchBoundaryTrial.library(p,true).trace(SPEECH));
    }
    @Test void metadataDoesNotEnterRawModelInputAndAllNewDraftsExerciseConceptSearch() throws Exception {
        var p=plan(); var search=StructuredContextSearch.load();
        for(var c:p.conditions()) {
            var context=MechanismRoutingTrial.context(new MechanismRoutingTrial.Condition(c.id(),c.labelStatus(),c.raw()));
            String input=JSON.writeValueAsString(context.reviewInput());
            assertThat(input).doesNotContain("expectedDecision","labelStatus","SYNTHETIC_DRAFT",c.boundary());
            if(c.id().startsWith("normal-")) assertThat(search.queryProfiles(SPEECH,c.raw())).as(c.id()).isNotEmpty();
        }
    }
    @Test void counterbalancesArmOrderAndRejectsChangedSnapshot() throws Exception {
        assertThat(StructuredSearchBoundaryTrial.arms(1)).containsExactly("OFF","ON");
        assertThat(StructuredSearchBoundaryTrial.arms(2)).containsExactly("ON","OFF");
        var tree=(tools.jackson.databind.node.ObjectNode)JSON.readTree(JSON.writeValueAsString(plan()));
        tree.put("searchCatalogSha256","a".repeat(64));
        assertThatThrownBy(() -> StructuredSearchBoundaryTrial.validate(JSON.readValue(tree.toString(),StructuredSearchBoundaryTrial.Plan.class)))
                .hasMessageContaining("Frozen inputs/contracts changed");
    }
    @Test void requestAndCostCapsStopBeforeTheUnapprovedAttempt() throws Exception {
        var budget=new StructuredSearchBoundaryTrial.Budget(temporary);
        for(int i=0;i<160;i++) budget.reserve(0);
        assertThatThrownBy(() -> budget.reserve(0)).hasMessageContaining("Reservation exhausted");
        assertThat(budget.attempts).isEqualTo(160); assertThat(budget.reserved).isLessThanOrEqualTo(1);
        assertThat(budget.exhausted).isTrue();
        var expensive=new StructuredSearchBoundaryTrial.Budget(temporary);
        assertThatThrownBy(() -> expensive.reserve(10_000_000)).hasMessageContaining("Reservation exhausted");
        assertThat(expensive.attempts).isZero();
    }
}
