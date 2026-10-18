package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Fixed verification requests: only an advisory, previously extracted source reading changes. */
public final class RelationGuidedVerificationTrial {
    static final String BASELINE_SHA = "7f54f8fba2e975d9d8fb0a0fa480624385f6a1b0dd4c6f0eeb1604035000a121";
    static final String EXTRACTION_SHA = "09663c7ea0e1b90452c1fa92c6994775cda5ca006202eb87c13c122825e8adf9";
    static final String CONSENT = "NEW_USD050_RELATION_GUIDED_VERIFICATION_TEXT_APPROVED";
    static final List<String> IDS = List.of("R01-166", "R04", "R05", "R06", "R08");
    static final String FIELD = "sourceRelationReading";
    static final double CAP = .50;
    static final int CALLS = 30, OUTPUT_CAP = 8000;
    record Plan(String schemaVersion, String model, String engineRevision, String baselineSha256,
                String extractionSha256, int maxHttpAttempts, double proposedEstimatedCapUsd,
                double reservationEstimatedUsd, String comparisonFactor, String readingSelection,
                boolean independentGold, Map<String, JsonNode> fixedReadings,
                List<DefaultVerificationPlan.Request> requests, List<JsonNode> requestBodies) {}
    private RelationGuidedVerificationTrial() {}

    static Plan prepare(byte[] baseline, byte[] extraction) throws Exception {
        check(BASELINE_SHA.equals(ReferenceOnlyTrial.sha(baseline)) && EXTRACTION_SHA.equals(ReferenceOnlyTrial.sha(extraction)),
                "Exact frozen verification plan and extraction results required");
        return build(JSON.readTree(baseline), JSON.readTree(extraction));
    }

    static Plan build(JsonNode baseline, JsonNode extraction) {
        check("COMPLETE".equals(extraction.path("state").asText()) && extraction.path("httpAttempts").asInt() == 15,
                "Completed 15-call extraction required");
        var base = new LinkedHashMap<String, DefaultVerificationPlan.Request>();
        for (JsonNode row : baseline.path("requests")) if ("BASELINE".equals(row.path("arm").asText())
                && row.path("repeat").asInt() == 1 && IDS.contains(row.path("caseId").asText())) {
            var r = JSON.treeToValue(row, DefaultVerificationPlan.Request.class);
            check(base.put(r.caseId(), r) == null, "Duplicate frozen verification request");
        }
        check(base.size() == 5, "Five original verification requests required");
        var readings = new LinkedHashMap<String, JsonNode>();
        for (String id : IDS) {
            var rows = new ArrayList<JsonNode>();
            extraction.path("results").forEach(row -> {
                if (id.equals(row.path("conditionId").asText()) && row.path("repeat").asInt() == 1) rows.add(row);
            });
            check(rows.size() == 1, "Exactly one FIRST extraction repeat required: " + id);
            JsonNode row = rows.get(0), input = JSON.readTree(base.get(id).input());
            check(row.path("assessment").path("accepted").asBoolean() && row.path("output").path("observations").size() == 1,
                    "Structurally accepted reading required");
            check(CurrentRequestCounterevidencePlan.projected(input.path("raw"))
                    .equals(CurrentRequestCounterevidencePlan.projected(row.at("/input/segments"))), "Reading/raw snapshot mismatch");
            check(input.at("/candidates/0/anchorId").asText().equals(row.at("/input/focusId").asText()), "Reading/anchor mismatch");
            check(JSON.readTree(row.path("providerContent").asText()).equals(row.path("output")), "Raw extraction content differs from saved reading");
            JsonNode observation = row.path("output").path("observations").get(0);
            check(input.at("/candidates/0/anchorId").asText().equals(observation.path("anchorId").asText()), "Reading output anchor mismatch");
            Set<String> allowed = new HashSet<>(); input.path("raw").forEach(segment -> allowed.add(segment.path("id").asText()));
            observation.path("evidenceIds").forEach(value -> check(allowed.contains(value.asText()), "Reading evidence outside raw"));
            observation.path("links").forEach(link -> check(allowed.contains(link.path("segmentId").asText()), "Reading link outside raw"));
            var note = JSON.createObjectNode();
            note.put("origin", "SEPARATE_MODEL_SOURCE_EXTRACTION"); note.put("advisoryOnly", true);
            note.put("notEvidenceOrDecision", true); note.put("verifyAgainstRaw", true);
            note.set("observations", row.path("output").path("observations").deepCopy());
            readings.put(id, note);
        }
        var requests = new ArrayList<DefaultVerificationPlan.Request>();
        var orders = List.of(IDS, List.of("R08", "R06", "R05", "R04", "R01-166"),
                List.of("R05", "R06", "R08", "R01-166", "R04"));
        for (int repeat = 1; repeat <= 3; repeat++) for (String id : orders.get(repeat - 1)) {
            var source = base.get(id);
            String expected = Set.of("R04", "R08").contains(id) ? "REVIEW_REQUIRED" : "PASS";
            check(expected.equals(source.expectedDecision()), "User-approved decision drift");
            check(source.system().equals(CandidateReviewEngine.verificationPrompt(0) + "\n" + DefaultVerificationPlan.reference(source.system())),
                    "Current default prompt differs from frozen baseline");
            var on = (ObjectNode) JSON.readTree(source.input());
            check(!on.has(FIELD), "Baseline already has independent reading");
            on.set(FIELD, readings.get(id).deepCopy());
            for (String arm : repeat % 2 == 1 ? List.of("WITHOUT", "WITH") : List.of("WITH", "WITHOUT"))
                requests.add(new DefaultVerificationPlan.Request(id, source.labelStatus(), expected, arm, repeat,
                        source.system(), arm.equals("WITH") ? on.toString() : source.input()));
        }
        List<JsonNode> bodies = requests.stream().map(RelationGuidedVerificationTrial::body).toList();
        var plan = new Plan("relation-guided-verification-1", "gpt-6-luna", CandidateReviewEngine.REVISION,
                BASELINE_SHA, EXTRACTION_SHA, CALLS, CAP, reservation(bodies), "ONE_INPUT_FIELD_ONLY",
                "FIRST_REPEAT_EACH_CASE_UNCHANGED_NO_BEST_OF_OR_LABEL_SELECTION", false,
                Collections.unmodifiableMap(readings), List.copyOf(requests), bodies);
        validate(plan); return plan;
    }

