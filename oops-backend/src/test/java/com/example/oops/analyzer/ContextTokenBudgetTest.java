package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Budget B counts rendered CONTEXT rows with their markers, not raw STT text. Not model usage. */
class ContextTokenBudgetTest {
    private static ReviewInput.Segment speech(int index, long startMs, String text) {
        return new ReviewInput.Segment("stt-index-" + index, TimelineEventType.SPEECH, startMs, startMs + 1500, text, null);
    }

    private final ReviewInput transcript = new ReviewInput(List.of(
            speech(0, 0, "아까 그 이야기는 사실이 아니었어"), speech(1, 2_000, "중간 대화"),
            speech(2, 4_000, "그러니까 그 말은 믿으면 안 돼")));
    private final ExperimentalBatchRendering rendering = new ExperimentalBatchRendering(transcript, Set.of("stt-index-2"));

    private List<ReviewInput.Segment> supplied(int... indexes) {
        return Arrays.stream(indexes).mapToObj(i -> transcript.segments().get(i)).toList();
    }

    @Test void measuresExactlyTheRenderedContextText() {
        var raw = supplied(0, 2);
        var m = new ContextTokenBudget(10_000).measure(rendering, raw);
        assertThat(m.contextTokens()).isEqualTo(ContextTokenBudget.count(rendering.budgetedContext(raw))).isPositive();
    }

    @Test void judgedOnlyBatchUsesNoContextBudget() {
        assertThat(new ContextTokenBudget(1).measure(rendering, supplied(2)).contextTokens()).isZero();
    }

    @Test void omittedMarkerOnAContextRowIsChargedButOnTheJudgedRowIsNot() {
        var budget = new ContextTokenBudget(10_000);
        var a = transcript.segments().get(0);
        var c = transcript.segments().get(2);
        var withoutMiddle = new ReviewInput(List.of(a, c));
        // Context row c: identical text and times; only the omittedBefore marker differs.
        int marked = budget.measure(new ExperimentalBatchRendering(transcript, Set.of(a.id())), List.of(a, c)).contextTokens();
        int unmarked = budget.measure(new ExperimentalBatchRendering(withoutMiddle, Set.of(a.id())), List.of(a, c)).contextTokens();
        assertThat(marked).isGreaterThan(unmarked);
        // Judged row c carries the marker; the single context row a is unchanged.
        int judgedMarked = budget.measure(rendering, List.of(a, c)).contextTokens();
        int judgedUnmarked = budget.measure(new ExperimentalBatchRendering(withoutMiddle, Set.of(c.id())), List.of(a, c)).contextTokens();
        assertThat(judgedMarked).isEqualTo(judgedUnmarked);
    }

    @Test void budgetBoundaryIsInclusive() {
        var raw = supplied(0, 2);
        int tokens = new ContextTokenBudget(10_000).measure(rendering, raw).contextTokens();
        assertThat(new ContextTokenBudget(tokens).measure(rendering, raw).exceeded()).isFalse();
        assertThat(new ContextTokenBudget(tokens - 1).measure(rendering, raw).exceeded()).isTrue();
    }

    @Test void countIsReproducibleAndCounterIsIdentified() {
        var raw = supplied(0, 1, 2);
        var budget = new ContextTokenBudget(10_000);
        assertThat(budget.measure(rendering, raw)).isEqualTo(budget.measure(rendering, raw));
        assertThat(ContextTokenBudget.COUNTER_ID).isEqualTo("jtokkit-1.1.0:o200k_base");
    }

    @Test void rejectsNonPositiveBudget() {
        assertThatThrownBy(() -> new ContextTokenBudget(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
