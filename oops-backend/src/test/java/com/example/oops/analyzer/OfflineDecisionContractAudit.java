package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Offline parsed-response replay. No API client, repair, label generation or production writes. */
public final class OfflineDecisionContractAudit {
    private OfflineDecisionContractAudit() {}
    static final Set<String> DECISIONS = Set.of("PASS", "REVIEW_REQUIRED", "UNCERTAIN");

    static Map<String,Object> inspect(ContextPolicyTrial.Task task, JsonNode output) {
        return replay(task, output).report();
    }

    record Replay(Map<String,Object> report, Map<String,Object> validation) {}

    private static Replay replay(ContextPolicyTrial.Task task, JsonNode output) {
        CandidateReviewEngine.VerificationResult response = null;
        String parsedDecision = null;
        boolean readable = false;
        try {
            response = JSON.treeToValue(output, CandidateReviewEngine.VerificationResult.class);
            var candidateId = JSON.readTree(task.input()).path("candidates").get(0).path("candidateId").asText();
            if (response != null && response.verifications() != null && response.verifications().size() == 1) {
                var item = response.verifications().get(0);
                if (item != null && candidateId.equals(item.candidateId()) && item.assessment() != null) {
                    parsedDecision = item.assessment().decision();
                    readable = parsedDecision != null && DECISIONS.contains(parsedDecision);
                }
            }
        } catch (RuntimeException invalidParsedShape) {
            // Saved parsed JSON only. This cannot diagnose lost original HTTP/invalid-JSON responses.
        }
        var validation = ContextPolicyTrial.assess(task, response).get(0);
        boolean accepted = Boolean.TRUE.equals(validation.get("accepted"));
        String failure = (String) validation.get("failureCode");
        boolean labeled = "USER_REVIEWED_DEVELOPMENT_FLOW".equals(task.spec().labelStatus())
                && task.spec().expected() != null && DECISIONS.contains(task.spec().expected());
        String agreement = !labeled ? "UNLABELED" : !readable ? "UNREADABLE"
                : task.spec().expected().equals(parsedDecision) ? "MATCH" : "MISMATCH";
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("caseId", task.spec().id()); row.put("labelStatus", task.spec().labelStatus());
        row.put("developmentExpected", labeled ? task.spec().expected() : null);
        row.put("parsedModelDecision", readable ? parsedDecision : null);
        row.put("decisionReadable", readable); row.put("developmentLabelAgreement", agreement);
        row.put("contractAccepted", accepted); row.put("contractFailureCode", failure);
        row.put("contractFailureKind", accepted ? "NONE" : failureKind(failure));
        row.put("evidenceMeaningReview", "NOT_AUTOMATICALLY_ASSESSED");
        return new Replay(row, validation);
    }

    static String failureKind(String code) {
        if ("LINKED_PASS_CONTRAST_REQUIRED".equals(code)) return "REQUIRED_CONTRAST_FIELD";
        if ("TARGET_MENTION_RELATION".equals(code)) return "GROUNDING_RELATION_CONTRACT";
        if ("TARGET_EVIDENCE_REQUIRED".equals(code)) return "TARGET_GROUNDING_CONTRACT";
        return "OTHER_CONTRACT_OR_RESPONSE_SHAPE";
    }

