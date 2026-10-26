package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** One-use, text-only comparison of a fixed cross-case probe with/without one draft casebook asset. */
public final class CrossCasebookDevelopmentProbeTrial {
    static final String CONSENT = "CROSS_CASEBOOK_DEV_PROBE_TEXT_20261026_APPROVED";
    static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    static final Path DATASET = Path.of("../datasets/controversy");
    static final Path PROBE = DATASET.resolve("evaluation/development-probe-practice-value-20261026");
    static final Path SELECTORS = PROBE.resolve("input-selectors.json");
    static final Path INPUTS = PROBE.resolve("materialized-inputs.json");
    static final Path BASELINE = DATASET.resolve("guidelines/runtime.json");
    static final Path CANDIDATE = DATASET.resolve(
            "experiments/d05-taekwondo-belt-standard-devaluation-preview-20261026/runtime.json");
    static final Path PAIRED_PREFLIGHT = DATASET.resolve(
            "experiments/d05-taekwondo-belt-standard-devaluation-preview-20261026/paired-asset-preflight-20261026-v3.json");
    static final Path EXPERIMENTS = DATASET.resolve("experiments");
    static final int ANALYSES = 6;
    static final int MAX_HTTP_TOTAL = 24;
    static final int MAX_HTTP_PER_ANALYSIS = 4;
    static final int MAX_BODY_BYTES = 120_000;
    static final int MAX_OUTPUT_TOKENS = 8_000;
    static final int SPACING_MS = 15_000;
    static final double CAP_USD = .55;
    static final String D05_ID = "podcast-dev#d05-taekwondo-belt-standard-devaluation";
    static final List<String> CASE_IDS = List.of("DEV-01", "DEV-02", "DEV-03");

    record CaseInput(String id, ReviewInput review) {}
    record Arm(String id, String path, String sha256) {}
    record Plan(String schemaVersion, String scope, String consentToken, String model, String reasoningEffort,
                int analyses, int maxHttpAttempts, int maxHttpPerAnalysis, int maxRequestBodyBytes,
                int maxOutputTokens, int spacingMs, double capUsd, double worstCaseReservedUsd,
                Map<String, String> sourceHashes, Map<String, String> implementationHashes,
                List<Arm> arms, List<CaseInput> cases) {}

    private CrossCasebookDevelopmentProbeTrial() {}

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static byte[] bounded(Path path) throws Exception {
        check(Files.size(path) <= 8_000_000, "Bounded local source required");
        return Files.readAllBytes(path);
    }

    static Map<String, String> implementationHashes() throws Exception {
        var hashes = new TreeMap<>(SocialDefectCurrentModelTrial.implementation());
        for (String file : List.of(
                "src/test/java/com/example/oops/analyzer/CrossCasebookDevelopmentProbeTrial.java",
                "src/test/java/com/example/oops/analyzer/CrossCasebookDevelopmentProbeTrialTest.java",
                "src/test/java/com/example/oops/analyzer/CasebookReferenceIsolationPreflight.java",
                "src/test/java/com/example/oops/analyzer/MechanismRoutingTrial.java",
                "src/test/java/com/example/oops/analyzer/CasebookHoldoutCurrentPipelineTrial.java",
                "src/test/java/com/example/oops/analyzer/DefaultVerificationTrial.java"))
            hashes.put(file, sha(Files.readAllBytes(Path.of(file))));
        return hashes;
    }

    static JsonNode projectSegments(Iterable<JsonNode> segments) {
        var projected = JSON.createArrayNode();
        for (JsonNode segment : segments) {
            check(segment.path("startMs").isIntegralNumber() && segment.path("endMs").isIntegralNumber()
                            && segment.path("text").isTextual(), "Transcript segment shape must be integral/text");
            var row = projected.addObject();
            row.put("startMs", segment.path("startMs").asLong());
            row.put("endMs", segment.path("endMs").asLong());
            row.put("text", segment.path("text").asText());
        }
        return projected;
    }

