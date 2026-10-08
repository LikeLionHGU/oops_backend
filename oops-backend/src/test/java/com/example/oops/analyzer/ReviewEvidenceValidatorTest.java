package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.example.oops.analyzer.ReviewEvaluation.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewEvidenceValidatorTest {
    private final ReviewInput input = new ReviewInput(List.of(
            new ReviewInput.Segment("stt-1", TimelineEventType.SPEECH, 0, 1, "😀돈이 아깝다", null)));

    private ReviewEvaluation result(Decision decision, EvidenceSpan span, List<String> missing) {
        return new ReviewEvaluation("reviewer", ExecutionStatus.SUCCESS, List.of("stt-1"),
                List.of(new Observation("stt-1", decision, "구체적 근거", List.of(span), missing)));
    }

    @Test
    void acceptsCodePointOffsetsIncludingEmojiAndExplicitPass() {
        var result = result(Decision.PASS, new EvidenceSpan("stt-1", "돈이 아깝다", 1, 7), List.of());
        assertThat(ReviewEvidenceValidator.validate(input, result)).isEmpty();
        assertThat(result.observations().get(0).decision()).isEqualTo(Decision.PASS);
    }

    @Test
    void rejectsInventedQuoteNormalizedQuoteAndInvalidRanges() {
        for (var span : List.of(new EvidenceSpan("stt-1", "사기다", 1, 4),
                new EvidenceSpan("stt-1", "돈이아깝다", 1, 7), new EvidenceSpan("stt-1", "돈", -1, 2),
                new EvidenceSpan("stt-1", "돈", 1, 99), new EvidenceSpan("stt-1", "", 1, 1))) {
            assertThat(ReviewEvidenceValidator.validate(input, result(Decision.REVIEW_REQUIRED, span, List.of()))).isNotEmpty();
        }
    }

    @Test
    void rejectsUnknownIdAndRequiresInformationForUncertainty() {
        assertThat(ReviewEvidenceValidator.validate(input,
                result(Decision.REVIEW_REQUIRED, new EvidenceSpan("missing", "돈", 1, 2), List.of()))).isNotEmpty();
        var span = new EvidenceSpan("stt-1", "돈", 1, 2);
        assertThat(ReviewEvidenceValidator.validate(input, result(Decision.UNCERTAIN, span, List.of()))).isNotEmpty();
        assertThat(ReviewEvidenceValidator.validate(input, result(Decision.UNCERTAIN, span, List.of("지칭 대상")))).isEmpty();
    }

    @Test
    void failedAndUnassessedExecutionNeverPublishPass() {
        var pass = result(Decision.PASS, new EvidenceSpan("stt-1", "돈", 1, 2), List.of());
        for (var status : List.of(ExecutionStatus.FAILED, ExecutionStatus.NOT_ASSESSED)) {
            assertThat(ReviewEvidenceValidator.validate(input,
                    new ReviewEvaluation("reviewer", status, List.of("stt-1"), pass.observations()))).isNotEmpty();
            var empty = new ReviewEvaluation("reviewer", status, List.of(), List.of());
            assertThat(ReviewEvidenceValidator.validate(input, empty)).isEmpty();
            assertThat(empty.observations()).isEmpty();
        }
    }

    @Test
    void emptySuccessDoesNotSynthesizePassAndNeedsReviewedIds() {
        var empty = new ReviewEvaluation("reviewer", ExecutionStatus.SUCCESS, List.of("stt-1"), List.of());
        assertThat(ReviewEvidenceValidator.validate(input, empty)).isEmpty();
        assertThat(empty.observations()).isEmpty();
        assertThat(ReviewEvidenceValidator.validate(input,
                new ReviewEvaluation("reviewer", ExecutionStatus.SUCCESS, List.of(), List.of()))).isNotEmpty();
    }

    @Test
    void rejectsUnknownOrDuplicateReviewedIdsAndUnreviewedAnchor() {
        assertThat(ReviewEvidenceValidator.validate(input,
                new ReviewEvaluation("reviewer", ExecutionStatus.SUCCESS, List.of("missing"), List.of()))).isNotEmpty();
        assertThat(ReviewEvidenceValidator.validate(input,
                new ReviewEvaluation("reviewer", ExecutionStatus.SUCCESS, List.of("stt-1", "stt-1"), List.of()))).isNotEmpty();
        var observation = new Observation("missing", Decision.REVIEW_REQUIRED, "근거",
                List.of(new EvidenceSpan("stt-1", "돈", 1, 2)), List.of());
        assertThat(ReviewEvidenceValidator.validate(input,
                new ReviewEvaluation("reviewer", ExecutionStatus.SUCCESS, List.of("stt-1"), List.of(observation))))
                .anyMatch(error -> error.contains("Unreviewed anchor"));
    }

    @Test
    void suppliedContextCanSupportEvidenceWithoutBeingMarkedReviewed() {
        var input = new ReviewInput(List.of(
                new ReviewInput.Segment("a", TimelineEventType.SPEECH, 0, 1, "그 지역 주민", null),
                new ReviewInput.Segment("b", TimelineEventType.SPEECH, 2, 3, "열등하다", null)));
        var observation = new Observation("b", Decision.REVIEW_REQUIRED, "주민을 낮춰 부르는 발언",
                List.of(new EvidenceSpan("b", "열등하다", 0, 4),
                        new EvidenceSpan("a", "그 지역 주민", 0, 7, EvidenceRole.TARGET)), List.of());
        var result = new ReviewEvaluation("reviewer", ExecutionStatus.SUCCESS, List.of("b"),
                List.of(observation), List.of("a", "b"));
        assertThat(ReviewEvidenceValidator.validate(input, result)).isEmpty();
        assertThat(result.reviewedSegmentIds()).containsExactly("b");
        assertThat(ReviewEvidenceValidator.validate(input, new ReviewEvaluation("reviewer", ExecutionStatus.SUCCESS,
                List.of("b"), List.of(observation), List.of("b"))))
                .anyMatch(error -> error.contains("Unsupplied evidence"));
    }
}