    static JsonNode body(DefaultVerificationPlan.Request r) {
        var props = new OpenAiProperties("offline-test-key", null, null, "https://api.openai.com/v1",
                "gpt-6-luna", "none", Duration.ofSeconds(90), null);
        var client = new OpenAiClient(RestClient.create(), props);
        var body = new LinkedHashMap<>(client.replayRequestBody(r.system(), r.input(), CandidateReviewEngine.VerificationResult.class));
        body.put("max_completion_tokens", OUTPUT_CAP);
        return JSON.readTree(JSON.writeValueAsString(body));
    }

    static double reserveOne(JsonNode body) { return (JSON.writeValueAsBytes(body).length + 1024d) * .10 / 1_000_000d + OUTPUT_CAP * .50 / 1_000_000d; }
    static double reservation(List<JsonNode> bodies) { return bodies.stream().mapToDouble(RelationGuidedVerificationTrial::reserveOne).sum(); }

    static void validate(Plan p) {
        if (IssueScopeComparisonPlan.VERSION.equals(p.schemaVersion())) { IssueScopeComparisonPlan.validate(p); return; }
        check("relation-guided-verification-1".equals(p.schemaVersion()) && "gpt-6-luna".equals(p.model())
                && CandidateReviewEngine.REVISION.equals(p.engineRevision()) && BASELINE_SHA.equals(p.baselineSha256())
                && EXTRACTION_SHA.equals(p.extractionSha256()) && p.maxHttpAttempts() == CALLS
                && p.proposedEstimatedCapUsd() == CAP && p.requests().size() == CALLS && p.requestBodies().size() == CALLS
                && !p.independentGold() && p.fixedReadings().keySet().equals(new HashSet<>(IDS)), "Known fixed comparison required");
        check(Math.abs(p.reservationEstimatedUsd() - reservation(p.requestBodies())) < 1e-12
                && p.reservationEstimatedUsd() <= CAP, "Frozen budget/body accounting changed");
        var counts = new HashMap<String, Integer>();
        for (int i = 0; i < CALLS; i += 2) {
            var a = p.requests().get(i); var b = p.requests().get(i + 1);
            check(a.caseId().equals(b.caseId()) && a.repeat() == b.repeat() && a.system().equals(b.system())
                    && a.expectedDecision().equals(b.expectedDecision()) && Set.of(a.arm(), b.arm()).equals(Set.of("WITHOUT", "WITH")), "Unpaired requests");
            var off = JSON.readTree(a.arm().equals("WITHOUT") ? a.input() : b.input());
            var on = (ObjectNode) JSON.readTree(a.arm().equals("WITH") ? a.input() : b.input());
            check(!off.has(FIELD) && on.path(FIELD).equals(p.fixedReadings().get(a.caseId())), "Fixed machine reading changed");
            on.remove(FIELD); check(on.equals(off), "Raw/candidate/hypothesis changed across arms");
            counts.merge(a.caseId(), 1, Integer::sum);
        }
        check(counts.size() == 5 && counts.values().stream().allMatch(n -> n == 3), "Five cases by three repeats required");
        for (int i = 0; i < CALLS; i++) {
            check(body(p.requests().get(i)).equals(p.requestBodies().get(i)), "Frozen API request changed");
            check(!p.requestBodies().get(i).toString().contains("expectedDecision")
                    && !p.requestBodies().get(i).toString().contains("USER_APPROVED"), "Evaluator label in provider body");
        }
    }

