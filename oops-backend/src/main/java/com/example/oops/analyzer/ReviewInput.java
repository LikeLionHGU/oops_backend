package com.example.oops.analyzer;

import com.example.oops.domain.ScreenText;
import com.example.oops.domain.ScreenTextRole;
import com.example.oops.domain.TimelineEventType;
import com.example.oops.domain.TranscriptSegment;

import java.util.*;

/** Immutable raw-text snapshot. Fallback IDs are stable only within this input snapshot. */
public record ReviewInput(List<Segment> segments) {
    private static final int MAX_CONTEXT_SEGMENTS = 8;
    private static final int MAX_CONTEXT_CODE_POINTS = 4_000;

    public ReviewInput {
        segments = segments.stream().sorted(Comparator.comparingLong(Segment::startMs)
                .thenComparingLong(Segment::endMs).thenComparing(Segment::id)).toList();
        if (segments.stream().map(Segment::id).distinct().count() != segments.size()) {
            throw new IllegalArgumentException("Duplicate review input IDs");
        }
    }

    public static ReviewInput from(List<TranscriptSegment> transcript, List<ScreenText> screenTexts) {
        List<Segment> segments = new ArrayList<>();
        if (transcript != null) {
            for (int i = 0; i < transcript.size(); i++) {
                TranscriptSegment s = transcript.get(i);
                if (s.getText() != null && !s.getText().isBlank()) {
                    segments.add(new Segment(id(TimelineEventType.SPEECH, s.getId(), i),
                            TimelineEventType.SPEECH, s.getStartMs(), s.getEndMs(), s.getText(), null));
                }
            }
        }
        if (screenTexts != null) {
            for (int i = 0; i < screenTexts.size(); i++) {
                ScreenText s = screenTexts.get(i);
                if (s.getText() != null && !s.getText().isBlank()) {
                    segments.add(new Segment(id(TimelineEventType.CAPTION, s.getId(), i),
                            TimelineEventType.CAPTION, s.getStartMs(), s.getEndMs(), s.getText(), s.getConfidence(), s.roleOrUncertain()));
                }
            }
        }
        return new ReviewInput(segments);
    }

    public static String id(TimelineEventType type, Long databaseId, int index) {
        String prefix = type == TimelineEventType.SPEECH ? "stt-" : "ocr-";
        return prefix + (databaseId == null ? "index-" + index : databaseId);
    }

    public Optional<Segment> find(String id) {
        return segments.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public Window contextFor(String id) {
        return contextFor(id, Set.of());
    }

    /** Exclude segments already supplied as primary input before applying context limits. */
    Window contextFor(String id, Set<String> excludedIds) {
        Segment anchor = find(id).orElseThrow(() -> new IllegalArgumentException("Unknown segment: " + id));
        int anchorIndex = segments.indexOf(anchor);
        List<Segment> before = new ArrayList<>(), after = new ArrayList<>(), related = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (i == anchorIndex || excludedIds.contains(s.id())) continue;
            long gap = gap(anchor, s);
            if (s.type() != anchor.type()) {
                if (gap <= 2_000) related.add(s);
            } else if (gap <= 15_000) {
                (i < anchorIndex ? before : after).add(s);
            }
        }
        List<Segment> keptBefore = bounded(before, anchor), keptAfter = bounded(after, anchor);
        List<Segment> keptRelated = bounded(related, anchor);
        return new Window(anchor, keptBefore, keptAfter, keptRelated,
                before.size() + after.size() + related.size()
                        - keptBefore.size() - keptAfter.size() - keptRelated.size());
    }

    private static long gap(Segment a, Segment b) {
        return Math.max(0, Math.max(a.startMs() - b.endMs(), b.startMs() - a.endMs()));
    }

    /** Keep complete raw segments, nearest first for selection, chronological for presentation. */
    private static List<Segment> bounded(List<Segment> candidates, Segment anchor) {
        List<Segment> selected = new ArrayList<>();
        int remaining = MAX_CONTEXT_CODE_POINTS;
        for (Segment s : candidates.stream().sorted(Comparator.comparingLong((Segment s) -> gap(anchor, s))
                .thenComparingLong(s -> Math.abs(s.startMs() - anchor.startMs())).thenComparing(Segment::id)).toList()) {
            int size = s.text().codePointCount(0, s.text().length());
            if (selected.size() < MAX_CONTEXT_SEGMENTS && size <= remaining) {
                selected.add(s);
                remaining -= size;
            }
        }
        return candidates.stream().filter(selected::contains).toList();
    }

    public record Segment(String id, TimelineEventType type, long startMs, long endMs,
                          String text, Double confidence, ScreenTextRole role) {
        public Segment(String id, TimelineEventType type, long startMs, long endMs, String text, Double confidence) {
            this(id, type, startMs, endMs, text, confidence, type == TimelineEventType.CAPTION ? ScreenTextRole.UNCERTAIN : null);
        }

        public boolean reviewTarget() { return type == TimelineEventType.SPEECH || role == ScreenTextRole.EDITORIAL; }
        public Segment {
            if (id == null || id.isBlank() || (type != TimelineEventType.SPEECH && type != TimelineEventType.CAPTION)
                    || startMs < 0 || endMs < startMs || text == null || text.isBlank()) {
                throw new IllegalArgumentException("Invalid raw review segment");
            }
        }
    }

    public record Window(Segment anchor, List<Segment> before, List<Segment> after,
                         List<Segment> related, int omittedSegments) {
        public Window {
            before = List.copyOf(before);
            after = List.copyOf(after);
            related = List.copyOf(related);
        }
    }
}
