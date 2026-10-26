package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Freshly approved, bounded text-only D05 paired trial. Not wired into the application. */
public final class CasebookReferenceIsolationModelTrial {
    static final String CONSENT_ENV = "CASEBOOK_REFERENCE_ISOLATION_TRIAL_CONSENT";
    static final String CONSENT = "CASEBOOK_REFERENCE_ISOLATION_D05_20261026_USD070_TEXT_APPROVED";
    static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    static final String DESIGN_RESOURCE = "/evals/casebook-reference-isolation-plan.json";
    static final String PREFLIGHT_RELATIVE = "../datasets/controversy/experiments/casebook-reference-isolation-video104-preflight-20261026-v5/preflight.json";
    static final int REPEATS = 5;
    static final int MAX_HTTP_TOTAL = 40;
    static final int MAX_HTTP_PER_ANALYSIS = 4;
    static final int MAX_BODY_BYTES = 120_000;
    static final int MAX_OUTPUT_TOKENS = 8_000;
    static final int SPACING_MS = 15_000;
    static final double CAP_USD = .70;
    static final double INPUT_RATE = .10;
    static final double OUTPUT_RATE = .50;
    static final String MODEL = "gpt-6-luna";

    record Condition(String id, int maxExamples, List<String> expectedSelection) {}
    record DiscoveryFingerprint(String userInputSha256, String systemPromptSha256) {}
    record Plan(String schemaVersion, String scope, String sourceCaseId, String expectedDecisionLocalOnly,
                List<String> sourceSegmentIds, String reviewSha256, String transcriptSha256,
                String runtimeSha256, String offlinePreflightSha256, String implementationSha256,
                Map<String, String> implementation, String model, String reasoningEffort,
                int repeatsPerCondition, List<String> conditionOrder, List<Condition> conditions,
                Map<String, DiscoveryFingerprint> expectedDiscoveryFingerprints,
                int maxHttpAttemptsTotal, int maxHttpAttemptsPerAnalysis, int maxRequestBodyBytes,
                int maxOutputTokens, int spacingBetweenAnalysesMs, boolean outboundRequestGuardDryRunPassed,
                double hardCapUsd,
                double worstCaseReservedEstimateUsd) {}

    private record CallTrace(String type, String userInputSha256, String systemPromptSha256,
                             boolean responsePresent, String failureCode) {}

    static final class Guard {
        private int totalAttempts;
        private int attemptsThisAnalysis;
        private double reservedUsd;
        private boolean violation;
        private String violationCode;
        private final Set<String> bodyHashesThisAnalysis = new HashSet<>();
        private final Path output;

        Guard(Path output) { this.output = output; }
        void beginAnalysis() { attemptsThisAnalysis = 0; bodyHashesThisAnalysis.clear(); }

