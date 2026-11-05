package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** A6-H-T: manual speaker turns decide priority (own turn, adjacent replies, same speaker elsewhere). Shape only. */
class SpeakerTurnContextSelectorTest {
    private static final int LARGE = 1_000_000;
    private static final SpeakerTurnContextSelector.SpeakerLabel A = SpeakerTurnContextSelector.SpeakerLabel.of("A");
    private static final SpeakerTurnContextSelector.SpeakerLabel B = SpeakerTurnContextSelector.SpeakerLabel.of("B");
    private static final SpeakerTurnContextSelector.SpeakerLabel C = SpeakerTurnContextSelector.SpeakerLabel.of("C");
    private static final SpeakerTurnContextSelector.SpeakerLabel UNKNOWN = SpeakerTurnContextSelector.SpeakerLabel.unclear();

    private static ReviewInput.Segment speech(int index, long startMs, String text) {
        return new ReviewInput.Segment("stt-index-" + index, TimelineEventType.SPEECH, startMs, startMs + 1500, text, null);
    }

    /** A generalizes, B objects, C chats nearby, A retracts much later. */
    private final ReviewInput transcript = new ReviewInput(List.of(
            speech(0, 0, "요즘 신입들은 끈기가 없어"),
            speech(1, 2_000, "그건 너무 일반화야"),
            speech(2, 4_000, "점심 뭐 먹지"),
            speech(3, 6_000, "날씨 좋네"),
            speech(4, 8_000, "커피 마실래"),
            speech(5, 300_000, "맞아 일부 사례만 보고 말했네")));
    private final Map<String, SpeakerTurnContextSelector.SpeakerLabel> labels = Map.of(
            "stt-index-0", A, "stt-index-1", B, "stt-index-2", C, "stt-index-3", C, "stt-index-4", C, "stt-index-5", A);

    private ReviewInput.Segment seg(ReviewInput t, int i) {
        return t.segments().get(i);
    }

    private int tokens(ReviewInput t, ReviewInput.Segment target, int... context) {
        List<ReviewInput.Segment> raw = new ArrayList<>(List.of(target));
        Arrays.stream(context).forEach(i -> raw.add(seg(t, i)));
        return new ContextTokenBudget(LARGE).measure(new ExperimentalBatchRendering(t, Set.of(target.id())), raw).contextTokens();
    }

    private SpeakerTurnContextSelector selector(Map<String, SpeakerTurnContextSelector.SpeakerLabel> l, int budget) {
        return new SpeakerTurnContextSelector(l, "speaker-v0", new ContextTokenBudget(budget));
    }

    @Test void prefersTheReplyAndTheSameSpeakersDistantRetractionOverNearbyChatter() {
        var target = seg(transcript, 0);
        int budget = tokens(transcript, target, 1, 5);
        var selected = selector(labels, budget).select(target, transcript).contextSegmentIds();
        assertThat(selected).containsExactly("stt-index-1", "stt-index-5");
    }

    @Test void ownTurnSegmentsComeFirst() {
        var t = new ReviewInput(List.of(speech(0, 0, "앞 대화"), speech(1, 2_000, "내 말의 앞부분"),
                speech(2, 4_000, "내 말의 뒷부분"), speech(3, 6_000, "다른 사람 대답")));
        var l = Map.of("stt-index-0", B, "stt-index-1", A, "stt-index-2", A, "stt-index-3", B);
        var target = seg(t, 1);
        int onlyOwnTurn = tokens(t, target, 2);
        assertThat(selector(l, onlyOwnTurn).select(target, t).contextSegmentIds()).containsExactly("stt-index-2");
    }

    @Test void ambiguousSegmentsNeverCountAsTheSameSpeaker() {
        var t = new ReviewInput(List.of(speech(0, 0, "대상 발언"), speech(1, 2_000, "섞인 구간"),
                speech(2, 4_000, "다른 대화"), speech(3, 200_000, "누군가의 나중 발언")));
        var l = Map.of("stt-index-0", A, "stt-index-1", UNKNOWN, "stt-index-2", C, "stt-index-3", UNKNOWN);
        var target = seg(t, 0);
        int one = tokens(t, target, 1);
        // The adjacent ambiguous segment is a neighboring turn; the distant ambiguous one is not "speaker A elsewhere".
        assertThat(selector(l, one).select(target, t).contextSegmentIds()).containsExactly("stt-index-1");
        var ranked = selector(l, LARGE).priority(target, t);
        assertThat(ranked).containsExactly("stt-index-1", "stt-index-2", "stt-index-3");
    }

    @Test void greedySkipsWhatDoesNotFitAndReturnsTranscriptOrder() {
        var target = seg(transcript, 0);
        var selection = selector(labels, LARGE).select(target, transcript);
        assertThat(selection.contextSegmentIds()).containsExactly("stt-index-1", "stt-index-2", "stt-index-3", "stt-index-4", "stt-index-5");
        assertThatCode(() -> selection.toBatch(target, transcript)).doesNotThrowAnyException();
        assertThat(selector(labels, 1).select(target, transcript).contextSegmentIds()).isEmpty();
    }

    @Test void requiresALabelForEverySegmentAndRecordsTheLabelVersion() {
        var partial = new HashMap<>(labels);
        partial.remove("stt-index-3");
        assertThatThrownBy(() -> selector(partial, LARGE).select(seg(transcript, 0), transcript))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(selector(labels, 64).id())
                .isEqualTo("A6-H-T:speaker-turn-v1:labels=speaker-v0:" + ContextTokenBudget.COUNTER_ID + ":B=64");
    }
}