    static Map<String,Object> audit(JsonNode plan, JsonNode saved) {
        List<ContextPolicyTrial.Task> tasks = new ArrayList<>();
        plan.path("tasks").forEach(t -> tasks.add(JSON.treeToValue(t, ContextPolicyTrial.Task.class)));
        BanterPolicyTrial.validatePlan(plan, tasks);
        Map<String,ContextPolicyTrial.Task> byId = new HashMap<>();
        tasks.forEach(t -> byId.put(t.spec().id(), t));
        var results = saved.path("results");
        check(results.isArray() && results.size() == 72, "Complete 72-row study required");
        Set<String> seen = new HashSet<>();
        List<Map<String,Object>> rows = new ArrayList<>();
        for (var result : results) {
            String id = result.path("caseId").asText(), arm = result.path("arm").asText();
            int repeat = result.path("repeat").asInt();
            check(byId.containsKey(id) && Set.of("BASELINE", "COMPARISON").contains(arm)
                    && repeat >= 1 && repeat <= 3 && seen.add(id + ":" + arm + ":" + repeat), "Unknown/duplicate study tuple");
            var task = byId.get(id);
            var calls = result.path("calls");
            check(calls.isArray() && calls.size() == 1, "Exactly one saved parsed call required");
            var call = calls.get(0);
            String system = arm.equals("BASELINE") ? task.baselineSystem() : task.comparisonSystem();
            check(system.equals(call.path("system").asText()) && JSON.readTree(task.input()).equals(call.path("input"))
                    && call.path("failureCode").isNull(), "Frozen request mismatch/provider failure");
            var replay = replay(task, call.path("output"));
            var row = replay.report();
            var prior = result.path("assessments");
            check(prior.isArray() && prior.size() == 1
                    && prior.get(0).path("accepted").isBoolean()
                    && prior.get(0).path("accepted").asBoolean() == (boolean) row.get("contractAccepted")
                    && Objects.equals(prior.get(0).path("failureCode").isNull() ? null
                            : prior.get(0).path("failureCode").asText(), row.get("contractFailureCode"))
                    && prior.get(0).path("observation").equals(JSON.valueToTree(replay.validation().get("observation"))),
                    "Validator replay drift; do not rewrite historical outcomes");
            row.put("arm", arm); row.put("repeat", repeat); rows.add(row);
        }
        Map<String,Object> summary = new LinkedHashMap<>();
        for (String arm : List.of("BASELINE", "COMPARISON")) {
            var selected = rows.stream().filter(r -> arm.equals(r.get("arm"))).toList();
            summary.put(arm, Map.of("rows", selected.size(),
                    "parsedDecisions", counts(selected, "parsedModelDecision"),
                    "developmentLabelAgreement", counts(selected, "developmentLabelAgreement"),
                    "contractFailureKinds", counts(selected, "contractFailureKind"),
                    "contractFailureCodes", counts(selected, "contractFailureCode"),
                    "contractAccepted", selected.stream().filter(r -> Boolean.TRUE.equals(r.get("contractAccepted"))).count()));
        }
        return Map.of("schemaVersion", "offline-decision-contract-audit-1", "providerCalls", 0,
                "scope", "PARSED_RESPONSES_LABEL_AGREEMENT_AND_UNMODIFIED_VALIDATOR_REPLAY",
                "semanticCorrectnessAutomaticallyEstablished", false, "summary", summary, "rows", rows);
    }

    static Map<String,Long> counts(List<Map<String,Object>> rows, String field) {
        Map<String,Long> counts = new TreeMap<>();
        rows.forEach(r -> counts.merge(Objects.toString(r.get(field), "NONE"), 1L, Long::sum));
        return counts;
    }

    static byte[] bounded(Path path) throws Exception {
        check(Files.size(path) <= 8_388_608, "Audit input exceeds 8 MiB");
        return Files.readAllBytes(path);
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 3, "Frozen plan, saved results and new private report path required");
        byte[] plan = bounded(Path.of(args[0])), saved = bounded(Path.of(args[1]));
        var report = new LinkedHashMap<>(audit(JSON.readTree(plan), JSON.readTree(saved)));
        report.put("planSha256", ReferenceOnlyTrial.sha(plan));
        report.put("resultsSha256", ReferenceOnlyTrial.sha(saved));
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root), "New private report path required");
        Files.writeString(output, JSON.writeValueAsString(report), StandardOpenOption.CREATE_NEW);
        System.out.println("OFFLINE_AUDIT rows=72 providerCalls=0 historicalContractDrift=0");
        System.out.println(JSON.writeValueAsString(report.get("summary")));
    }
}
