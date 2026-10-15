package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ContextRetrievalContrastAudit.*;
import static org.assertj.core.api.Assertions.*;

class ContextRetrievalContrastAuditTest {
    private final ContextExampleSelectionTest fixture=new ContextExampleSelectionTest();
    private final ReviewGuidelineLibrary.Archive before=new ContextUnitRoutingTest().units();
    private final ReviewGuidelineLibrary.Archive after=new ContextRelationRetrievalTest().archive();
    private Probe probe(String kind) {
        String text=kind.startsWith("NEAR")?"검색용으로 고정한 별도 문구":"화창한 해변에서 조개를 관찰했어요";
        return new Probe(kind,"pair",kind,"source-0","graphic-body-metaphor","current-1",fixture.query(text),
                new SourceRelations(List.of(),List.of(),List.of(),List.of("current-1"),List.of()));
    }
    private Plan plan() {
        return new Plan("context-retrieval-probes-1","DRAFT",false,false,
                List.of(probe("NEAR_REVIEW"),probe("PARAPHRASE_REVIEW"),probe("NEAR_NORMAL"),probe("PARAPHRASE_NORMAL")));
    }
    @Test void separatesEligibilityAndSelectionAndMarksManualKeyAsControl() throws Exception {
        var rows=evaluate(plan(),before,after,fixture.library(before,2,3000,""),fixture.library(after,2,3000,""));
        assertThat(rows).hasSize(16);
        for(var r:rows) if(r.kind().startsWith("PARAPHRASE")) {
            if(r.arm().equals("V7_MANUAL_KEY_AND_ROLES")) {
                assertThat(r.eligibleFullWindowRank()).isPositive(); assertThat(r.selectedRank()).isPositive();
            } else {
                assertThat(r.expectedTrigramHits()).isLessThan(3);
                assertThat(r.stage()).isEqualTo("NOT_ELIGIBLE");
            }
        }
        assertThat(summarize(rows).stream().filter(s -> s.kind().equals("NEAR_NORMAL") && s.arm().equals("V6_LEXICAL"))
                .findFirst().orElseThrow().selected()).isEqualTo(1); // Normal reference presence is not a verdict failure.
        assertThat(rows.stream().map(Row::inputSha256)).allMatch(s -> s.matches("[0-9a-f]{64}"));
    }
    @Test void reportsEligibleButBudgetOmittedSeparatelyFromRetrievalMiss() throws Exception {
        var rows=evaluate(plan(),before,after,fixture.library(before,2,256,""),fixture.library(after,2,256,""));
        assertThat(rows.stream().filter(r -> r.kind().startsWith("NEAR")))
                .allMatch(r -> r.stage().equals("ELIGIBLE_NOT_SELECTED") && r.eligibleFullWindowRank()>0 && r.selectedRank()==0);
    }
    @Test void rejectsIncompletePairsApprovalPromotionAndUnknownTargets() {
        var p=plan();
        assertThatThrownBy(() -> validate(new Plan(p.schemaVersion(),p.status(),true,false,p.probes()),after)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(new Plan(p.schemaVersion(),p.status(),false,true,p.probes()),after)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(new Plan(p.schemaVersion(),p.status(),false,false,p.probes().subList(0,3)),after)).isInstanceOf(IllegalArgumentException.class);
        var q=p.probes().get(0);
        var bad=new Probe(q.id(),q.pairId(),q.kind(),"missing",q.manualKey(),q.anchorId(),q.raw(),q.roles());
        var modified=new ArrayList<>(p.probes()); modified.set(0,bad);
        assertThatThrownBy(() -> validate(new Plan(p.schemaVersion(),p.status(),false,false,modified),after)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnknownRoleIdsRatherThanInventingSourceText() {
        var p=plan(); var q=p.probes().get(0);
        var bad=new Probe(q.id(),q.pairId(),q.kind(),q.expectedExampleId(),q.manualKey(),q.anchorId(),q.raw(),
                new SourceRelations(List.of("invented"),List.of(),List.of(),List.of(q.anchorId()),List.of()));
        var modified=new ArrayList<>(p.probes()); modified.set(0,bad);
        assertThatThrownBy(() -> validate(new Plan(p.schemaVersion(),p.status(),false,false,modified),after)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsContentChangesBetweenArchives() {
        var p=plan(); var bad=fixture.archive(2);
        assertThatThrownBy(() -> evaluate(p,bad,after,fixture.library(bad,2,3000,""),fixture.library(after,2,3000,"")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
