package com.example.oops.analyzer;

import java.util.*;
import static com.example.oops.analyzer.ReviewDiagnostics.*;

/** Read-only DTOs for stored legacy diagnostics/responses; no execution path. */
public final class DialogueReview {
    private DialogueReview() {}
    public record LlmDecision(String unitId, String relation, TextReviewEngine.LlmDecision assessment) {}
    /** Controlled shape only: never retain rejected quotes, target names, reasons or unknown IDs. */
    public record RejectedShape(String decision, String relation, String category,
                                boolean targetPresent, int outsideEvidenceCount) {}
    public record Trace(String unitId, List<String> segmentIds, long startMs, long endMs,
                        State state, String relation, String anchorId, String reason,
                        String category, String target, List<String> missingInformation,
                        List<EvidenceLink> evidence, String failure, boolean truncated,
                        RejectedShape rejectedShape) {}
    public record Diagnostics(int requested, int assessed, int invalidAttempts, int conflicts,
                              int uncertain, int unselectedAnchors, boolean truncated, List<Trace> units,
                              String executionMode, int calls, int budgetSkipped) {}
}
