package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.*;
import static com.example.oops.domain.TimelineEventType.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.*;

class StructuredContextSearchTest {
    private final StructuredContextSearch search = StructuredContextSearch.load();
    private ReviewInput.Segment line(String id, long start, String text) {
        return new ReviewInput.Segment(id, SPEECH, start, start + 1500, text, null);
    }
    private List<ReviewInput.Segment> raw(String text) { return List.of(line("s", 0, text)); }
    private ContextExample example(String id, String mechanism, String text) {
        return new ContextExample(id, id, List.of(mechanism), List.of("SPEECH"),
                new Comparison("REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT", List.of("판단용 원문"), List.of(),
                        List.of("자료 한계"), List.of()),
                new RetrievalView("frozen-legacy-lexical-1", "FROZEN_LEGACY_NOT_SOURCE_EVIDENCE", text, retrievalHash(text)));
    }
    @Test void conceptualExpansionRecoversQuestionDespiteZeroCommonTrigrams() {
        var e = example("question", "sexual-objectification", "초면 상대에게 성적 관계를 집요하게 묻는 질문");
        var input = raw("오늘 팬티는 무슨 색깔?");
        assertThat(new ContextExampleSelector(List.of(e), List.of()).rank(SPEECH, input, Set.of())).isEmpty();
        var matches = new ContextExampleSelector(List.of(e), List.of(), search).rank(SPEECH, input, Set.of());
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).searchProfiles()).containsExactly("intimate-information-question");
        assertThat(matches.get(0).score()).isZero();
    }
    @Test void collectiveEvaluationOutranksAccidentalLexicalOverlap() {
        var group = example("group", "group-generalization", "집단 전체를 싸잡아 일반화");
        var unrelated = example("other", "privacy-exposure", "모든 진영이 모두 공격했다는 내용");
        var ranked = new ContextExampleSelector(List.of(unrelated, group), List.of(), search)
                .rank(SPEECH, raw("이 진영이 모두 공격했다는 내용"), Set.of());
        assertThat(ranked.get(0).example().id()).isEqualTo("group");
    }
    @Test void loneSensitiveWordOrScopeDoesNotActivateConcepts() {
        for (String text : List.of("팬티를 세탁했어요", "새 속옷을 샀어요", "오늘 무슨 색깔로 칠할까?",
                "사람들이 모두 모였어요", "그 담당자의 대응에 문제가 있다", "모든 메뉴를 보여 주세요"))
            assertThat(search.queryProfiles(SPEECH, raw(text))).as(text).isEmpty();
    }
    @Test void adjacentUtterancesCanLinkButDistantConversationsCannot() {
        assertThat(search.queryProfiles(SPEECH, List.of(line("a", 0, "오늘 팬티는"), line("b", 2000, "무슨 색깔?"))))
                .containsExactly("intimate-information-question");
        assertThat(search.queryProfiles(SPEECH, List.of(line("a", 0, "오늘 팬티는"), line("b", 60000, "무슨 색깔?")))).isEmpty();
    }
    @Test void exclusionsChannelsAndMissingSearchTextRemainBoundaries() {
        var e = example("question", "sexual-objectification", "성적 관계를 묻는 질문");
        var selector = new ContextExampleSelector(List.of(e), List.of(), search);
        assertThat(selector.rank(SPEECH, raw("팬티는 무슨 색깔?"), Set.of("question"))).isEmpty();
        assertThat(selector.rank(CAPTION, raw("팬티는 무슨 색깔?"), Set.of())).isEmpty();
        var missing = new ContextExample(e.id(), e.familyId(), e.mechanismIds(), e.channels(), e.context());
        assertThat(search.match(missing, Set.of("intimate-information-question"))).isEmpty();
    }
    @Test void mutableJudgmentExplanationIsNotSearchInput() {
        var e = example("question", "sexual-objectification", "일반적인 인터뷰");
        var updated = new ContextExample(e.id(), e.familyId(), e.mechanismIds(), e.channels(),
                new Comparison(e.context().coverage(), List.of("성적 관계를 묻는 질문"), List.of(), List.of("불명"), List.of()), e.retrievalView());
        assertThat(search.match(updated, Set.of("intimate-information-question"))).isEmpty();
    }
    @Test void normalContextsMayRetrieveReferencesWithoutReceivingVerdicts() {
        assertThat(search.queryProfiles(SPEECH, raw("속옷 색깔을 묻는 질문은 불편할 수 있으니 하지 말자")))
                .containsExactly("intimate-information-question");
        // Retrieval is deliberately not a PASS/REVIEW_REQUIRED classifier.
    }
    @Test void optionalRouterBalanceKeepsConceptComponentVisibleAndBounded() {
        var e = example("question", "sexual-objectification", "성적 관계를 묻는 질문");
        var matches = new ContextExampleSelector(List.of(e), List.of(), search)
                .rank(SPEECH, raw("팬티는 무슨 색깔?"), Set.of());
        var plan = ContextReferenceBalance.select(matches, Set.of("another-key"), 3, true, items -> true);
        assertThat(plan.selected()).containsExactly(e);
        var choice = plan.trace().choices().get(0);
        assertThat(choice.conceptComponent()).isEqualTo(.20);
        assertThat(choice.selectionScore()).isEqualTo(choice.lexicalComponent()+choice.hintBonus()
                +choice.newKeyBonus()+choice.relationComponent()+choice.conceptComponent());
    }
    @Test void oldSchemasDoNotSilentlyChangeWhenFeatureIsEnabled() {
        var a = new ContextExampleSelectionTest().archive(2);
        var l = library(JSON.writeValueAsString(a), true, 3, 3000, "");
        assertThat(l.select(SPEECH, raw("공공시설이 없는 생활조건"), "q").trace().structuredSearch()).isNull();
    }
    @Test void currentBoundarySupplementPreservesLeadingReferenceAndPayloadBudget() {
        String text = boundaryArchive();
        var input = raw("오늘 팬티는 무슨 색깔? 줄임말 뜻을 설명합니다");
        var baseline = new ReviewGuidelineLibrary(TrialSupport.resources(text), true, "classpath:frozen.json",
                6000, 2, 3000, "", true, false);
        baseline.load();
        assertThat(baseline.trace(SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE");
        var before = baseline.select(SPEECH, input, "baseline");
        var after = library(text, true, 2, 3000, "").select(SPEECH, input, "supplemented");
        assertThat(before.trace().exampleIds()).containsExactly("lead", "tail");
        assertThat(after.trace().exampleIds()).containsExactly("lead", "boundary");
        assertThat(after.trace().boundarySupplement().state()).isEqualTo("BOUNDARY_SUPPLEMENTED");
        assertThat(after.trace().boundarySupplement().removedId()).isEqualTo("tail");
        assertThat(after.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
        // A one-slot limit preserves the lead rather than forcing a boundary reference.
        assertThat(library(text, true, 1, 3000, "").select(SPEECH, input, "single").trace().exampleIds())
                .containsExactly("lead");
        assertThat(library(text, true, 2, 256, "").select(SPEECH, input, "budget").trace().exampleIds())
                .isEmpty();
    }
    @Test void currentBoundarySupplementCannotRestoreAnExcludedFamily() {
        var selection = library(boundaryArchive(), true, 2, 3000, "boundary-family")
                .select(SPEECH, raw("오늘 팬티는 무슨 색깔? 줄임말 뜻을 설명합니다"), "excluded");
        assertThat(selection.trace().state()).isEqualTo("READY_WORKING_REFERENCE");
        assertThat(selection.trace().exampleIds()).containsExactly("lead", "tail");
        assertThat(selection.trace().boundarySupplement()).isNotNull();
        assertThat(selection.trace().boundarySupplement().addedId()).isNull();
    }
    /** Authored wiring fixture, not a private incident, approved label, or model-quality benchmark. */
    private String boundaryArchive() {
        var sources = new ArrayList<SourceCase>();
        var examples = new ArrayList<ContextExample>();
        for (String id : List.of("lead", "tail", "boundary")) {
            String searchText = id.equals("boundary") ? "성적 사생활 속옷 질문. 뜻 설명과 직접 요청을 구별"
                    : "성적 사생활 속옷 질문. 오늘 팬티는 무슨 색깔? 줄임말 뜻을 설명합니다";
            sources.add(new SourceCase(id, id + "-family", "UNREVIEWED_DRAFT"));
            examples.add(new ContextExample(id, id + "-family", List.of("sexual-objectification"), List.of("SPEECH"),
                    new Comparison("REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT", List.of("합성 배선 확인용 맥락".repeat(25)),
                            List.of(), List.of("실제 사건과 무관"), List.of(), List.of()),
                    new RetrievalView("context-unit-retrieval-1", "AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE",
                            searchText, retrievalHash(searchText)), List.of(),
                    new ContextRelationRetrieval.Profile("authored-relation-search-1", "AUTHORED_RETRIEVAL_NOT_SOURCE_EVIDENCE",
                            "a".repeat(64), List.of("synthetic"), "상황", "", "평가", "연결")));
        }
        var rule = new Rule("sexual-objectification", "TARGET_TREATMENT", List.of("SPEECH"),
                "구체적 상황 확인", "뜻풀이와 요청 구분", "현재 원문 확인", "정보 부족은 명시",
                sources.stream().map(SourceCase::caseId).toList());
        return JSON.writeValueAsString(new Archive("review-guidelines-7", "synthetic-boundary-wiring",
                "WORKING_REFERENCE_NOT_VALIDATED", false, "REFERENCE_ONLY_CURRENT_INPUT_EVIDENCE_REQUIRED",
                "a".repeat(64), null, sources, List.of(rule), "b".repeat(64), examples));
    }
    @org.junit.jupiter.api.Tag("dataset-backed")
    @Test void privateV7SelectionUsesProductionBudgetsAndCanRollBackExactly() throws Exception {
        Path runtime = Path.of("../datasets/controversy/guidelines/runtime.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(runtime));
        String text = Files.readString(runtime);
        var off = library(text, false, 3, 3000, "");
        var on = library(text, true, 3, 3000, "");
        var input = raw("오늘 팬티는 무슨 색깔?");
        assertThat(off.select(SPEECH, input, "q").trace().exampleIds()).isEmpty();
        var selection = on.select(SPEECH, input, "q");
        assertThat(selection.trace().exampleIds()).contains("leemiju-mijupichu#s1-sexual", "kimwonhun-nego#s1-sexual");
        assertThat(selection.trace().structuredSearch().queryProfileIds()).containsExactly("intimate-information-question");
        assertThat(selection.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
        assertThat(selection.prompt()).doesNotContain("queryAll", "referenceAll", "intimate-information-question");
        assertThat(library(text, true, 0, 3000, "").select(SPEECH, input, "q").trace().exampleIds()).isEmpty();
        assertThat(library(text, true, 3, 256, "").select(SPEECH, input, "q").trace().exampleIds()).isEmpty();
        var archive = JSON.readValue(text, Archive.class);
        String excluded = archive.examples().stream().filter(e -> Set.of("leemiju-mijupichu#s1-sexual", "kimwonhun-nego#s1-sexual").contains(e.id()))
                .map(ContextExample::familyId).collect(java.util.stream.Collectors.joining(","));
        assertThat(library(text, true, 3, 3000, excluded)
                .select(SPEECH, input, "q").trace().exampleIds()).doesNotContain("leemiju-mijupichu#s1-sexual", "kimwonhun-nego#s1-sexual");
        var historical = TrialSupport.readyLibrary(text);
        assertThat(off.select(SPEECH, input, "q")).isEqualTo(historical.select(SPEECH, input, "q"));
    }
    private ReviewGuidelineLibrary library(String text, boolean enabled, int count, int budget, String excluded) {
        var l = new ReviewGuidelineLibrary(TrialSupport.resources(text), true, "classpath:frozen.json", 6000,
                count, budget, excluded, enabled);
        l.load(); assertThat(l.trace(SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE"); return l;
    }
}
