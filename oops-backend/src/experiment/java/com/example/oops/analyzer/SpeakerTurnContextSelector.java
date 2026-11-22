package com.example.oops.analyzer;

import java.util.*;

/**
 * A6-H-T: manual speaker labels (written blind to risk labels, bound to frozen STT segment IDs)
 * set priority. Tiers: rest of the target's own turn, the turns right before and after it, the same
 * speaker's other segments, then everything else; nearest first within a tier, then transcript order.
 * A turn is a maximal run of one speaker; each ambiguous/multi-speaker segment is its own turn and
 * never matches a speaker. Greedy under budget B, skip what does not fit, never truncate.
 * Oracle-assisted diagnostic: not equivalent to a text-only production condition.
 */
final class SpeakerTurnContextSelector implements ContextSelector {
    private static final String POLICY = "speaker-turn-v1";

    record SpeakerLabel(String speakerId, boolean ambiguous) {
        SpeakerLabel {
            if (!ambiguous && (speakerId == null || speakerId.isBlank())) throw new IllegalArgumentException("Speaker ID required");
            if (ambiguous) speakerId = null;
        }

        static SpeakerLabel of(String speakerId) {
            return new SpeakerLabel(speakerId, false);
        }

        static SpeakerLabel unclear() {
            return new SpeakerLabel(null, true);
        }

        boolean sameSpeaker(SpeakerLabel other) {
            return !ambiguous && !other.ambiguous && speakerId.equals(other.speakerId);
        }
    }

    private final Map<String, SpeakerLabel> labels;
    private final String labelVersion;
    private final ContextTokenBudget budget;

    SpeakerTurnContextSelector(Map<String, SpeakerLabel> labels, String labelVersion, ContextTokenBudget budget) {
        this.labels = Map.copyOf(labels);
        if (labelVersion == null || labelVersion.isBlank()) throw new IllegalArgumentException("Label version required");
        this.labelVersion = labelVersion;
        this.budget = Objects.requireNonNull(budget);
    }

    String id() {
        return "A6-H-T:" + POLICY + ":labels=" + labelVersion + ":" + ContextTokenBudget.COUNTER_ID + ":B=" + budget.budget();
    }

    @Override
    public ContextSelection select(ReviewInput.Segment target, ReviewInput transcript) {
        var rendering = new ExperimentalBatchRendering(transcript, Set.of(target.id()));
        List<ReviewInput.Segment> chosen = new ArrayList<>();
        for (String id : priority(target, transcript)) {
            var candidate = transcript.find(id).orElseThrow();
            List<ReviewInput.Segment> raw = new ArrayList<>(chosen);
            raw.add(target);
            raw.add(candidate);
            if (!budget.measure(rendering, raw).exceeded()) chosen.add(candidate);
        }
        var order = transcript.segments();
        var ids = chosen.stream().sorted(Comparator.comparingInt(order::indexOf)).map(ReviewInput.Segment::id).toList();
        return new ContextSelection(ids, id());
    }

    /** Every non-target segment, highest priority first. */
    List<String> priority(ReviewInput.Segment target, ReviewInput transcript) {
        var order = transcript.segments();
        int targetIndex = order.indexOf(target);
        if (targetIndex < 0) throw new IllegalArgumentException("Target outside transcript: " + target.id());
        List<SpeakerLabel> segmentLabels = order.stream().map(s -> {
            var label = labels.get(s.id());
            if (label == null) throw new IllegalArgumentException("No speaker label for segment: " + s.id());
            return label;
        }).toList();
        int[] turn = turns(segmentLabels);
        var targetLabel = segmentLabels.get(targetIndex);
        List<Integer> others = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) if (i != targetIndex) others.add(i);
        others.sort(Comparator.<Integer>comparingInt(i -> tier(turn[i], turn[targetIndex], segmentLabels.get(i).sameSpeaker(targetLabel)))
                .thenComparingLong(i -> distance(target, order.get(i)))
                .thenComparingInt(i -> i));
        return others.stream().map(i -> order.get(i).id()).toList();
    }

    private static int tier(int turn, int targetTurn, boolean sameSpeaker) {
        if (turn == targetTurn) return 0;
        if (Math.abs(turn - targetTurn) == 1) return 1;
        return sameSpeaker ? 2 : 3;
    }

    private static int[] turns(List<SpeakerLabel> labels) {
        int[] turn = new int[labels.size()];
        for (int i = 1; i < labels.size(); i++)
            turn[i] = labels.get(i).sameSpeaker(labels.get(i - 1)) ? turn[i - 1] : turn[i - 1] + 1;
        return turn;
    }

    private static long distance(ReviewInput.Segment target, ReviewInput.Segment other) {
        return other.startMs() >= target.endMs() ? other.startMs() - target.endMs()
                : Math.max(0, target.startMs() - other.endMs());
    }
}
