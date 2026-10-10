package com.example.oops.transcript;

import com.example.oops.analyzer.ReviewDiagnostics;
import com.example.oops.analyzer.ReviewInput;
import com.example.oops.domain.TimelineEventType;
import com.example.oops.service.ReviewDiagnosticsStore;
import org.springframework.stereotype.Component;
import java.util.*;

/** Local manual-review plan only. No recognition calls, corrections or transcript writes. */
@Component
public class SttReviewPlanner {
    public static final String REVISION = "2026-10-10-stt-review-plan-1";
    private static final int MAX_CLIPS = 3;
    private static final long MAX_PLANNED_MS = 20000;
    public record Item(String segmentId, long startMs, long endMs, String rawText,
                       List<String> missingInformation, List<String> candidateIds,
                       String state, Long clipStartMs, Long clipEndMs) {}
    public record Plan(String revision, String state, boolean executionEnabled, int additionalCalls,
                       int maxClips, long maxPlannedMs, long plannedMs, List<Item> items) {}

    public Plan plan(ReviewInput input, long durationMs, ReviewDiagnosticsStore.Snapshot snapshot) {
        if (snapshot == null) return result("DIAGNOSTICS_UNAVAILABLE", 0, List.of());
        var speech = snapshot.analyzers().stream().filter(a -> "speech-review".equals(a.evaluatorId())).findFirst().orElse(null);
        if (speech == null) return result("DIAGNOSTICS_UNAVAILABLE", 0, List.of());
        var candidates = speech.candidatePipeline() == null ? List.<com.example.oops.analyzer.CandidateReviewDiagnostics.Trace>of()
                : speech.candidatePipeline().candidates();
        List<Item> items = new ArrayList<>();
        long planned = 0;
        int selected = 0;
        for (var trace : speech.segments().stream().sorted(Comparator.comparingLong(ReviewDiagnostics.SegmentTrace::startMs)).toList()) {
            if (trace.type() != TimelineEventType.SPEECH) continue;
            var missing = trace.decisions().stream().filter(d -> d.decision() == com.example.oops.analyzer.ReviewEvaluation.Decision.UNCERTAIN)
                    .flatMap(d -> d.missingInformation().stream()).filter(SttReviewPlanner::transcriptionSignal).distinct().toList();
            if (missing.isEmpty()) continue;
            var raw = input.find(trace.segmentId()).orElse(null);
            if (raw == null || raw.type() != TimelineEventType.SPEECH || raw.startMs() != trace.startMs() || raw.endMs() != trace.endMs())
                return result("SOURCE_SNAPSHOT_MISMATCH", 0, List.of());
            var related = candidates.stream().filter(c -> Set.of("PASS", "REVIEW_REQUIRED", "UNCERTAIN").contains(c.state()))
                    .filter(c -> trace.segmentId().equals(c.anchorId()) || c.contextSegmentIds().contains(trace.segmentId()))
                    .map(com.example.oops.analyzer.CandidateReviewDiagnostics.Trace::candidateId).distinct().toList();
            long start = Math.max(0, raw.startMs() - 1000), end = Math.min(durationMs, raw.endMs() + 1000);
            String state;
            if (related.isEmpty()) state = "DEFERRED_NO_CANDIDATE_LINK";
            else if (start >= end || selected >= MAX_CLIPS || end - start + planned > MAX_PLANNED_MS) state = "DEFERRED_PLAN_BUDGET";
            else { state = "SELECTED_FOR_MANUAL_AUDIO_REVIEW"; selected++; planned += end - start; }
            boolean keepClip = state.equals("SELECTED_FOR_MANUAL_AUDIO_REVIEW");
            items.add(new Item(raw.id(), raw.startMs(), raw.endMs(), raw.text(), missing, related,
                    state, keepClip ? start : null, keepClip ? end : null));
        }
        return result("PLAN_ONLY_NOT_ERROR_CERTIFICATION", planned, List.copyOf(items));
    }
    private static boolean transcriptionSignal(String information) {
        return information.matches("(?s).*(?:전사|오인식|음성|발음|알아듣).*");
    }
    private static Plan result(String state, long planned, List<Item> items) {
        return new Plan(REVISION, state, false, 0, MAX_CLIPS, MAX_PLANNED_MS, planned, items);
    }
}
