package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.oops.analyzer.ContextSemanticRankPreparation.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;

class ReferenceBudgetAndRankTest {
    @Test void countIsConfigurableBeyondThreeButWholeExampleBudgetsStillApply() {
        var f=new ContextExampleSelectionTest(); var a=f.archive(5);
        var raw=f.query("공공시설이 없는 생활조건을 열등하게 비교한다");
        assertThat(f.library(a,4,3000,"").select(com.example.oops.domain.TimelineEventType.SPEECH,raw,"q").trace().exampleIds()).hasSize(4);
        var bounded=f.library(a,32,256,"").select(com.example.oops.domain.TimelineEventType.SPEECH,raw,"q");
        assertThat(bounded.trace().exampleIds()).hasSize(1); assertThat(bounded.trace().budgetSkipped()).isPositive();
        String contexts=JSON.writeValueAsString(ReferenceOnlyTrial.reference(bounded.prompt()).path("referenceContexts"));
        assertThat(contexts.codePointCount(0,contexts.length())).isLessThanOrEqualTo(256);
        assertThat(f.library(a,0,3000,"").select(com.example.oops.domain.TimelineEventType.SPEECH,raw,"q").trace().exampleIds()).isEmpty();
        assertThatThrownBy(() -> f.library(a,33,3000,"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.library(a,-1,3000,"")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void springDefaultIsThreeWhileHistoricalExplicitConstructorRemainsCompatible() throws Exception {
        var constructor=Arrays.stream(ReviewGuidelineLibrary.class.getConstructors())
                .filter(c -> c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)).findFirst().orElseThrow();
        var value=constructor.getParameters()[4].getAnnotation(org.springframework.beans.factory.annotation.Value.class);
        assertThat(value.value()).isEqualTo("${oops.analysis.guidelines-example-max-cases:3}");
        assertThat(constructor.getParameters()[7].getAnnotation(org.springframework.beans.factory.annotation.Value.class).value())
                .isEqualTo("${oops.analysis.structured-context-search-enabled:true}");
        try(var input=getClass().getResourceAsStream("/application.yml")) {
            assertThat(new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))
                    .contains("guidelines-example-max-cases: ${REVIEW_GUIDELINES_MAX_CASES:3}");
        }
    }
    @Test void rankingAllowsHonestTiesOrAbstentionWithoutManufacturingAStrictWinner() {
        var allowed=Set.of("r1","r2","r3");
        assertThat(failure(new Response(false,List.of(List.of("r2"),List.of("r1","r3"))),allowed)).isNull();
        assertThat(failure(new Response(false,List.of(List.of("r1","r2","r3"))),allowed)).isNull();
        assertThat(failure(new Response(true,List.of()),allowed)).isNull();
        assertThat(failure(new Response(true,List.of(List.of("r1"))),allowed)).isEqualTo("ABSTAIN_WITH_RANKS");
    }
    @Test void rankingRejectsUnknownDuplicateOmittedIdsAndMissingFields() {
        var allowed=Set.of("r1","r2");
        assertThat(failure(new Response(false,List.of(List.of("unknown"),List.of("r2"))),allowed)).isEqualTo("UNKNOWN_OR_DUPLICATE_RANK_ID");
        assertThat(failure(new Response(false,List.of(List.of("r1"),List.of("r1"))),allowed)).isEqualTo("UNKNOWN_OR_DUPLICATE_RANK_ID");
        assertThat(failure(new Response(false,List.of(List.of("r1"))),allowed)).isEqualTo("INCOMPLETE_RANKING");
        assertThat(failure(new Response(null,List.of()),allowed)).isEqualTo("RANK_FIELDS");
        assertThat(failure(null,allowed)).isEqualTo("NO_RESPONSE");
    }
    @Test void preparationHidesGroundTruthCaseIdsAndControlsPositionBias() throws Exception {
        var fixture=new ContextSelectionFactorialAuditTest().fixture();
        var analysis=ContextSelectionFactorialAudit.audit(fixture.plan(),fixture.sha(),fixture.saved());
        // Synthetic saved selections contain one reference, so no extra ranking call is prepared.
        assertThat(tasks(fixture.plan(),analysis)).isEmpty();
        var row=analysis.rows().stream().filter(r -> r.arm().equals("S3_NEWKEY_ON")).findFirst().orElseThrow();
        var selection=row.selection();
        var two=new ReviewGuidelineLibrary.SelectionTrace(selection.requestKey(),selection.state(),selection.retrievalMethod(),
                selection.availableExamples(),2,0,selection.payloadCodePoints(),selection.baseCodePoints(),List.of("source-0","source-1"),selection.mechanismIds(),null);
        var controlled=new ContextSelectionFactorialAudit.Row(row.requestKey(),row.probeId(),row.kind(),row.repeat(),row.arm(),1,2,
                row.payloadCodePoints(),0,false,List.of(),List.of(),two);
        var work=tasks(fixture.plan(),new ContextSelectionFactorialAudit.Analysis(List.of(controlled),List.of()));
        assertThat(work).hasSize(4);
        for(var task:work) {
            var input=JSON.readTree(task.input()); assertThat(input.size()).isEqualTo(2);
            assertThat(task.input()).doesNotContain("source-0","source-1","expectedTarget","NEAR_NORMAL","SOURCE","normalContrasts","sourceSha256");
            assertThat(input.get("profiles").size()).isEqualTo(2);
            assertThat(task.inputSha256()).isEqualTo(ReviewGuidelineLibrary.retrievalHash(task.input()));
        }
        var forward=JSON.readTree(work.get(0).input()).path("profiles"); var reverse=JSON.readTree(work.get(1).input()).path("profiles");
        assertThat(forward.get(0)).isEqualTo(reverse.get(1)); assertThat(work.get(0).localIdMap()).isEqualTo(work.get(1).localIdMap());
        assertThat(work.get(2).expectedTargetForDevelopmentOnly()).isNull();
    }
}
