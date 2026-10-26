package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** One-use, text-only GPT-6 Luna smoke run for the frozen social-defect plan. */
public final class SocialDefectCurrentModelTrial {
    static final String CONSENT = "SOCIAL_DEFECT_CURRENT_MODEL_TRIAL_20261026_APPROVED";
    static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    static final String PLAN_RELATIVE = "../datasets/controversy/experiments/social-defect-current-model-plan-20261026-v1/plan.json";
    static final int MAX_BODY_BYTES = 120_000;
    static final int MAX_HTTP_TOTAL = 24;
    static final int MAX_HTTP_PER_ANALYSIS = 4;
    static final double CAP_USD = .50;
    static final double INPUT_RATE = .10;
    static final double OUTPUT_RATE = .50;
    static final int RESERVED_OUTPUT_TOKENS = 8_000;
    static final int MAX_OUTPUT_TOKENS = RESERVED_OUTPUT_TOKENS;

    record CaseInput(String id, String expected, ContentGenre genre, ReviewInput review,
                     List<String> sourceIds, String kind) {}

    private SocialDefectCurrentModelTrial() {}

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static Map<String, String> implementation() throws Exception {
        var result = new TreeMap<String, String>();
        try (var stream = Files.walk(Path.of("src/main"))) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".json")).toList())
                result.put(path.toString(), sha(Files.readAllBytes(path)));
        }
        for (String file : List.of("build.gradle",
                "src/test/java/com/example/oops/analyzer/SocialDefectCurrentModelTrial.java",
                "src/test/java/com/example/oops/analyzer/SocialDefectCurrentModelTrialTest.java",
                "src/test/java/com/example/oops/analyzer/MechanismRoutingTrial.java",
                "src/test/java/com/example/oops/analyzer/V7RoutingVideoTrial.java",
                "src/test/java/com/example/oops/analyzer/TextBoundaryTrial.java",
                "src/test/java/com/example/oops/analyzer/DefaultVerificationTrial.java"))
            result.put(file, sha(Files.readAllBytes(Path.of(file))));
        return result;
    }

    static tools.jackson.databind.JsonNode readBounded(Path path) throws Exception {
        check(Files.size(path) <= 1_000_000, "Bounded local input required");
        return JSON.readTree(Files.readAllBytes(path));
    }

    static List<CaseInput> loadCases(Path planPath) throws Exception {
        var plan = readBounded(planPath);
        check(plan.path("schemaVersion").asText().equals("current-model-smoke-plan-1")
                && plan.path("status").asText().equals("FROZEN_OFFLINE_NOT_EXECUTED"), "Frozen plan required");
        check(plan.path("repository").path("head").asText().equals("632308de080854d5eca71dbe7f021eb6e3ef2e1e"), "Unexpected plan base");

        Path root = Path.of("../datasets/controversy").toRealPath();
        Path unitPath = root.resolve(plan.at("/fixedConfiguration/casebookUnit/path").asText()).toRealPath();
        byte[] unitBytes = OfflineDecisionContractAudit.bounded(unitPath);
        check(sha(unitBytes).equals(plan.at("/fixedConfiguration/casebookUnit/sha256").asText()), "Casebook unit drift");
        var unit = JSON.readTree(unitBytes);
        check(unit.path("id").asText().equals("podcast-dev#podcast-social-defect")
                && unit.path("status").asText().equals("DRAFT")
                && !unit.path("trainingUseAuthorized").asBoolean(true), "Unpromoted development case required");

        Path sourcePath = root.resolve(plan.at("/cases/0/source/path").asText()).toRealPath();
        byte[] sourceBytes = OfflineDecisionContractAudit.bounded(sourcePath);
        check(sha(sourceBytes).equals(plan.at("/cases/0/source/sha256").asText()), "Positive source drift");
        var source = JSON.readTree(sourceBytes);
        check(source.path("sourceKind").asText().equals("USER_SUPPLIED_SCRIPT_NOT_AUDIO_VERIFIED"), "Known transcript provenance required");

        Path positiveApprovalPath = root.resolve(plan.at("/cases/0/source/humanReviewPath").asText()).toRealPath();
        byte[] positiveApprovalBytes = OfflineDecisionContractAudit.bounded(positiveApprovalPath);
        check(sha(positiveApprovalBytes).equals(plan.at("/cases/0/source/humanReviewSha256").asText()), "Positive approval drift");
        var positiveApproval = JSON.readTree(positiveApprovalBytes);
        check(positiveApproval.path("trainingUseAuthorized").asBoolean(true) == false, "No training authorization expected");
        Set<String> approvedPositiveEvents = new HashSet<>();
        for (var review : positiveApproval.path("reviews")) {
            if (!review.path("decision").asText().equals("REVIEW_REQUIRED")) continue;
            review.path("eventIds").forEach(id -> approvedPositiveEvents.add(id.asText()));
        }
        for (var id : plan.at("/cases/0/source/approvedEventIds"))
            check(approvedPositiveEvents.contains(id.asText()), "Approved positive event missing");

        Path normalApprovalPath = root.resolve(plan.at("/cases/1/source/approvalPath").asText()).toRealPath();
        byte[] normalApprovalBytes = OfflineDecisionContractAudit.bounded(normalApprovalPath);
        check(sha(normalApprovalBytes).equals(plan.at("/cases/1/source/approvalSha256").asText()), "Normal approval drift");
        var normalApproval = JSON.readTree(normalApprovalBytes).path("review");
        check(normalApproval.path("decision").asText().equals("PASS")
                && normalApproval.path("approvalBasis").asText().equals("USER_APPROVED_DEVELOPMENT_BOUNDARY"), "Approved normal boundary required");

        Path runtimePath = root.resolve("guidelines/runtime.json").toRealPath();
        byte[] runtimeBytes = OfflineDecisionContractAudit.bounded(runtimePath);
        check(sha(runtimeBytes).equals(plan.at("/fixedConfiguration/runtimeSha256").asText()), "Runtime drift");
        var runtime = JSON.readTree(runtimeBytes);
        check(runtime.path("status").asText().equals("WORKING_REFERENCE_NOT_VALIDATED"), "Expected working reference status");
        var examples = runtime.path("examples");
        var socialExample = findExample(examples, unit.path("id").asText());
        check(socialExample != null, "Positive example not present in runtime");
        var expectedPositiveText = new HashSet<String>();
        for (var segment : source.path("segments")) expectedPositiveText.add(segment.path("text").asText());
        var runtimeFlow = socialExample.at("/context/flow");
        check(runtimeFlow.isArray() && runtimeFlow.size() >= expectedPositiveText.size(), "Runtime positive flow missing");
        String normalText = unit.at("/normalContrasts/0/text").asText();
        check(sha(normalText.getBytes(StandardCharsets.UTF_8)).equals(plan.at("/cases/1/source/sha256").asText()), "Normal control text drift");
        check(socialExample.at("/context/normalContrasts").toString().contains(normalText), "Normal text already in runtime reference");

        List<String> positiveIds = new ArrayList<>();
        plan.at("/cases/0/source/segmentIds").forEach(id -> positiveIds.add(id.asText()));
        var positiveSegments = new ArrayList<ReviewInput.Segment>();
        for (var segment : source.path("segments")) {
            String id = segment.path("id").asText();
            if (!positiveIds.contains(id)) continue;
            positiveSegments.add(new ReviewInput.Segment(id, TimelineEventType.SPEECH,
                    segment.path("startMs").asLong(), segment.path("endMs").asLong(), segment.path("text").asText(), null));
        }
        check(positiveSegments.size() == positiveIds.size(), "Exact positive excerpt required");
        var positive = new CaseInput(plan.at("/cases/0/caseId").asText(), "REVIEW_REQUIRED", ContentGenre.TALK_PODCAST,
                new ReviewInput(List.copyOf(positiveSegments)), List.copyOf(positiveIds), "USER_APPROVED_DEVELOPMENT_BOUNDARY");
        check(positiveApproval.path("reviews").toString().contains("개인 결함"), "Approved basis provenance missing");

        String normalId = "synthetic-1";
        var normal = new CaseInput(plan.at("/cases/1/caseId").asText(), "PASS", ContentGenre.TALK_PODCAST,
                new ReviewInput(List.of(new ReviewInput.Segment(normalId, TimelineEventType.SPEECH, 0, 1000, normalText, null))),
                List.of(normalId), "USER_APPROVED_SYNTHETIC_DEVELOPMENT_CONTROL");
        return List.of(positive, normal);
    }

    static tools.jackson.databind.JsonNode findExample(tools.jackson.databind.JsonNode examples, String id) {
        if (!examples.isArray()) return null;
        for (var example : examples) if (example.path("id").asText().equals(id)) return example;
        return null;
    }

    static tools.jackson.databind.JsonNode preflight(Path planPath, String expectedPlanSha) throws Exception {
        byte[] planBytes = OfflineDecisionContractAudit.bounded(planPath);
        check(sha(planBytes).equals(expectedPlanSha), "Plan SHA mismatch");
        var plan = JSON.readTree(planBytes);
        List<CaseInput> cases = loadCases(planPath);
        var runtime = JSON.readTree(Files.readAllBytes(Path.of("../datasets/controversy/guidelines/runtime.json")));
        var library = new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(runtime.toString()), true,
                "classpath:frozen.json", 6000, 3, 3000, "", true);
        library.load();
        check("READY_WORKING_REFERENCE".equals(library.trace(TimelineEventType.SPEECH).state()), "Runtime reference unavailable");

        var caseRows = JSON.createArrayNode();
        for (var c : cases) {
            var focus = c.review().segments();
            var selected = library.select(TimelineEventType.SPEECH, focus, "offline-" + c.id(), focus);
            if (c.kind().equals("USER_APPROVED_DEVELOPMENT_BOUNDARY"))
                check(selected.trace().exampleIds().contains("podcast-dev#podcast-social-defect"), "Approved positive reference not retrieved");
            var row = caseRows.addObject();
            row.put("caseId", c.id());
            row.put("expectedDecisionLocalOnly", c.expected());
            row.put("caseKind", c.kind());
            row.set("rawSegmentIds", JSON.valueToTree(c.sourceIds()));
            row.put("rawSha256", sha(JSON.writeValueAsBytes(c.review().segments())));
            row.set("retrievedExampleIds", JSON.valueToTree(selected.trace().exampleIds()));
            row.put("referencePayloadCodePoints", selected.trace().payloadCodePoints());
        }
        var manifest = JSON.createObjectNode();
        manifest.put("schemaVersion", "social-defect-current-model-preflight-1");
        manifest.put("planSha256", expectedPlanSha);
        manifest.put("model", plan.at("/fixedConfiguration/model").asText());
        manifest.put("runtimeSha256", plan.at("/fixedConfiguration/runtimeSha256").asText());
        manifest.put("implementationSha256", sha(JSON.writeValueAsBytes(implementation())));
        manifest.set("implementation", JSON.valueToTree(implementation()));
        manifest.set("cases", caseRows);
        manifest.put("analyses", plan.at("/execution/analyses").asInt());
        manifest.put("maxHttpAttempts", MAX_HTTP_TOTAL);
        manifest.put("hardTrialCapUsd", CAP_USD);
        manifest.put("worstCaseReservedEstimateUsd", reservation(MAX_HTTP_TOTAL, MAX_BODY_BYTES));
        manifest.put("providerCalls", 0);
        manifest.put("labelLeakCheck", "Expected decisions are manifest-only and excluded from API request construction.");
        return manifest;
    }

    static double reservation(int attempts, int bodyBytes) {
        return attempts * ((bodyBytes + 1024) * INPUT_RATE / 1_000_000d
                + RESERVED_OUTPUT_TOKENS * OUTPUT_RATE / 1_000_000d);
    }

    static final class Guard {
        int totalAttempts;
        int analysisAttempts;
        double reservedUsd;
        boolean exhausted;
        final Set<String> bodyHashesThisAnalysis = new HashSet<>();
        final Path output;

        Guard(Path output) { this.output = output; }
        void beginAnalysis() { analysisAttempts = 0; bodyHashesThisAnalysis.clear(); }

        byte[] accept(byte[] requestBody, String uri) throws Exception {
            check(ENDPOINT.equals(uri), "Only the approved Chat Completions endpoint is allowed");
            check(requestBody.length <= MAX_BODY_BYTES, "Request body cap exceeded");
            var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(requestBody);
            check(body.path("model").asText().equals("gpt-6-luna"), "Unexpected model");
            check(body.path("reasoning_effort").asText().equals("none"), "Unexpected reasoning setting");
            if (!body.has("max_completion_tokens")) body.put("max_completion_tokens", MAX_OUTPUT_TOKENS);
            check(body.path("max_completion_tokens").asInt() > 0
                    && body.path("max_completion_tokens").asInt() <= MAX_OUTPUT_TOKENS, "Unexpected output cap");
            check(!body.has("tools") && !body.has("modalities"), "Tools/media are not approved");
            var messages = body.path("messages");
            check(messages.isArray() && messages.size() == 2, "Only fixed system/user text messages are allowed");
            for (var message : messages) {
                check(message.path("content").isTextual(), "Multimodal message content is not approved");
                String content = message.path("content").asText();
                check(!content.contains("image_url") && !content.contains("audio_url")
                        && !content.contains("data:image/") && !content.contains("data:audio/"), "Media payload is not approved");
            }
            var userTree = JSON.readTree(messages.get(1).path("content").asText());
            check(!hasForbiddenLabel(userTree), "Evaluation label/approval data must not be sent");
            byte[] bounded = JSON.writeValueAsBytes(body);
            check(bounded.length <= MAX_BODY_BYTES, "Request body cap exceeded after output limit");
            String digest = sha(bounded);
            check(bodyHashesThisAnalysis.add(digest), "Duplicate request/retry blocked within one analysis");
            check(totalAttempts < MAX_HTTP_TOTAL && analysisAttempts < MAX_HTTP_PER_ANALYSIS,
                    "Fixed HTTP attempt cap reached");
            double next = reservation(1, bounded.length);
            check(reservedUsd + next <= CAP_USD, "USD 0.50 reservation cap reached");
            totalAttempts++;
            analysisAttempts++;
            reservedUsd += next;
            Files.writeString(output.resolve("reservation.json"), JSON.writeValueAsString(Map.of(
                    "reservedEstimatedUsd", reservedUsd, "httpAttempts", totalAttempts,
                    "maxHttpAttempts", MAX_HTTP_TOTAL, "perAnalysisMaxHttpAttempts", MAX_HTTP_PER_ANALYSIS,
                    "hardTrialCapUsd", CAP_USD, "actualBillingConfirmed", false)));
            return bounded;
        }

        static boolean hasForbiddenLabel(tools.jackson.databind.JsonNode node) {
            if (node.isObject()) {
                var fields = node.properties();
                for (var field : fields) {
                    if (Set.of("expectedDecision", "labelStatus", "approvalBasis", "userConfirmation",
                            "trainingUseAuthorized", "independentGold").contains(field.getKey())) return true;
                    if (hasForbiddenLabel(field.getValue())) return true;
                }
            } else if (node.isArray()) {
                for (var child : node) if (hasForbiddenLabel(child)) return true;
            }
            return false;
        }
    }

    static Map<String, Object> finding(RiskFinding f) {
        var row = new LinkedHashMap<String, Object>();
        row.put("category", f.getCategory()); row.put("text", f.primaryText()); row.put("reason", f.getReason());
        row.put("startMs", f.getStartMs()); row.put("endMs", f.getEndMs());
        row.put("target", f.getTarget() == null ? "" : f.getTarget()); row.put("supports", f.validatedSupports());
        return row;
    }

    static void save(Path output, List<Map<String, Object>> rows, Guard guard, double usageUsd, String state,
                     String planSha, String manifestSha) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "social-defect-current-model-results-1");
        result.put("planSha256", planSha); result.put("manifestSha256", manifestSha); result.put("state", state);
        result.put("scope", "KNOWN_CASE_STORED_TEXT_CANDIDATE_TO_FINDING_SMOKE_NOT_GENERALIZATION");
        result.put("results", rows); result.put("httpAttempts", guard.totalAttempts);
        result.put("reservedEstimatedUsd", guard.reservedUsd); result.put("usageEstimatedUsd", usageUsd);
        result.put("actualBillingConfirmed", false); result.put("approvedCapUsd", CAP_USD); result.put("mediaSent", false);
        Files.writeString(output.resolve("results.json"), JSON.writeValueAsString(result));
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 4 && args[0].equals("preflight")
                        || args.length == 6 && args[0].equals("run"),
                "preflight PLAN_PATH PLAN_SHA NEW_MANIFEST_PATH | run PLAN_PATH PLAN_SHA MANIFEST_PATH MANIFEST_SHA NEW_OUTPUT_PATH required");
        Path planPath = Path.of(args[1]).toRealPath();
        String planSha = args[2];
        if (args[0].equals("preflight")) {
            Path manifestPath = Path.of(args[3]).toAbsolutePath().normalize();
            Path experiments = Path.of("../datasets/controversy/experiments").toRealPath();
            check(!Files.exists(manifestPath) && manifestPath.getParent().startsWith(experiments), "New private preflight output required");
            Files.createDirectories(manifestPath.getParent());
            check(manifestPath.getParent().toRealPath().startsWith(experiments), "Private preflight output must stay in experiments");
            var manifest = preflight(planPath, planSha);
            byte[] bytes = JSON.writeValueAsBytes(manifest);
            Files.write(manifestPath, bytes, StandardOpenOption.CREATE_NEW);
            System.out.println("PREFLIGHT_OK cases=2 analyses=6 maxHttp=24 reserveUsd="
                    + manifest.path("worstCaseReservedEstimateUsd").asDouble()
                    + " providerCalls=0 manifestSha=" + sha(bytes));
            return;
        }

        String manifestSha = args[4];
        Path manifestPath = Path.of(args[3]).toRealPath();
        byte[] manifestBytes = OfflineDecisionContractAudit.bounded(manifestPath);
        check(sha(manifestBytes).equals(manifestSha), "Manifest SHA mismatch");
        var expectedManifest = preflight(planPath, planSha);
        check(JSON.readTree(manifestBytes).equals(expectedManifest), "Plan, data, runtime, implementation, or retrieval changed after preflight");
        check(CONSENT.equals(System.getenv("SOCIAL_DEFECT_CURRENT_MODEL_TRIAL_CONSENT")), "Fresh exact-plan consent required");
        check(Math.abs(reservation(MAX_HTTP_TOTAL, MAX_BODY_BYTES) - .3864576) < 1e-9,
                "Fixed budget calculation drift");
        String key = System.getenv("OPENAI_API_KEY");
        check(key != null && !key.isBlank(), "OPENAI_API_KEY required; never print it");

        Path output = Path.of(args[5]).toAbsolutePath().normalize();
        Path experiments = Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(experiments),
                "New private output required; this run cannot resume");
        DefaultVerificationTrial.claimRun(planPath, output, planSha, CONSENT, MAX_HTTP_TOTAL);
        Files.createDirectory(output);
        Files.write(output.resolve("plan.json"), Files.readAllBytes(planPath), StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("manifest.json"), manifestBytes, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("authorization.json"), JSON.writeValueAsString(Map.of(
                "planSha256", planSha, "manifestSha256", manifestSha, "consent", CONSENT,
                "textEgressApproved", true, "mediaEgressApproved", false,
                "maxHttpAttempts", MAX_HTTP_TOTAL, "perAnalysisMaxHttpAttempts", MAX_HTTP_PER_ANALYSIS,
                "hardTrialCapUsd", CAP_USD, "previousBudgetReused", false)));

        var guard = new Guard(output);
        var props = new OpenAiProperties(key, System.getenv("OPENAI_ORG_ID"), System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1", "gpt-6-luna", "none", Duration.ofSeconds(60),
                new OpenAiProperties.Pricing(INPUT_RATE, .01, OUTPUT_RATE, null, null));
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        var builder = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key)
                .requestInterceptor((request, body, execution) -> {
                    try {
                        byte[] bounded = guard.accept(body, request.getURI().toString());
                        request.getHeaders().setContentLength(bounded.length);
                        return execution.execute(request, bounded);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Approved trial request guard blocked egress", e);
                    }
                });
        if (props.hasOrganization()) builder.defaultHeader("OpenAI-Organization", props.organization());
        if (props.hasProject()) builder.defaultHeader("OpenAI-Project", props.project());
        var client = new MechanismRoutingTrial.Client(builder.build(), props);
        Path root = Path.of("../datasets/controversy").toRealPath();
        String runtime = Files.readString(root.resolve("guidelines/runtime.json"));
        var library = new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(runtime), true,
                "classpath:frozen.json", 6000, 3, 3000, "", true);
        library.load();
        var cases = loadCases(planPath);
        var rows = new ArrayList<Map<String, Object>>();
        double totalUsageUsd = 0;
        save(output, rows, guard, totalUsageUsd, "RUNNING", planSha, manifestSha);
        try {
            for (int repeat = 1; repeat <= 3; repeat++) {
                for (var c : cases) {
                    guard.beginAnalysis();
                    client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("social-defect-current-model-trial");
                    var context = new AnalysisContext(Video.builder().filename("private-" + c.id()).build(), c.genre(),
                            List.of(), List.of(), c.review());
                    long started = System.nanoTime();
                    var result = CandidateReviewEngine.run(client, context,
                            new CandidateReviewEngine.RunServices(null, null, library, null, null),
                            new CandidateReviewEngine.RunOptions(24, 0, false,
                                    new CandidateReviewEngine.VerificationFeatures(false, false, false), false));
                    var usage = client.videoUsage();
                    totalUsageUsd += usage.costUsd();
                    var row = new LinkedHashMap<String, Object>();
                    row.put("caseId", c.id()); row.put("kind", c.kind()); row.put("expectedDecisionLocalOnly", c.expected());
                    row.put("repeat", repeat); row.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
                    row.put("status", result.status()); row.put("diagnostics", result.diagnostics());
                    row.put("evaluations", result.evaluations()); row.put("unassessedSegmentIds", result.unassessedSegmentIds());
                    row.put("calls", List.copyOf(client.calls)); row.put("usage", usage);
                    row.put("findings", result.findings().stream().map(SocialDefectCurrentModelTrial::finding).toList());
                    rows.add(row);
                    boolean ok = !guard.exhausted && totalUsageUsd <= CAP_USD && client.failureCount() == 0
                            && usage.calls() == client.calls.size();
                    client.endVideo();
                    save(output, rows, guard, totalUsageUsd, ok ? "RUNNING" : "INCOMPLETE", planSha, manifestSha);
                    System.out.println("SOCIAL_DEFECT_SMOKE " + rows.size() + "/6 " + c.id()
                            + " repeat=" + repeat + " findings=" + result.findings().size()
                            + " status=" + result.status() + " calls=" + usage.calls());
                    check(ok, "Provider/schema/budget failure; stop without retry");
                }
            }
            check(rows.size() == 6, "All six analyses must complete");
            save(output, rows, guard, totalUsageUsd, "COMPLETE", planSha, manifestSha);
            System.out.println("SOCIAL_DEFECT_SMOKE_COMPLETE analyses=6 http=" + guard.totalAttempts
                    + " usageEstimatedUsd=" + totalUsageUsd + " reserved=" + guard.reservedUsd);
        } catch (Exception e) {
            save(output, rows, guard, totalUsageUsd, "INCOMPLETE", planSha, manifestSha);
            throw e;
        }
    }
}
