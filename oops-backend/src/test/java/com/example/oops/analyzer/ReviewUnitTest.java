package com.example.oops.analyzer;

import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewUnitTest {
    private ReviewInput.Segment speech(String id, long start, long end, String text) {
        return new ReviewInput.Segment(id, TimelineEventType.SPEECH, start, end, text, null);
    }

    @Test
    void adjacentFragmentsAreLinkedWithoutMergingOrChangingRawText() {
        var input = new ReviewInput(List.of(speech("a", 0, 1_000, "젊은 사람들이"),
                speech("b", 1_500, 3_000, "이걸로 대신 먹는 거야"), speech("c", 4_000, 5_000, "못 먹으니까")));
        var units = ReviewUnit.plan(TextReviewBatchPlanner.withContext(input, input.segments()));
        assertThat(units).hasSize(3).allSatisfy(u -> {
            assertThat(u.segmentIds()).containsExactly("a", "b", "c");
            assertThat(u.limited()).isFalse();
        });
        assertThat(input.find("b").orElseThrow().text()).isEqualTo("이걸로 대신 먹는 거야");
    }

    @Test
    void windowsDoNotBridgeLongSilenceOrJoinOtherSourceAsSpeech() {
        var input = new ReviewInput(List.of(speech("a", 0, 1_000, "짧은 발언"),
                speech("b", 7_000, 8_000, "다른 발언"),
                new ReviewInput.Segment("ocr", TimelineEventType.CAPTION, 0, 2_000, "간판", 0.9, ScreenTextRole.BACKGROUND)));
        var batch = TextReviewBatchPlanner.withContext(input, List.of(input.find("a").orElseThrow()));
        assertThat(ReviewUnit.plan(batch).get(0).segmentIds()).containsExactly("a");
        assertThat(batch.context()).extracting(ReviewInput.Segment::id).contains("ocr", "b");
    }

    @Test
    void windowsHaveTimeCountAndCharacterBoundsAndEveryAnchorSurvives() {
        var input = new ReviewInput(IntStream.range(0, 60)
                .mapToObj(i -> speech("s" + i, i * 1_000, i * 1_000 + 500, "일반적인 발언".repeat(20))).toList());
        for (var batch : TextReviewBatchPlanner.plan(input, TimelineEventType.SPEECH, 3)) {
            assertThat(ReviewUnit.plan(batch)).hasSize(batch.primary().size()).allSatisfy(u -> {
                assertThat(u.segmentIds()).contains(u.anchorId()).hasSizeLessThanOrEqualTo(32);
                assertThat(u.endMs() - u.startMs()).isLessThanOrEqualTo(30_000);
                assertThat(u.segmentIds().stream().map(input::find).map(Optional::orElseThrow)
                        .mapToInt(s -> s.text().codePointCount(0, s.text().length())).sum()).isLessThanOrEqualTo(4_000);
            });
        }
    }

    @Test
    void oversizedSingleUtteranceIsNotTruncatedOrSilentlyDropped() {
        var segment = speech("a", 0, 31_000, "😀".repeat(4_001));
        var input = new ReviewInput(List.of(segment));
        var unit = ReviewUnit.plan(TextReviewBatchPlanner.withContext(input, input.segments())).get(0);
        assertThat(unit.segmentIds()).containsExactly("a");
        assertThat(unit.limited()).isTrue();
        assertThat(input.find("a").orElseThrow().text()).isEqualTo(segment.text());
    }

    @Test
    void directionalViewsRetainEarlierAndLaterChainsWithoutMakingOneLongWindow() {
        var input = new ReviewInput(IntStream.range(0, 13)
                .mapToObj(i -> speech("s" + i, i * 5_000, i * 5_000 + 1_000, "발언" + i)).toList());
        var batch = TextReviewBatchPlanner.withContext(input, input.segments());
        var centred = ReviewUnit.plan(batch).stream().filter(u -> u.anchorId().equals("s6")).findFirst().orElseThrow();
        assertThat(centred.segmentIds()).doesNotContain("s1", "s11");
        var views = ReviewUnit.dialoguePlan(batch).stream().filter(u -> u.anchorId().equals("s6")).toList();
        assertThat(views).anySatisfy(u -> {
            assertThat(u.view()).isEqualTo(ReviewUnit.View.TRAILING);
            assertThat(u.segmentIds()).contains("s1", "s6").doesNotContain("s11");
        }).anySatisfy(u -> {
            assertThat(u.view()).isEqualTo(ReviewUnit.View.LEADING);
            assertThat(u.segmentIds()).contains("s6", "s11").doesNotContain("s1");
        });
        assertThat(ReviewUnit.all(batch)).allSatisfy(u -> {
            assertThat(u.segmentIds()).contains(u.anchorId()).hasSizeLessThanOrEqualTo(32);
            assertThat(u.endMs() - u.startMs()).isLessThanOrEqualTo(30_000);
        });
    }

    @Test
    void alternateViewsDeduplicateAndDoNotBridgeSilenceOrApplyToOcr() {
        var input = new ReviewInput(List.of(speech("a", 0, 1000, "첫 발언"), speech("b", 7000, 8000, "다른 대화")));
        var batch = TextReviewBatchPlanner.withContext(input, input.segments());
        assertThat(ReviewUnit.dialoguePlan(batch)).isEmpty();
        assertThat(ReviewUnit.all(batch)).allSatisfy(u -> assertThat(u.segmentIds()).hasSize(1));
        var ocr = new ReviewInput.Segment("ocr", TimelineEventType.CAPTION, 0, 1000,
                "자막", 0.9, ScreenTextRole.EDITORIAL);
        assertThat(ReviewUnit.dialoguePlan(new TextReviewBatchPlanner.Batch(List.of(ocr), List.of(), false, false))).isEmpty();
    }

    @Test
    void alternateViewsKeepCharacterAndCountBounds() {
        var input = new ReviewInput(IntStream.range(0, 45)
                .mapToObj(i -> speech("s" + i, i * 500, i * 500 + 300, "😀".repeat(200))).toList());
        for (var batch : TextReviewBatchPlanner.plan(input, TimelineEventType.SPEECH, 3)) {
            assertThat(ReviewUnit.all(batch)).allSatisfy(u -> {
                assertThat(u.segmentIds()).hasSizeLessThanOrEqualTo(32);
                assertThat(u.segmentIds().stream().map(input::find).map(Optional::orElseThrow)
                        .mapToInt(s -> s.text().codePointCount(0, s.text().length())).sum()).isLessThanOrEqualTo(4000);
            });
        }
    }
}
