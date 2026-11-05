package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

/** Selector contract and the A0-T page converter. Selection shape only, not retrieval quality. */
class ContextSelectorContractTest {
    private static ReviewInput.Segment speech(int index, long startMs) {
        return new ReviewInput.Segment("stt-index-" + index, TimelineEventType.SPEECH, startMs, startMs + 1500, "발언 " + index, null);
    }

    private static ReviewInput transcript(int count) {
        return new ReviewInput(IntStream.range(0, count).mapToObj(i -> speech(i, i * 2_000L)).toList());
    }

    private static ReviewInput.Segment segment(ReviewInput t, String id) {
        return t.find(id).orElseThrow();
    }

    @Test void a0tReturnsPlannerWindowMinusTargetSoOtherPrimariesBecomeContext() {
        var t = transcript(10);
        var target = segment(t, "stt-index-4");
        var page = DialogueReadingPage.plan(t).get(0);
        var window = DialogueReadingPage.windows(page).stream().filter(u -> u.anchorId().equals(target.id())).findFirst().orElseThrow();
        var selection = new DialoguePageContextSelector().select(target, t);
        assertThat(selection.contextSegmentIds()).doesNotContain(target.id())
                .containsExactlyElementsOf(window.segmentIds().stream().filter(id -> !id.equals(target.id())).toList());
        assertThat(selection.selectorId()).isEqualTo(DialoguePageContextSelector.ID);
    }

    @Test void a0tKeepsPagePrefixContextForALaterPage() {
        var t = transcript(70);
        var pages = DialogueReadingPage.plan(t);
        assertThat(pages).hasSizeGreaterThan(1);
        var second = pages.get(1);
        assertThat(second.context()).isNotEmpty();
        var target = second.primary().get(0);
        var selection = new DialoguePageContextSelector().select(target, t);
        assertThat(selection.contextSegmentIds()).containsAll(second.context().stream().map(ReviewInput.Segment::id).toList());
        assertThat(selection.contextSegmentIds()).doesNotContain(target.id());
    }

    @Test void a0tGivesNoContextWhenThePlannerAdvertisesNoWindow() {
        var oversized = new ReviewInput.Segment("stt-index-0", TimelineEventType.SPEECH, 0, 1500, "가".repeat(7_000), null);
        var t = new ReviewInput(List.of(oversized, speech(1, 2_000)));
        assertThat(new DialoguePageContextSelector().select(oversized, t).contextSegmentIds()).isEmpty();
    }

    @Test void selectionContractBuildsTheExperimentalBatch() {
        var t = transcript(5);
        var target = segment(t, "stt-index-2");
        var batch = new ContextSelection(List.of("stt-index-0", "stt-index-4"), "test").toBatch(target, t);
        assertThat(batch.primaryIds()).containsExactly("stt-index-2");
        assertThat(batch.contextIds()).containsExactly("stt-index-0", "stt-index-4");
    }

    @Test void selectionContractRejectsSelfDuplicateUnknownAndUnorderedIds() {
        var t = transcript(5);
        var target = segment(t, "stt-index-2");
        assertThatThrownBy(() -> new ContextSelection(List.of("stt-index-2"), "test").toBatch(target, t))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextSelection(List.of("stt-index-0", "stt-index-0"), "test").toBatch(target, t))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextSelection(List.of("missing"), "test").toBatch(target, t))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextSelection(List.of("stt-index-4", "stt-index-0"), "test").toBatch(target, t))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextSelection(List.of(), " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
