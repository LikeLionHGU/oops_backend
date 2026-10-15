package com.example.oops.analyzer;

import java.nio.file.*;
import java.util.*;
import java.sql.DriverManager;
import static com.example.oops.analyzer.TextBoundaryTrial.*;
import static com.example.oops.domain.TimelineEventType.SPEECH;

/** Free stored-input retrieval comparison. Manual hints here never enter the production pipeline. */
public final class ContextUnitPreviewAudit {
    public static void main(String[] args) throws Exception {
        check(args.length == 5, "Frozen input plan, baseline runtime, preview runtime, new private report, read-only database required");
        byte[] source = OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        var base = ReferenceOnlyTrial.library(Path.of(args[1]));
        var units = ReferenceOnlyTrial.library(Path.of(args[2]));
        check(base.trace(SPEECH).state().equals("READY_WORKING_REFERENCE")
                && units.trace(SPEECH).state().equals("READY_WORKING_REFERENCE"), "Both archives must load");
        List<Map<String,Object>> rows = new ArrayList<>();
        try (var db = DriverManager.getConnection("jdbc:h2:file:" + Path.of(args[4]).toAbsolutePath()
                + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "")) {
        db.setReadOnly(true);
        for (var task : JSON.readTree(source).path("tasks")) {
            String id = task.path("spec").path("id").asText();
            var input = JSON.readTree(task.path("input").asText());
            var raw = CandidateHypothesisTrial.raw(input);
            var c = input.path("candidates").get(0);
            Set<String> focusIds = new HashSet<>();
            c.path("proposedEvidence").forEach(q -> focusIds.add(q.path("segmentId").asText()));
            var focus = raw.segments().stream().filter(s -> focusIds.contains(s.id())).toList();
            Set<String> manual = id.equals("64-graphic") ? Set.of("graphic-body-metaphor")
                    : id.equals("64-region") ? Set.of("local-life-devaluation") : Set.of();
            var before = base.select(SPEECH, raw.segments(), id, focus);
            var lexical = units.select(SPEECH, raw.segments(), id, focus);
            var hint = units.select(SPEECH, raw.segments(), id, focus, manual);
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("caseId", id); row.put("baselineSelector", before.trace()); row.put("unitLexicalSelector", lexical.trace());
            row.put("unitManualHintSelector", hint.trace()); row.put("manualHintKeysNotModelPredictions", manual);
            if (task.path("spec").path("video").asLong() > 0) {
                var spec = JSON.treeToValue(task.path("spec"), ContextPolicyTrial.Spec.class);
                var context = ContextPolicyTrial.context(db, spec.video());
                var engineBase = ContextPolicyTrial.capture(spec, context, base);
                var engineUnits = ContextPolicyTrial.capture(spec, context, units);
                boolean same = engineBase.baselineSystem().equals(task.path("baselineSystem").asText())
                        && engineBase.input().equals(task.path("input").asText());
                row.put("reconstructedBaselineMatchesSaved", same);
                row.put("reconstructedSystemExact", engineBase.baselineSystem().equals(task.path("baselineSystem").asText()));
                row.put("reconstructedInputJsonEqual", JSON.readTree(engineBase.input()).equals(input));
                String marker = "contextReference=";
                String savedSystem = task.path("baselineSystem").asText();
                row.put("reconstructedReferenceJsonEqual", JSON.readTree(engineBase.baselineSystem().substring(
                        engineBase.baselineSystem().lastIndexOf(marker) + marker.length())).equals(JSON.readTree(
                        savedSystem.substring(savedSystem.lastIndexOf(marker) + marker.length()))));
                // Actual engine performs discovery-batch selection and freezes it; selector focus alone is not this path.
                row.put("engineBaselineReference", engineBase.baselineSystem().substring(engineBase.baselineSystem().lastIndexOf("contextReference=")));
                row.put("engineUnitReference", engineUnits.baselineSystem().substring(engineUnits.baselineSystem().lastIndexOf("contextReference=")));
                System.out.println("UNIT_ENGINE " + id + " reconstructedBaselineMatchesSaved=" + same);
            }
            rows.add(row);
            System.out.println("UNIT_RETRIEVAL " + id + " baseline=" + before.trace().exampleIds()
                    + " unitLexical=" + lexical.trace().exampleIds() + " manualHint=" + hint.trace().exampleIds());
        }
        }
        var report = Map.of("schemaVersion", "context-unit-retrieval-audit-1", "providerCalls", 0,
                "classificationMeasured", false, "semanticDetectionMeasured", false,
                "sourcePlanSha256", ReferenceOnlyTrial.sha(source),
                "baselineSha256", ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(args[1]))),
                "previewSha256", ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(args[2]))), "rows", rows);
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root), "New private report required");
        Files.writeString(output, JSON.writeValueAsString(report), StandardOpenOption.CREATE_NEW);
    }
}
