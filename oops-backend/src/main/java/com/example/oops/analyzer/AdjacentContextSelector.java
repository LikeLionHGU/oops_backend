package com.example.oops.analyzer;

import java.util.*;

/**
 * A1-T: grow one contiguous block outward from the target, nearest neighbor in time first
 * (ties go to earlier speech). Whole segments only; a side stops at its first neighbor that
 * would push the re-rendered context over budget B.
 */
final class AdjacentContextSelector implements ContextSelector {
    private static final String POLICY = "adjacent-nearest-v1";
    private final ContextTokenBudget budget;

    AdjacentContextSelector(ContextTokenBudget budget) {
        this.budget = Objects.requireNonNull(budget);
    }

    String id() {
        return "A1-T:" + POLICY + ":" + ContextTokenBudget.COUNTER_ID + ":B=" + budget.budget();
    }

    @Override
    public ContextSelection select(ReviewInput.Segment target, ReviewInput transcript) {
        var ordered = transcript.segments();
        int center = ordered.indexOf(target);
        if (center < 0) throw new IllegalArgumentException("Target outside transcript: " + target.id());
        var rendering = new ExperimentalBatchRendering(transcript, Set.of(target.id()));
        int left = center - 1, right = center + 1;
        boolean leftOpen = left >= 0, rightOpen = right < ordered.size();
        List<ReviewInput.Segment> chosen = new ArrayList<>();
        while (leftOpen || rightOpen) {
            boolean takeLeft = leftOpen && (!rightOpen
                    || gap(ordered.get(left), target) <= gap(target, ordered.get(right)));
            var next = ordered.get(takeLeft ? left : right);
            if (fits(rendering, target, chosen, next)) {
                chosen.add(next);
                if (takeLeft) leftOpen = --left >= 0;
                else rightOpen = ++right < ordered.size();
            } else if (takeLeft) leftOpen = false;
            else rightOpen = false;
        }
        var ids = chosen.stream().sorted(Comparator.comparingInt(ordered::indexOf)).map(ReviewInput.Segment::id).toList();
        return new ContextSelection(ids, id());
    }

    private boolean fits(ExperimentalBatchRendering rendering, ReviewInput.Segment target,
                         List<ReviewInput.Segment> chosen, ReviewInput.Segment next) {
        List<ReviewInput.Segment> raw = new ArrayList<>(chosen);
        raw.add(target);
        raw.add(next);
        return !budget.measure(rendering, raw).exceeded();
    }

    private static long gap(ReviewInput.Segment earlier, ReviewInput.Segment later) {
        return Math.max(0, later.startMs() - earlier.endMs());
    }
}
