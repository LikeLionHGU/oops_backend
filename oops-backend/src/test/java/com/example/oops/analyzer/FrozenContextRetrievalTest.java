package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static com.example.oops.domain.TimelineEventType.SPEECH;
import static org.assertj.core.api.Assertions.*;

class FrozenContextRetrievalTest {
    private final ContextExampleSelectionTest fixture = new ContextExampleSelectionTest();
    private Archive frozen(Archive a) {
        Map<String,String> conditions = new HashMap<>();
        a.guidelines().forEach(r -> conditions.put(r.id(), r.condition()));
        var examples = a.examples().stream().map(e -> {
            String text = String.join(" ", e.context().flow()) + " "
                    + String.join(" ", e.context().criticismHypotheses()) + " "
                    + e.mechanismIds().stream().map(conditions::get).reduce("", (x,y) -> x + " " + y);
            return new ContextExample(e.id(), e.familyId(), e.mechanismIds(), e.channels(), e.context(),
                    new RetrievalView("frozen-legacy-lexical-1", "FROZEN_LEGACY_NOT_SOURCE_EVIDENCE", text, retrievalHash(text)));
        }).toList();
        return with(a, "review-guidelines-5", examples);
    }
    private Archive with(Archive a, String schema, List<ContextExample> examples) {
        return new Archive(schema, a.version(), a.status(), false, a.usage(), a.sourceBundleSha256(),
                a.refreshOrDeleteBy(), a.sourceCases(), a.guidelines(), a.contextDictionarySha256(), examples);
    }
    @Test void migratedIndexPreservesExactLegacyRanksAndScores() {
        var a = fixture.archive(3); var b = frozen(a);
        var raw = fixture.query("공공시설이 없는 생활조건을 열등하게 비교한다");
        var old = new ContextExampleSelector(a.examples(), a.guidelines()).rank(SPEECH, raw, Set.of());
        var updated = new ContextExampleSelector(b.examples(), b.guidelines()).rank(SPEECH, raw, Set.of());
        assertThat(updated.stream().map(m -> m.example().id()).toList())
                .isEqualTo(old.stream().map(m -> m.example().id()).toList());
        assertThat(updated.stream().map(ContextExampleSelector.Match::score).toList())
                .isEqualTo(old.stream().map(ContextExampleSelector.Match::score).toList());
    }
    @Test void explanationChangesOnlyTheJudgmentPayloadNotRanking() {
        var a = frozen(fixture.archive(2)); var e = a.examples().get(0); var c = e.context();
        var changed = new ContextExample(e.id(), e.familyId(), e.mechanismIds(), e.channels(),
                new Comparison(c.coverage(), c.flow(), List.of("새 판단 가설 설명"), c.missingContext(), c.sourceInterpretations()), e.retrievalView());
        var b = with(a, a.schemaVersion(), List.of(changed, a.examples().get(1)));
        var raw = fixture.query("공공시설이 없는 생활조건");
        var x = fixture.library(a, 2, 3000, "").select(SPEECH, raw, "query");
        var y = fixture.library(b, 2, 3000, "").select(SPEECH, raw, "query");
        assertThat(y.trace().exampleIds()).isEqualTo(x.trace().exampleIds());
        assertThat(y.prompt()).contains("새 판단 가설 설명").doesNotContain("retrievalView", "FROZEN_LEGACY_NOT_SOURCE_EVIDENCE");
        assertThat(x.prompt()).doesNotContain("새 판단 가설 설명");
    }
    @Test void invalidOrMissingFrozenViewDoesNotFallBackToMutableExplanation() {
        var a = frozen(fixture.archive(2)); var e = a.examples().get(0);
        for (var view : Arrays.asList((RetrievalView)null,
                new RetrievalView("frozen-legacy-lexical-1", "FROZEN_LEGACY_NOT_SOURCE_EVIDENCE", "tampered", e.retrievalView().sha256()),
                new RetrievalView("unknown", "FROZEN_LEGACY_NOT_SOURCE_EVIDENCE", e.retrievalView().text(), e.retrievalView().sha256()))) {
            var bad = new ContextExample(e.id(), e.familyId(), e.mechanismIds(), e.channels(), e.context(), view);
            assertThat(fixture.library(with(a,a.schemaVersion(),List.of(bad,a.examples().get(1))),2,3000,"")
                    .trace(SPEECH).state()).isEqualTo("INVALID_ARCHIVE");
        }
    }
    @Test void legacyArchiveCannotSilentlyOptIntoFrozenRouting() {
        var a = frozen(fixture.archive(2));
        assertThat(fixture.library(with(a,"review-guidelines-4",a.examples()),2,3000,"")
                .trace(SPEECH).state()).isEqualTo("INVALID_ARCHIVE");
    }
    @Test void excludedFamilyStillCannotBeSelected() {
        var a = frozen(fixture.archive(2));
        var selected = fixture.library(a,2,3000,"family-0").select(SPEECH,
                fixture.query("공공시설이 없는 생활조건"),"query");
        assertThat(selected.trace().exampleIds()).doesNotContain("source-0");
    }
    @Test void mutableRuleConditionCannotChangeFrozenRanks() {
        var a = frozen(fixture.archive(2)); var r = a.guidelines().get(0);
        var changed = new Rule(r.id(),r.axis(),r.channels(),"새로운 판단 조건 문구",r.normalContrast(),
                r.requiredEvidence(),r.missingContext(),r.sourceCaseIds());
        var raw = fixture.query("공공시설이 없는 생활조건");
        var old = new ContextExampleSelector(a.examples(),a.guidelines()).rank(SPEECH,raw,Set.of());
        var updated = new ContextExampleSelector(a.examples(),List.of(changed)).rank(SPEECH,raw,Set.of());
        assertThat(updated).isEqualTo(old);
    }
}
