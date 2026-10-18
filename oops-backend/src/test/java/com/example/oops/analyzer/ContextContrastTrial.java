package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Explicitly approved 12-call baseline only; not a runtime card publisher. */
public final class ContextContrastTrial {
    static final String SHA = "a0a36a57887c5a7c4e5aa1091b690d20500a74b10941a463379657cc46876e58";
    static final String CONSENT = "USER_APPROVED_CONTEXT_CONTRAST_USD010_TEXT";
    private ContextContrastTrial() {}
    static void validate(byte[] bytes) throws Exception {
        check(SHA.equals(ReferenceOnlyTrial.sha(bytes)) && ContextContrastPlan.build().equals(JSON.readTree(bytes)), "Exact frozen plan required");
        check(JSON.readTree(bytes).path("reservationEstimatedUsd").asDouble() <= .10, "Reservation exceeds new approval");
    }
    static SourceFirstJudgmentContract.Assessment assess(JsonNode request, JsonNode output) {
        try { return SourceFirstJudgmentContract.assess(request.path("input"), null, JSON.treeToValue(output, SourceFirstJudgmentContract.Outcome.class)); }
        catch (RuntimeException ex) { return SourceFirstJudgmentContract.invalid("UNPARSEABLE_OUTPUT"); }
    }
    static JsonNode audit(JsonNode plan, JsonNode result) {
        check(SHA.equals(result.path("planSha256").asText()) && "COMPLETE".equals(result.path("state").asText())
                && result.path("httpAttempts").asInt() == 12 && result.path("results").size() == 12, "Complete matching results required");
        var audit = JSON.createObjectNode().put("publicationAuthorized", false).put("independentGold", false)
                .put("reasonValidity", "PENDING_HUMAN_REVIEW");
        var totals = audit.putObject("totals");
        for (int i = 0; i < 12; i++) {
            var req = plan.path("requests").get(i); var row = result.path("results").get(i);
            check(row.path("index").asInt(-1) == i && row.path("id").equals(req.path("id")), "Order drift");
            var assessed = assess(req, row.path("output"));
            check(JSON.valueToTree(assessed).equals(row.path("assessment")), "Assessment replay differs");
            String id = req.path("caseId").asText();
            var group = totals.has(id) ? (tools.jackson.databind.node.ObjectNode) totals.path(id) : totals.putObject(id);
            for (String field : List.of("responses", assessed.state())) group.put(field, group.path(field).asInt() + 1);
            if (req.at("/localContextLabel/expectedDecision").equals(row.at("/output/decision"))) {
                group.put("decisionLabelMatch", group.path("decisionLabelMatch").asInt() + 1);
                if (assessed.state().equals("ASSESSMENT_RECORDED")) group.put("matchAndRecorded", group.path("matchAndRecorded").asInt() + 1);
            }
        }
        return audit;
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && "audit".equals(args[0])) {
            byte[] planBytes = OfflineDecisionContractAudit.bounded(Path.of(args[1])); validate(planBytes);
            var results = JSON.readTree(OfflineDecisionContractAudit.bounded(Path.of(args[2])));
            SourceRelationExtractionTrial.writePrivate(Path.of(args[3]), JSON.writeValueAsBytes(audit(JSON.readTree(planBytes), results)));
            return;
        }
        check(args.length == 3 && CONSENT.equals(args[1]), "New USD0.10/text approval required");
        byte[] bytes = OfflineDecisionContractAudit.bounded(Path.of(args[0])); validate(bytes);
        var plan = JSON.readTree(bytes); var out = Path.of(args[2]).toAbsolutePath().normalize();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(Path.of("../datasets/controversy/experiments").toRealPath()), "New private results directory required");
        String key = System.getenv("OPENAI_API_KEY"); check(key != null && !key.isBlank(), "API key unavailable");
        SourceRelationExtractionTrial.writePrivate(Path.of(args[0] + ".execution-claim.json"), JSON.writeValueAsBytes(Map.of("sha", SHA, "consent", CONSENT, "resumable", false)));
        Files.createDirectory(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        SourceRelationExtractionTrial.writePrivate(out.resolve("plan.json"), bytes);
        SourceRelationExtractionTrial.writePrivate(out.resolve("authorization.json"), JSON.writeValueAsBytes(Map.of("consent", CONSENT, "capUsd", .10, "textEgressApproved", true, "mediaEgressApproved", false, "priorBudgetReused", false)));
        var guard = new SourceRelationExtractionTrial.Guard(); int[] attempts = {0}; double[] reserved = {0};
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()); factory.setReadTimeout(Duration.ofSeconds(90));
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory).defaultHeader("Authorization", "Bearer " + key)
                .requestInterceptor((request, body, execution) -> {
                    byte[] accepted = guard.accept(body, request.getURI().toString());
                    double next = SourceFirstJudgmentTrial.reserve(JSON.readTree(accepted));
                    check(attempts[0] < 12 && reserved[0] + next <= .10, "Approved attempt/reservation limit");
                    attempts[0]++; reserved[0] += next;
                    try { FinalReasonPremiseTrial.save(out.resolve("reservation.json"), Map.of("httpAttempts", attempts[0], "reservedEstimatedUsd", reserved[0])); }
                    catch (Exception ex) { throw new IllegalStateException("Cannot preserve reservation", ex); }
                    request.getHeaders().setContentLength(accepted.length); return execution.execute(request, accepted);
                });
        for (String header : List.of("OpenAI-Organization", "OpenAI-Project")) {
            String v = System.getenv(header.equals("OpenAI-Organization") ? "OPENAI_ORG_ID" : "OPENAI_PROJECT_ID"); if (v != null && !v.isBlank()) builder.defaultHeader(header, v);
        }
        var client = builder.build(); var rows = new ArrayList<Map<String, Object>>(); double cost = 0;
        try {
            for (var req : plan.path("requests")) {
                int i = rows.size(); guard.begin(JSON.writeValueAsBytes(req.path("body"))); long start = System.nanoTime();
                var response = JSON.readTree(SourceRelationExtractionTrial.postAndPreserve(client, req.path("body"), out.resolve(String.format(Locale.ROOT, "provider-response-%02d.json", i + 1))));
                var usage = response.path("usage"); long in = usage.path("prompt_tokens").asLong(-1), cached = usage.at("/prompt_tokens_details/cached_tokens").asLong(0), tokens = usage.path("completion_tokens").asLong(-1);
                check(in >= 0 && cached >= 0 && cached <= in && tokens >= 0, "Invalid usage; raw preserved");
                cost += ((in - cached) * .10 + cached * .01 + tokens * .50) / 1_000_000d;
                JsonNode output; try { output = JSON.readTree(response.at("/choices/0/message/content").asText()); } catch (RuntimeException ex) { output = JSON.nullNode(); }
                rows.add(Map.of("index", i, "id", req.path("id").asText(), "output", output, "providerResponse", response,
                        "assessment", assess(req, output), "elapsedMs", (System.nanoTime() - start) / 1_000_000));
                FinalReasonPremiseTrial.saveResults(out, SHA, rows, attempts[0], reserved[0], cost, "RUNNING");
                check("gpt-6-luna".equals(response.path("model").asText()) && "stop".equals(response.at("/choices/0/finish_reason").asText()) && !output.isNull(), "Provider/output failure; stop without retry");
                System.out.println("CONTEXT_CONTRAST " + (i + 1) + "/12 " + req.path("id").asText());
            }
            FinalReasonPremiseTrial.saveResults(out, SHA, rows, attempts[0], reserved[0], cost, "COMPLETE");
            SourceRelationExtractionTrial.writePrivate(out.resolve("offline-audit.json"), JSON.writeValueAsBytes(audit(plan, JSON.readTree(Files.readAllBytes(out.resolve("results.json"))))));
        } catch (Exception ex) { FinalReasonPremiseTrial.saveResults(out, SHA, rows, attempts[0], reserved[0], cost, "STOPPED"); throw ex; }
    }
}
