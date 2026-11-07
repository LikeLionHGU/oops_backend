package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

/** T0: the judged segment alone, with no context, whatever the page plan says. */
class TargetOnlyContextSelectorTest {
    private static ReviewInput transcript(int count) {
        return new ReviewInput(IntStream.range(0, count)
                .mapToObj(i -> new ReviewInput.Segment("stt-index-" + i, TimelineEventType.SPEECH, i * 2_000L, i * 2_000L + 1500,
                        "발언 내용 " + i, null))
                .toList());
    }

    @Test void selectsNoContextAndBuildsATargetOnlyBatch() {
        var t = transcript(5);
        var target = t.find("stt-index-2").orElseThrow();
        var selection = new TargetOnlyContextSelector().select(target, t);
        assertThat(selection.contextSegmentIds()).isEmpty();
        assertThat(selection.status()).isEqualTo(ContextSelection.Status.SELECTED);
        assertThat(selection.selectorId()).isEqualTo("T0:target-only-v1");
        var batch = selection.toBatch(target, t);
        assertThat(batch.primaryIds()).containsExactly("stt-index-2");
        assertThat(batch.contextIds()).isEmpty();
    }

    @Test void rejectsATargetOutsideTheTranscript() {
        var t = transcript(3);
        var outside = new ReviewInput.Segment("stt-index-9", TimelineEventType.SPEECH, 0, 1, "밖", null);
        assertThatThrownBy(() -> new TargetOnlyContextSelector().select(outside, t))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stt-index-9");
    }
}
