package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.*;

/** Reproduces an observed limitation, NOT the desired future selection contract. Update when fixed. */
class RoutingSelectionLimitationTest {
    @Test void discoveryFamilyDiversityCurrentlyDisplacesASecondRoutedMechanismFromTheSameIncident() {
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
        var units = new Archive("review-guidelines-6",a.version(),a.status(),false,a.usage(),a.sourceBundleSha256(),
                a.refreshOrDeleteBy(),sources,a.guidelines(),a.contextDictionarySha256(),examples);
        var library = f.library(units,2,3000,""); var raw = f.query(retrieval);
        assertThat(library.contextUnitArchive()).isTrue();
        var keys = Set.of("mechanism-a","mechanism-b");
        var ranked = new ContextExampleSelector(examples,units.guidelines()).rank(TimelineEventType.SPEECH,raw,Set.of(),keys);
        assertThat(ranked.subList(0,2)).allMatch(ContextExampleSelector.Match::routed);
        assertThat(ranked.stream().map(m -> m.example().id())).containsExactly("source-0","source-1","source-2");
        // Known defect: diversity fills slot 2 with an un-routed example before the second pass.
        assertThat(library.select(TimelineEventType.SPEECH,raw,"discovery",List.of(),keys).trace().exampleIds())
                .containsExactly("source-0","source-2");
        // Candidate-focused selection does not apply discovery's family-diversity pass.
        assertThat(library.select(TimelineEventType.SPEECH,raw,"candidate",raw,keys).trace().exampleIds())
                .containsExactly("source-0","source-1");
    }
}
