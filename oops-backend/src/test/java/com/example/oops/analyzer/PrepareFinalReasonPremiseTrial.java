package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Offline-only plan preparation. Execution requires a separate exact-plan cost/egress approval. */
public final class PrepareFinalReasonPremiseTrial {
    static final String BUNDLE_SHA = "96b9519ef1d5509afc75637f94099192390c0502f66bd527d7cb9bebd75eee99";
    static final String VERSION = "final-reason-premise-trial-plan-1";
    static final int MAX_CALLS = 37, OUTPUT_CAP = 2048;
    record Request(String id, String origin, String expectedRouteDraft, String labelStatus, JsonNode input, JsonNode body) {}
    record Plan(String version, String sourceBundleSha256, String model, int maxHttpAttempts, double proposedCapUsd,
                double reservationEstimatedUsd, boolean executionApproved, boolean runtimeConnected,
                boolean independentGold, List<Request> requests) {}
    private PrepareFinalReasonPremiseTrial() {}

    static JsonNode body(JsonNode input) {
        return JSON.valueToTree(Map.of("model", "gpt-6-luna", "reasoning_effort", "none",
                "max_completion_tokens", OUTPUT_CAP, "response_format", FinalReasonPremiseAudit.responseFormat(),
                "messages", List.of(Map.of("role", "system", "content", FinalReasonPremiseAudit.SYSTEM),
                        Map.of("role", "user", "content", input.toString()))));
    }
    static Request request(String id, String origin, String expected, JsonNode input) {
        FinalReasonPremiseAudit.source(input);
        return new Request(id, origin, expected, "DEVELOPER_DRAFT_NOT_HUMAN_GOLD", input.deepCopy(), body(input));
    }
    static JsonNode changed(JsonNode original, String decision, String reason, String targetReason, String alternative) {
        var input = (ObjectNode) original.deepCopy(); var judgment = input.putObject("judgmentToCheck");
        judgment.put("decision", decision); judgment.put("reason", reason); judgment.putNull("target");
        judgment.put("targetReason", targetReason); judgment.put("alternativeInterpretation", alternative);
        return input;
    }
    static double reservation(List<Request> requests) {
        return requests.stream().mapToDouble(r -> (JSON.writeValueAsBytes(r.body()).length + 1024d) * .10 / 1_000_000d
                + OUTPUT_CAP * .50 / 1_000_000d).sum();
    }
    static Plan build(JsonNode bundle) {
        check(FinalReasonPremiseAudit.VERSION.equals(bundle.path("version").asText())
                && FinalReasonPremiseAudit.SYSTEM.equals(bundle.path("system").asText())
                && FinalReasonPremiseAudit.responseFormat().equals(bundle.path("responseFormat"))
                && bundle.path("requests").size() == 30, "Known thirty-input audit bundle required");
        var byCase = new HashMap<String, JsonNode>(); var saved = new ArrayList<Request>();
        for (JsonNode row : bundle.path("requests")) {
            check(row.path("auditOutput").isNull() && "NOT_RUN".equals(row.path("auditState").asText()), "Unexecuted inputs required");
            String id = row.path("caseId").asText(); check(RelationGuidedVerificationTrial.IDS.contains(id), "Unknown source case");
            byCase.putIfAbsent(id, row.path("input"));
            saved.add(request("saved-" + row.path("sourceRequestIndex").asInt(), "SAVED_MODEL_REASON:" + id,
                    id.equals("R01-166") ? "RECHECK_REQUIRED" : "NO_RECORDED_CONFLICT", row.path("input")));
        }
        check(byCase.size() == 5 && saved.stream().map(Request::id).distinct().count() == 30, "Five fixed cases, unique thirty sources required");
        var contrasts = new ArrayList<Request>();
        for (int repeat = 1; repeat <= 2; repeat++) {
            contrasts.add(request("R01-aligned-" + repeat, "DRAFT_SAME_RAW_ALIGNED_REASON", "NO_RECORDED_CONFLICT",
                    changed(byCase.get("R01-166"), "PASS",
                            "앞의 약어 뜻 질문에 답해 문구의 의미를 설명했다. 뒤의 집에서 입지 않는다는 말과 보여주겠다는 말은 자기 설명·제안이며, 이를 상대에게 답변이나 행동을 요구한 것으로 단정하지 않는다.",
                            "현재 청자에게 실제 답변을 요구했다고 확정하지 않는다.", "별도의 성적 소재 판단과 발화 행위의 구별은 서로 다른 문제다.")));
            contrasts.add(request("R04-reversed-" + repeat, "DRAFT_SAME_RAW_REVERSED_DIRECTION", "RECHECK_REQUIRED",
                    changed(byCase.get("R04"), "PASS", "‘제게 한번 보여 주세요’는 화자가 자신의 속옷을 보여주겠다는 자발적 자기 제안이다.",
                            "보여주는 행위의 주체는 화자 자신이다.", "상대에게 보여 달라는 요구가 아니다.")));
            contrasts.add(request("R08-persistence-" + repeat, "DRAFT_SAME_RAW_INVENTED_PERSISTENCE", "RECHECK_REQUIRED",
                    changed(byCase.get("R08"), "REVIEW_REQUIRED", "상대가 답하기 싫다고 거절한 뒤에도 화자가 같은 속옷 질문을 반복하며 답을 강요했다.",
                            "거절 뒤에도 같은 상대에게 요청을 계속했다.", "주제를 전환하지 않고 지속적으로 압박했다.")));
        }
        JsonNode canary = JSON.readTree("""
                {"anchorId":"s1","raw":[{"id":"s0","text":"이 약어는 무슨 뜻인가요?","startMs":0,"endMs":1000},
                {"id":"s1","text":"오늘 기분은 어때라는 문장을 줄인 말입니다.","startMs":1000,"endMs":2000}],
                "judgmentToCheck":{"decision":"PASS","reason":"앞의 뜻 질문에 문장의 축약 의미를 설명했다.",
                "target":null,"targetReason":null,"alternativeInterpretation":"실제 상대에게 답변을 요구한 질문으로 단정하지 않는다."}}
                """);
        var requests = new ArrayList<Request>();
        requests.add(request("compatibility-1", "SYNTHETIC_SCHEMA_COMPATIBILITY_ONLY", "NO_RECORDED_CONFLICT", canary));
        for (int i = 0; i < 30; i++) { requests.add(saved.get(i)); if ((i + 1) % 5 == 0) requests.add(contrasts.get(i / 5)); }
        var plan = new Plan(VERSION, BUNDLE_SHA, "gpt-6-luna", MAX_CALLS, .50, reservation(requests), false, false, false, List.copyOf(requests));
        validate(plan); return plan;
    }
    static void validate(Plan plan) {
        check(VERSION.equals(plan.version()) && BUNDLE_SHA.equals(plan.sourceBundleSha256()) && "gpt-6-luna".equals(plan.model())
                && plan.maxHttpAttempts() == MAX_CALLS && plan.requests().size() == MAX_CALLS
                && plan.proposedCapUsd() == .50 && !plan.executionApproved() && !plan.runtimeConnected() && !plan.independentGold(),
                "Known unapproved diagnostic trial required");
        check(Math.abs(plan.reservationEstimatedUsd() - reservation(plan.requests())) < 1e-12 && plan.reservationEstimatedUsd() <= .50,
                "Budget changed or exceeded");
        check(plan.requests().get(0).id().equals("compatibility-1") && plan.requests().stream().map(Request::id).distinct().count() == MAX_CALLS,
                "Compatibility first, unique requests required");
        for (var r : plan.requests()) {
            check(r.body().equals(body(r.input())) && r.labelStatus().equals("DEVELOPER_DRAFT_NOT_HUMAN_GOLD"), "Frozen body or label changed");
            var keys = new HashSet<String>(r.input().propertyNames());
            check(keys.equals(Set.of("anchorId", "raw", "judgmentToCheck")), "Only source and checked judgment may be sent");
            check(!r.body().toString().contains("expectedRouteDraft") && !r.body().toString().contains("DEVELOPER_DRAFT"), "Evaluation label leaked");
        }
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 2, "frozen audit bundle, new private directory required");
        byte[] source = OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        check(BUNDLE_SHA.equals(ReferenceOnlyTrial.sha(source)), "Exact saved input bundle required");
        Plan plan = build(JSON.readTree(source)); byte[] bytes = JSON.writeValueAsBytes(plan);
        Path out = Path.of(args[1]).toAbsolutePath().normalize();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(Path.of("../datasets/controversy/experiments").toRealPath()),
                "New private output required");
        Files.createDirectory(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        SourceRelationExtractionTrial.writePrivate(out.resolve("plan.json"), bytes);
        System.out.println("PREMISE_TRIAL_PREPARED providerCalls=0 maxCalls=37 reservationEstimatedUsd=" + plan.reservationEstimatedUsd()
                + " planSha256=" + ReferenceOnlyTrial.sha(bytes));
    }
}
