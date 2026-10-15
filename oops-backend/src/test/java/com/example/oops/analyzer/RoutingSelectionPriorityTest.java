package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.*;

/** At equal lexical relevance, distinct routed mechanisms are not displaced by family diversity. */
class RoutingSelectionPriorityTest {
    Archive units() {
        var f = new ContextExampleSelectionTest(); var a = f.archive(3);
        String retrieval = "검색용 고정 문구의 서로 다른 논란 흐름";
        List<SourceCase> sources = new ArrayList<>(); List<ContextExample> examples = new ArrayList<>();
        for (int i=0; i<3; i++) {
            String family = i<2 ? "same-incident" : "other-incident";
            var e = a.examples().get(i);
            sources.add(new SourceCase(e.id(),family,"UNREVIEWED_DRAFT"));
            var c = e.context();
            examples.add(new ContextExample(e.id(),family,e.mechanismIds(),e.channels(),
                    new Comparison(c.coverage(),c.flow(),c.criticismHypotheses(),c.missingContext(),c.sourceInterpretations(),List.of()),
                    new RetrievalView("context-unit-retrieval-1","AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE",retrieval,retrievalHash(retrieval)),
                    i==0 ? List.of("mechanism-a") : i==1 ? List.of("mechanism-b") : List.of()));
        }
        return new Archive("review-guidelines-6",a.version(),a.status(),false,a.usage(),a.sourceBundleSha256(),
                a.refreshOrDeleteBy(),sources,a.guidelines(),a.contextDictionarySha256(),examples);
    }
    @Test void equallyRelevantSecondRoutedMechanismWinsOverUnroutedDifferentFamily() {
        var f = new ContextExampleSelectionTest(); var units = units();
        String retrieval = units.examples().get(0).retrievalView().text();
        var examples = units.examples();
        var library = f.library(units,2,3000,""); var raw = f.query(retrieval);
        assertThat(library.contextUnitArchive()).isTrue();
        var keys = Set.of("mechanism-a","mechanism-b");
        var ranked = new ContextExampleSelector(examples,units.guidelines()).rank(TimelineEventType.SPEECH,raw,Set.of(),keys);
        assertThat(ranked.subList(0,2)).allMatch(ContextExampleSelector.Match::routed);
        assertThat(ranked.stream().map(m -> m.example().id())).containsExactly("source-0","source-1","source-2");
        // Previously [source-0, source-2]: discovery's family diversity displaced routed source-1.
        assertThat(library.select(TimelineEventType.SPEECH,raw,"discovery",List.of(),keys).trace().exampleIds())
                .containsExactly("source-0","source-1");
        // Candidate-focused selection does not apply discovery's family-diversity pass.
        assertThat(library.select(TimelineEventType.SPEECH,raw,"candidate",raw,keys).trace().exampleIds())
                .containsExactly("source-0","source-1");
    }
    @Test void emptyUnknownOrExcludedHintsKeepTheHistoricalLexicalSelection() {
        var f = new ContextExampleSelectionTest(); var a = units();
        var raw = f.query(a.examples().get(0).retrievalView().text());
        var library = f.library(a,2,3000,"");
        var baseline = library.select(TimelineEventType.SPEECH,raw,"q");
        assertThat(baseline.trace().exampleIds()).containsExactly("source-0","source-2");
        assertThat(library.select(TimelineEventType.SPEECH,raw,"q",List.of(),Set.of("unknown"))).isEqualTo(baseline);
        var excluded = f.library(a,2,3000,"same-incident");
        assertThat(excluded.select(TimelineEventType.SPEECH,raw,"q",List.of(),Set.of("mechanism-a","mechanism-b")))
                .isEqualTo(excluded.select(TimelineEventType.SPEECH,raw,"q"));
        assertThat(excluded.select(TimelineEventType.SPEECH,raw,"q").trace().exampleIds()).containsExactly("source-2");
    }
    @Test void remainingSlotStillAcceptsUnroutedLexicalExamplesAndOneSlotStaysBounded() {
        var f = new ContextExampleSelectionTest(); var a = units();
        var raw = f.query(a.examples().get(0).retrievalView().text());
        assertThat(f.library(a,2,3000,"").select(TimelineEventType.SPEECH,raw,"q",List.of(),Set.of("mechanism-a"))
                .trace().exampleIds()).containsExactly("source-0","source-2");
        assertThat(f.library(a,1,3000,"").select(TimelineEventType.SPEECH,raw,"q",List.of(),Set.of("mechanism-b"))
                .trace().exampleIds()).containsExactly("source-1");
        assertThat(f.library(a,0,3000,"").select(TimelineEventType.SPEECH,raw,"q",List.of(),Set.of("mechanism-b"))
                .trace().exampleIds()).isEmpty();
        assertThat(f.library(a,2,256,"").select(TimelineEventType.SPEECH,raw,"q",List.of(),Set.of("mechanism-b"))
                .trace().exampleIds()).isEmpty();
    }
    @Test void oversizedRoutedExampleCannotBlockASmallerRoutedOrLexicalFallback() {
        var f = new ContextExampleSelectionTest(); var a = units();
        var examples = new ArrayList<>(a.examples()); var e = examples.get(0); var c = e.context();
        examples.set(0,new ContextExample(e.id(),e.familyId(),e.mechanismIds(),e.channels(),
                new Comparison(c.coverage(),List.of("가".repeat(350),"나".repeat(350),"다".repeat(350)),
                        c.criticismHypotheses(),c.missingContext(),c.sourceInterpretations(),List.of()),e.retrievalView(),e.routingKeys()));
        var large = new Archive(a.schemaVersion(),a.version(),a.status(),false,a.usage(),a.sourceBundleSha256(),
                a.refreshOrDeleteBy(),a.sourceCases(),a.guidelines(),a.contextDictionarySha256(),examples);
        var library = f.library(large,2,1000,"");
        assertThat(library.contextUnitArchive()).isTrue();
        var selection = library.select(TimelineEventType.SPEECH,f.query(e.retrievalView().text()),"q",List.of(),Set.of("mechanism-a","mechanism-b"));
        assertThat(selection.trace().exampleIds()).containsExactly("source-1","source-2");
        assertThat(selection.trace().budgetSkipped()).isOne();
        assertThat(selection.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
    }
}
