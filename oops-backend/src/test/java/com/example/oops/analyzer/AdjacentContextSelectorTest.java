package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

/** A1-T: contiguous nearest-first neighbors inside the rendered context budget. Shape only. */
class AdjacentContextSelectorTest {
    private static ReviewInput.Segment speech(int index, long startMs) {
        return new ReviewInput.Segment("stt-index-" + index, TimelineEventType.SPEECH, startMs, startMs + 1500, "발언 내용 " + index, null);
    }

    private static ReviewInput evenTranscript(int count) {
        return new ReviewInput(IntStream.range(0, count).mapToObj(i -> speech(i, i * 2_000L)).toList());
    }

    private static int contextTokens(ReviewInput t, ReviewInput.Segment target, List<String> contextIds) {
        List<ReviewInput.Segment> raw = new ArrayList<>(List.of(target));
        contextIds.forEach(id -> raw.add(t.find(id).orElseThrow()));
        return new ContextTokenBudget(1_000_000)
                .measure(new ExperimentalBatchRendering(t, Set.of(target.id())), raw).contextTokens();
    }

    private static List<String> ids(int... indexes) {
        return Arrays.stream(indexes).mapToObj(i -> "stt-index-" + i).toList();
    }

    @Test void selectsAContiguousBlockAroundTheTargetWithinBudget() {
        var t = evenTranscript(11);
        var target = t.find("stt-index-5").orElseThrow();
        int budget = contextTokens(t, target, ids(3, 4, 6, 7));
        var selection = new AdjacentContextSelector(new ContextTokenBudget(budget)).select(target, t);
        assertThat(selection.contextSegmentIds()).containsExactlyElementsOf(ids(3, 4, 6, 7));
        assertThat(contextTokens(t, target, selection.contextSegmentIds())).isLessThanOrEqualTo(budget);
        assertThatCode(() -> selection.toBatch(target, t)).doesNotThrowAnyException();
    }

    @Test void selectionIsMaximalSoTheNextNeighborWouldExceedTheBudget() {
        var t = evenTranscript(11);
        var target = t.find("stt-index-5").orElseThrow();
        int budget = contextTokens(t, target, ids(4, 6)) + 1;
        var selected = new AdjacentContextSelector(new ContextTokenBudget(budget)).select(target, t).contextSegmentIds();
        assertThat(selected).containsExactlyElementsOf(ids(4, 6));
        assertThat(contextTokens(t, target, ids(3, 4, 6))).isGreaterThan(budget);
    }

    @Test void prefersTheNeighborCloserInTimeAndBreaksTiesTowardEarlierSpeech() {
        var uneven = new ReviewInput(List.of(speech(0, 0), speech(1, 20_000), speech(2, 22_000)));
        var target = uneven.find("stt-index-1").orElseThrow();
        int one = contextTokens(uneven, target, ids(2));
        assertThat(new AdjacentContextSelector(new ContextTokenBudget(one)).select(target, uneven).contextSegmentIds())
                .containsExactly("stt-index-2");
        var even = evenTranscript(3);
        var middle = even.find("stt-index-1").orElseThrow();
        int single = contextTokens(even, middle, ids(0));
        assertThat(new AdjacentContextSelector(new ContextTokenBudget(single)).select(middle, even).contextSegmentIds())
                .containsExactly("stt-index-0");
    }

    @Test void expandsOneSidedAtTranscriptEdges() {
        var t = evenTranscript(4);
        var first = t.find("stt-index-0").orElseThrow();
        var selected = new AdjacentContextSelector(new ContextTokenBudget(1_000_000)).select(first, t).contextSegmentIds();
        assertThat(selected).containsExactlyElementsOf(ids(1, 2, 3));
    }

    @Test void returnsEmptyWhenNoWholeSegmentFits() {
        var t = evenTranscript(3);
        var target = t.find("stt-index-1").orElseThrow();
        assertThat(new AdjacentContextSelector(new ContextTokenBudget(1)).select(target, t).contextSegmentIds()).isEmpty();
    }

    @Test void selectorIdRecordsPolicyCounterAndBudget() {
        assertThat(new AdjacentContextSelector(new ContextTokenBudget(512)).id())
                .isEqualTo("A1-T:adjacent-nearest-v1:" + ContextTokenBudget.COUNTER_ID + ":B=512");
    }
}
