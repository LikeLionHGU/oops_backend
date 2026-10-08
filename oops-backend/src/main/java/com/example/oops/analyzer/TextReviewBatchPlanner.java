package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import java.util.*;

/** Shared primary-input batching and bounded cross-source context; no keyword gate. */
final class TextReviewBatchPlanner {
    private static final int MAX_PRIMARY = 20;
    private static final int MAX_PRIMARY_CODE_POINTS = 12_000;
    private static final long MAX_TIME_SPAN_MS = 120_000;
    private static final int MAX_CONTEXT = 16;
    private static final int MAX_CONTEXT_CODE_POINTS = 8_000;

    static List<Batch> plan(ReviewInput input, TimelineEventType type, int overlap) {
        if (overlap < 0 || overlap >= MAX_PRIMARY) throw new IllegalArgumentException("Invalid overlap");
        List<ReviewInput.Segment> primary = input.segments().stream().filter(s -> s.type() == type && s.reviewTarget()).toList();
        List<Batch> batches = new ArrayList<>();
        for (int start = 0; start < primary.size();) {
            int end = start, characters = 0;
            long latestEnd = primary.get(start).endMs();
            while (end < primary.size() && end - start < MAX_PRIMARY) {
                var s = primary.get(end);
                int size = s.text().codePointCount(0, s.text().length());
                long nextEnd = Math.max(latestEnd, s.endMs());
                if (end > start && (characters + size > MAX_PRIMARY_CODE_POINTS
                        || nextEnd - primary.get(start).startMs() > MAX_TIME_SPAN_MS)) break;
                characters += size;
                latestEnd = nextEnd;
                end++;
            }
            batches.add(withContext(input, primary.subList(start, end)));
            if (end == primary.size()) break;
            start = Math.max(start + 1, end - overlap);
        }
        return List.copyOf(batches);
    }

    static Batch withContext(ReviewInput input, List<ReviewInput.Segment> primary) {
        Set<String> primaryIds = new HashSet<>();
        primary.forEach(s -> primaryIds.add(s.id()));
        Map<String, ReviewInput.Segment> nearby = new LinkedHashMap<>();
        boolean limited = false;
        for (var anchor : primary) {
            var window = input.contextFor(anchor.id(), primaryIds);
            limited |= window.omittedSegments() > 0;
            for (var group : List.of(window.before(), window.after(), window.related())) {
                for (var s : group) if (!primaryIds.contains(s.id())) nearby.putIfAbsent(s.id(), s);
            }
        }
        if (!primary.isEmpty() && primary.get(0).type() == TimelineEventType.SPEECH) {
            long start = primary.stream().mapToLong(ReviewInput.Segment::startMs).min().orElseThrow();
            long end = primary.stream().mapToLong(ReviewInput.Segment::endMs).max().orElseThrow();
            // Short per-segment context can omit the beginning of a chain at a batch boundary.
            // Select candidates using the same bounded windows, then apply the shared 16/8000 budget.
            var surrounding = input.segments().stream().filter(s -> s.type() == TimelineEventType.SPEECH
                    && !primaryIds.contains(s.id()) && s.endMs() >= start - ReviewUnit.MAX_SPAN_MS
                    && s.startMs() <= end + ReviewUnit.MAX_SPAN_MS).toList();
            var provisional = new Batch(primary, surrounding, false, false);
            Set<String> windowIds = new HashSet<>();
            ReviewUnit.all(provisional).forEach(u -> windowIds.addAll(u.segmentIds()));
            surrounding.stream().filter(s -> windowIds.contains(s.id())).forEach(s -> nearby.putIfAbsent(s.id(), s));
        }
        List<ReviewInput.Segment> selected = new ArrayList<>();
        int remaining = MAX_CONTEXT_CODE_POINTS;
        for (var s : nearby.values().stream().sorted(Comparator
                // Keep the speech chain before dense nearby OCR consumes the bounded context budget.
                .comparingInt((ReviewInput.Segment s) -> !primary.isEmpty()
                        && primary.get(0).type() == TimelineEventType.SPEECH
                        && s.type() != TimelineEventType.SPEECH ? 1 : 0)
                .thenComparingLong((ReviewInput.Segment s) -> primary.stream()
                        .mapToLong(p -> Math.max(0, Math.max(s.startMs() - p.endMs(), p.startMs() - s.endMs())))
                        .min().orElseThrow()).thenComparingLong(ReviewInput.Segment::startMs)
                .thenComparing(ReviewInput.Segment::id)).toList()) {
            int size = s.text().codePointCount(0, s.text().length());
            if (selected.size() < MAX_CONTEXT && size <= remaining) {
                selected.add(s);
                remaining -= size;
            }
        }
        limited |= selected.size() < nearby.size();
        selected.sort(Comparator.comparingLong(ReviewInput.Segment::startMs).thenComparing(ReviewInput.Segment::id));
        boolean oversized = primary.stream().anyMatch(s -> s.text().codePointCount(0, s.text().length()) > MAX_PRIMARY_CODE_POINTS
                || s.endMs() - s.startMs() > MAX_TIME_SPAN_MS);
        return new Batch(primary, selected, limited, oversized);
    }

    record Batch(List<ReviewInput.Segment> primary, List<ReviewInput.Segment> context,
                 boolean contextLimited, boolean oversizedPrimary) {
        Batch { primary = List.copyOf(primary); context = List.copyOf(context); }
    }
}