        byte[] accept(byte[] requestBody, String uri) throws Exception {
            try {
                check(ENDPOINT.equals(uri), "Only the approved OpenAI endpoint is allowed");
                check(requestBody.length <= MAX_BODY_BYTES, "Request body cap exceeded before token guard");
                ObjectNode body = (ObjectNode) JSON.readTree(requestBody);
                check(body.path("model").asText().equals(MODEL), "Unexpected model");
                check(body.path("reasoning_effort").asText().equals("none"), "Unexpected reasoning setting");
                body.put("max_completion_tokens", MAX_OUTPUT_TOKENS);
                check(!body.has("tools") && !body.has("modalities"), "Tools/media are not approved");
                var messages = body.path("messages");
                check(messages.isArray() && messages.size() == 2, "Only fixed system/user text messages are allowed");
                for (var message : messages) {
                    check(message.path("content").isTextual(), "Multimodal content is not approved");
                    String content = message.path("content").asText();
                    check(!content.contains("image_url") && !content.contains("audio_url")
                                    && !content.contains("data:image/") && !content.contains("data:audio/"),
                            "Media payload is not approved");
                }
                var userTree = JSON.readTree(messages.get(1).path("content").asText());
                check(!SocialDefectCurrentModelTrial.Guard.hasForbiddenLabel(userTree),
                        "Expected decision or review approval data must not be sent");

                byte[] bounded = JSON.writeValueAsBytes(body);
                check(bounded.length <= MAX_BODY_BYTES, "Request body cap exceeded after output token guard");
                String digest = sha(bounded);
                check(bodyHashesThisAnalysis.add(digest), "Duplicate request blocked; automatic retry is not authorized");
                check(totalAttempts < MAX_HTTP_TOTAL && attemptsThisAnalysis < MAX_HTTP_PER_ANALYSIS,
                        "Fixed HTTP attempt cap reached");
                double next = reservation(1, bounded.length);
                check(reservedUsd + next <= CAP_USD, "Hard estimated-cost cap reached");

                totalAttempts++;
                attemptsThisAnalysis++;
                reservedUsd += next;
                if (output != null) {
                    Files.writeString(output.resolve("reservation.json"), JSON.writeValueAsString(Map.of(
                            "httpAttempts", totalAttempts, "maxHttpAttempts", MAX_HTTP_TOTAL,
                            "perAnalysisMaxHttpAttempts", MAX_HTTP_PER_ANALYSIS,
                            "reservedEstimatedUsd", reservedUsd, "hardCapUsd", CAP_USD,
                            "inputPer1mUsd", INPUT_RATE, "outputPer1mUsd", OUTPUT_RATE,
                            "maxOutputTokensPerAttempt", MAX_OUTPUT_TOKENS,
                            "actualBillingConfirmed", false)), StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
                return bounded;
            } catch (Exception e) {
                violation = true;
                violationCode = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                throw e;
            }
        }
    }

    private static final class TrialClient extends OpenAiClient {
        private final String condition;
        private final DiscoveryFingerprint expectedDiscovery;
        private final List<CallTrace> callTraces = new ArrayList<>();
        private boolean discoveryFingerprintMatched;
        private boolean discoveryFingerprintFailed;
        private String discoveryFingerprintFailureCode;
        private int discoveryCalls;

        TrialClient(RestClient rest, OpenAiProperties properties, String condition,
                    DiscoveryFingerprint expectedDiscovery) {
            super(rest, properties);
            this.condition = condition;
            this.expectedDiscovery = expectedDiscovery;
        }

        @Override public <T> Optional<T> completeAsJson(String system, String input, Class<T> type) {
            String inputSha = canonicalInputShaUnchecked(input);
            String systemSha = canonicalSystemShaUnchecked(system);
            if (type == CandidateReviewEngine.Discovery.class && discoveryCalls++ == 0) {
                boolean inputMatches = expectedDiscovery.userInputSha256().equals(inputSha);
                boolean systemMatches = expectedDiscovery.systemPromptSha256().equals(systemSha);
                if (!inputMatches || !systemMatches) {
                    discoveryFingerprintFailed = true;
                    discoveryFingerprintFailureCode = !inputMatches ? "OFFLINE_INPUT_SHA_MISMATCH" : "OFFLINE_SYSTEM_SHA_MISMATCH";
                    callTraces.add(new CallTrace(type.getSimpleName(), inputSha, systemSha, false,
                            discoveryFingerprintFailureCode));
                    throw new IllegalStateException("Discovery request differs from offline preflight for " + condition);
                }
                discoveryFingerprintMatched = true;
            }
            try {
                Optional<T> response = super.completeAsJson(system, input, type);
                callTraces.add(new CallTrace(type.getSimpleName(), inputSha, systemSha,
                        response.isPresent(), failureCode().orElse(null)));
                return response;
            } catch (RuntimeException e) {
                callTraces.add(new CallTrace(type.getSimpleName(), inputSha, systemSha, false,
                        "CLIENT_EXCEPTION_" + e.getClass().getSimpleName()));
                throw e;
            }
        }
    }

    private CasebookReferenceIsolationModelTrial() {}

    static double reservation(int attempts, int bodyBytes) {
        return attempts * ((bodyBytes + 1024) * INPUT_RATE / 1_000_000d
                + MAX_OUTPUT_TOKENS * OUTPUT_RATE / 1_000_000d);
    }

    static boolean isComparable(TextReviewEngine.Result result, String pipelineOutcome) {
        if (result == null || result.status() != AnalyzerStatus.SUCCESS
                || result.diagnostics() == null || result.diagnostics().truncated()
                || result.diagnostics().status() != AnalyzerStatus.SUCCESS
                || result.diagnostics().candidatePipeline() == null
                || !result.unassessedSegmentIds().isEmpty() || !result.conflictingSegmentIds().isEmpty()) return false;
        CandidateReviewDiagnostics diagnostics = result.diagnostics().candidatePipeline();
        if (diagnostics.truncated() || diagnostics.invalidProposals() > 0 || diagnostics.verificationFailed() > 0
                || diagnostics.budgetSkipped() > 0 || diagnostics.limitedBatches() > 0
                || diagnostics.truncatedBatches() > 0 || diagnostics.repairCalls() > 0
                || diagnostics.discoveryRepairCalls() > 0) return false;
        return Set.of("REVIEW_REQUIRED", "PASS_AFTER_VERIFICATION", "NO_CANDIDATE", "UNCERTAIN")
                .contains(pipelineOutcome);
    }

    @FunctionalInterface
    interface AnalysisStep {
        boolean run(int index, String conditionId) throws Exception;
    }

    /** Runs in order and stops immediately after the first non-comparable analysis. */
    static int runUntilFailure(List<String> conditionOrder, AnalysisStep step) throws Exception {
        int attempted = 0;
        for (int index = 0; index < conditionOrder.size(); index++) {
            attempted++;
            if (!step.run(index, conditionOrder.get(index))) return attempted;
        }
        return attempted;
    }

    static Plan prepare() throws Exception {
        JsonNode design;
        try (var stream = CasebookReferenceIsolationModelTrial.class.getResourceAsStream(DESIGN_RESOURCE)) {
            check(stream != null, "Frozen design plan missing");
            design = JSON.readTree(stream);
        }
        check(design.path("status").asText().equals("DESIGN_ONLY_FRESH_APPROVAL_REQUIRED_FOR_MODEL_CALLS")
                        && design.at("/target/caseId").asText().equals("D05-taekwondo-belt-generalization"),
                "Only the frozen D05 design may be used");
        check(design.at("/protocol/pairedRepeatsPerCondition").asInt() == REPEATS
                        && design.at("/protocol/spacingBetweenAnalysesMs").asInt() == SPACING_MS,
                "Repeat count and spacing must match the reviewed design");

        Path reviewPath = Path.of(CasebookHoldoutCurrentPipelineTrial.REVIEW_RELATIVE).toRealPath();
        Path transcriptPath = Path.of(CasebookHoldoutCurrentPipelineTrial.TRANSCRIPT_RELATIVE).toRealPath();
        Path runtimePath = Path.of(CasebookHoldoutCurrentPipelineTrial.RUNTIME_RELATIVE).toRealPath();
        byte[] reviewBytes = CasebookHoldoutCurrentPipelineTrial.bounded(reviewPath);
        byte[] transcriptBytes = CasebookHoldoutCurrentPipelineTrial.bounded(transcriptPath);
        byte[] runtimeBytes = CasebookHoldoutCurrentPipelineTrial.bounded(runtimePath);
        JsonNode review = JSON.readTree(reviewBytes);
        JsonNode preflight = JSON.readTree(CasebookHoldoutCurrentPipelineTrial.bounded(Path.of(PREFLIGHT_RELATIVE)));
        check(preflight.path("state").asText().equals("OFFLINE_REQUEST_CAPTURE_PASS")
                        && preflight.path("providerCalls").asInt(-1) == 0
                        && preflight.at("/pairedComparison/allIsolationChecksPassed").asBoolean(false),
                "Successful zero-provider offline request capture required");
        check(sha(reviewBytes).equals(preflight.path("sourceReviewSha256").asText())
                        && sha(transcriptBytes).equals(preflight.path("storedTranscriptSha256").asText())
                        && sha(runtimeBytes).equals(preflight.path("runtimeGuidelinesSha256").asText()),
                "Reviewed source, transcript, or runtime dictionary changed since preflight");

        var holdout = CasebookHoldoutCurrentPipelineTrial.prepare();
        var d05 = holdout.cases().stream().filter(c -> c.caseId().equals("D05-taekwondo-belt-generalization"))
                .findFirst().orElseThrow();
        check(d05.expectedDecisionLocalOnly().equals("REVIEW_REQUIRED"), "User-reviewed D05 label required locally");
        List<Condition> conditions = List.of(
                new Condition("CURRENT_SELECTED_REFERENCE", 3, List.of("podcast-dev#podcast-boast-response")),
                new Condition("GLOBAL_GUIDELINES_WITHOUT_EXAMPLES", 0, List.of()));
        var fingerprints = new LinkedHashMap<String, DiscoveryFingerprint>();
        for (int i = 0; i < conditions.size(); i++) {
            String id = conditions.get(i).id();
            JsonNode call = preflight.path("conditions").get(i).path("capturedCalls").get(0);
            List<String> selected = new ArrayList<>();
            preflight.path("conditions").get(i).path("selectedExampleIds").forEach(v -> selected.add(v.asText()));
            check(selected.equals(conditions.get(i).expectedSelection()), "Frozen selection drift for " + id);
            fingerprints.put(id, new DiscoveryFingerprint(call.path("userInputSha256").asText(),
                    call.path("systemPromptSha256").asText()));
        }
        check(fingerprints.size() == 2 && fingerprints.values().stream().allMatch(f ->
                        f.userInputSha256().matches("[0-9a-f]{64}") && f.systemPromptSha256().matches("[0-9a-f]{64}")),
                "Both exact discovery requests must be frozen");

        String runtimeText = new String(runtimeBytes, StandardCharsets.UTF_8);
        for (Condition condition : conditions) {
            var captured = CasebookReferenceIsolationPreflight.capture(condition.id(), condition.maxExamples(),
                    d05.review(), runtimeText);
            check(captured.providerCalls() == 0 && captured.calls().size() == 1
                            && captured.selectedIds().equals(condition.expectedSelection()),
                    "Final local engine capture differs from expected selection for " + condition.id());
            var call = captured.calls().get(0);
            var fingerprint = fingerprints.get(condition.id());
            boolean sameType = call.type().equals(CandidateReviewEngine.Discovery.class.getSimpleName());
            boolean sameInput = CasebookReferenceIsolationPreflight.canonicalInputSha(call.input()).equals(fingerprint.userInputSha256());
            boolean sameSystem = CasebookReferenceIsolationPreflight.canonicalSystemSha(call.system()).equals(fingerprint.systemPromptSha256());
            check(sameType && sameInput && sameSystem,
                    "Final local discovery request differs from offline preflight for " + condition.id()
                            + " type=" + sameType + " inputHash=" + sameInput + " systemHash=" + sameSystem
                            + " actualInputSha=" + CasebookReferenceIsolationPreflight.canonicalInputSha(call.input())
                            + " expectedInputSha=" + fingerprint.userInputSha256()
                            + " actualSystemSha=" + CasebookReferenceIsolationPreflight.canonicalSystemSha(call.system())
                            + " expectedSystemSha=" + fingerprint.systemPromptSha256());
            var dryGuard = new Guard(null);
            dryGuard.accept(JSON.writeValueAsBytes(call.providerBody()), ENDPOINT);
            check(!dryGuard.violation && dryGuard.totalAttempts == 1,
                    "Outbound request guard dry-run failed for " + condition.id());
        }

        var order = new ArrayList<String>();
        for (var node : design.at("/protocol/conditionOrder")) order.add(node.asText());
        check(order.size() == 10, "Exactly ten analyses required");
        List<String> expectedOrder = List.of("CURRENT_SELECTED_REFERENCE", "GLOBAL_GUIDELINES_WITHOUT_EXAMPLES",
                "GLOBAL_GUIDELINES_WITHOUT_EXAMPLES", "CURRENT_SELECTED_REFERENCE",
                "GLOBAL_GUIDELINES_WITHOUT_EXAMPLES", "CURRENT_SELECTED_REFERENCE",
                "CURRENT_SELECTED_REFERENCE", "GLOBAL_GUIDELINES_WITHOUT_EXAMPLES",
                "CURRENT_SELECTED_REFERENCE", "GLOBAL_GUIDELINES_WITHOUT_EXAMPLES");
        check(order.equals(expectedOrder), "Condition order differs from frozen plan");

        Map<String, String> implementation = implementation();
        Plan plan = new Plan("casebook-reference-isolation-model-trial-1",
                "D05_STORED_TRANSCRIPT_REFERENCE_ISOLATION_TEXT_ONLY_NOT_GENERALIZATION",
                d05.caseId(), d05.expectedDecisionLocalOnly(), d05.sourceSegmentIds(), sha(reviewBytes),
                sha(transcriptBytes), sha(runtimeBytes), sha(CasebookHoldoutCurrentPipelineTrial.bounded(Path.of(PREFLIGHT_RELATIVE))),
                sha(JSON.writeValueAsBytes(implementation)), implementation, MODEL, "none", REPEATS,
                List.copyOf(order), conditions, Collections.unmodifiableMap(fingerprints), MAX_HTTP_TOTAL,
                MAX_HTTP_PER_ANALYSIS, MAX_BODY_BYTES, MAX_OUTPUT_TOKENS, SPACING_MS, true, CAP_USD,
                reservation(MAX_HTTP_TOTAL, MAX_BODY_BYTES));
        validate(plan);
        return plan;
    }

    static void validate(Plan plan) {
        check(plan.schemaVersion().equals("casebook-reference-isolation-model-trial-1")
                        && plan.sourceCaseId().equals("D05-taekwondo-belt-generalization")
                        && plan.expectedDecisionLocalOnly().equals("REVIEW_REQUIRED")
                        && plan.model().equals(MODEL) && plan.reasoningEffort().equals("none")
                        && plan.repeatsPerCondition() == 5 && plan.conditionOrder().size() == 10
                        && plan.maxHttpAttemptsTotal() == 40 && plan.maxHttpAttemptsPerAnalysis() == 4
                        && plan.maxRequestBodyBytes() == 120_000 && plan.maxOutputTokens() == 8_000
                        && plan.spacingBetweenAnalysesMs() == 15_000 && plan.outboundRequestGuardDryRunPassed()
                        && plan.hardCapUsd() == .70
                        && plan.worstCaseReservedEstimateUsd() <= plan.hardCapUsd(),
                "Frozen bounded trial configuration required");
        check(plan.conditions().equals(List.of(
                        new Condition("CURRENT_SELECTED_REFERENCE", 3, List.of("podcast-dev#podcast-boast-response")),
                        new Condition("GLOBAL_GUIDELINES_WITHOUT_EXAMPLES", 0, List.of()))),
                "Frozen conditions required");
    }

    static Map<String, String> implementation() throws Exception {
        var hashes = new TreeMap<String, String>();
        try (var stream = Files.walk(Path.of("src/main"))) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".json")).toList())
                hashes.put(file.toString(), sha(Files.readAllBytes(file)));
        }
        for (String file : List.of("build.gradle",
                "src/test/java/com/example/oops/analyzer/CasebookReferenceIsolationPreflight.java",
                "src/test/java/com/example/oops/analyzer/CasebookReferenceIsolationPreflightTest.java",
                "src/test/java/com/example/oops/analyzer/CasebookReferenceIsolationModelTrial.java",
                "src/test/java/com/example/oops/analyzer/CasebookReferenceIsolationModelTrialTest.java",
                "src/test/java/com/example/oops/analyzer/CasebookHoldoutCurrentPipelineTrial.java",
                "src/test/java/com/example/oops/analyzer/SocialDefectCurrentModelTrial.java",
                "src/test/java/com/example/oops/analyzer/V7RoutingVideoTrial.java",
                "src/test/resources/evals/casebook-reference-isolation-plan.json"))
            hashes.put(file, sha(Files.readAllBytes(Path.of(file))));
        return Collections.unmodifiableMap(hashes);
    }

    static JsonNode manifest(Plan plan) throws Exception {
        var result = JSON.createObjectNode();
        result.put("schemaVersion", "casebook-reference-isolation-model-manifest-1");
        result.put("state", "FROZEN_OFFLINE_NOT_EXECUTED");
        result.put("sourceCaseId", plan.sourceCaseId());
        result.put("reviewSha256", plan.reviewSha256());
        result.put("transcriptSha256", plan.transcriptSha256());
        result.put("runtimeSha256", plan.runtimeSha256());
        result.put("offlinePreflightSha256", plan.offlinePreflightSha256());
        result.put("implementationSha256", plan.implementationSha256());
        result.set("implementation", JSON.valueToTree(plan.implementation()));
        result.put("model", plan.model());
        result.put("reasoningEffort", plan.reasoningEffort());
        result.set("conditions", JSON.valueToTree(plan.conditions()));
        result.set("expectedDiscoveryFingerprints", JSON.valueToTree(plan.expectedDiscoveryFingerprints()));
        result.put("analyses", plan.conditionOrder().size());
        result.put("maxHttpAttemptsTotal", plan.maxHttpAttemptsTotal());
        result.put("maxHttpAttemptsPerAnalysis", plan.maxHttpAttemptsPerAnalysis());
        result.put("maxRequestBodyBytes", plan.maxRequestBodyBytes());
        result.put("maxOutputTokens", plan.maxOutputTokens());
        result.put("spacingBetweenAnalysesMs", plan.spacingBetweenAnalysesMs());
        result.put("outboundRequestGuardDryRunPassed", plan.outboundRequestGuardDryRunPassed());
        result.put("hardCapUsd", plan.hardCapUsd());
        result.put("worstCaseReservedEstimateUsd", plan.worstCaseReservedEstimateUsd());
        result.put("providerCalls", 0);
        result.put("textEgressOnly", true);
        result.put("expectedDecisionIncludedInModelInput", false);
        return result;
    }

    public static void main(String[] args) throws Exception {
        check((args.length == 2 && args[0].equals("prepare"))
                        || (args.length == 6 && args[0].equals("run")),
                "prepare NEW_PLAN_DIRECTORY | run PLAN_PATH PLAN_SHA MANIFEST_PATH MANIFEST_SHA NEW_OUTPUT_DIRECTORY required");
        Path experiments = Path.of("../datasets/controversy/experiments").toRealPath();
        if (args[0].equals("prepare")) {
            Path directory = Path.of(args[1]).toAbsolutePath().normalize();
            check(!Files.exists(directory) && directory.startsWith(experiments), "New private experiment directory required");
            Plan plan = prepare();
            byte[] planBytes = JSON.writeValueAsBytes(plan);
            JsonNode manifest = manifest(plan);
            byte[] manifestBytes = JSON.writeValueAsBytes(manifest);
            Files.createDirectory(directory);
            Files.write(directory.resolve("plan.json"), planBytes, StandardOpenOption.CREATE_NEW);
            Files.write(directory.resolve("manifest.json"), manifestBytes, StandardOpenOption.CREATE_NEW);
            System.out.println("REFERENCE_ISOLATION_PREPARED analyses=10 maxHttp=40 capUsd=0.70 reserved="
                    + plan.worstCaseReservedEstimateUsd() + " providerCalls=0 planSha=" + sha(planBytes)
                    + " manifestSha=" + sha(manifestBytes));
            return;
        }
        execute(args, experiments);
    }

    private static void execute(String[] args, Path experiments) throws Exception {
        Path planPath = Path.of(args[1]).toRealPath();
        byte[] planBytes = CasebookHoldoutCurrentPipelineTrial.bounded(planPath);
        check(sha(planBytes).equals(args[2]) && planPath.startsWith(experiments), "Frozen plan hash/path mismatch");
        Plan plan = JSON.readValue(planBytes, Plan.class);
        validate(plan);
        Plan fresh = prepare();
        check(JSON.valueToTree(fresh).equals(JSON.valueToTree(plan)), "Plan source/configuration drift; no calls made");

        Path manifestPath = Path.of(args[3]).toRealPath();
        byte[] manifestBytes = CasebookHoldoutCurrentPipelineTrial.bounded(manifestPath);
        check(manifestPath.startsWith(experiments) && sha(manifestBytes).equals(args[4])
                        && JSON.readTree(manifestBytes).equals(manifest(plan)),
                "Frozen request manifest mismatch; no calls made");
        check(CONSENT.equals(System.getenv(CONSENT_ENV)), "Fresh exact-plan and text-egress approval required");

        String key = System.getenv("OPENAI_API_KEY");
        check(key != null && !key.isBlank(), "OPENAI_API_KEY required; never print");
        Path output = Path.of(args[5]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(experiments),
                "New private output directory required; this trial cannot resume");
        Files.writeString(planPath.getParent().resolve("execution-claim.json"), JSON.writeValueAsString(Map.of(
                "planSha256", args[2], "manifestSha256", args[4], "consent", CONSENT,
                "output", output.toString(), "maxHttpAttempts", MAX_HTTP_TOTAL, "resumable", false)),
                StandardOpenOption.CREATE_NEW);
        Files.createDirectory(output);
        Files.write(output.resolve("plan.json"), planBytes, StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("manifest.json"), manifestBytes, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("authorization.json"), JSON.writeValueAsString(Map.of(
                "planSha256", args[2], "manifestSha256", args[4], "consent", CONSENT,
                "textEgressApproved", true, "mediaEgressApproved", false,
                "hardCapUsd", CAP_USD, "maxHttpAttemptsTotal", MAX_HTTP_TOTAL,
                "maxHttpAttemptsPerAnalysis", MAX_HTTP_PER_ANALYSIS,
                "maxOutputTokens", MAX_OUTPUT_TOKENS, "previousBudgetReused", false)), StandardOpenOption.CREATE_NEW);

        var guard = new Guard(output);
        var props = new OpenAiProperties(key, System.getenv("OPENAI_ORG_ID"), System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1", MODEL, "none", Duration.ofSeconds(60),
                new OpenAiProperties.Pricing(INPUT_RATE, .01, OUTPUT_RATE, null, null));
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        var builder = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key)
                .requestInterceptor((request, body, execution) -> {
                    byte[] bounded;
                    try { bounded = guard.accept(body, request.getURI().toString()); }
                    catch (Exception e) { throw new IllegalArgumentException("Trial request guard denied the call", e); }
                    request.getHeaders().setContentLength(bounded.length);
                    return execution.execute(request, bounded);
                });
        if (props.hasOrganization()) builder.defaultHeader("OpenAI-Organization", props.organization());
        if (props.hasProject()) builder.defaultHeader("OpenAI-Project", props.project());
        RestClient rest = builder.build();
        var rows = new ArrayList<Map<String, Object>>();
        double[] usageUsd = {0};
        save(output, rows, guard, usageUsd[0], "RUNNING", plan, args[2], args[4]);
        var repeatCounts = new HashMap<String, Integer>();
        try {
            int attempted = runUntilFailure(plan.conditionOrder(), (index, conditionId) -> {
                Condition condition = plan.conditions().stream().filter(c -> c.id().equals(conditionId)).findFirst().orElseThrow();
                int repeat = repeatCounts.merge(conditionId, 1, Integer::sum);
                guard.beginAnalysis();
                var library = library(condition, new String(Files.readAllBytes(Path.of(CasebookHoldoutCurrentPipelineTrial.RUNTIME_RELATIVE)),
                        StandardCharsets.UTF_8));
                var client = new TrialClient(rest, props, conditionId, plan.expectedDiscoveryFingerprints().get(conditionId));
                client.beginVideo(0L);
                client.beginAnalyzer("casebook-reference-isolation-model-trial");
                var source = CasebookHoldoutCurrentPipelineTrial.prepare().cases().stream()
                        .filter(c -> c.caseId().equals(plan.sourceCaseId())).findFirst().orElseThrow();
                var context = new AnalysisContext(Video.builder().filename("private-d05-reference-isolation").build(),
                        ContentGenre.GENERAL, List.of(), List.of(), source.review());
                long started = System.nanoTime();
                TextReviewEngine.Result result = CandidateReviewEngine.run(client, context,
                        new CandidateReviewEngine.RunServices(null, null, library, null, null),
                        new CandidateReviewEngine.RunOptions(24, 0, false,
                                new CandidateReviewEngine.VerificationFeatures(false, false, false), false));
                var usage = client.videoUsage();
                usageUsd[0] += usage.costUsd();
                var row = row(condition, repeat, source.expectedDecisionLocalOnly(), result, client, usage,
                        (System.nanoTime() - started) / 1_000_000);
                rows.add(row);
                boolean comparable = isComparable(result, String.valueOf(row.get("pipelineOutcome")));
                boolean okay = !guard.violation && !client.discoveryFingerprintFailed
                        && client.discoveryFingerprintMatched && client.failureCount() == 0
                        && client.callTraces.stream().allMatch(CallTrace::responsePresent)
                        && comparable
                        && client.callTraces.size() <= MAX_HTTP_PER_ANALYSIS
                        && guard.attemptsThisAnalysis <= MAX_HTTP_PER_ANALYSIS
                        && guard.reservedUsd <= CAP_USD && usageUsd[0] <= CAP_USD;
                save(output, rows, guard, usageUsd[0], okay ? "RUNNING" : "STOPPED", plan, args[2], args[4]);
                client.endVideo();
                System.out.println("REFERENCE_ISOLATION_MODEL " + (index + 1) + "/10 " + conditionId
                        + " repeat=" + repeat + " outcome=" + row.get("pipelineOutcome")
                        + " calls=" + guard.attemptsThisAnalysis + " reservedUsd=" + String.format(Locale.ROOT, "%.6f", guard.reservedUsd));
                if (!okay) return false;
                if (index + 1 < plan.conditionOrder().size()) Thread.sleep(SPACING_MS);
                return true;
            });
            check(attempted == plan.conditionOrder().size(), "Provider/schema/fingerprint/budget failure; stop without retry");
            check(rows.size() == 10 && guard.totalAttempts <= MAX_HTTP_TOTAL, "Incomplete paired trial");
            save(output, rows, guard, usageUsd[0], "COMPLETE", plan, args[2], args[4]);
            writeSummary(output, rows, plan, guard);
            System.out.println("REFERENCE_ISOLATION_MODEL_COMPLETE analyses=10 http=" + guard.totalAttempts
                    + " reservedUsd=" + guard.reservedUsd + " usageEstimatedUsd=" + usageUsd[0]
                    + " actualBillingConfirmed=false");
        } catch (Exception e) {
            save(output, rows, guard, usageUsd[0], "STOPPED", plan, args[2], args[4]);
            throw e;
        }
    }

    private static ReviewGuidelineLibrary library(Condition condition, String runtime) {
        ResourceLoader loader = new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                check(location.equals("classpath:frozen.json"), "Only the frozen local runtime is allowed");
                return new ByteArrayResource(runtime.getBytes(StandardCharsets.UTF_8));
            }
        };
        var library = new ReviewGuidelineLibrary(loader, true, "classpath:frozen.json", 6000,
                condition.maxExamples(), 3000, "", true, true);
        library.load();
        check("READY_WORKING_REFERENCE".equals(library.trace(TimelineEventType.SPEECH).state()),
                "Frozen runtime reference unavailable");
        return library;
    }

    private static Map<String, Object> row(Condition condition, int repeat, String expected,
                                            TextReviewEngine.Result result, TrialClient client,
                                            OpenAiClient.TokenUsage usage, long elapsedMs) throws Exception {
        var findings = result.findings().stream().map(f -> Map.of(
                "category", f.getCategory(), "text", f.primaryText(), "reason", f.getReason(),
                "startMs", f.getStartMs(), "endMs", f.getEndMs(),
                "target", f.getTarget() == null ? "" : f.getTarget(), "supports", f.validatedSupports())).toList();
        var row = new LinkedHashMap<String, Object>();
        row.put("caseId", "D05-taekwondo-belt-generalization");
        row.put("condition", condition.id());
        row.put("repeat", repeat);
        row.put("expectedDecisionLocalOnly", expected);
        row.put("elapsedMs", elapsedMs);
        row.put("status", result.status());
        row.put("diagnostics", result.diagnostics());
        row.put("evaluations", result.evaluations());
        row.put("unassessedSegmentIds", result.unassessedSegmentIds());
        row.put("findings", findings);
        row.put("pipelineOutcome", CasebookHoldoutCurrentPipelineTrial.auditRow(JSON.valueToTree(Map.of(
                "caseId", "D05-taekwondo-belt-generalization", "repeat", repeat,
                "expectedDecisionLocalOnly", expected, "status", result.status(), "diagnostics", result.diagnostics(),
                "unassessedSegmentIds", result.unassessedSegmentIds(), "findings", findings))).get("pipelineOutcome"));
        row.put("calls", client.callTraces);
        row.put("usage", usage);
        row.put("selectedExampleIds", result.diagnostics().candidatePipeline().contextSelections().stream()
                .flatMap(trace -> trace.exampleIds().stream()).distinct().toList());
        return row;
    }

    private static void writeSummary(Path output, List<Map<String, Object>> rows, Plan plan, Guard guard) throws Exception {
        var grouped = new LinkedHashMap<String, Map<Integer, Map<String, Object>>>();
        for (var row : rows) grouped.computeIfAbsent(String.valueOf(row.get("condition")), ignored -> new TreeMap<>())
                .put((Integer) row.get("repeat"), row);
        var pairs = new ArrayList<Map<String, Object>>();
        for (int repeat = 1; repeat <= REPEATS; repeat++) {
            var selected = grouped.get("CURRENT_SELECTED_REFERENCE").get(repeat);
            var omitted = grouped.get("GLOBAL_GUIDELINES_WITHOUT_EXAMPLES").get(repeat);
            pairs.add(Map.of("repeat", repeat, "selectedReferenceOutcome", selected.get("pipelineOutcome"),
                    "noExampleOutcome", omitted.get("pipelineOutcome"),
                    "selectedReferenceMatchesReviewedDecision", matches(plan.expectedDecisionLocalOnly(), String.valueOf(selected.get("pipelineOutcome"))),
                    "noExampleMatchesReviewedDecision", matches(plan.expectedDecisionLocalOnly(), String.valueOf(omitted.get("pipelineOutcome")))));
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("schemaVersion", "casebook-reference-isolation-summary-1");
        summary.put("state", "OFFLINE_AUDITED_MODEL_RESULTS");
        summary.put("scope", "ONE_D05_STORED_TRANSCRIPT_EXCERPT_NOT_VIDEO_LEVEL_GENERALIZATION");
        summary.put("providerCalls", 0);
        summary.put("httpAttempts", guard.totalAttempts);
        summary.put("reservedEstimatedUsd", guard.reservedUsd);
        summary.put("actualBillingConfirmed", false);
        summary.put("hardCapUsd", CAP_USD);
        summary.put("mediaSent", false);
        summary.put("independentGold", false);
        summary.put("runtimePromotion", false);
        summary.put("pairedComparisons", pairs);
        summary.put("conditions", Map.of(
                "CURRENT_SELECTED_REFERENCE", outcomeCounts(grouped.get("CURRENT_SELECTED_REFERENCE")),
                "GLOBAL_GUIDELINES_WITHOUT_EXAMPLES", outcomeCounts(grouped.get("GLOBAL_GUIDELINES_WITHOUT_EXAMPLES"))));
        Files.write(output.resolve("review-summary.json"), JSON.writeValueAsBytes(summary), StandardOpenOption.CREATE_NEW);
    }

    private static Map<String, Integer> outcomeCounts(Map<Integer, Map<String, Object>> rows) {
        var counts = new TreeMap<String, Integer>();
        rows.values().forEach(r -> counts.merge(String.valueOf(r.get("pipelineOutcome")), 1, Integer::sum));
        return counts;
    }

    private static boolean matches(String expected, String outcome) {
        return CasebookHoldoutCurrentPipelineTrial.compareExpected(expected,
                CasebookHoldoutCurrentPipelineTrial.PipelineOutcome.valueOf(outcome)).equals("MATCH");
    }

    private static void save(Path output, List<Map<String, Object>> rows, Guard guard, double usageUsd,
                             String state, Plan plan, String planSha, String manifestSha) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "casebook-reference-isolation-model-results-1");
        result.put("state", state);
        result.put("scope", "D05_TEXT_ONLY_REFERENCE_ISOLATION_NOT_GENERALIZATION");
        result.put("planSha256", planSha);
        result.put("manifestSha256", manifestSha);
        result.put("results", rows);
        result.put("httpAttempts", guard.totalAttempts);
        result.put("reservedEstimatedUsd", guard.reservedUsd);
        result.put("requestGuardViolation", guard.violation);
        result.put("requestGuardViolationCode", guard.violationCode);
        result.put("usageEstimatedUsd", usageUsd);
        result.put("actualBillingConfirmed", false);
        result.put("hardCapUsd", CAP_USD);
        result.put("maxHttpAttemptsTotal", MAX_HTTP_TOTAL);
        result.put("maxHttpAttemptsPerAnalysis", MAX_HTTP_PER_ANALYSIS);
        result.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        result.put("mediaSent", false);
        result.put("executionConfiguration", Map.of("model", MODEL, "reasoningEffort", "none",
                "maxExamplesCurrent", 3, "maxExamplesNoExamples", 0, "analysisSpacingMs", SPACING_MS,
                "maxBodyBytes", MAX_BODY_BYTES));
        Files.writeString(output.resolve("results.json"), JSON.writeValueAsString(result),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String canonicalInputShaUnchecked(String input) {
        try { return CasebookReferenceIsolationPreflight.canonicalInputSha(input); }
        catch (Exception e) { throw new IllegalStateException("Unable to fingerprint discovery input", e); }
    }

    private static String canonicalSystemShaUnchecked(String system) {
        try { return CasebookReferenceIsolationPreflight.canonicalSystemSha(system); }
        catch (Exception e) { throw new IllegalStateException("Unable to fingerprint discovery system prompt", e); }
    }
}
