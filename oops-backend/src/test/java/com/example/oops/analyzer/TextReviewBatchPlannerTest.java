package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

class TextReviewBatchPlannerTest {
    private ReviewInput.Segment segment(String id, TimelineEventType type, long start, String text) {
        return new ReviewInput.Segment(id, type, start, start + 500, text, type == TimelineEventType.CAPTION ? 0.8 : null);
    }

    @Test
    void chronologicalPrimaryCoverageAndOverlapDoNotDependOnKeywordMatches() {
        var segments = IntStream.range(0, 43).mapToObj(i -> segment("s" + i,
                TimelineEventType.SPEECH, i * 1_000, "일상 대화" + i)).toList();
        var reversed = new ArrayList<>(segments);
        Collections.reverse(reversed);
        var batches = TextReviewBatchPlanner.plan(new ReviewInput(reversed), TimelineEventType.SPEECH, 3);
        assertThat(batches).hasSize(3);
        assertThat(batches.get(1).primary().get(0).id()).isEqualTo("s17");
        assertThat(batches.stream().flatMap(b -> b.primary().stream()).map(ReviewInput.Segment::id).distinct())
                .containsExactlyElementsOf(segments.stream().map(ReviewInput.Segment::id).toList());
    }

    @Test
    void primaryLimitsCharactersAndTimeWithoutDroppingSegments() {
        var input = new ReviewInput(List.of(segment("a", TimelineEventType.SPEECH, 0, "가".repeat(7_000)),
                segment("b", TimelineEventType.SPEECH, 1_000, "나".repeat(7_000)),
                segment("c", TimelineEventType.SPEECH, 200_000, "멀리 있는 발언")));
        var batches = TextReviewBatchPlanner.plan(input, TimelineEventType.SPEECH, 3);
        assertThat(batches).hasSize(3);
        assertThat(batches).allSatisfy(b -> assertThat(b.primary()).hasSize(1));
        assertThat(batches.stream().flatMap(b -> b.primary().stream()).map(ReviewInput.Segment::id))
                .containsExactly("a", "b", "c");
    }

    @Test
    void contextContainsBothSourcesAndExcludesAlreadyProvidedPrimaryBeforeLimits() {
        var segments = new ArrayList<>(IntStream.range(0, 20).mapToObj(i -> segment("s" + i,
                TimelineEventType.SPEECH, i * 500, "발언" + i)).toList());
        segments.add(segment("ocr1", TimelineEventType.CAPTION, 1_000, "자막1"));
        segments.add(segment("ocr2", TimelineEventType.CAPTION, 1_500, "자막2"));
        segments.add(segment("distant", TimelineEventType.CAPTION, 60_000, "먼 자막"));
        var batch = TextReviewBatchPlanner.plan(new ReviewInput(segments), TimelineEventType.SPEECH, 3).get(0);
        assertThat(batch.primary()).hasSize(20);
        assertThat(batch.context()).extracting(ReviewInput.Segment::id).containsExactly("ocr1", "ocr2");
        assertThat(batch.contextLimited()).isFalse();
    }

    @Test
    void limitsCrossSourceContextButNeverClipsRawText() {
        var segments = new ArrayList<ReviewInput.Segment>();
        segments.add(segment("anchor", TimelineEventType.SPEECH, 0, "발언"));
        IntStream.range(0, 30).forEach(i -> segments.add(segment("ocr" + i, TimelineEventType.CAPTION, 0, "자막" + i)));
        var batch = TextReviewBatchPlanner.plan(new ReviewInput(segments), TimelineEventType.SPEECH, 3).get(0);
        assertThat(batch.context()).hasSize(8);
        assertThat(batch.contextLimited()).isTrue();
        assertThat(new ReviewInput(segments).segments()).hasSize(31);
    }

    @Test
    void oversizedSingleInputIsNotSilentlySkippedAndEmptyTypeHasNoBatches() {
        var input = new ReviewInput(List.of(segment("long", TimelineEventType.SPEECH, 0, "가".repeat(12_001))));
        var batch = TextReviewBatchPlanner.plan(input, TimelineEventType.SPEECH, 3).get(0);
        assertThat(batch.oversizedPrimary()).isTrue();
        assertThat(batch.primary().get(0).text()).hasSize(12_001);
        assertThat(TextReviewBatchPlanner.plan(input, TimelineEventType.CAPTION, 2)).isEmpty();
        assertThatThrownBy(() -> TextReviewBatchPlanner.plan(input, TimelineEventType.SPEECH, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