    static Map<String, Object> audit(Plan p, JsonNode results) {
        check("COMPLETE".equals(results.path("state").asText()) && results.path("httpAttempts").asInt() == CALLS
                && results.path("results").size() == CALLS, "Complete 30-call results required");
        var totals = new LinkedHashMap<String, Map<String, Integer>>();
        for (int i = 0; i < CALLS; i++) {
            var req = p.requests().get(i); var row = results.path("results").get(i);
            check(row.path("requestIndex").asInt(-1) == i && req.caseId().equals(row.path("caseId").asText())
                    && req.arm().equals(row.path("arm").asText()) && req.repeat() == row.path("repeat").asInt(), "Result order mismatch");
            var output = JSON.treeToValue(row.path("output"), CandidateReviewEngine.VerificationResult.class);
            check(JSON.valueToTree(DefaultVerificationPlan.assess(req, output)).equals(row.path("assessment")), "Validator replay changed");
            boolean accepted = row.at("/assessment/accepted").asBoolean();
            String decision = row.at("/output/verifications/0/assessment/decision").asText("MISSING");
            for (String key : List.of(req.arm() + ":ALL", req.arm() + ":" + req.caseId())) {
                var group = totals.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
                group.merge("responses", 1, Integer::sum);
                if (accepted) group.merge("contractAccepted", 1, Integer::sum);
                if (req.expectedDecision().equals(decision)) {
                    group.merge("decisionMatch", 1, Integer::sum);
                    if (accepted) group.merge("matchAndAccepted", 1, Integer::sum);
                }
            }
        }
        return Map.of("calls", CALLS, "totals", totals, "independentGold", false,
                "meaningRequiresSourceReview", true, "runtimePromotion", false);
    }

    static void save(Path out, String sha, List<Map<String, Object>> rows, int attempts, double reserved, double usage, String state) throws Exception {
        Path path = out.resolve("results.json");
        Files.writeString(path, JSON.writeValueAsString(Map.of("planSha256", sha, "state", state, "results", rows,
                "httpAttempts", attempts, "reservedEstimatedUsd", reserved, "usageEstimatedUsd", usage, "actualBillingConfirmed", false)));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    static void run(Plan p, byte[] planBytes, Path planFile, Path out) throws Exception {
        validate(p); String sha = ReferenceOnlyTrial.sha(planBytes);
        String consent = IssueScopeComparisonPlan.VERSION.equals(p.schemaVersion()) ? IssueScopeComparisonPlan.CONSENT : CONSENT;
        String key = System.getenv("OPENAI_API_KEY"); check(key != null && !key.isBlank(), "OPENAI_API_KEY required; never print");
        SourceRelationExtractionTrial.writePrivate(planFile.toRealPath().getParent().resolve("execution-claim.json"),
                JSON.writeValueAsBytes(Map.of("planSha256", sha, "consent", consent, "output", out.toString(), "resumable", false)));
        Files.createDirectory(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        SourceRelationExtractionTrial.writePrivate(out.resolve("plan.json"), planBytes);
        SourceRelationExtractionTrial.writePrivate(out.resolve("authorization.json"), JSON.writeValueAsBytes(Map.of(
                "consent", consent, "newEstimatedCapUsd", CAP, "textEgressApproved", true, "mediaEgressApproved", false, "previousBudgetReused", false)));
        var guard = new SourceRelationExtractionTrial.Guard();
        int[] attempts = {0}; double[] reserved = {0};
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build()); factory.setReadTimeout(Duration.ofSeconds(90));
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key).requestInterceptor((request, bytes, execution) -> {
                    byte[] checked = guard.accept(bytes, request.getURI().toString());
                    double next = reserveOne(JSON.readTree(checked));
                    check(attempts[0] < CALLS && reserved[0] + next <= CAP, "Call/cost cap exhausted");
                    attempts[0]++; reserved[0] += next;
                    try { Files.writeString(out.resolve("reservation.json"), JSON.writeValueAsString(Map.of(
                            "maxHttpAttempts", CALLS, "httpAttempts", attempts[0], "reservedEstimatedUsd", reserved[0], "capUsd", CAP))); }
                    catch (java.io.IOException ex) { throw new IllegalStateException("Cannot persist reservation", ex); }
                    request.getHeaders().setContentLength(checked.length); return execution.execute(request, checked);
                });
        for (String header : List.of("OpenAI-Organization", "OpenAI-Project")) {
            String value = System.getenv(header.equals("OpenAI-Organization") ? "OPENAI_ORG_ID" : "OPENAI_PROJECT_ID");
            if (value != null && !value.isBlank()) builder.defaultHeader(header, value);
        }
        RestClient client = builder.build(); var rows = new ArrayList<Map<String, Object>>(); double cost = 0;
        try {
            for (int i = 0; i < CALLS; i++) {
                var req = p.requests().get(i); JsonNode body = p.requestBodies().get(i); guard.begin(JSON.writeValueAsBytes(body));
                long start = System.nanoTime();
                JsonNode response = JSON.readTree(SourceRelationExtractionTrial.postAndPreserve(client, body,
                        out.resolve(String.format(Locale.ROOT, "provider-response-%02d.json", i + 1))));
                JsonNode usage = response.path("usage"), choice = response.at("/choices/0");
                long input = usage.path("prompt_tokens").asLong(-1), output = usage.path("completion_tokens").asLong(-1);
                long cached = usage.at("/prompt_tokens_details/cached_tokens").asLong(0);
                check(input >= 0 && output >= 0 && cached >= 0 && cached <= input, "Invalid usage; raw response preserved");
                cost += new OpenAiClient.TokenUsage(1, input, cached, output, p.model(),
                        new OpenAiProperties.Pricing(.10, .01, .50, null, null)).costUsd();
                String content = choice.at("/message/content").asText("");
                CandidateReviewEngine.VerificationResult parsed = null;
                try { parsed = JSON.readValue(content, CandidateReviewEngine.VerificationResult.class); }
                catch (RuntimeException ignored) { }
                var row = new LinkedHashMap<String, Object>();
                row.put("requestIndex", i); row.put("caseId", req.caseId()); row.put("arm", req.arm()); row.put("repeat", req.repeat());
                row.put("providerResponse", response); row.put("output", parsed); row.put("usage", usage);
                row.put("assessment", DefaultVerificationPlan.assess(req, parsed)); row.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
                rows.add(row); save(out, sha, rows, attempts[0], reserved[0], cost, "RUNNING");
                check("stop".equals(choice.path("finish_reason").asText()) && parsed != null && parsed.verifications() != null
                        && parsed.verifications().size() == 1, "Provider/output shape failure; stop without retry");
                System.out.println("RELATION_GUIDED_VERIFICATION " + (i + 1) + "/30 " + req.caseId() + " " + req.arm());
            }
            save(out, sha, rows, attempts[0], reserved[0], cost, "COMPLETE");
            SourceRelationExtractionTrial.writePrivate(out.resolve("offline-audit.json"),
                    JSON.writeValueAsBytes(audit(p, JSON.readTree(Files.readAllBytes(out.resolve("results.json"))))));
        } catch (Exception ex) { save(out, sha, rows, attempts[0], reserved[0], cost, "STOPPED"); throw ex; }
    }

