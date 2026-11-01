package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** One-use paired, text-only model trial for a single casebook asset addition. */
public final class D05CasebookAdditionModelTrial {
    static final String CONSENT = "D05_CASEBOOK_ADDITION_TEXT_TRIAL_20261026_APPROVED";
    static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    static final Path DATASET = Path.of("../datasets/controversy");
    static final Path BASELINE = DATASET.resolve("guidelines/runtime.json");
    static final Path CANDIDATE = DATASET.resolve(
            "experiments/d05-taekwondo-belt-standard-devaluation-preview-20261026/runtime.json");
    static final Path PREFLIGHT = DATASET.resolve(
            "experiments/d05-taekwondo-belt-standard-devaluation-preview-20261026/paired-asset-preflight-20261026-v3.json");
    static final Path INTAKE = DATASET.resolve("research/d05-taekwondo-belt-standard-devaluation-intake-20261026.json");
    static final int ANALYSES = 4;
    static final int MAX_HTTP_TOTAL = 16;
    static final int MAX_HTTP_PER_ANALYSIS = 4;
    static final int MAX_BODY_BYTES = 120_000;
    static final int MAX_OUTPUT_TOKENS = 8_000;
    static final int SPACING_MS = 15_000;
    static final double CAP_USD = .50;
    static final String D05_ID = "podcast-dev#d05-taekwondo-belt-standard-devaluation";

    record CaseInput(String caseId, String provenance, String expectedDecisionLocalOnly, ReviewInput review) {}
    record Arm(String id, String runtimePath, String runtimeSha256) {}
    record Plan(String schemaVersion, String scope, String consentToken, String model, String reasoningEffort,
                int analyses, int maxHttpAttempts, int maxHttpPerAnalysis, int maxRequestBodyBytes,
                int maxOutputTokens, int spacingMs, double capUsd, double worstCaseReservedUsd,
                Map<String, String> sourceHashes, Map<String, String> implementationHashes,
                List<Arm> arms, List<CaseInput> cases) {}

    private D05CasebookAdditionModelTrial() {}

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static byte[] bounded(Path path) throws Exception {
        check(Files.size(path) <= 8_000_000, "Bounded local source required");
        return Files.readAllBytes(path);
    }

    static Map<String, String> implementationHashes() throws Exception {
        var hashes = new TreeMap<>(CasebookHoldoutCurrentPipelineTrial.implementation());
        for (String file : List.of(
                "src/test/java/com/example/oops/analyzer/D05CasebookAdditionModelTrial.java",
                "src/test/java/com/example/oops/analyzer/D05CasebookAdditionPreflightTest.java"))
            hashes.put(file, sha(Files.readAllBytes(Path.of(file))));
        return hashes;
    }

