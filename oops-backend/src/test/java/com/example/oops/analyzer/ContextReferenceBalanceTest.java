package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.*;

/** Heuristic ranking contracts only; these authored scores are not model accuracy measurements. */
class ContextReferenceBalanceTest {
    ContextExample example(int id,String... keys) {
        var e = new RoutingSelectionPriorityTest().units().examples().get(id%3);
        return new ContextExample("example-"+id,e.familyId(),e.mechanismIds(),e.channels(),e.context(),e.retrievalView(),List.of(keys));
    }
    ContextExampleSelector.Match match(int id,double lexical,String... keys) {
        return new ContextExampleSelector.Match(example(id,keys),lexical,keys.length>0);
    }
    @Test void strongLexicalMatchCanBeatAnUnrelatedRoutingHint() {
        var p = ContextReferenceBalance.select(List.of(match(0,0,"a"),match(1,9)),Set.of("a"),1,true,items -> true);
        assertThat(p.selected()).extracting(ContextExample::id).containsExactly("example-1");
        assertThat(p.trace().uncoveredKeys()).containsExactly("a");
        assertThat(p.trace().countOmissions()).isOne();
        assertThat(p.trace().choices()).singleElement().satisfies(c -> {
            assertThat(c.hintBonus()).isZero(); assertThat(c.selectionScore()).isCloseTo(.9,within(.00001));
        });
    }
    @Test void equallyRelevantExamplesPreferAnAdditionalMechanismOverADuplicate() {
        var p = ContextReferenceBalance.select(List.of(match(0,5,"a"),match(1,5,"a"),match(2,5,"b")),
                Set.of("a","b"),2,false,items -> true);
        assertThat(p.selected()).extracting(ContextExample::id).containsExactly("example-0","example-2");
        assertThat(p.trace().selectedKeys()).containsExactly("a","b");
        assertThat(p.trace().uncoveredKeys()).isEmpty();
    }
    @Test void mechanismCoverageDoesNotForceAWeakExampleOverMuchStrongerEvidence() {
        var p = ContextReferenceBalance.select(List.of(match(0,9,"a"),match(1,8,"a"),match(2,0,"b")),
                Set.of("a","b"),2,false,items -> true);
        assertThat(p.selected()).extracting(ContextExample::id).containsExactly("example-0","example-1");
        assertThat(p.trace().uncoveredKeys()).containsExactly("b");
    }
    @Test void sizeFailurePreservesCompleteExamplesAndReportsMissingCoverage() {
        var large = match(0,9,"a"); var small = match(1,2);
        var p = ContextReferenceBalance.select(List.of(large,small),Set.of("a"),2,true,
                items -> items.stream().noneMatch(e -> e.id().equals("example-0")));
        assertThat(p.selected()).containsExactly(small.example());
        assertThat(p.trace().sizeOmissions()).isOne();
        assertThat(p.trace().uncoveredKeys()).containsExactly("a");
        assertThat(p.trace().omissions()).containsExactly(new ContextReferenceBalance.Omission("example-0","SIZE_LIMIT"));
        assertThat(JSON.writeValueAsString(p.trace())).doesNotContain("검색용","공공시설","생활조건");
    }
    @Test void zeroLimitHasBoundedOmissionDiagnosticsAndDeterministicCoverage() {
        var matches = java.util.stream.IntStream.range(0,30).mapToObj(i -> match(i,1,"a")).toList();
        var p = ContextReferenceBalance.select(matches,Set.of("a"),0,true,items -> true);
        assertThat(p.selected()).isEmpty(); assertThat(p.trace().countOmissions()).isEqualTo(30);
        assertThat(p.trace().omissions()).hasSize(20); assertThat(p.trace().omissionsTruncated()).isTrue();
        assertThat(p).isEqualTo(ContextReferenceBalance.select(matches,Set.of("a"),0,true,items -> true));
    }
    @Test void sameExampleAcrossFocusAndContextUsesItsBestLexicalScoreWithoutDuplicatingIt() {
        var weak = match(0,0,"a"); var strong = new ContextExampleSelector.Match(weak.example(),9,true);
        var p = ContextReferenceBalance.select(List.of(weak,match(1,2),strong),Set.of("a"),2,true,items -> true);
        assertThat(p.selected()).extracting(ContextExample::id).containsExactly("example-0","example-1");
        assertThat(p.trace().choices().get(0).lexicalComponent()).isCloseTo(.9,within(.00001));
    }
    @Test void actualV6LibraryCanSelectAnExactLexicalReferenceOverAWrongHint() {
        var a = new RoutingSelectionPriorityTest().units(); var f = new ContextExampleSelectionTest();
        String query = "음식의 맛과 가격을 평가하는 개인 취향의 구체적인 비교 맥락";
        var examples = new ArrayList<>(a.examples());
        for (int i=0;i<examples.size();i++) {
            var e = examples.get(i); String text = i==0?"전혀 무관한 신체 이미지":query;
            examples.set(i,new ContextExample(e.id(),e.familyId(),e.mechanismIds(),e.channels(),e.context(),
                    new RetrievalView("context-unit-retrieval-1","AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE",text,retrievalHash(text)),e.routingKeys()));
        }
        var archive = new Archive(a.schemaVersion(),a.version(),a.status(),false,a.usage(),a.sourceBundleSha256(),
                a.refreshOrDeleteBy(),a.sourceCases(),a.guidelines(),a.contextDictionarySha256(),examples);
        var selection = f.library(archive,1,3000,"").select(com.example.oops.domain.TimelineEventType.SPEECH,
                f.query(query),"q",List.of(),Set.of("mechanism-a"));
        assertThat(selection.trace().exampleIds()).containsExactly("source-1");
        assertThat(selection.trace().balance().uncoveredKeys()).containsExactly("mechanism-a");
        assertThat(selection.trace().balance().choices().get(0).hintBonus()).isZero();
        assertThat(selection.prompt()).doesNotContain("selectionScore","uncoveredKeys");
    }
}