    public static void main(String[] args) throws Exception {
        check(args.length >= 1, "prepare/run/audit required");
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        Path out = Path.of(args[args.length - 1]).toAbsolutePath().normalize();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(root), "New private output required");
        if ("prepare".equals(args[0])) {
            check(args.length == 4, "prepare frozen baseline, extraction results, new plan directory");
            Plan p = prepare(OfflineDecisionContractAudit.bounded(Path.of(args[1])), OfflineDecisionContractAudit.bounded(Path.of(args[2])));
            Files.createDirectory(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            byte[] bytes = JSON.writeValueAsBytes(p); SourceRelationExtractionTrial.writePrivate(out.resolve("plan.json"), bytes);
            System.out.println("RELATION_GUIDED_PREPARED calls=30 providerCalls=0 reservationEstimatedUsd=" + p.reservationEstimatedUsd()
                    + " planSha256=" + ReferenceOnlyTrial.sha(bytes)); return;
        }
        byte[] bytes = OfflineDecisionContractAudit.bounded(Path.of(args[1])); Plan p = JSON.readValue(bytes, Plan.class); validate(p);
        if ("audit".equals(args[0])) {
            check(args.length == 4, "audit frozen plan, saved results, new audit file");
            JsonNode results = JSON.readTree(OfflineDecisionContractAudit.bounded(Path.of(args[2])));
            check(ReferenceOnlyTrial.sha(bytes).equals(results.path("planSha256").asText()), "Results/plan mismatch");
            SourceRelationExtractionTrial.writePrivate(out, JSON.writeValueAsBytes(audit(p, results))); return;
        }
        String consent = IssueScopeComparisonPlan.VERSION.equals(p.schemaVersion()) ? IssueScopeComparisonPlan.CONSENT : CONSENT;
        check("run".equals(args[0]) && args.length == 5 && ReferenceOnlyTrial.sha(bytes).equals(args[2]) && consent.equals(args[3]),
                "New exact-plan USD0.50/text approval required");
        run(p, bytes, Path.of(args[1]), out);
    }
}
