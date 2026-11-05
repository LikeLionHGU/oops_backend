package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** A5-T: lexical BM25 over the frozen transcript, greedy under budget B. Ranking shape, not quality. */
class Bm25ContextSelectorTest {
    private static ReviewInput.Segment speech(int index, long startMs, String text) {
        return new ReviewInput.Segment("stt-index-" + index, TimelineEventType.SPEECH, startMs, startMs + 1500, text, null);
    }

    private static final int LARGE = 1_000_000;

    private final ReviewInput transcript = new ReviewInput(List.of(
            speech(0, 0, "요즘 신입들은 끈기가 없다는 말이 있어"),
            speech(1, 2_000, "오늘 날씨 좋다"),
            speech(2, 4_000, "그냥 오늘 이야기"),
            speech(3, 300_000, "신입 끈기 얘기는 일반화였어"),
            speech(4, 302_000, "오늘 점심 뭐 먹지"),
            speech(5, 600_000, "신입들 끈기 문제")));

    private ReviewInput.Segment seg(int i) {
        return transcript.segments().get(i);
    }

    @Test void ranksSharedRareTermsAndDropsSegmentsWithoutOverlap() {
        var ranking = new Bm25ContextSelector(new ContextTokenBudget(LARGE)).ranking(seg(5), transcript);
        assertThat(ranking).extracting(Bm25ContextSelector.Scored::segmentId)
                .doesNotContain("stt-index-5", "stt-index-1", "stt-index-2", "stt-index-4")
                .contains("stt-index-0", "stt-index-3");
        assertThat(ranking).allMatch(s -> s.score() > 0);
        assertThat(ranking).isSortedAccordingTo(Comparator.comparingDouble(Bm25ContextSelector.Scored::score).reversed());
    }

    @Test void koreanCharacterBigramsMatchAcrossParticles() {
        var t = new ReviewInput(List.of(speech(0, 0, "끈기가"), speech(1, 2_000, "끈기"), speech(2, 4_000, "날씨")));
        var ranking = new Bm25ContextSelector(new ContextTokenBudget(LARGE)).ranking(t.segments().get(0), t);
        assertThat(ranking).extracting(Bm25ContextSelector.Scored::segmentId).containsExactly("stt-index-1");
    }

    @Test void equalScoresGoToTheSegmentCloserInTime() {
        var t = new ReviewInput(List.of(speech(0, 0, "신입 끈기"), speech(1, 100_000, "신입 끈기 이야기"),
                speech(2, 104_000, "신입 끈기")));
        var ranking = new Bm25ContextSelector(new ContextTokenBudget(LARGE)).ranking(t.segments().get(1), t);
        assertThat(ranking.get(0).score()).isEqualTo(ranking.get(1).score());
        assertThat(ranking).extracting(Bm25ContextSelector.Scored::segmentId).containsExactly("stt-index-2", "stt-index-0");
    }

    @Test void greedySkipsARankedSegmentThatDoesNotFitAndKeepsLowerOnesThatDo() {
        var t = new ReviewInput(List.of(
                speech(0, 0, "신입 끈기 " + "아주 긴 설명 ".repeat(40)),
                speech(1, 200_000, "신입 끈기 대상"),
                speech(2, 400_000, "끈기")));
        var target = t.segments().get(1);
        var selector = new Bm25ContextSelector(new ContextTokenBudget(LARGE));
        assertThat(selector.ranking(target, t).get(0).segmentId()).isEqualTo("stt-index-0");
        var rendering = new ExperimentalBatchRendering(t, Set.of(target.id()));
        int smallOnly = new ContextTokenBudget(LARGE).measure(rendering, List.of(target, t.segments().get(2))).contextTokens();
        var selected = new Bm25ContextSelector(new ContextTokenBudget(smallOnly)).select(target, t).contextSegmentIds();
        assertThat(selected).containsExactly("stt-index-2");
    }

    @Test void selectionIsReturnedInTranscriptOrderAndPassesTheContract() {
        var selection = new Bm25ContextSelector(new ContextTokenBudget(LARGE)).select(seg(5), transcript);
        assertThat(selection.contextSegmentIds()).containsExactly("stt-index-0", "stt-index-3");
        assertThatCode(() -> selection.toBatch(seg(5), transcript)).doesNotThrowAnyException();
    }

    @Test void selectorIdRecordsParametersCounterAndBudget() {
        assertThat(new Bm25ContextSelector(new ContextTokenBudget(256)).id())
                .isEqualTo("A5-T:bm25-bigram-v1:k1=1.2:b=0.75:" + ContextTokenBudget.COUNTER_ID + ":B=256");
    }
}