    static Plan prepare() throws Exception {
        byte[] baselineBytes = bounded(BASELINE), candidateBytes = bounded(CANDIDATE);
        byte[] preflightBytes = bounded(PREFLIGHT), intakeBytes = bounded(INTAKE);
        var preflight = JSON.readTree(preflightBytes);
        check(preflight.path("state").asText().equals("OFFLINE_REQUEST_CAPTURE_PASS")
                        && preflight.path("providerCalls").asInt(-1) == 0
                        && !preflight.path("mediaEgress").asBoolean(true)
                        && preflight.path("pairs").size() == 2,
                "Passing no-provider paired preflight required");
        check(sha(baselineBytes).equals(preflight.path("baselineRuntimeSha256").asText())
                        && sha(candidateBytes).equals(preflight.path("candidateRuntimeSha256").asText()),
                "Runtime changed after paired preflight");
        check(preflight.at("/pairs/0/candidateSelectedD05").asBoolean(false)
                        && preflight.at("/pairs/1/candidateSelectedD05").asBoolean(false),
                "D05 must be retrieved for both positive and normal-control inputs");

        var holdout = CasebookHoldoutCurrentPipelineTrial.prepare();
        var positive = holdout.cases().stream()
                .filter(c -> c.caseId().equals("D05-taekwondo-belt-generalization"))
                .findFirst().orElseThrow();
        check(positive.expectedDecisionLocalOnly().equals("REVIEW_REQUIRED"), "Approved positive label required locally");
        var intake = JSON.readTree(intakeBytes);
        check(intake.path("id").asText().equals(D05_ID)
                        && intake.path("decision").asText().equals("REVIEW_REQUIRED")
                        && intake.path("action").asText().equals("ADD"), "D05 intake plan required");
        Path reviewPath = DATASET.resolve("research/d05-taekwondo-belt-standard-devaluation-review-20261026.json");
        byte[] reviewBytes = bounded(reviewPath);
        var reviewRecord = JSON.readTree(reviewBytes);
        check(!reviewRecord.path("trainingUseAuthorized").asBoolean(true)
                        && !reviewRecord.at("/reviews/0/runtimePromoted").asBoolean(true)
                        && reviewRecord.at("/reviews/0/approvalBasis").asText().equals("USER_APPROVED_DEVELOPMENT_BOUNDARY"),
                "Unpromoted user-approved D05 review required");
        check(intake.at("/approval/sha256").asText().equals(sha(reviewBytes))
                        && intake.at("/source/sha256").asText().equals(holdout.transcriptSha256())
                        && reviewRecord.at("/reviews/0/sourceSha256").asText().equals(holdout.transcriptSha256()),
                "D05 approval/intake/source hashes must stay linked");
        String normalText = intake.path("normalContrasts").get(0).path("text").asText();
        check(!normalText.isBlank(), "Approved synthetic normal contrast required");
        var normal = new CaseInput("D05_SYNTHETIC_NORMAL", "SYNTHETIC_APPROVED_DEVELOPMENT_CONTROL", "PASS",
                new ReviewInput(List.of(new ReviewInput.Segment("synthetic-d05-normal-control",
                        TimelineEventType.SPEECH, 0, 1_000, normalText, null))));
        var actual = new CaseInput("D05_POSITIVE", "USER_APPROVED_SOURCE_TRANSCRIPT_NOT_AUDIO_VERIFIED",
                "REVIEW_REQUIRED", positive.review());
        check(sha(bounded(Path.of(CasebookHoldoutCurrentPipelineTrial.TRANSCRIPT_RELATIVE)))
                        .equals(holdout.transcriptSha256()), "Saved source transcript drift");

        var arms = List.of(new Arm("BASELINE_64", BASELINE.toString(), sha(baselineBytes)),
                new Arm("CANDIDATE_65", CANDIDATE.toString(), sha(candidateBytes)));
        var cases = List.of(actual, normal);
        var sourceHashes = new TreeMap<String, String>();
        sourceHashes.put("pairedPreflight", sha(preflightBytes));
        sourceHashes.put("d05Intake", sha(intakeBytes));
        sourceHashes.put("d05Review", sha(reviewBytes));
        sourceHashes.put("storedTranscript", holdout.transcriptSha256());
        sourceHashes.put("baselineRuntime", sha(baselineBytes));
        sourceHashes.put("candidateRuntime", sha(candidateBytes));
        double reserve = SocialDefectCurrentModelTrial.reservation(MAX_HTTP_TOTAL, MAX_BODY_BYTES);
        var plan = new Plan("casebook-asset-addition-model-trial-1",
                "PAIRED_SOURCE_CASE_AND_SYNTHETIC_NORMAL_CONTROL; SELF_RETRIEVAL_NOT_GENERALIZATION",
                CONSENT, "gpt-6-luna", "none", ANALYSES, MAX_HTTP_TOTAL, MAX_HTTP_PER_ANALYSIS,
                MAX_BODY_BYTES, MAX_OUTPUT_TOKENS, SPACING_MS, CAP_USD, reserve,
                Collections.unmodifiableMap(new TreeMap<>(sourceHashes)), implementationHashes(), arms, cases);
        validate(plan);
        return plan;
    }

