package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CandidateContextWindowTest {
    @Test void reservesMoreSpaceForContinuationWithoutIncreasingAnyBudget() {
        var lines = java.util.stream.IntStream.range(0, 46)
                .mapToObj(i -> speech(i, i * 2000L, "대화 " + i)).toList();
        var base = lines.subList(8, 23);
        var w = CandidateContextWindow.extend(new ReviewInput(lines), "s15", base);
        assertThat(w.raw()).containsAll(base).contains(lines.get(30), lines.get(31));
        assertThat(w.raw().get(0).startMs()).isGreaterThan(0);
        assertThat(w.raw().get(w.raw().size() - 1).endMs() - w.raw().get(0).startMs())
                .isLessThanOrEqualTo(CandidateContextWindow.MAX_SPAN_MS);
        assertThat(w.raw()).hasSizeLessThanOrEqualTo(CandidateContextWindow.MAX_SEGMENTS);
        assertThat(w.limited()).isTrue();
    }
    @Test void usesAvailableEarlierContextWhenContinuationEnds() {
        var lines = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> speech(i, i * 2000L, "대화 " + i)).toList();
        var w = CandidateContextWindow.extend(new ReviewInput(lines), "s19", List.of(lines.get(19)));
        assertThat(w.raw()).containsExactlyElementsOf(lines);
        assertThat(w.limited()).isFalse();
    }
    ReviewInput.Segment speech(int i, long time, String text) {
        return new ReviewInput.Segment("s" + i, TimelineEventType.SPEECH, time, time + 1500, text, null);
    }
    @Test void extendsBothSidesWithoutDroppingOriginalEvidenceAndNeverAddsOcr() {
        List<ReviewInput.Segment> speech = new ArrayList<>();
        for (int i = 0; i < 40; i++) speech.add(speech(i, i * 2000L, "지역과 선택지 이야기 " + i));
        List<ReviewInput.Segment> all = new ArrayList<>(speech);
        all.add(new ReviewInput.Segment("ocr", TimelineEventType.CAPTION, 20000, 21000, "OCR 문구", .9));
        var base = speech.subList(15, 25);
        var window = CandidateContextWindow.extend(new ReviewInput(all), "s20", base);
        assertThat(window.raw()).containsAll(base).doesNotContain(all.get(40));
        assertThat(window.raw().get(0).startMs()).isLessThan(base.get(0).startMs());
        assertThat(window.raw().get(window.raw().size() - 1).endMs()).isGreaterThan(base.get(9).endMs());
        assertThat(window.raw().get(window.raw().size() - 1).endMs() - window.raw().get(0).startMs()).isLessThanOrEqualTo(60000);
        assertThat(window.expanded()).isTrue();
        assertThat(window.limited()).isTrue();
    }
    @Test void doesNotJumpLongSilenceToAnotherScene() {
        var a = speech(0, 0, "앞 이야기"); var b = speech(1, 60000, "다른 이야기");
        var w = CandidateContextWindow.extend(new ReviewInput(List.of(a, b)), "s0", List.of(a));
        assertThat(w.raw()).containsExactly(a);
        assertThat(w.limited()).isFalse();
    }
    @Test void textBudgetCountsUnicodeCodePointsAndRetainsWholeSegments() {
        var lines = java.util.stream.IntStream.range(0, 5).mapToObj(i -> speech(i, i * 2000, "😀".repeat(2000))).toList();
        var w = CandidateContextWindow.extend(new ReviewInput(lines), "s2", List.of(lines.get(2)));
        assertThat(w.raw()).hasSize(3);
        assertThat(w.limited()).isTrue();
        assertThat(w.raw()).allSatisfy(s -> assertThat(s.text().codePointCount(0, s.text().length())).isEqualTo(2000));
    }
    @Test void segmentBudgetIsEnforcedForDenseSpeech() {
        var lines = java.util.stream.IntStream.range(0, 80).mapToObj(i -> speech(i, i * 100, "말" + i)).toList();
        var w = CandidateContextWindow.extend(new ReviewInput(lines), "s40", List.of(lines.get(40)));
        assertThat(w.raw()).hasSize(48);
        assertThat(w.limited()).isTrue();
    }
    @Test void skippedInteriorRawIsRestoredRatherThanSplicingDistantQuotes() {
        var lines = java.util.stream.IntStream.range(0, 5).mapToObj(i -> speech(i, i * 2000, "말" + i)).toList();
        var w = CandidateContextWindow.extend(new ReviewInput(lines), "s2", List.of(lines.get(0), lines.get(2), lines.get(4)));
        assertThat(w.raw()).containsExactlyElementsOf(lines);
    }
    @Test void impossibleContiguousBaselineIsNotExpandedAndMarksLimited() {
        var a = speech(0, 0, "앞 이야기"); var b = speech(1, 100000, "다른 이야기");
        var w = CandidateContextWindow.extend(new ReviewInput(List.of(a, b)), "s0", List.of(a, b));
        assertThat(w.raw()).containsExactly(a, b);
        assertThat(w.expanded()).isFalse();
        assertThat(w.limited()).isTrue();
    }
}
