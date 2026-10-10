package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic retrieval/contract tests, not claims of semantic recall or model accuracy. */
class ContextExampleSelectionTest {
    @Test void nearbyContextRoutesBeforeFarWindowWhenAnchorHasNoLexicalMatch() {
        var a = new CandidateReferenceRepairTest().distinctExamples();
        var l = library(a, 1, 3000, "");
        var anchor = new ReviewInput.Segment("anchor", TimelineEventType.SPEECH, 20000, 21500, "이걸로 대신하는 거야", null);
        var nearby = new ReviewInput.Segment("near", TimelineEventType.SPEECH, 18000, 19500, "공공시설이 없는 생활조건", null);
        var distant = new ReviewInput.Segment("far", TimelineEventType.SPEECH, 65000, 66500, "메뉴 선택권을 무시하는 설명", null);
        var selection = l.select(TimelineEventType.SPEECH, List.of(nearby, anchor, distant), "candidate", List.of(anchor));
        assertThat(selection.trace().exampleIds()).containsExactly("source-0");
        assertThat(selection.prompt()).doesNotContain("메뉴 선택권을 무시하는 설명");
    }
    @Test void contextualMatchGetsASlotBeforeSecondFocusedMatchEvenInSameFamily() {
        var a = archive(3);
        String focusedText = "음식메뉴 선택권의 의미를 검토한다";
        String surrounding = "공공시설의 부재와 생활조건의 제약을 말하고 지역주민의 대체선택을 설명하는 연결을 확인한다";
        List<String> texts = List.of(focusedText, surrounding, focusedText + " 별개의 상품리뷰 해석");
        var sources = a.sourceCases().stream().map(s -> new SourceCase(s.caseId(), "same-family", s.status())).toList();
        List<ContextExample> examples = new ArrayList<>();
        for (int i = 0; i < 3; i++) examples.add(new ContextExample("source-" + i, "same-family", List.of("mechanism-1"),
                List.of("SPEECH", "CAPTION"), new Comparison("REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT",
                List.of(texts.get(i)), List.of(), List.of("원문 미확보"), List.of())));
        var custom = new Archive(a.schemaVersion(), a.version(), a.status(), false, a.usage(), a.sourceBundleSha256(),
                a.refreshOrDeleteBy(), sources, a.guidelines(), a.contextDictionarySha256(), examples);
        var l = library(custom, 2, 3000, "");
        var raw = query(focusedText + " " + surrounding);
        var selection = l.select(TimelineEventType.SPEECH, raw, "candidate", query(focusedText));
        assertThat(selection.trace().exampleIds()).containsExactly("source-0", "source-1");
        assertThat(selection.trace().retrievalMethod()).isEqualTo("CANDIDATE_NEAR_CONTEXT_INTERLEAVED_LOCAL_NOT_SEMANTIC");
        assertThat(l.select(TimelineEventType.SPEECH, raw, "candidate", query(focusedText))).isEqualTo(selection);
        assertThat(library(custom, 1, 3000, "").select(TimelineEventType.SPEECH, raw, "candidate", query(focusedText))
                .trace().exampleIds()).containsExactly("source-0");
        assertThat(library(custom, 2, 3000, "same-family").select(TimelineEventType.SPEECH, raw, "candidate", query(focusedText))
                .trace().exampleIds()).isEmpty();
    }
    Archive archive(int count) {
        var f = new ReviewGuidelineLibraryTest(); var a = f.archive();
        List<SourceCase> sources = new ArrayList<>(); List<ContextExample> examples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String cid = "source-" + i, family = "family-" + (i % 2);
            sources.add(new SourceCase(cid, family, "UNREVIEWED_DRAFT"));
            examples.add(new ContextExample(cid, family, List.of("mechanism-1"), List.of("SPEECH", "CAPTION"),
                    new Comparison("REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT", List.of("공공시설이 없다는 설명", "생활조건을 열등하게 비교"),
                            List.of("생활조건을 낮추는 연결이라는 비판"), List.of("원영상 미확보"), List.of())));
        }
        var r = a.guidelines().get(0);
        var rule = new Rule(r.id(), r.axis(), r.channels(), r.condition(), r.normalContrast(), r.requiredEvidence(),
                r.missingContext(), sources.stream().map(SourceCase::caseId).toList());
        return new Archive("review-guidelines-4", "synthetic-patterns", a.status(), false, a.usage(),
                a.sourceBundleSha256(), a.refreshOrDeleteBy(), sources, List.of(rule), "c".repeat(64), examples);
    }
    ReviewGuidelineLibrary library(Archive a, int max, int exampleBudget, String excluded) {
        var resources = mock(ResourceLoader.class);
        when(resources.getResource("classpath:test.json")).thenReturn(new ByteArrayResource(JSON.writeValueAsString(a).getBytes(StandardCharsets.UTF_8)));
        var l = new ReviewGuidelineLibrary(resources, true, "classpath:test.json", 6000, max, exampleBudget, excluded);
        l.load(); return l;
    }
    List<ReviewInput.Segment> query(String text) {
        return List.of(new ReviewInput.Segment("current-1", TimelineEventType.SPEECH, 0, 1500, text, null));
    }
    @Test void unrelatedOrEmptyQueryStillReceivesAllPatternsWithoutExamples() {
        var l = library(archive(4), 2, 3000, "");
        for (var raw : List.of(query("오늘 바닷물 온도를 측정했다"), List.<ReviewInput.Segment>of())) {
            var s = l.select(TimelineEventType.SPEECH, raw, "discovery-1");
            assertThat(s.prompt()).contains("mechanism-1", "단순 불만 제외").doesNotContain("공공시설이 없다는 설명");
            assertThat(s.trace().exampleIds()).isEmpty();
        }
    }
    @Test void boundedDeterministicReferencesHideCaseIdsAndPreferFamilyDiversity() {
        var l = library(archive(100), 2, 3000, ""); var raw = query("공공시설이 없는 생활조건을 열등하게 비교한다");
        var s = l.select(TimelineEventType.SPEECH, raw, "discovery-1");
        assertThat(s.trace().exampleIds()).containsExactly("source-0", "source-1");
        assertThat(s.trace().matchedExamples()).isEqualTo(100);
        assertThat(s.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
        assertThat(s.prompt()).contains("공공시설이 없다는 설명").doesNotContain("source-0", "family-0", "current-1");
        assertThat(l.select(TimelineEventType.SPEECH, raw, "discovery-1")).isEqualTo(s);
        assertThat(library(archive(2), 2, 3000, "").select(TimelineEventType.SPEECH, raw, "discovery-1").trace().payloadCodePoints())
                .isEqualTo(s.trace().payloadCodePoints());
    }
    @Test void exclusionZeroLimitAndWholeExampleBudgetDoNotRemovePatterns() {
        var raw = query("공공시설이 없는 생활조건을 열등하게 비교한다");
        var excluded = library(archive(4), 2, 3000, "family-0").select(TimelineEventType.SPEECH, raw, "v");
        assertThat(excluded.trace().exampleIds()).containsExactly("source-1", "source-3");
        var a = archive(4);
        var large = a.examples().stream().map(e -> new ContextExample(e.id(), e.familyId(), e.mechanismIds(), e.channels(),
                new Comparison(e.context().coverage(), List.of("가".repeat(350)), e.context().criticismHypotheses(),
                        e.context().missingContext(), e.context().sourceInterpretations()))).toList();
        var oversized = new Archive(a.schemaVersion(), a.version(), a.status(), false, a.usage(), a.sourceBundleSha256(),
                a.refreshOrDeleteBy(), a.sourceCases(), a.guidelines(), a.contextDictionarySha256(), large);
        for (var l : List.of(library(archive(4), 0, 3000, ""), library(oversized, 2, 256, ""))) {
            var s = l.select(TimelineEventType.SPEECH, raw, "v");
            assertThat(s.trace().exampleIds()).isEmpty();
            assertThat(s.prompt()).contains("mechanism-1").doesNotContain("공공시설이 없다는 설명");
        }
    }
    @Test void backgroundOcrCannotRouteReferenceExamples() {
        var l = library(archive(2), 2, 3000, "");
        var background = new ReviewInput.Segment("ocr-1", TimelineEventType.CAPTION, 0, 1500,
                "공공시설이 없는 생활조건을 열등하게 비교한다", .9, com.example.oops.domain.ScreenTextRole.BACKGROUND);
        assertThat(l.select(TimelineEventType.CAPTION, List.of(background), "ocr").trace().exampleIds()).isEmpty();
    }
    @Test void sourceOrPatternMappingMismatchRejectsArchive() {
        var a = archive(2); var e = a.examples().get(0);
        var malformed = new ContextExample(e.id(), "wrong-family", e.mechanismIds(), e.channels(), e.context());
        var bad = new Archive(a.schemaVersion(), a.version(), a.status(), false, a.usage(), a.sourceBundleSha256(),
                a.refreshOrDeleteBy(), a.sourceCases(), a.guidelines(), a.contextDictionarySha256(), List.of(malformed, a.examples().get(1)));
        assertThat(library(bad, 2, 3000, "").prompt(TimelineEventType.SPEECH)).isEmpty();
    }
    @Test void currentCandidateDiscoveryDoesNotDependOnAnyMatchedExample() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        f.verification(new CandidateReviewEngine.Verification("candidate-1", f.assessment("stt-index-0", "PASS", "그 집")));
        var result = CandidateReviewEngine.run(f.client, f.context("그 집은 조용하다"), 24, null, null, library(archive(4), 0, 3000, ""));
        assertThat(result.diagnostics().candidatePipeline().exploredSegments()).isEqualTo(1);
        assertThat(result.diagnostics().candidatePipeline().contextSelections()).hasSize(2)
                .allSatisfy(s -> assertThat(s.exampleIds()).isEmpty());
    }
}
