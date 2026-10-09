package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import java.util.*;

/** Bounded speech context with more room for a continuation, not an inferred topic boundary. */
final class CandidateContextWindow {
    static final long MAX_SPAN_MS = 60_000;
    static final int MAX_SEGMENTS = 48;
    static final int MAX_CODE_POINTS = 6_000;
    private static final long MAX_GAP_MS = 5_000;
    record Window(List<ReviewInput.Segment> raw, boolean expanded, boolean limited) {
        Window { raw = List.copyOf(raw); }
    }
    private CandidateContextWindow() {}

    static Window extend(ReviewInput input, String anchorId, List<ReviewInput.Segment> base) {
        var source = input.segments().stream().filter(s -> s.type() == TimelineEventType.SPEECH).toList();
        var anchor = input.find(anchorId).orElseThrow();
        if (anchor.type() != TimelineEventType.SPEECH || base.isEmpty() || !base.contains(anchor)) {
            throw new IllegalArgumentException("A speech anchor and original window are required");
        }
        int left = source.size(), right = -1;
        for (var s : base) {
            int index = source.indexOf(s);
            if (index < 0) throw new IllegalArgumentException("Unknown or non-speech baseline");
            left = Math.min(left, index); right = Math.max(right, index);
        }
        List<ReviewInput.Segment> contiguous = source.subList(left, right + 1);
        if (!fits(contiguous) || hasGap(contiguous)) return new Window(base, false, true);
        int characters = contiguous.stream().mapToInt(CandidateContextWindow::size).sum();
        long start = contiguous.get(0).startMs(), end = contiguous.stream().mapToLong(ReviewInput.Segment::endMs).max().orElseThrow();
        boolean blockedLeft = false, blockedRight = false, limited = false;
        while (true) {
            var before = left > 0 && !blockedLeft ? source.get(left - 1) : null;
            var after = right + 1 < source.size() && !blockedRight ? source.get(right + 1) : null;
            if (before == null && after == null) break;
            // Roughly twice as much reach for following speech. Preserve baseline quotes and
            // allow earlier speech to use spare budget when the continuation ends or hits a gap.
            boolean takeLeft = before != null && (after == null || 2 * Math.max(0, anchor.startMs() - before.endMs())
                    <= Math.max(0, after.startMs() - anchor.endMs()));
            var next = takeLeft ? before : after;
            long gap = takeLeft ? Math.max(0, start - next.endMs()) : Math.max(0, next.startMs() - end);
            long nextStart = Math.min(start, next.startMs()), nextEnd = Math.max(end, next.endMs());
            if (gap > MAX_GAP_MS || nextEnd - nextStart > MAX_SPAN_MS || right - left + 1 >= MAX_SEGMENTS
                    || characters + size(next) > MAX_CODE_POINTS) {
                if (gap <= MAX_GAP_MS) limited = true;
                if (takeLeft) blockedLeft = true; else blockedRight = true;
                continue;
            }
            if (takeLeft) left--; else right++;
            start = nextStart; end = nextEnd; characters += size(next);
        }
        var selected = source.subList(left, right + 1);
        return new Window(selected, !selected.equals(base), limited);
    }
    private static boolean fits(List<ReviewInput.Segment> lines) {
        return lines.size() <= MAX_SEGMENTS && lines.stream().mapToInt(CandidateContextWindow::size).sum() <= MAX_CODE_POINTS
                && lines.stream().mapToLong(ReviewInput.Segment::endMs).max().orElseThrow() - lines.get(0).startMs() <= MAX_SPAN_MS;
    }
    private static boolean hasGap(List<ReviewInput.Segment> lines) {
        long end = lines.get(0).endMs();
        for (var line : lines.subList(1, lines.size())) {
            if (line.startMs() - end > MAX_GAP_MS) return true;
            end = Math.max(end, line.endMs());
        }
        return false;
    }
    private static int size(ReviewInput.Segment s) { return s.text().codePointCount(0, s.text().length()); }
}
