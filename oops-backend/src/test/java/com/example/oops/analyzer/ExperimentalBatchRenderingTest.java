package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Rendering contract for experimental batches: roles, gap markers, budgeted context. Not accuracy. */
class ExperimentalBatchRenderingTest {
    private static ReviewInput.Segment speech(int index, long startMs, String text) {
        return new ReviewInput.Segment("stt-index-" + index, TimelineEventType.SPEECH, startMs, startMs + 1500, text, null);
    }

    private final ReviewInput transcript = new ReviewInput(List.of(
            speech(0, 0, "앞 발언"), speech(1, 2_000, "바로 다음"), speech(2, 4_000, "건너뛴 발언"),
            speech(3, 6_000, "또 건너뜀"), speech(4, 480_000, "대상 발언")));
    private final ExperimentalBatchRendering rendering = new ExperimentalBatchRendering(transcript, Set.of("stt-index-4"));

    private List<ReviewInput.Segment> supplied(int... indexes) {
        return Arrays.stream(indexes).mapToObj(i -> transcript.segments().get(i)).toList();
    }

    @Test void rendersChronologicallyWithJudgedAndContextOnlyRoles() {
        var rows = rendering.render(supplied(4, 0, 1));
        assertThat(rows).extracting(r -> r.get("id")).containsExactly("stt-index-0", "stt-index-1", "stt-index-4");
        assertThat(rows).extracting(r -> r.get("batchRole")).containsExactly("CONTEXT_ONLY", "CONTEXT_ONLY", "JUDGED");
        assertThat(rows.get(0)).containsKeys("id", "startMs", "endMs", "text");
    }

    @Test void marksOmittedTranscriptSegmentsBetweenSuppliedRowsOnly() {
        var rows = rendering.render(supplied(0, 1, 4));
        assertThat(rows.get(0)).doesNotContainKey("omittedBefore");
        assertThat(rows.get(1)).doesNotContainKey("omittedBefore");
        assertThat(rows.get(2).get("omittedBefore")).isEqualTo(Map.of("segments", 2, "gapMs", 480_000L - 3_500L));
    }

    @Test void budgetedContextCoversContextRowsAndTheirMarkersButNotTheJudgedSegment() {
        var budgeted = rendering.budgetedContext(supplied(0, 4));
        assertThat(budgeted).contains("앞 발언", "CONTEXT_ONLY").doesNotContain("대상 발언", "JUDGED");
        var markerOnContext = new ExperimentalBatchRendering(transcript, Set.of("stt-index-0"))
                .budgetedContext(supplied(0, 4));
        assertThat(markerOnContext).contains("대상 발언", "omittedBefore");
    }

    @Test void systemContractIsAppendedOnceAndVersioned() {
        String system = rendering.system("BASE");
        assertThat(system).startsWith("BASE").contains(ExperimentalBatchRendering.VERSION);
        assertThat(system.split(ExperimentalBatchRendering.VERSION, -1)).hasSize(2);
    }

    @Test void rejectsSegmentsOutsideTheTranscript() {
        var foreign = speech(9, 900_000, "없는 구간");
        assertThatThrownBy(() -> rendering.render(List.of(foreign))).isInstanceOf(IllegalArgumentException.class);
    }
}
