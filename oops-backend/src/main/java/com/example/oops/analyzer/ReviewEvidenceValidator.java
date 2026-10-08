package com.example.oops.analyzer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Validates linkage and result contracts, NOT interpretation or transcription accuracy. */
public final class ReviewEvidenceValidator {
    private ReviewEvidenceValidator() {}

    public static List<String> validate(ReviewInput input, ReviewEvaluation evaluation) {
        List<String> errors = new ArrayList<>();
        if (evaluation.evaluatorId() == null || evaluation.evaluatorId().isBlank()) errors.add("Missing evaluator ID");
        var reviewed = new HashSet<>(evaluation.reviewedSegmentIds());
        var supplied = new HashSet<>(evaluation.suppliedSegmentIds());
        if (supplied.size() != evaluation.suppliedSegmentIds().size()) errors.add("Duplicate supplied IDs");
        for (String id : supplied) if (input.find(id).isEmpty()) errors.add("Unknown supplied ID: " + id);
        if (!supplied.containsAll(reviewed)) errors.add("Reviewed IDs must be supplied");
        if (reviewed.size() != evaluation.reviewedSegmentIds().size()) errors.add("Duplicate reviewed IDs");
        for (String id : reviewed) {
            if (input.find(id).isEmpty()) errors.add("Unknown reviewed ID: " + id);
        }
        if (evaluation.status() != ReviewEvaluation.ExecutionStatus.SUCCESS && !evaluation.observations().isEmpty()) {
            errors.add("Unsuccessful execution cannot publish decisions");
        }
        if (evaluation.status() == ReviewEvaluation.ExecutionStatus.SUCCESS && reviewed.isEmpty()) {
            errors.add("Successful execution must identify reviewed input");
        }
        for (var observation : evaluation.observations()) {
            if (!reviewed.contains(observation.anchorId())) errors.add("Unreviewed anchor: " + observation.anchorId());
            if (observation.reason() == null || observation.reason().isBlank()) errors.add("Missing decision reason");
            if (observation.evidence().isEmpty()) errors.add("Decision requires raw evidence");
            if (observation.evidence().stream().noneMatch(s -> s.segmentId().equals(observation.anchorId())
                    && s.role() == ReviewEvaluation.EvidenceRole.PRIMARY)) errors.add("Anchor requires primary evidence");
            if (observation.decision() == ReviewEvaluation.Decision.UNCERTAIN
                    && (observation.missingInformation().isEmpty()
                    || observation.missingInformation().stream().anyMatch(String::isBlank))) {
                errors.add("Uncertain decision requires missing information");
            }
            for (var span : observation.evidence()) {
                if (span.role() == null) errors.add("Missing evidence role");
                if (!supplied.contains(span.segmentId())) errors.add("Unsupplied evidence: " + span.segmentId());
                var segment = input.find(span.segmentId());
                if (segment.isEmpty()) {
                    errors.add("Unknown evidence ID: " + span.segmentId());
                    continue;
                }
                String raw = segment.get().text();
                int count = raw.codePointCount(0, raw.length());
                if (span.start() < 0 || span.end() <= span.start() || span.end() > count) {
                    errors.add("Invalid evidence range: " + span.segmentId());
                    continue;
                }
                String excerpt = raw.substring(raw.offsetByCodePoints(0, span.start()), raw.offsetByCodePoints(0, span.end()));
                if (span.quote() == null || span.quote().isBlank() || !excerpt.equals(span.quote())) {
                    errors.add("Evidence quote differs from raw text: " + span.segmentId());
                }
            }
        }
        // No observations remains an empty result, never an implicit PASS.
        return List.copyOf(errors);
    }
}