    static String canonicalSelectionSha(JsonNode projected) throws Exception {
        check(projected.isArray(), "Projected selector content must be an array");
        return sha((JSON.writeValueAsString(projected) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    static String checkedRuntimeSnapshot(Arm arm, byte[] bytes) throws Exception {
        check(sha(bytes).equals(arm.sha256()), "Runtime changed after approval: " + arm.id());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static Map<String, String> snapshotRuntimeTexts(List<Arm> arms) throws Exception {
        var snapshots = new LinkedHashMap<String, String>();
        Path datasetRoot = DATASET.toRealPath();
        for (Arm arm : arms) {
            Path expected = switch (arm.id()) {
                case "BASELINE_64" -> BASELINE.normalize();
                case "CANDIDATE_65" -> CANDIDATE.normalize();
                default -> throw new IllegalArgumentException("Unexpected runtime arm: " + arm.id());
            };
            Path declared = Path.of(arm.path()).normalize();
            check(declared.equals(expected), "Runtime path differs from approved arm: " + arm.id());
            Path actual = declared.toRealPath();
            check(actual.startsWith(datasetRoot), "Runtime must remain inside the dataset root: " + arm.id());
            check(!snapshots.containsKey(arm.id()), "Duplicate runtime arm: " + arm.id());
            snapshots.put(arm.id(), checkedRuntimeSnapshot(arm, bounded(actual)));
        }
        check(snapshots.keySet().equals(Set.of("BASELINE_64", "CANDIDATE_65")),
                "Exact approved runtime arms required");
        return Collections.unmodifiableMap(snapshots);
    }

    static String runtimeTextForAnalysis(Map<String, String> snapshots, Arm arm) {
        String snapshot = snapshots.get(arm.id());
        check(snapshot != null, "Missing verified runtime snapshot: " + arm.id());
        return snapshot;
    }

    /** Re-selects the frozen source slices instead of trusting the materialized fixture's own hash. */
    static Map<String, String> validateInputBindings(byte[] selectorsBytes, byte[] inputsBytes) throws Exception {
        JsonNode selectors = JSON.readTree(selectorsBytes), inputs = JSON.readTree(inputsBytes);
        Path datasetRoot = DATASET.toRealPath();
        check(selectors.path("items").size() == CASE_IDS.size()
                        && inputs.path("items").size() == CASE_IDS.size(),
                "Exact selector/materialized item count required");
        var materializedById = new HashMap<String, JsonNode>();
        for (JsonNode item : inputs.path("items"))
            check(materializedById.put(item.path("sampleId").asText(), item) == null,
                    "Duplicate materialized sample id");
        var sourceHashes = new TreeMap<String, String>();
        var selectedIds = new HashSet<String>();
        for (JsonNode item : selectors.path("items")) {
            String sampleId = item.path("sampleId").asText();
            check(CASE_IDS.contains(sampleId), "Unexpected selector sample id");
            check(selectedIds.add(sampleId), "Duplicate selector sample id: " + sampleId);
            JsonNode materialized = materializedById.get(sampleId);
            check(materialized != null, "Selector has no matching materialized input");

            Path sourcePath = datasetRoot.resolve(item.at("/source/path").asText()).normalize().toRealPath();
            check(sourcePath.startsWith(datasetRoot), "Transcript source must remain inside the dataset root");
            byte[] sourceBytes = bounded(sourcePath);
            String sourceSha = sha(sourceBytes);
            check(sourceSha.equals(item.at("/source/sha256").asText()), "Transcript source hash drift: " + sampleId);
            sourceHashes.put("transcript:" + sampleId, sourceSha);

            JsonNode selector = item.path("selector");
            JsonNode rows = JSON.readTree(sourceBytes).at(selector.path("jsonPointer").asText());
            check(rows.isArray(), "Selector JSON pointer must resolve to an array: " + sampleId);
            var selected = new ArrayList<JsonNode>();
            switch (selector.path("mode").asText()) {
                case "array-slice" -> {
                    int start = selector.path("startIndexInclusive").asInt(-1);
                    int end = selector.path("endIndexExclusive").asInt(-1);
                    check(start >= 0 && end >= start && end <= rows.size(), "Invalid frozen array slice: " + sampleId);
                    for (int i = start; i < end; i++) selected.add(rows.get(i));
                }
                case "array-filter-by-startMs" -> {
                    long lower = selector.path("startMsInclusive").asLong(Long.MIN_VALUE);
                    long upper = selector.path("startMsExclusive").asLong(Long.MIN_VALUE);
                    check(lower < upper, "Invalid frozen time range: " + sampleId);
                    rows.forEach(row -> {
                        if (row.path("startMs").isIntegralNumber()) {
                            long startMs = row.path("startMs").asLong();
                            if (lower <= startMs && startMs < upper) selected.add(row);
                        }
                    });
                }
                default -> throw new IllegalArgumentException("Unsupported frozen selector mode: " + sampleId);
            }
            JsonNode selectedProjection = projectSegments(selected);
            JsonNode materializedProjection = projectSegments(materialized.path("segments"));
            check(selectedProjection.size() == selector.path("expectedSegmentCount").asInt(-1)
                            && selectedProjection.size() == materializedProjection.size(),
                    "Selected segment count differs from frozen input: " + sampleId);
            String selectedSha = canonicalSelectionSha(selectedProjection);
            check(selectedSha.equals(item.path("selectedContentSha256").asText())
                            && selectedSha.equals(canonicalSelectionSha(materializedProjection)),
                    "Materialized text/time does not match the approved source selector: " + sampleId);
            check(selectedProjection.equals(materializedProjection), "Source-order selection drift: " + sampleId);
        }
        check(materializedById.keySet().equals(new HashSet<>(CASE_IDS)),
                "Materialized inputs must contain exactly the frozen sample ids");
        check(selectedIds.equals(new HashSet<>(CASE_IDS)),
                "Selectors must bind each frozen sample id exactly once");
        return Collections.unmodifiableMap(sourceHashes);
    }

    static List<CaseInput> loadCases(byte[] inputsBytes) throws Exception {
        JsonNode root = JSON.readTree(inputsBytes);
        check(root.path("schemaVersion").asText().equals("cross-case-development-inputs-1")
                        && root.path("id").asText().equals("cross-case-practice-value-probe-20261026-v1")
                        && root.path("status").asText().equals("MATERIALIZED_TEXT_ONLY_NO_LABELS"),
                "Frozen materialized text-only fixture required");
        check(!SocialDefectCurrentModelTrial.Guard.hasForbiddenLabel(root),
                "Expected labels or review approvals must not appear in model input fixture");
        check(root.path("items").size() == 3, "Exactly three fixed probe inputs required");
        var byId = new TreeMap<String, CaseInput>();
        for (JsonNode item : root.path("items")) {
            String id = item.path("sampleId").asText();
            check(CASE_IDS.contains(id) && !byId.containsKey(id), "Unexpected or duplicate sample");
            var segments = new ArrayList<ReviewInput.Segment>();
            int index = 0;
            for (JsonNode segment : item.path("segments")) {
                segments.add(new ReviewInput.Segment("seg-" + String.format(Locale.ROOT, "%04d", ++index),
                        TimelineEventType.SPEECH, segment.path("startMs").asLong(), segment.path("endMs").asLong(),
                        segment.path("text").asText(), null));
            }
            check(!segments.isEmpty(), "Non-empty text excerpt required");
            byId.put(id, new CaseInput(id, new ReviewInput(segments)));
        }
        check(new ArrayList<>(byId.keySet()).equals(CASE_IDS), "Fixed sample order and membership required");
        check(byId.get("DEV-01").review().segments().size() == 9
                        && byId.get("DEV-02").review().segments().size() == 5
                        && byId.get("DEV-03").review().segments().size() == 40,
                "Frozen 9/5/40-segment inputs required");
        return List.copyOf(byId.values());
    }

    static Plan prepare() throws Exception {
        byte[] selectors = bounded(SELECTORS), inputs = bounded(INPUTS);
        byte[] baseline = bounded(BASELINE), candidate = bounded(CANDIDATE), paired = bounded(PAIRED_PREFLIGHT);
        JsonNode selectorTree = JSON.readTree(selectors);
        check(selectorTree.path("status").asText().equals("FROZEN_TEXT_SELECTORS_NOT_RUN")
                        && !selectorTree.path("sourcePolicy").path("mediaSent").asBoolean(true)
                        && selectorTree.path("sourcePolicy").path("expectedLabelsIncluded").asBoolean(true) == false,
                "Frozen text-only source selectors required");
        JsonNode pairedTree = JSON.readTree(paired);
        check(pairedTree.path("state").asText().equals("OFFLINE_REQUEST_CAPTURE_PASS")
                        && pairedTree.path("providerCalls").asInt(-1) == 0
                        && pairedTree.path("mediaEgress").asBoolean(true) == false,
                "Passing no-provider D05 runtime preflight required");
        check(sha(baseline).equals(pairedTree.path("baselineRuntimeSha256").asText())
                        && sha(candidate).equals(pairedTree.path("candidateRuntimeSha256").asText()),
                "Runtime drifted after D05 preflight");
        JsonNode baselineTree = JSON.readTree(baseline), candidateTree = JSON.readTree(candidate);
        check(baselineTree.path("examples").size() == 64 && candidateTree.path("examples").size() == 65,
                "Expected baseline-64 and candidate-65 runtime snapshots required");
        check(loadCases(inputs).size() == 3, "Fixed cases required");
        var sourceHashes = new TreeMap<String, String>();
        sourceHashes.put("selectors", sha(selectors)); sourceHashes.put("materializedInputs", sha(inputs));
        sourceHashes.put("baselineRuntime", sha(baseline)); sourceHashes.put("candidateRuntime", sha(candidate));
        sourceHashes.put("pairedD05Preflight", sha(paired));
        sourceHashes.putAll(validateInputBindings(selectors, inputs));
        double reserve = SocialDefectCurrentModelTrial.reservation(MAX_HTTP_TOTAL, MAX_BODY_BYTES);
        var plan = new Plan("cross-casebook-development-probe-trial-1",
                "THREE_FIXED_TEXT_ONLY_DEVELOPMENT_INPUTS_PAIRED_BASELINE64_VS_D05_CANDIDATE65; NOT_GENERALIZATION_PROOF",
                CONSENT, "gpt-6-luna", "none", ANALYSES, MAX_HTTP_TOTAL, MAX_HTTP_PER_ANALYSIS,
                MAX_BODY_BYTES, MAX_OUTPUT_TOKENS, SPACING_MS, CAP_USD, reserve,
                Collections.unmodifiableMap(sourceHashes), implementationHashes(),
                List.of(new Arm("BASELINE_64", BASELINE.toString(), sha(baseline)),
                        new Arm("CANDIDATE_65", CANDIDATE.toString(), sha(candidate))), loadCases(inputs));
        validate(plan);
        return plan;
    }

    static void validate(Plan plan) {
        check(plan.schemaVersion().equals("cross-casebook-development-probe-trial-1")
                        && plan.consentToken().equals(CONSENT) && plan.model().equals("gpt-6-luna")
                        && plan.reasoningEffort().equals("none") && plan.analyses() == 6
                        && plan.maxHttpAttempts() == 24 && plan.maxHttpPerAnalysis() == 4
                        && plan.maxRequestBodyBytes() == 120_000 && plan.maxOutputTokens() == 8_000
                        && plan.spacingMs() == 15_000 && plan.capUsd() == .55
                        && plan.worstCaseReservedUsd() <= plan.capUsd()
                        && Math.abs(plan.worstCaseReservedUsd() - .3864576) < 1e-9,
                "Exact newly approved bounded plan required");
        check(plan.arms().size() == 2 && plan.cases().stream().map(CaseInput::id).toList().equals(CASE_IDS),
                "Exact two-arm, three-case comparison required");
    }

    static void assertSameFrozenPlan(Plan frozen, Plan current) {
        check(JSON.valueToTree(frozen).equals(JSON.valueToTree(current)),
                "Frozen implementation, transcript source, selector, input, runtime, or policy drift");
    }

    static JsonNode manifest(Plan plan) throws Exception {
        Plan current = prepare();
        assertSameFrozenPlan(plan, current);
        return manifest(plan, snapshotRuntimeTexts(plan.arms()));
    }

    static JsonNode manifest(Plan plan, Map<String, String> runtimeSnapshots) throws Exception {
        var rows = JSON.createArrayNode();
        boolean candidateRetrievedD05ForSourceCase = false;
        for (CaseInput sample : plan.cases()) {
            var pair = new ArrayList<CasebookReferenceIsolationPreflight.Arm>();
            for (Arm arm : plan.arms()) {
                String runtimeText = runtimeTextForAnalysis(runtimeSnapshots, arm);
                var captured = CasebookReferenceIsolationPreflight.capture(arm.id() + "_" + sample.id(), 3,
                        sample.review(), runtimeText);
                check(captured.providerCalls() == 0 && !captured.calls().isEmpty()
                                && captured.calls().stream().allMatch(call -> call.type().equals("Discovery")),
                        "Offline discovery-only request capture required");
                if (sample.id().equals("DEV-01") && arm.id().equals("CANDIDATE_65"))
                    candidateRetrievedD05ForSourceCase = captured.selectedIds().contains(D05_ID);
                pair.add(captured);
                var row = rows.addObject();
                row.put("caseId", sample.id()); row.put("arm", arm.id());
                row.put("inputSha256", sha(JSON.writeValueAsBytes(sample.review().segments())));
                row.set("selectedExampleIds", JSON.valueToTree(captured.selectedIds()));
                row.put("offlineProviderCalls", captured.providerCalls());
                row.put("discoveryCallCount", captured.calls().size());
                var callFingerprints = JSON.createArrayNode();
                for (var call : captured.calls()) {
                    var fingerprint = callFingerprints.addObject();
                    fingerprint.put("discoveryInputSha256", CasebookReferenceIsolationPreflight.canonicalInputSha(call.input()));
                    fingerprint.put("systemPromptSha256", CasebookReferenceIsolationPreflight.canonicalSystemSha(call.system()));
                }
                row.set("discoveryCallFingerprints", callFingerprints);
            }
            check(pair.get(0).calls().size() == pair.get(1).calls().size(),
                    "Baseline/candidate discovery batching must match");
            for (int i = 0; i < pair.get(0).calls().size(); i++) {
                var beforeCall = pair.get(0).calls().get(i); var afterCall = pair.get(1).calls().get(i);
                JsonNode before = JSON.readTree(beforeCall.input()), after = JSON.readTree(afterCall.input());
                check(before.equals(after) && !before.has("expectedDecision") && !before.has("caseId"),
                        "Paired model input must be identical and label-free");
                String marker = "\ncontextReference=";
                int oldMarker = beforeCall.system().lastIndexOf(marker), newMarker = afterCall.system().lastIndexOf(marker);
                check(oldMarker >= 0 && newMarker >= 0
                                && beforeCall.system().substring(0, oldMarker).equals(afterCall.system().substring(0, newMarker)),
                        "Shared prompt prefix must remain fixed");
                JsonNode oldPayload = JSON.readTree(beforeCall.system().substring(oldMarker + marker.length()));
                JsonNode newPayload = JSON.readTree(afterCall.system().substring(newMarker + marker.length()));
                check(oldPayload.path("reviewGuidelines").equals(newPayload.path("reviewGuidelines")),
                        "Global judgment guidelines must remain fixed");
                check(normalizedBody(beforeCall.providerBody()).equals(normalizedBody(afterCall.providerBody())),
                        "Provider configuration outside selected casebook references must remain fixed");
            }
        }
        check(plan.arms().get(1).id().equals("CANDIDATE_65"), "Candidate arm ordering changed");
        check(plan.cases().get(0).id().equals("DEV-01"), "Original D05 sample must remain first");
        check(candidateRetrievedD05ForSourceCase,
                "Candidate must retrieve the new D05 reference for its source case");
        var manifest = JSON.createObjectNode();
        manifest.put("schemaVersion", "cross-casebook-development-probe-manifest-1");
        manifest.put("planSha256", sha(JSON.writeValueAsBytes(plan)));
        manifest.set("sourceHashes", JSON.valueToTree(plan.sourceHashes()));
        manifest.set("implementationHashes", JSON.valueToTree(plan.implementationHashes()));
        manifest.put("model", plan.model()); manifest.put("reasoningEffort", plan.reasoningEffort());
        manifest.put("analyses", plan.analyses()); manifest.put("maxHttpAttempts", plan.maxHttpAttempts());
        manifest.put("maxHttpPerAnalysis", plan.maxHttpPerAnalysis());
        manifest.put("maxRequestBodyBytes", plan.maxRequestBodyBytes()); manifest.put("maxOutputTokens", plan.maxOutputTokens());
        manifest.put("hardTrialCapUsd", plan.capUsd()); manifest.put("worstCaseReservedUsd", plan.worstCaseReservedUsd());
        manifest.put("spacingBetweenAnalysesMs", plan.spacingMs()); manifest.put("providerCalls", 0);
        manifest.put("mediaEgress", false); manifest.put("expectedLabelsInModelInputs", false);
        manifest.set("conditions", rows);
        return manifest;
    }

    static JsonNode normalizedBody(JsonNode body) {
        ObjectNode copy = (ObjectNode) body.deepCopy();
        var messages = copy.withArray("messages");
        check(messages.size() == 2, "Exactly system/user text message pair required");
        ((ObjectNode) messages.get(0)).put("content", "<SYSTEM_WITHOUT_CONTEXT_PAYLOAD>");
        return copy;
    }

    static final class Guard {
        int totalAttempts, analysisAttempts;
        double reservedUsd;
        final Set<String> bodyHashes = new HashSet<>();
        final Path output;
        Guard(Path output) { this.output = output; }
        void beginAnalysis() { analysisAttempts = 0; bodyHashes.clear(); }

        byte[] accept(byte[] requestBody, String uri) throws Exception {
            check(ENDPOINT.equals(uri), "Only approved OpenAI endpoint is allowed");
            check(requestBody.length <= MAX_BODY_BYTES, "Request body exceeds approved byte cap");
            var body = (ObjectNode) JSON.readTree(requestBody);
            check(body.path("model").asText().equals("gpt-6-luna")
                            && body.path("reasoning_effort").asText().equals("none"), "Model/effort drift");
            if (!body.has("max_completion_tokens")) body.put("max_completion_tokens", MAX_OUTPUT_TOKENS);
            check(body.path("max_completion_tokens").asInt() > 0
                            && body.path("max_completion_tokens").asInt() <= MAX_OUTPUT_TOKENS
                            && !body.has("tools") && !body.has("modalities"), "Output/media settings drift");
            var messages = body.path("messages");
            check(messages.isArray() && messages.size() == 2, "Only two text messages are allowed");
            for (JsonNode message : messages) {
                check(message.path("content").isTextual(), "Multimodal payload is forbidden");
                String content = message.path("content").asText();
                check(!content.contains("image_url") && !content.contains("audio_url")
                        && !content.contains("data:image/") && !content.contains("data:audio/"),
                        "Media payload is forbidden");
            }
            check(!SocialDefectCurrentModelTrial.Guard.hasForbiddenLabel(
                    JSON.readTree(messages.get(1).path("content").asText())), "Expected labels must remain local");
            byte[] bounded = JSON.writeValueAsBytes(body);
            check(bounded.length <= MAX_BODY_BYTES, "Bounded serialized request required");
            check(bodyHashes.add(sha(bounded)), "Duplicate/retry request blocked");
            check(totalAttempts < MAX_HTTP_TOTAL && analysisAttempts < MAX_HTTP_PER_ANALYSIS,
                    "HTTP request limit reached");
            double next = SocialDefectCurrentModelTrial.reservation(1, bounded.length);
            check(reservedUsd + next <= CAP_USD, "Approved $0.55 request reservation cap reached");
            totalAttempts++; analysisAttempts++; reservedUsd += next;
            Files.writeString(output.resolve("reservation.json"), JSON.writeValueAsString(Map.of(
                    "reservedEstimatedUsd", reservedUsd, "httpAttempts", totalAttempts,
                    "maxHttpAttempts", MAX_HTTP_TOTAL, "maxHttpPerAnalysis", MAX_HTTP_PER_ANALYSIS,
                    "hardTrialCapUsd", CAP_USD, "actualBillingConfirmed", false)));
            return bounded;
        }
    }

    static Map<String, Object> resultRow(int index, CaseInput sample, Arm arm, long elapsedMs,
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
        row.put("index", index); row.put("caseId", sample.id()); row.put("arm", arm.id());
        row.put("elapsedMs", elapsedMs); row.put("status", result.status()); row.put("pipelineOutcome", outcome);
        row.put("findings", result.findings().stream().map(SocialDefectCurrentModelTrial::finding).toList());
        row.put("diagnostics", diagnostics); row.put("unassessedSegmentIds", result.unassessedSegmentIds());
        row.put("calls", List.copyOf(client.calls)); row.put("usage", usage);
        return row;
    }

    static void save(Path output, Plan plan, String planSha, String manifestSha,
                     List<Map<String, Object>> rows, Guard guard, double usageUsd, String state) throws Exception {
        Files.writeString(output.resolve("results.json"), JSON.writeValueAsString(Map.ofEntries(
                Map.entry("schemaVersion", "cross-casebook-development-probe-results-1"), Map.entry("state", state),
                Map.entry("planSha256", planSha), Map.entry("manifestSha256", manifestSha),
                Map.entry("results", rows), Map.entry("httpAttempts", guard.totalAttempts),
                Map.entry("reservedEstimatedUsd", guard.reservedUsd), Map.entry("usageEstimatedUsd", usageUsd),
                Map.entry("actualBillingConfirmed", false), Map.entry("approvedCapUsd", plan.capUsd()),
                Map.entry("mediaSent", false), Map.entry("analysesPlanned", plan.analyses()))));
    }

    public static void main(String[] args) throws Exception {
        check((args.length == 2 && args[0].equals("prepare")) || (args.length == 6 && args[0].equals("run")),
                "prepare NEW_PLAN_DIR | run PLAN SHA MANIFEST SHA NEW_OUTPUT_DIR required");
        Path experimentRoot = EXPERIMENTS.toRealPath();
        if (args[0].equals("prepare")) {
            Path output = Path.of(args[1]).toAbsolutePath().normalize();
            check(!Files.exists(output) && output.getParent().toRealPath().startsWith(experimentRoot),
                    "New private plan directory required under experiments");
            Plan plan = prepare(); byte[] planBytes = JSON.writeValueAsBytes(plan);
            JsonNode manifest = manifest(plan); byte[] manifestBytes = JSON.writeValueAsBytes(manifest);
            Files.createDirectory(output);
            Files.write(output.resolve("plan.json"), planBytes, StandardOpenOption.CREATE_NEW);
            Files.write(output.resolve("manifest.json"), manifestBytes, StandardOpenOption.CREATE_NEW);
            System.out.println("CROSS_CASEBOOK_PREPARED analyses=6 maxHttp=24 reservedUsd="
                    + plan.worstCaseReservedUsd() + " providerCalls=0 planSha=" + sha(planBytes)
                    + " manifestSha=" + sha(manifestBytes));
            return;
        }
        Path planPath = Path.of(args[1]).toRealPath(); byte[] planBytes = bounded(planPath);
        check(sha(planBytes).equals(args[2]), "Plan SHA mismatch");
        Path manifestPath = Path.of(args[3]).toRealPath(); byte[] manifestBytes = bounded(manifestPath);
        check(sha(manifestBytes).equals(args[4]), "Manifest SHA mismatch");
        Plan plan = JSON.readValue(planBytes, Plan.class); validate(plan);
        Plan current = prepare(); assertSameFrozenPlan(plan, current);
        Map<String, String> runtimeSnapshots = snapshotRuntimeTexts(plan.arms());
        check(JSON.readTree(manifestBytes).equals(manifest(plan, runtimeSnapshots)),
                "Input, runtime, implementation or retrieval drift");
        check(CONSENT.equals(System.getenv("CROSS_CASEBOOK_DEV_PROBE_CONSENT")), "Exact-plan consent token required");
        Path output = Path.of(args[5]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(experimentRoot),
                "New private output directory required; execution cannot resume");
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
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build()); factory.setReadTimeout(Duration.ofSeconds(60));
        var builder = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + key)
                .requestInterceptor((request, body, execution) -> {
                    try {
                        byte[] approved = guard.accept(body, request.getURI().toString());
                        request.getHeaders().setContentLength(approved.length);
                        return execution.execute(request, approved);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Text-only bounded trial guard blocked request", e);
                    }
                });
        if (props.hasOrganization()) builder.defaultHeader("OpenAI-Organization", props.organization());
        if (props.hasProject()) builder.defaultHeader("OpenAI-Project", props.project());
        var client = new MechanismRoutingTrial.Client(builder.build(), props);
        var rows = new ArrayList<Map<String, Object>>(); double totalUsageUsd = 0;
        save(output, plan, args[2], args[4], rows, guard, totalUsageUsd, "RUNNING");
        try {
            int index = 0;
            for (CaseInput sample : plan.cases()) for (Arm arm : plan.arms()) {
                index++; guard.beginAnalysis(); client.calls.clear(); client.beginVideo(0L);
                client.beginAnalyzer("cross-casebook-" + sample.id() + "-" + arm.id().toLowerCase(Locale.ROOT));
                var library = new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(
                        runtimeTextForAnalysis(runtimeSnapshots, arm)), true, "classpath:frozen.json", 6000, 3, 3000, "", true);
                library.load();
                var context = new AnalysisContext(Video.builder().filename("private-cross-casebook-probe").build(),
                        ContentGenre.GENERAL, List.of(), List.of(), sample.review());
                long started = System.nanoTime();
                var analyzed = CandidateReviewEngine.run(client, context,
                        new CandidateReviewEngine.RunServices(null, null, library, null, null),
                        new CandidateReviewEngine.RunOptions(24, 0, false,
                                new CandidateReviewEngine.VerificationFeatures(false, false, false), false));
                var usage = client.videoUsage(); totalUsageUsd += usage.costUsd();
                var row = resultRow(index, sample, arm, (System.nanoTime() - started) / 1_000_000,
                        analyzed, client, usage); rows.add(row);
                boolean ok = analyzed.status() != AnalyzerStatus.FAILED && analyzed.status() != AnalyzerStatus.SKIPPED
                        && guard.totalAttempts <= MAX_HTTP_TOTAL && guard.analysisAttempts <= MAX_HTTP_PER_ANALYSIS
                        && guard.reservedUsd <= CAP_USD && totalUsageUsd <= CAP_USD
                        && client.failureCount() == 0 && usage.calls() == client.calls.size();
                client.endVideo(); save(output, plan, args[2], args[4], rows, guard, totalUsageUsd,
                        ok ? "RUNNING" : "STOPPED");
                System.out.println("CROSS_CASEBOOK " + index + "/" + ANALYSES + " " + sample.id() + " " + arm.id()
                        + " outcome=" + row.get("pipelineOutcome") + " http=" + usage.calls());
                check(ok, "Provider/pipeline/budget failure; stop without retry");
                if (index < ANALYSES) Thread.sleep(SPACING_MS);
            }
            check(rows.size() == ANALYSES, "All six paired analyses required");
            save(output, plan, args[2], args[4], rows, guard, totalUsageUsd, "COMPLETE");
        } catch (Exception e) {
            save(output, plan, args[2], args[4], rows, guard, totalUsageUsd, "STOPPED"); throw e;
        }
    }
}