    static void validate(Plan plan) {
        check(plan.schemaVersion().equals("casebook-asset-addition-model-trial-1")
                        && plan.consentToken().equals(CONSENT)
                        && plan.model().equals("gpt-6-luna") && plan.reasoningEffort().equals("none")
                        && plan.analyses() == 4 && plan.maxHttpAttempts() == 16
                        && plan.maxHttpPerAnalysis() == 4 && plan.maxRequestBodyBytes() == 120_000
                        && plan.maxOutputTokens() == 8_000 && plan.spacingMs() == 15_000
                        && plan.capUsd() == .50 && plan.worstCaseReservedUsd() <= plan.capUsd()
                        && Math.abs(plan.worstCaseReservedUsd() - .2576384) < 1e-9,
                "Frozen, bounded four-analysis trial required");
        check(plan.arms().size() == 2 && plan.cases().size() == 2, "Two runtime arms and two fixed cases required");
        check(plan.cases().stream().filter(c -> c.expectedDecisionLocalOnly().equals("REVIEW_REQUIRED")).count() == 1
                        && plan.cases().stream().filter(c -> c.expectedDecisionLocalOnly().equals("PASS")).count() == 1,
                "One local positive and one local normal control required");
    }

    static JsonNode manifest(Plan plan) throws Exception {
        var rows = JSON.createArrayNode();
        for (Arm arm : plan.arms()) {
            String runtimeText = Files.readString(Path.of(arm.runtimePath()));
            var library = new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(runtimeText), true,
                    "classpath:frozen.json", 6000, 3, 3000, "", true);
            library.load();
            check("READY_WORKING_REFERENCE".equals(library.trace(TimelineEventType.SPEECH).state()),
                    "Runtime guideline library must load");
            for (CaseInput sample : plan.cases()) {
                var captured = CasebookReferenceIsolationPreflight.capture("" + arm.id() + "_" + sample.caseId(),
                        3, sample.review(), runtimeText);
                var row = rows.addObject();
                row.put("arm", arm.id()); row.put("caseId", sample.caseId());
                row.put("provenance", sample.provenance());
                row.put("expectedDecisionLocalOnly", sample.expectedDecisionLocalOnly());
                row.put("inputSha256", CasebookReferenceIsolationPreflight.canonicalInputSha(
                        JSON.writeValueAsString(sample.review().segments())));
                row.set("selectedExampleIds", JSON.valueToTree(captured.selectedIds()));
                var fingerprints = new ArrayList<Map<String, String>>();
                for (var call : captured.calls()) fingerprints.add(Map.of(
                        "inputSha256", CasebookReferenceIsolationPreflight.canonicalInputSha(call.input()),
                        "systemPromptSha256", CasebookReferenceIsolationPreflight.canonicalSystemSha(call.system())));
                row.set("discoveryRequestFingerprints", JSON.valueToTree(fingerprints));
                row.put("providerCalls", captured.providerCalls());
            }
        }
        var result = JSON.createObjectNode();
        result.put("schemaVersion", "casebook-asset-addition-model-trial-manifest-1");
        result.put("planSha256", sha(JSON.writeValueAsBytes(plan)));
        result.set("sourceHashes", JSON.valueToTree(plan.sourceHashes()));
        result.set("implementationHashes", JSON.valueToTree(plan.implementationHashes()));
        result.put("model", plan.model()); result.put("reasoningEffort", plan.reasoningEffort());
        result.put("maxHttpAttempts", plan.maxHttpAttempts()); result.put("maxHttpPerAnalysis", plan.maxHttpPerAnalysis());
        result.put("maxRequestBodyBytes", plan.maxRequestBodyBytes()); result.put("maxOutputTokens", plan.maxOutputTokens());
        result.put("hardTrialCapUsd", plan.capUsd()); result.put("worstCaseReservedUsd", plan.worstCaseReservedUsd());
        result.put("analyses", plan.analyses()); result.put("providerCalls", 0); result.put("mediaEgress", false);
        result.put("expectedDecisionsInModelInputs", false); result.set("conditions", rows);
        return result;
    }

    static final class Guard {
        int totalAttempts, analysisAttempts;
        double reservedUsd;
        final Set<String> bodyHashes = new HashSet<>();
        final Path output;
        Guard(Path output) { this.output = output; }
        void beginAnalysis() { analysisAttempts = 0; bodyHashes.clear(); }
        byte[] accept(byte[] requestBody, String uri) throws Exception {
            check(ENDPOINT.equals(uri), "Only the approved OpenAI endpoint is allowed");
            check(requestBody.length <= MAX_BODY_BYTES, "Request body exceeds approved limit");
            var body = (ObjectNode) JSON.readTree(requestBody);
            check(body.path("model").asText().equals("gpt-6-luna")
                            && body.path("reasoning_effort").asText().equals("none"), "Model settings drift");
            if (!body.has("max_completion_tokens")) body.put("max_completion_tokens", MAX_OUTPUT_TOKENS);
            check(body.path("max_completion_tokens").asInt() > 0
                            && body.path("max_completion_tokens").asInt() <= MAX_OUTPUT_TOKENS
                            && !body.has("tools") && !body.has("modalities"), "Output/media settings drift");
            var messages = body.path("messages");
            check(messages.isArray() && messages.size() == 2, "Only system and user text messages are approved");
            for (var message : messages) {
                check(message.path("content").isTextual(), "Multimodal request is forbidden");
                String content = message.path("content").asText();
                check(!content.contains("image_url") && !content.contains("audio_url")
                        && !content.contains("data:image/") && !content.contains("data:audio/"), "Media payload forbidden");
            }
            check(!SocialDefectCurrentModelTrial.Guard.hasForbiddenLabel(JSON.readTree(messages.get(1).path("content").asText())),
                    "Expected labels must remain local");
            byte[] bounded = JSON.writeValueAsBytes(body);
            check(bounded.length <= MAX_BODY_BYTES, "Request body exceeds approved limit");
            String digest = sha(bounded);
            check(bodyHashes.add(digest), "Duplicate/retry request blocked");
            check(totalAttempts < MAX_HTTP_TOTAL && analysisAttempts < MAX_HTTP_PER_ANALYSIS,
                    "HTTP attempt limit reached");
            double next = SocialDefectCurrentModelTrial.reservation(1, bounded.length);
            check(reservedUsd + next <= CAP_USD, "Hard $0.50 reservation limit reached");
            totalAttempts++; analysisAttempts++; reservedUsd += next;
            Files.writeString(output.resolve("reservation.json"), JSON.writeValueAsString(Map.of(
                    "reservedEstimatedUsd", reservedUsd, "httpAttempts", totalAttempts,
                    "maxHttpAttempts", MAX_HTTP_TOTAL, "maxHttpPerAnalysis", MAX_HTTP_PER_ANALYSIS,
                    "hardTrialCapUsd", CAP_USD, "actualBillingConfirmed", false)));
            return bounded;
        }
    }

    static Map<String, Object> resultRow(int index, Arm arm, CaseInput sample, long elapsedMs,
                                         TextReviewEngine.Result result, MechanismRoutingTrial.Client client,
                                         OpenAiClient.TokenUsage usage) {
        var diagnostics = result.diagnostics();
        var pipeline = diagnostics == null ? null : diagnostics.candidatePipeline();
        var states = pipeline == null ? List.<String>of()
                : pipeline.candidates().stream().map(CandidateReviewDiagnostics.Trace::state).toList();
        var outcome = CasebookHoldoutCurrentPipelineTrial.classifyOutcome(result.status(), result.findings().size(),
                result.unassessedSegmentIds().size(), diagnostics != null && diagnostics.truncated()
                        || pipeline != null && pipeline.truncated(), pipeline == null ? 0 : pipeline.proposed(),
                pipeline == null ? 0 : pipeline.uncertain(), pipeline == null ? 0 : pipeline.verificationFailed(), states);
        var row = new LinkedHashMap<String, Object>();
        row.put("index", index); row.put("arm", arm.id()); row.put("caseId", sample.caseId());
        row.put("provenance", sample.provenance()); row.put("expectedDecisionLocalOnly", sample.expectedDecisionLocalOnly());
        row.put("elapsedMs", elapsedMs); row.put("status", result.status()); row.put("pipelineOutcome", outcome);
        row.put("expectedComparisonLocalOnly", CasebookHoldoutCurrentPipelineTrial.compareExpected(
                sample.expectedDecisionLocalOnly(), outcome));
        row.put("findings", result.findings().stream().map(SocialDefectCurrentModelTrial::finding).toList());
        row.put("diagnostics", diagnostics); row.put("unassessedSegmentIds", result.unassessedSegmentIds());
        row.put("calls", List.copyOf(client.calls)); row.put("usage", usage);
        return row;
    }

    static void save(Path output, Plan plan, String planSha, String manifestSha,
                     List<Map<String, Object>> rows, Guard guard, double usageUsd, String state) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "casebook-asset-addition-model-trial-results-1");
        result.put("state", state); result.put("scope", plan.scope());
        result.put("planSha256", planSha); result.put("manifestSha256", manifestSha);
        result.put("results", rows); result.put("httpAttempts", guard.totalAttempts);
        result.put("reservedEstimatedUsd", guard.reservedUsd); result.put("usageEstimatedUsd", usageUsd);
        result.put("actualBillingConfirmed", false); result.put("approvedCapUsd", plan.capUsd());
        result.put("mediaSent", false); result.put("analysesPlanned", plan.analyses());
        Files.writeString(output.resolve("results.json"), JSON.writeValueAsString(result));
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 2 && args[0].equals("prepare") || args.length == 6 && args[0].equals("run"),
                "prepare NEW_PLAN_DIR | run PLAN_PATH PLAN_SHA MANIFEST_PATH MANIFEST_SHA NEW_OUTPUT_DIR");
        Path experiments = DATASET.resolve("experiments").toRealPath();
        if (args[0].equals("prepare")) {
            Path output = Path.of(args[1]).toAbsolutePath().normalize();
            check(!Files.exists(output) && output.getParent().toRealPath().startsWith(experiments),
                    "New private trial directory required");
            Plan plan = prepare();
            byte[] planBytes = JSON.writeValueAsBytes(plan);
            JsonNode manifest = manifest(plan); byte[] manifestBytes = JSON.writeValueAsBytes(manifest);
            Files.createDirectory(output);
            Files.write(output.resolve("plan.json"), planBytes, StandardOpenOption.CREATE_NEW);
            Files.write(output.resolve("manifest.json"), manifestBytes, StandardOpenOption.CREATE_NEW);
            System.out.println("D05_ADDITION_PREFLIGHT analyses=4 maxHttp=16 reserveUsd=" + plan.worstCaseReservedUsd()
                    + " providerCalls=0 planSha=" + sha(planBytes) + " manifestSha=" + sha(manifestBytes));
            return;
        }

        Path planPath = Path.of(args[1]).toRealPath(); byte[] planBytes = bounded(planPath);
        check(sha(planBytes).equals(args[2]), "Plan SHA mismatch");
        Path manifestPath = Path.of(args[3]).toRealPath(); byte[] manifestBytes = bounded(manifestPath);
        check(sha(manifestBytes).equals(args[4]), "Manifest SHA mismatch");
        Plan plan = JSON.readValue(planBytes, Plan.class); validate(plan);
        check(JSON.readTree(manifestBytes).equals(manifest(plan)), "Source, implementation, runtime or retrieval drift");
        check(CONSENT.equals(System.getenv("D05_CASEBOOK_ADDITION_TRIAL_CONSENT")), "Fresh exact-plan consent required");
        Path output = Path.of(args[5]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(experiments),
                "New private output directory required; no resume");
        String key = System.getenv("OPENAI_API_KEY"); check(key != null && !key.isBlank(), "OPENAI_API_KEY required; never print");
        DefaultVerificationTrial.claimRun(planPath, output, args[2], CONSENT, MAX_HTTP_TOTAL);
        Files.createDirectory(output);
        Files.write(output.resolve("plan.json"), planBytes, StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("manifest.json"), manifestBytes, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("authorization.json"), JSON.writeValueAsString(Map.of(
                "planSha256", args[2], "manifestSha256", args[4], "textEgressApproved", true,
                "mediaEgressApproved", false, "maxHttpAttempts", MAX_HTTP_TOTAL,
                "maxHttpPerAnalysis", MAX_HTTP_PER_ANALYSIS, "hardTrialCapUsd", CAP_USD,
                "previousBudgetReused", false)), StandardOpenOption.CREATE_NEW);

        var guard = new Guard(output);
        var props = new OpenAiProperties(key, System.getenv("OPENAI_ORG_ID"), System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1", plan.model(), plan.reasoningEffort(), Duration.ofSeconds(60),
                new OpenAiProperties.Pricing(.10, .01, .50, null, null));
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        var builder = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key)
                .requestInterceptor((request, body, execution) -> {
                    try {
                        byte[] approved = guard.accept(body, request.getURI().toString());
                        request.getHeaders().setContentLength(approved.length);
                        return execution.execute(request, approved);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Approved text-only egress guard blocked request", e);
                    }
                });
        if (props.hasOrganization()) builder.defaultHeader("OpenAI-Organization", props.organization());
        if (props.hasProject()) builder.defaultHeader("OpenAI-Project", props.project());
        var client = new MechanismRoutingTrial.Client(builder.build(), props);
        var rows = new ArrayList<Map<String, Object>>(); double totalUsageUsd = 0;
        save(output, plan, args[2], args[4], rows, guard, totalUsageUsd, "RUNNING");
        try {
            int index = 0;
            for (CaseInput sample : plan.cases()) {
                for (Arm arm : plan.arms()) {
                    index++; guard.beginAnalysis(); client.calls.clear(); client.beginVideo(0L);
                    client.beginAnalyzer("d05-casebook-addition-" + arm.id().toLowerCase(Locale.ROOT));
                    var library = new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(Files.readString(Path.of(arm.runtimePath()))),
                            true, "classpath:frozen.json", 6000, 3, 3000, "", true);
                    library.load();
                    var context = new AnalysisContext(Video.builder().filename("private-d05-" + arm.id()).build(),
                            ContentGenre.GENERAL, List.of(), List.of(), sample.review());
                    long started = System.nanoTime();
                    var analyzed = CandidateReviewEngine.run(client, context,
                            new CandidateReviewEngine.RunServices(null, null, library, null, null),
                            new CandidateReviewEngine.RunOptions(24, 0, false,
                                    new CandidateReviewEngine.VerificationFeatures(false, false, false), false));
                    var usage = client.videoUsage(); totalUsageUsd += usage.costUsd();
                    var row = resultRow(index, arm, sample, (System.nanoTime() - started) / 1_000_000,
                            analyzed, client, usage);
                    rows.add(row);
                    boolean ok = analyzed.status() == AnalyzerStatus.SUCCESS
                            && guard.totalAttempts <= MAX_HTTP_TOTAL && guard.analysisAttempts <= MAX_HTTP_PER_ANALYSIS
                            && guard.reservedUsd <= CAP_USD && totalUsageUsd <= CAP_USD
                            && client.failureCount() == 0 && usage.calls() == client.calls.size();
                    client.endVideo(); save(output, plan, args[2], args[4], rows, guard, totalUsageUsd,
                            ok ? "RUNNING" : "STOPPED");
                    System.out.println("D05_ADDITION " + index + "/" + ANALYSES + " " + sample.caseId()
                            + " " + arm.id() + " outcome=" + row.get("pipelineOutcome") + " http=" + usage.calls());
                    check(ok, "Provider, pipeline, schema, or budget failure; stop without retry");
                    if (index < ANALYSES) Thread.sleep(SPACING_MS);
                }
            }
            check(rows.size() == ANALYSES, "All four paired analyses required");
            save(output, plan, args[2], args[4], rows, guard, totalUsageUsd, "COMPLETE");
        } catch (Exception e) {
            save(output, plan, args[2], args[4], rows, guard, totalUsageUsd, "STOPPED"); throw e;
        }
    }
}
