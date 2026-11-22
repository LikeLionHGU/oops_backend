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

/** Explicitly approved, test-only execution. No retries, repair, media or runtime wiring. */
public final class FinalReasonPremiseTrial {
    static final String CONSENT = "NEW_USD050_FINAL_REASON_PREMISE_AUDIT_TEXT_APPROVED";
    private FinalReasonPremiseTrial() {}
    static void save(Path file, Object value) throws Exception {
        Files.writeString(file, JSON.writeValueAsString(value));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
    static FinalReasonPremiseAudit.Routing assess(JsonNode input, JsonNode response) {
        FinalReasonPremiseAudit.Result parsed;
        try { parsed = JSON.treeToValue(response, FinalReasonPremiseAudit.Result.class); }
        catch (RuntimeException ex) { return FinalReasonPremiseAudit.incomplete("UNPARSEABLE_AUDIT"); }
        return FinalReasonPremiseAudit.route(input, parsed);
    }
    static Map<String, Object> audit(PrepareFinalReasonPremiseTrial.Plan plan, JsonNode results) {
        check("COMPLETE".equals(results.path("state").asText()) && results.path("results").size() == 37
                && results.path("httpAttempts").asInt() == 37, "Complete 37-call results required");
        var totals = new LinkedHashMap<String, Map<String, Integer>>();
        for (int i = 0; i < 37; i++) {
            var request = plan.requests().get(i); var row = results.path("results").get(i);
            check(row.path("index").asInt(-1) == i && request.id().equals(row.path("id").asText()), "Result order mismatch");
            var route = assess(request.input(), row.path("output"));
            check(JSON.valueToTree(route).equals(row.path("routing")), "Saved routing differs from offline replay");
            var group = totals.computeIfAbsent(request.origin(), ignored -> new LinkedHashMap<>());
            group.merge("responses", 1, Integer::sum); group.merge(route.route().name(), 1, Integer::sum);
            if (request.expectedRouteDraft().equals(route.route().name())) group.merge("developerDraftAgreement", 1, Integer::sum);
        }
        return Map.of("calls", 37, "totals", totals, "humanGold", false, "semanticReviewRequired", true,
                "runtimeConnected", false, "publicationAuthorized", false);
    }
    static void run(PrepareFinalReasonPremiseTrial.Plan plan, byte[] bytes, Path planFile, Path out) throws Exception {
        PrepareFinalReasonPremiseTrial.validate(plan);
        String key = System.getenv("OPENAI_API_KEY"); check(key != null && !key.isBlank(), "API key required; never print");
        String sha = ReferenceOnlyTrial.sha(bytes);
        SourceRelationExtractionTrial.writePrivate(planFile.toRealPath().getParent().resolve("execution-claim.json"),
                JSON.writeValueAsBytes(Map.of("planSha256", sha, "consent", CONSENT, "output", out.toString(), "resumable", false)));
        Files.createDirectory(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        SourceRelationExtractionTrial.writePrivate(out.resolve("plan.json"), bytes);
        SourceRelationExtractionTrial.writePrivate(out.resolve("authorization.json"), JSON.writeValueAsBytes(Map.of("consent", CONSENT,
                "estimatedCapUsd", .50, "textEgressApproved", true, "mediaEgressApproved", false, "previousBudgetReused", false)));
        var guard = new SourceRelationExtractionTrial.Guard(); int[] attempts = {0}; double[] reserved = {0};
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build()); factory.setReadTimeout(Duration.ofSeconds(90));
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key).requestInterceptor((request, body, execution) -> {
                    byte[] checked = guard.accept(body, request.getURI().toString());
                    double next = (checked.length + 1024d) * .10 / 1_000_000d
                            + PrepareFinalReasonPremiseTrial.OUTPUT_CAP * .50 / 1_000_000d;
                    check(attempts[0] < 37 && reserved[0] + next <= .50, "Call/reservation cap exhausted");
                    attempts[0]++; reserved[0] += next;
                    try { save(out.resolve("reservation.json"), Map.of("httpAttempts", attempts[0], "reservedEstimatedUsd", reserved[0], "capUsd", .50)); }
                    catch (Exception ex) { throw new IllegalStateException("Cannot persist reservation", ex); }
                    request.getHeaders().setContentLength(checked.length); return execution.execute(request, checked);
                });
        for (String header : List.of("OpenAI-Organization", "OpenAI-Project")) {
            String value = System.getenv(header.equals("OpenAI-Organization") ? "OPENAI_ORG_ID" : "OPENAI_PROJECT_ID");
            if (value != null && !value.isBlank()) builder.defaultHeader(header, value);
        }
        var client = builder.build(); var rows = new ArrayList<Map<String, Object>>(); double cost = 0;
        try {
            for (int i = 0; i < 37; i++) {
                var request = plan.requests().get(i); guard.begin(JSON.writeValueAsBytes(request.body()));
                long start = System.nanoTime();
                JsonNode provider = JSON.readTree(SourceRelationExtractionTrial.postAndPreserve(client, request.body(),
                        out.resolve(String.format(Locale.ROOT, "provider-response-%02d.json", i + 1))));
                JsonNode usage = provider.path("usage"), choice = provider.at("/choices/0");
                long input = usage.path("prompt_tokens").asLong(-1), cached = usage.at("/prompt_tokens_details/cached_tokens").asLong(0),
                        output = usage.path("completion_tokens").asLong(-1);
                check(input >= 0 && cached >= 0 && cached <= input && output >= 0, "Invalid usage; raw response preserved");
                cost += ((input - cached) * .10 + cached * .01 + output * .50) / 1_000_000d;
                JsonNode parsed;
                try { parsed = JSON.readTree(choice.at("/message/content").asText()); }
                catch (RuntimeException ex) { parsed = JSON.nullNode(); }
                var route = assess(request.input(), parsed);
                rows.add(Map.of("index", i, "id", request.id(), "providerResponse", provider, "output", parsed,
                        "routing", route, "elapsedMs", (System.nanoTime() - start) / 1_000_000));
                saveResults(out, sha, rows, attempts[0], reserved[0], cost, "RUNNING");
                check("gpt-6-luna".equals(provider.path("model").asText()) && "stop".equals(choice.path("finish_reason").asText())
                        && !parsed.isNull(), "Provider/output failure; stop without retry");
                if (i == 0) check(route.route() != FinalReasonPremiseAudit.Route.INCOMPLETE_AUDIT,
                        "Compatibility contract failed; no remaining calls");
                System.out.println("PREMISE_AUDIT " + (i + 1) + "/37 " + request.id());
            }
            saveResults(out, sha, rows, attempts[0], reserved[0], cost, "COMPLETE");
            SourceRelationExtractionTrial.writePrivate(out.resolve("offline-audit.json"),
                    JSON.writeValueAsBytes(audit(plan, JSON.readTree(Files.readAllBytes(out.resolve("results.json"))))));
        } catch (Exception ex) { saveResults(out, sha, rows, attempts[0], reserved[0], cost, "STOPPED"); throw ex; }
    }
    static void saveResults(Path out, String sha, List<Map<String, Object>> rows, int attempts, double reserved, double cost, String state) throws Exception {
        save(out.resolve("results.json"), Map.of("planSha256", sha, "state", state, "httpAttempts", attempts,
                "reservedEstimatedUsd", reserved, "usageEstimatedUsd", cost, "actualBillingConfirmed", false, "results", rows));
    }
    public static void main(String[] args) throws Exception {
        check(args.length >= 4, "run/audit exact plan parameters required");
        Path planFile = Path.of(args[1]), out = Path.of(args[args.length - 1]).toAbsolutePath().normalize();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(Path.of("../datasets/controversy/experiments").toRealPath()),
                "New private experiment output required");
        byte[] bytes = OfflineDecisionContractAudit.bounded(planFile);
        var plan = JSON.readValue(bytes, PrepareFinalReasonPremiseTrial.Plan.class); PrepareFinalReasonPremiseTrial.validate(plan);
        if ("audit".equals(args[0])) {
            check(args.length == 4, "audit plan, results, new output file");
            JsonNode results = JSON.readTree(OfflineDecisionContractAudit.bounded(Path.of(args[2])));
            check(ReferenceOnlyTrial.sha(bytes).equals(results.path("planSha256").asText()), "Results/plan mismatch");
            SourceRelationExtractionTrial.writePrivate(out, JSON.writeValueAsBytes(audit(plan, results))); return;
        }
        check("run".equals(args[0]) && args.length == 5 && ReferenceOnlyTrial.sha(bytes).equals(args[2]) && CONSENT.equals(args[3]),
                "New exact-plan USD0.50/text consent required");
        run(plan, bytes, planFile, out);
    }
}
