package com.example.oops.analyzer;

import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

class ReviewInputTest {
    private ReviewInput.Segment speech(String id, long start, long end, String text) {
        return new ReviewInput.Segment(id, TimelineEventType.SPEECH, start, end, text, null);
    }

    @Test
    void preservesRawTextAndMultipleNeighborsAndCaptionsInChronologicalOrder() {
        var input = new ReviewInput(List.of(speech("anchor", 10_000, 11_000, "  원문 😀 "),
                speech("before1", 5_000, 6_000, "앞1"), speech("before2", 7_000, 8_000, "앞2"),
                speech("after1", 12_000, 13_000, "뒤1"), speech("after2", 14_000, 15_000, "뒤2"),
                speech("distant", 50_000, 51_000, "먼 발언"),
                new ReviewInput.Segment("ocr1", TimelineEventType.CAPTION, 9_000, 12_000, "자막1", 0.8),
                new ReviewInput.Segment("ocr2", TimelineEventType.CAPTION, 10_500, 11_500, "자막2", 0.5)));
        var window = input.contextFor("anchor");
        assertThat(window.anchor().text()).isEqualTo("  원문 😀 ");
        assertThat(window.before()).extracting(ReviewInput.Segment::id).containsExactly("before1", "before2");
        assertThat(window.after()).extracting(ReviewInput.Segment::id).containsExactly("after1", "after2");
        assertThat(window.related()).extracting(ReviewInput.Segment::id).containsExactly("ocr1", "ocr2");
        assertThat(window.related().get(1).confidence()).isEqualTo(0.5);
        assertThat(window.omittedSegments()).isZero();
    }

    @Test
    void supportsSttOnlyOcrOnlyAndEmptyInputWithDistinctFallbackIds() {
        var transcript = List.of(new TranscriptSegment(null, 0, 1, "발언"));
        var screen = List.of(new ScreenText(null, 0, 1, "자막", 0.9, null));
        assertThat(ReviewInput.from(transcript, null).segments()).extracting(ReviewInput.Segment::id).containsExactly("stt-index-0");
        assertThat(ReviewInput.from(null, screen).segments()).extracting(ReviewInput.Segment::id).containsExactly("ocr-index-0");
        assertThat(ReviewInput.from(transcript, screen).segments()).hasSize(2);
        assertThat(ReviewInput.from(null, null).segments()).isEmpty();
    }

    @Test
    void snapshotIsImmutableAndBlankTextDoesNotBecomeEvidence() {
        var source = new ArrayList<>(List.of(new TranscriptSegment(null, 0, 1, "원문"),
                new TranscriptSegment(null, 1, 2, " ")));
        var context = new AnalysisContext(null, null, source, null);
        source.clear();
        assertThat(context.transcript()).hasSize(2);
        assertThat(context.reviewInput().segments()).hasSize(1);
        assertThatThrownBy(() -> context.reviewInput().segments().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void boundsContextAndReportsOmissionWithoutClippingRawSegments() {
        var segments = new ArrayList<ReviewInput.Segment>();
        segments.add(speech("anchor", 10_000, 11_000, "기준"));
        IntStream.range(0, 10).forEach(i -> segments.add(speech("s" + i, i * 500, i * 500 + 100, "앞")));
        var window = new ReviewInput(segments).contextFor("anchor");
        assertThat(window.before()).hasSize(8);
        assertThat(window.omittedSegments()).isEqualTo(2);
        assertThat(window.before()).extracting(ReviewInput.Segment::id).containsExactly("s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9");
        var oversized = new ReviewInput(List.of(speech("anchor", 10, 11, "기준"), speech("long", 0, 1, "가".repeat(4_001))));
        assertThat(oversized.contextFor("anchor").before()).isEmpty();
        assertThat(oversized.contextFor("anchor").omittedSegments()).isOne();
        assertThat(oversized.find("long").orElseThrow().text()).hasSize(4_001);
    }

    @Test
    void rejectsDuplicateIdsInvalidTimesAndUnknownAnchor() {
        var s = speech("same", 0, 1, "원문");
        assertThatThrownBy(() -> new ReviewInput(List.of(s, s))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> speech("bad", 2, 1, "원문")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewInput(List.of(s)).contextFor("missing")).isInstanceOf(IllegalArgumentException.class);
    }
}
