package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.*;
import static com.example.oops.domain.TimelineEventType.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.Tag("dataset-backed")
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
        var historical = ContextRankReadinessAudit.library(text);
        assertThat(off.select(SPEECH, input, "q")).isEqualTo(historical.select(SPEECH, input, "q"));
    }
    private ReviewGuidelineLibrary library(String text, boolean enabled, int count, int budget, String excluded) {
        var l = new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(text), true, "classpath:frozen.json", 6000,
                count, budget, excluded, enabled);
        l.load(); assertThat(l.trace(SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE"); return l;
    }
}
