package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Authored routing tests; a supplied hint is NOT evidence of successful model classification. */
class ContextUnitRoutingTest {
    Archive units() {
        var source = new ContextExampleSelectionTest().archive(2);
        var examples = source.examples().stream().map(e -> {
            String text = "검색용으로 고정한 별도 문구";
            return new ContextExample(e.id(), e.familyId(), e.mechanismIds(), e.channels(),
                    new Comparison(e.context().coverage(), e.context().flow(), e.context().criticismHypotheses(),
                            e.context().missingContext(), e.context().sourceInterpretations(), List.of("합성 대조이며 실제 관측 아님")),
                    new RetrievalView("context-unit-retrieval-1", "AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE", text, retrievalHash(text)),
                    List.of(e.id().equals("source-0") ? "graphic-body-metaphor" : "local-life-devaluation"));
        }).toList();
        return new Archive("review-guidelines-6", "synthetic-units", source.status(), false, source.usage(),
                source.sourceBundleSha256(), source.refreshOrDeleteBy(), source.sourceCases(), source.guidelines(),
                source.contextDictionarySha256(), examples);
    }
    @Test void suppliedMechanismHintRecallsDifferentWordsWithoutHardFilteringOrBypassingExclusion() {
        var a = units(); var f = new ContextExampleSelectionTest();
        var raw = f.query("완전히 다른 이야기");
        var l = f.library(a, 2, 3000, "");
        assertThat(l.select(TimelineEventType.SPEECH, raw, "q").trace().exampleIds()).isEmpty();
        var routed = l.select(TimelineEventType.SPEECH, raw, "q", List.of(), Set.of("graphic-body-metaphor"));
        assertThat(routed.trace().exampleIds()).containsExactly("source-0");
        assertThat(routed.trace().retrievalMethod()).contains("NOT_CLASSIFICATION");
        assertThat(routed.prompt()).contains("normalContrasts", "합성 대조이며 실제 관측 아님");
        assertThat(l.select(TimelineEventType.SPEECH, List.of(), "q", List.of(), Set.of("graphic-body-metaphor"))
                .trace().exampleIds()).isEmpty();
        assertThat(f.library(a, 2, 3000, "family-0").select(TimelineEventType.SPEECH, raw, "q", List.of(),
                Set.of("graphic-body-metaphor")).trace().exampleIds()).isEmpty();
        var lexical = f.query("검색용으로 고정한 별도 문구");
        assertThat(l.select(TimelineEventType.SPEECH, lexical, "q", List.of(), Set.of("graphic-body-metaphor"))
                .trace().exampleIds()).containsExactly("source-0", "source-1"); // Nonmatching mechanism remains eligible.
        assertThat(l.select(TimelineEventType.SPEECH, lexical, "q", List.of(), Set.of("unknown")))
                .isEqualTo(l.select(TimelineEventType.SPEECH, lexical, "q"));
    }
    @Test void semanticExplanationEditsCannotChangeIndependentSearchTerms() {
        var a = units(); var f = new ContextExampleSelectionTest(); var raw = f.query("검색용으로 고정한 별도 문구");
        var oldRank = new ContextExampleSelector(a.examples(), a.guidelines()).rank(TimelineEventType.SPEECH, raw, Set.of());
        var changed = a.examples().stream().map(e -> new ContextExample(e.id(), e.familyId(), e.mechanismIds(), e.channels(),
                new Comparison(e.context().coverage(), List.of("전혀 다른 판단 설명"), List.of("새 비판 가설"),
                        e.context().missingContext(), e.context().sourceInterpretations(), e.context().normalContrasts()),
                e.retrievalView(), e.routingKeys())).toList();
        var newRank = new ContextExampleSelector(changed, a.guidelines()).rank(TimelineEventType.SPEECH, raw, Set.of());
        assertThat(newRank.stream().map(ContextExampleSelector.Match::score).toList())
                .isEqualTo(oldRank.stream().map(ContextExampleSelector.Match::score).toList());
        assertThat(newRank.stream().map(m -> m.example().id()).toList())
                .isEqualTo(oldRank.stream().map(m -> m.example().id()).toList());
    }
    @Test void rejectsBadRetrievalHashAndHonorsWholeExampleAndCountLimits() {
        var a = units(); var f = new ContextExampleSelectionTest();
        var raw = f.query("검색어가 겹치지 않는 무관한 대화");
        assertThat(f.library(a, 0, 3000, "").select(TimelineEventType.SPEECH, raw, "q", List.of(),
                Set.of("graphic-body-metaphor")).trace().exampleIds()).isEmpty();
        assertThat(f.library(a, 2, 256, "").select(TimelineEventType.SPEECH, raw, "q", List.of(),
                Set.of("graphic-body-metaphor")).trace().exampleIds()).isEmpty();
        var tree = JSON.valueToTree(a);
        ((tools.jackson.databind.node.ObjectNode) tree.path("examples").get(0).path("retrievalView")).put("sha256", "a".repeat(64));
        var bad = JSON.treeToValue(tree, Archive.class);
        assertThat(f.library(bad, 2, 3000, "").trace(TimelineEventType.SPEECH).state()).isEqualTo("INVALID_ARCHIVE");
    }
    @Test void oldSchemaPayloadDoesNotGainNullUnitFields() {
        var f = new ContextExampleSelectionTest();
        var l = f.library(f.archive(2), 2, 3000, "");
        assertThat(l.select(TimelineEventType.SPEECH, f.query("공공시설이 없는 생활조건을 열등하게 비교한다"), "q").prompt())
                .doesNotContain("normalContrasts", "routingKeys");
    }
    @Test void explicitUnitArchiveUsesCandidateSelectionBeforeFreezingButLegacyDoesNot() {
        var f = new ContextExampleSelectionTest();
        for (var archive : List.of(units(), f.archive(2))) {
            var library = spy(f.library(archive, 2, 3000, ""));
            var context = new CandidateReviewEngineTest().context("검색용으로 고정한 별도 문구", "후속 설명");
            var spec = new ContextPolicyTrial.Spec("synthetic", 0, 0, "TARGET_TREATMENT", List.of(0, 1),
                    "SYNTHETIC_PROPOSED_LABEL_NOT_GOLD", null);
            ContextPolicyTrial.capture(spec, context, library);
            if (archive.schemaVersion().equals("review-guidelines-6")) {
                verify(library).select(eq(TimelineEventType.SPEECH), anyCollection(), startsWith("unit-"), anyCollection());
            } else {
                verify(library, never()).select(eq(TimelineEventType.SPEECH), anyCollection(), startsWith("unit-"), anyCollection());
            }
        }
    }
}
