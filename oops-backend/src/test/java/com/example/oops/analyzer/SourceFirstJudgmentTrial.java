package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** One fresh USD0.50 design/test scope: no retries, no media, no report publication. */
public final class SourceFirstJudgmentTrial {
    static final String SOURCE_SHA = "49420803861035076abfa68e5dc9a99d4d8cbc1be75aa9ce71e192d0479c05d9";
    static final String CONSENT = "USER_APPROVED_SOURCE_FIRST_DESIGN_TEST_USD050_TEXT";
    static final int CALLS = 35, OUTPUT_CAP = 2048;
    record Request(String id, String caseId, String arm, int repeat, String expectedDecision, String labelStatus,
                   JsonNode input, SourceFirstReviewBoundary.Snapshot snapshot, JsonNode body) {}
    record Plan(String version, String sourceSha256, double capUsd, double reservationEstimatedUsd, int maxHttpAttempts, List<Request> requests) {}
    private SourceFirstJudgmentTrial() {}
    static JsonNode body(JsonNode input) {
        return JSON.valueToTree(Map.of("model", "gpt-6-luna", "reasoning_effort", "none", "max_completion_tokens", OUTPUT_CAP,
                "response_format", SourceFirstJudgmentContract.format(), "messages", List.of(
                        Map.of("role", "system", "content", SourceFirstJudgmentContract.SYSTEM), Map.of("role", "user", "content", input.toString()))));
    }
    static Request request(String id, String c, String arm, int repeat, String expected, String label, JsonNode input, SourceFirstReviewBoundary.Snapshot snapshot) {
        return new Request(id, c, arm, repeat, expected, label, input.deepCopy(), snapshot, body(input));
    }
    static double reserve(JsonNode body) { return (JSON.writeValueAsBytes(body).length + 1024d) * .10 / 1_000_000d + OUTPUT_CAP * .50 / 1_000_000d; }
    static Plan build(JsonNode bundle) {
        check(SourceFirstReviewBoundary.VERSION.equals(bundle.path("version").asText()) && bundle.path("stages").size() == 5, "Known five-stage source bundle required");
        var stages = new HashMap<String, JsonNode>(); bundle.path("stages").forEach(s -> check(stages.put(s.path("caseId").asText(), s) == null, "Duplicate source stage"));
        check(stages.keySet().equals(new HashSet<>(RelationGuidedVerificationTrial.IDS)), "Five known cases required");
        var requests = new ArrayList<Request>();
        JsonNode canary = JSON.readTree("""
                {"anchorId":"s1","raw":[{"id":"s0","startMs":0,"endMs":1000,"text":"이 약어는 무슨 뜻인가요?"},
                {"id":"s1","startMs":1000,"endMs":2000,"text":"오늘 기분은 어때라는 문장을 줄인 말입니다."}],"sourceReading":null}
                """);
        requests.add(request("compatibility-1", "compatibility", "COMPATIBILITY", 1, "PASS", "SYNTHETIC_NOT_GOLD", canary, null));
        for (int repeat = 1; repeat <= 3; repeat++) {
            var order = new ArrayList<>(RelationGuidedVerificationTrial.IDS); if (repeat == 2) Collections.reverse(order);
            for (String c : order) {
                var stage = stages.get(c);
                var snapshot = JSON.treeToValue(stage.path("frozenReading"), SourceFirstReviewBoundary.Snapshot.class);
                JsonNode on = stage.path("freshJudgmentSource"); SourceFirstReviewBoundary.verify(on, snapshot);
                var off = (ObjectNode) on.deepCopy(); off.putNull("sourceReading");
                String expected = Set.of("R04", "R08").contains(c) ? "REVIEW_REQUIRED" : "PASS";
                for (String arm : repeat % 2 == 1 ? List.of("RAW_ONLY", "FROZEN_SOURCE") : List.of("FROZEN_SOURCE", "RAW_ONLY"))
                    requests.add(request(c + "-" + arm + "-" + repeat, c, arm, repeat, expected, "USER_APPROVED_DEVELOPMENT_NOT_INDEPENDENT_GOLD",
                            arm.equals("RAW_ONLY") ? off : on, arm.equals("RAW_ONLY") ? null : snapshot));
            }
        }
        for (int repeat = 1; repeat <= 2; repeat++) for (String c : List.of("R04", "R05")) {
            JsonNode source = stages.get(c).path("freshJudgmentSource");
            var original = JSON.readValue(stages.get(c).at("/frozenReading/readingJson").asText(), SourceRelationExtractionTrial.Observation.class);
            var wrong = new SourceRelationExtractionTrial.Observation(original.anchorId(), c.equals("R04") ? "DEFINITION_OR_EXPANSION" : "DIRECT_REQUEST_TO_LISTENER",
                    c.equals("R04") ? "MENTIONED_PHRASE" : "CURRENT_ACT", original.links(), original.evidenceIds());
            var snapshot = SourceFirstReviewBoundary.freeze(source, wrong, SourceFirstReviewBoundary.hash("SYNTHETIC_WRONG_READING_NOT_ORIGINAL_MODEL_OUTPUT"));
            requests.add(request(c + "-CORRUPTED-" + repeat, c, "CORRUPTED_READING", repeat, "RECHECK_SOURCE",
                    "DEVELOPER_DRAFT_NOT_HUMAN_GOLD", SourceFirstReviewBoundary.judgmentSource(source, snapshot), snapshot));
        }
        var plan = new Plan(SourceFirstJudgmentContract.VERSION, SOURCE_SHA, .50, requests.stream().mapToDouble(r -> reserve(r.body())).sum(), CALLS, List.copyOf(requests));
        validate(plan); return plan;
    }
    static void validate(Plan p) {
        check(SourceFirstJudgmentContract.VERSION.equals(p.version()) && SOURCE_SHA.equals(p.sourceSha256()) && p.capUsd() == .50
                && p.maxHttpAttempts() == CALLS && p.requests().size() == CALLS && p.requests().get(0).arm().equals("COMPATIBILITY"), "Fixed 35-call scope required");
        check(Math.abs(p.reservationEstimatedUsd() - p.requests().stream().mapToDouble(r -> reserve(r.body())).sum()) < 1e-12 && p.reservationEstimatedUsd() <= .50, "Budget drift");
        check(p.requests().stream().map(Request::id).distinct().count() == CALLS, "Unique request IDs required");
        for (var r : p.requests()) {
            check(r.body().equals(body(r.input())) && r.input().propertyNames().equals(Set.of("anchorId", "raw", "sourceReading")), "Frozen body/input scope changed");
            SourceFirstReviewBoundary.readingInput(r.input());
            if (r.snapshot() != null) check(SourceFirstReviewBoundary.judgmentSource(r.input(), r.snapshot()).equals(r.input()), "Frozen reading input changed");
            else check(r.input().path("sourceReading").isNull(), "Raw-only source reading must be null");
            check(!r.body().toString().contains("expectedDecision") && !r.body().toString().contains("USER_APPROVED"), "Label egress");
        }
        for (int i = 1; i < 31; i += 2) {
            var a = p.requests().get(i); var b = p.requests().get(i + 1);
            check(a.caseId().equals(b.caseId()) && a.repeat() == b.repeat() && Set.of(a.arm(), b.arm()).equals(Set.of("RAW_ONLY", "FROZEN_SOURCE")), "Paired arms required");
            check(a.input().path("raw").equals(b.input().path("raw")) && a.input().path("anchorId").equals(b.input().path("anchorId")), "Pair raw drift");
        }
    }
    static SourceFirstJudgmentContract.Assessment assess(Request request, JsonNode parsed) {
        try { return SourceFirstJudgmentContract.assess(request.input(), request.snapshot(), JSON.treeToValue(parsed, SourceFirstJudgmentContract.Outcome.class)); }
        catch (RuntimeException ex) { return SourceFirstJudgmentContract.invalid("UNPARSEABLE_OUTPUT"); }
    }
    static Map<String, Object> audit(Plan plan, JsonNode results) {
        check("COMPLETE".equals(results.path("state").asText()) && results.path("httpAttempts").asInt() == CALLS && results.path("results").size() == CALLS, "Complete 35-call results required");
        var totals = new LinkedHashMap<String, Map<String, Integer>>();
        for (int i = 0; i < CALLS; i++) {
            var req = plan.requests().get(i); var row = results.path("results").get(i);
            check(row.path("index").asInt(-1) == i && req.id().equals(row.path("id").asText()), "Result order drift");
            var assessment = assess(req, row.path("output")); check(JSON.valueToTree(assessment).equals(row.path("assessment")), "Offline replay differs");
            for (String key : List.of(req.arm() + ":ALL", req.arm() + ":" + req.caseId())) {
                var group = totals.computeIfAbsent(key, ignored -> new LinkedHashMap<>()); group.merge("responses", 1, Integer::sum);
                group.merge(assessment.state(), 1, Integer::sum);
                var axes = evaluationAxes(req.caseId(), row.path("output"));
                group.merge("reasonValidityPendingHumanReview", 1, Integer::sum);
                if (req.arm().equals("RAW_ONLY") || req.arm().equals("FROZEN_SOURCE")) {
                    group.merge("readingDeclarationEvaluated", 1, Integer::sum);
                    if (axes.path("readingDeclarationMatchesApprovedFunction").asBoolean())
                        group.merge("readingDeclarationMatchesApprovedFunction", 1, Integer::sum);
                    if (axes.path("expressionReviewWithMatchingReading").asBoolean())
                        group.merge("expressionReviewWithMatchingReading", 1, Integer::sum);
                }
                if (req.expectedDecision().equals(row.at("/output/decision").asText())) {
                    group.merge("rawDecisionMatch", 1, Integer::sum);
                    if (assessment.state().equals("ASSESSMENT_RECORDED")) group.merge("matchAndRecorded", 1, Integer::sum);
                }
            }
        }
        return Map.of("calls", CALLS, "totals", totals, "independentGold", false, "publicationAuthorized", false, "meaningReviewRequired", true,
                "evaluationVersion", "separate-reading-policy-format-1",
                "decisionMatchMeaning", "Agreement with preserved development label, not reading correctness or reason validity");
    }
    /** Declared function is only one reading axis; free-text consistency still needs review. */
    static JsonNode evaluationAxes(String caseId, JsonNode output) {
        String function = switch (caseId) {
            case "R01-166", "R05" -> "DEFINITION_OR_EXPANSION";
            case "R04", "R08" -> "DIRECT_REQUEST_TO_LISTENER";
            case "R06" -> "REPORT_OR_QUOTE";
            default -> "";
        };
        String content = switch (caseId) {
            case "R01-166", "R05" -> "MENTIONED_PHRASE";
            case "R04", "R08" -> "CURRENT_ACT";
            case "R06" -> "REPORTED_CONTENT";
            default -> "";
        };
        boolean matches = !function.isEmpty() && function.equals(output.at("/readingUse/function").asText())
                && content.equals(output.at("/readingUse/contentUse").asText());
        return JSON.createObjectNode().put("readingDeclarationMatchesApprovedFunction", matches)
                .put("expressionReviewWithMatchingReading", matches && "REVIEW_REQUIRED".equals(output.path("decision").asText())
                        && "EXPRESSION_CONTENT".equals(output.path("basisKind").asText()))
                .put("reasonValidity", "PENDING_HUMAN_REVIEW").put("publicationAuthorized", false);
    }
    static void run(Plan p, byte[] planBytes, Path planFile, Path out) throws Exception {
        validate(p); String key = System.getenv("OPENAI_API_KEY"); check(key != null && !key.isBlank(), "API key unavailable; never print");
        String sha = ReferenceOnlyTrial.sha(planBytes);
        SourceRelationExtractionTrial.writePrivate(planFile.toRealPath().getParent().resolve("execution-claim.json"), JSON.writeValueAsBytes(Map.of("sha", sha, "consent", CONSENT, "resumable", false)));
        Files.createDirectory(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        SourceRelationExtractionTrial.writePrivate(out.resolve("plan.json"), planBytes);
        SourceRelationExtractionTrial.writePrivate(out.resolve("authorization.json"), JSON.writeValueAsBytes(Map.of("consent", CONSENT, "capUsd", .50, "textEgressApproved", true, "mediaEgressApproved", false, "priorBudgetReused", false)));
        var guard = new SourceRelationExtractionTrial.Guard(); int[] attempts = {0}; double[] reserved = {0};
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()); factory.setReadTimeout(Duration.ofSeconds(90));
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory).defaultHeader("Authorization", "Bearer " + key)
                .requestInterceptor((request, bytes, execution) -> {
                    byte[] accepted = guard.accept(bytes, request.getURI().toString()); double next = reserve(JSON.readTree(accepted));
                    check(attempts[0] < CALLS && reserved[0] + next <= .50, "Call/reservation cap exhausted"); attempts[0]++; reserved[0] += next;
                    try { FinalReasonPremiseTrial.save(out.resolve("reservation.json"), Map.of("httpAttempts", attempts[0], "reservedEstimatedUsd", reserved[0])); }
                    catch (Exception ex) { throw new IllegalStateException("Cannot persist reservation", ex); }
                    request.getHeaders().setContentLength(accepted.length); return execution.execute(request, accepted);
                });
        for (String header : List.of("OpenAI-Organization", "OpenAI-Project")) {
            String value = System.getenv(header.equals("OpenAI-Organization") ? "OPENAI_ORG_ID" : "OPENAI_PROJECT_ID"); if (value != null && !value.isBlank()) builder.defaultHeader(header, value);
        }
        var client = builder.build(); var rows = new ArrayList<Map<String, Object>>(); double cost = 0;
        try {
            for (int i = 0; i < CALLS; i++) {
                var req = p.requests().get(i); guard.begin(JSON.writeValueAsBytes(req.body())); long start = System.nanoTime();
                JsonNode response = JSON.readTree(SourceRelationExtractionTrial.postAndPreserve(client, req.body(), out.resolve(String.format(Locale.ROOT, "provider-response-%02d.json", i + 1))));
                JsonNode usage = response.path("usage"), choice = response.at("/choices/0");
                long in = usage.path("prompt_tokens").asLong(-1), cached = usage.at("/prompt_tokens_details/cached_tokens").asLong(0), tokens = usage.path("completion_tokens").asLong(-1);
                check(in >= 0 && tokens >= 0 && cached >= 0 && cached <= in, "Invalid usage; raw preserved"); cost += ((in - cached) * .10 + cached * .01 + tokens * .50) / 1_000_000d;
                JsonNode output; try { output = JSON.readTree(choice.at("/message/content").asText()); } catch (RuntimeException ex) { output = JSON.nullNode(); }
                var assessment = assess(req, output);
                rows.add(Map.of("index", i, "id", req.id(), "output", output, "providerResponse", response, "assessment", assessment, "elapsedMs", (System.nanoTime() - start) / 1_000_000));
                FinalReasonPremiseTrial.saveResults(out, sha, rows, attempts[0], reserved[0], cost, "RUNNING");
                check("gpt-6-luna".equals(response.path("model").asText()) && "stop".equals(choice.path("finish_reason").asText()) && !output.isNull(), "Provider/output failure; no retry");
                if (i == 0) check(assessment.state().equals("ASSESSMENT_RECORDED"), "Compatibility contract failed; stop");
                System.out.println("SOURCE_FIRST_JUDGMENT " + (i + 1) + "/35 " + req.id());
            }
            FinalReasonPremiseTrial.saveResults(out, sha, rows, attempts[0], reserved[0], cost, "COMPLETE");
            SourceRelationExtractionTrial.writePrivate(out.resolve("offline-audit.json"), JSON.writeValueAsBytes(audit(p, JSON.readTree(Files.readAllBytes(out.resolve("results.json"))))));
        } catch (Exception ex) { FinalReasonPremiseTrial.saveResults(out, sha, rows, attempts[0], reserved[0], cost, "STOPPED"); throw ex; }
    }
    public static void main(String[] args) throws Exception {
        check(args.length >= 3, "prepare/run/audit parameters required"); Path out = Path.of(args[args.length - 1]).toAbsolutePath().normalize();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(Path.of("../datasets/controversy/experiments").toRealPath()), "New private output required");
        byte[] bytes = OfflineDecisionContractAudit.bounded(Path.of(args[1]));
        if ("prepare".equals(args[0])) {
            check(args.length == 3 && SOURCE_SHA.equals(ReferenceOnlyTrial.sha(bytes)), "Exact stage bundle required");
            Plan p = build(JSON.readTree(bytes)); byte[] frozen = JSON.writeValueAsBytes(p);
            Files.createDirectory(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            SourceRelationExtractionTrial.writePrivate(out.resolve("plan.json"), frozen);
            System.out.println("SOURCE_FIRST_PREPARED calls=35 providerCalls=0 reservationEstimatedUsd=" + p.reservationEstimatedUsd() + " planSha256=" + ReferenceOnlyTrial.sha(frozen)); return;
        }
        Plan p = JSON.readValue(bytes, Plan.class); validate(p);
        if ("audit".equals(args[0])) {
            check(args.length == 4, "audit plan/results/new file"); JsonNode result = JSON.readTree(OfflineDecisionContractAudit.bounded(Path.of(args[2])));
            check(ReferenceOnlyTrial.sha(bytes).equals(result.path("planSha256").asText()), "Results/plan mismatch");
            SourceRelationExtractionTrial.writePrivate(out, JSON.writeValueAsBytes(audit(p, result))); return;
        }
        check("run".equals(args[0]) && args.length == 5 && ReferenceOnlyTrial.sha(bytes).equals(args[2]) && CONSENT.equals(args[3]), "Exact-plan new USD0.50/text approval required");
        run(p, bytes, Path.of(args[1]), out);
    }
}
