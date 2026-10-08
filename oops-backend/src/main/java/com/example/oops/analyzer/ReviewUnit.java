package com.example.oops.analyzer;

import java.util.*;

/** Bounded reading windows, not inferred sentences, speakers, or scene boundaries. */
record ReviewUnit(String anchorId, List<String> segmentIds, long startMs, long endMs, boolean limited, View view) {
    enum View { CENTRED, TRAILING, LEADING }
    static final long MAX_SPAN_MS = 30_000;
    private static final long MAX_GAP_MS = 5_000;
    private static final int MAX_SEGMENTS = 32;
    private static final int MAX_CODE_POINTS = 4_000;

    ReviewUnit { segmentIds = List.copyOf(segmentIds); }

    static List<ReviewUnit> plan(TextReviewBatchPlanner.Batch batch) {
        List<ReviewInput.Segment> supplied = new ArrayList<>(batch.primary());
        supplied.addAll(batch.context());
        supplied.sort(Comparator.comparingLong(ReviewInput.Segment::startMs)
                .thenComparingLong(ReviewInput.Segment::endMs).thenComparing(ReviewInput.Segment::id));
        return batch.primary().stream().map(anchor -> window(anchor, supplied, batch.contextLimited(), View.CENTRED)).toList();
    }

    /** Alternate bounded views for speech; IDs reference raw primary/context, not generated summaries. */
    static List<ReviewUnit> dialoguePlan(TextReviewBatchPlanner.Batch batch) {
        List<ReviewInput.Segment> supplied = new ArrayList<>(batch.primary());
        supplied.addAll(batch.context());
        supplied.sort(Comparator.comparingLong(ReviewInput.Segment::startMs)
                .thenComparingLong(ReviewInput.Segment::endMs).thenComparing(ReviewInput.Segment::id));
        List<ReviewUnit> result = new ArrayList<>();
        for (var anchor : batch.primary()) {
            if (anchor.type() != com.example.oops.domain.TimelineEventType.SPEECH) continue;
            Set<List<String>> seen = new HashSet<>();
            seen.add(window(anchor, supplied, batch.contextLimited(), View.CENTRED).segmentIds());
            for (var view : List.of(View.TRAILING, View.LEADING)) {
                var unit = window(anchor, supplied, batch.contextLimited(), view);
                if (seen.add(unit.segmentIds())) result.add(unit);
            }
        }
        return List.copyOf(result);
    }

    static List<ReviewUnit> all(TextReviewBatchPlanner.Batch batch) {
        List<ReviewUnit> units = new ArrayList<>(plan(batch));
        units.addAll(dialoguePlan(batch));
        return List.copyOf(units);
    }

    private static ReviewUnit window(ReviewInput.Segment anchor, List<ReviewInput.Segment> supplied, boolean limited, View view) {
        var source = supplied.stream().filter(s -> s.type() == anchor.type()).toList();
        int left = source.indexOf(anchor), right = left;
        long start = anchor.startMs(), end = anchor.endMs();
        int characters = size(anchor);
        boolean blockedLeft = false, blockedRight = false;
        limited |= end - start > MAX_SPAN_MS || characters > MAX_CODE_POINTS;
        while (true) {
            ReviewInput.Segment before = left > 0 && !blockedLeft ? source.get(left - 1) : null;
            ReviewInput.Segment after = right + 1 < source.size() && !blockedRight ? source.get(right + 1) : null;
            if (before == null && after == null) break;
            boolean takeLeft = before != null && (after == null || view == View.TRAILING
                    || view == View.CENTRED && Math.max(0, anchor.startMs() - before.endMs())
                    <= Math.max(0, after.startMs() - anchor.endMs()));
            var next = takeLeft ? before : after;
            long gap = takeLeft ? Math.max(0, start - next.endMs()) : Math.max(0, next.startMs() - end);
            long nextStart = Math.min(start, next.startMs()), nextEnd = Math.max(end, next.endMs());
            if (gap > MAX_GAP_MS || nextEnd - nextStart > MAX_SPAN_MS
                    || right - left + 1 >= MAX_SEGMENTS || characters + size(next) > MAX_CODE_POINTS) {
                if (gap <= MAX_GAP_MS) limited = true;
                if (takeLeft) blockedLeft = true; else blockedRight = true;
                continue;
            }
            if (takeLeft) left--; else right++;
            start = nextStart; end = nextEnd; characters += size(next);
        }
        return new ReviewUnit(anchor.id(), source.subList(left, right + 1).stream()
                .map(ReviewInput.Segment::id).toList(), start, end, limited, view);
    }

    private static int size(ReviewInput.Segment s) { return s.text().codePointCount(0, s.text().length()); }
}
