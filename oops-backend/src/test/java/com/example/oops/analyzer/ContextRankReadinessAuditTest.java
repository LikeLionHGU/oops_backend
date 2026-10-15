package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class ContextRankReadinessAuditTest {
    @Test void tieMemberOrderIsNotAnArtificialRankChange() {
        var a=new ContextSemanticRankPreparation.Response(false,List.of(List.of("r2","r1"),List.of("r3")));
        var b=new ContextSemanticRankPreparation.Response(false,List.of(List.of("r1","r2"),List.of("r3")));
        assertThat(ContextRankReadinessAudit.canonical(a)).isEqualTo(ContextRankReadinessAudit.canonical(b));
        assertThat(ContextRankReadinessAudit.canonical(a)).isNotEqualTo(ContextRankReadinessAudit.canonical(
                new ContextSemanticRankPreparation.Response(false,List.of(List.of("r1"),List.of("r2","r3")))));
    }
    @Test void doesNotBackfillMissingProfilesOrInjectTargetKeys() {
        var f=new ContextExampleSelectionTest(); var archive=f.archive(3); var library=f.library(archive,3,3000,"");
        var raw=List.of(Map.of("id","original-7","startMs",123,"endMs",456,"text","공공시설이 없는 생활조건을 열등하게 비교한다"));
        var call=Map.of("input",Map.of("raw",raw),"output",Map.of("candidates",List.of()));
        var saved=JSON.valueToTree(Map.of("results",List.of(Map.of("caseId","positive","repeat",1,"calls",List.of(call)),
                Map.of("caseId","positive","repeat",2,"calls",List.of(call)))));
        var rows=ContextRankReadinessAudit.retrieval(saved,archive,library);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("rankReadiness","UNPROFILED_SELECTED_REFERENCE");
        assertThat((List<?>)rows.get(0).get("comparisonInputs")).isEmpty();
        assertThat(rows.get(0).get("savedRaw")).isEqualTo(JSON.valueToTree(raw));
        assertThat(((ReviewGuidelineLibrary.SelectionTrace)rows.get(0).get("selection")).exampleIds()).hasSize(3);
    }
    @Test void preparedInputsKeepRawTextButExcludeTimesLabelsAndRealCaseIds() {
        var f=new ContextExampleSelectionTest(); var archive=new ContextRelationRetrievalTest().archive();
        var raw=List.of(Map.of("id","original-7","startMs",123,"endMs",456,"text","검색용으로 고정한 별도 문구"));
        var saved=JSON.valueToTree(Map.of("results",List.of(Map.of("caseId","private-label","repeat",1,"calls",List.of(
                Map.of("input",Map.of("raw",raw),"output",Map.of("candidates",List.of())))))));
        var rows=ContextRankReadinessAudit.retrieval(saved,archive,f.library(archive,3,3000,""));
        var row=rows.get(0); assertThat(row).containsEntry("rankReadiness","READY_SELECTED_POOL_ONLY");
        var input=JSON.valueToTree(row.get("comparisonInputs")); assertThat(input.size()).isEqualTo(2);
        assertThat(input.get(0).path("raw")).isEqualTo(input.get(1).path("raw"));
        assertThat(input.get(0).path("raw").get(0).path("text").asText()).isEqualTo(raw.get(0).get("text"));
        assertThat(input.toString()).doesNotContain("startMs","endMs","private-label","source-0","expected","routingKeys");
    }
    @Test void incompletePaidResultsAreRejectedRatherThanScoredAsSuccess() {
        var plan=new ContextSemanticRankPreparation.Plan("context-semantic-rank-plan-1","unused","gpt-6-luna","","","","",List.of());
        assertThatThrownBy(() -> ContextRankReadinessAudit.pairs(plan,"hash",JSON.valueToTree(Map.of("state","RUNNING"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
