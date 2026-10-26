package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Offline request-capture gate for the casebook reference isolation trial. Never contacts a provider. */
public final class CasebookReferenceIsolationPreflight {
    private static final Path EXPERIMENTS = Path.of("../datasets/controversy/experiments");
    private static final String PLAN_RESOURCE = "/evals/casebook-reference-isolation-plan.json";
    private static final String REVIEW_RELATIVE = CasebookHoldoutCurrentPipelineTrial.REVIEW_RELATIVE;
    private static final String TRANSCRIPT_RELATIVE = CasebookHoldoutCurrentPipelineTrial.TRANSCRIPT_RELATIVE;
    private static final String RUNTIME_RELATIVE = CasebookHoldoutCurrentPipelineTrial.RUNTIME_RELATIVE;
    private static final String DISCOVERY = CandidateReviewEngine.Discovery.class.getSimpleName();

    private CasebookReferenceIsolationPreflight() {}

    static record CapturedCall(String type, String system, String input, JsonNode providerBody) {}
    static record Arm(String id, int maxExamples, List<String> selectedIds, List<CapturedCall> calls,
                       int providerCalls,
                       TextReviewEngine.Result syntheticResult) {}

    static final class CaptureClient extends OpenAiClient {
        private final List<CapturedCall> calls = new ArrayList<>();
        private int providerCalls;

        CaptureClient() {
            super(RestClient.builder().build(), new OpenAiProperties("", null, null,
                    "https://api.openai.com/v1", "gpt-6-luna", "none", Duration.ofSeconds(60),
                    new OpenAiProperties.Pricing(.10, .01, .50, null, null)));
        }

        @Override public <T> Optional<T> completeAsJson(String system, String input, Class<T> type) {
            if (type != CandidateReviewEngine.Discovery.class) {
                throw new IllegalStateException("Offline capture accepts discovery only; verification is forbidden");
            }
            try {
                var body = JSON.valueToTree(replayRequestBody(system, input, type));
                calls.add(new CapturedCall(type.getSimpleName(), system, input, body));
                List<String> primaryIds = new ArrayList<>();
                JSON.readTree(input).path("primaryIds").forEach(id -> primaryIds.add(id.asText()));
                var synthetic = new CandidateReviewEngine.Discovery(primaryIds, List.of(), List.of(), false, List.of());
                return Optional.of(type.cast(synthetic));
            } catch (Exception e) {
                throw new IllegalStateException("Unable to capture the engine-built discovery request", e);
            }
        }

        @Override public <T> Optional<T> completeRoutingAsJson(String system, String input, Class<T> type) {
            providerCalls++;
            throw new IllegalStateException("Provider routing is forbidden in offline capture");
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "Pass one new output directory under datasets/controversy/experiments");
        Path experimentRoot = EXPERIMENTS.toRealPath();
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.startsWith(experimentRoot), "New private experiment output required");

        JsonNode plan;
        try (var stream = CasebookReferenceIsolationPreflight.class.getResourceAsStream(PLAN_RESOURCE)) {
            check(stream != null, "Frozen reference-isolation design missing");
            plan = JSON.readTree(stream);
        }
        check(plan.path("status").asText().equals("DESIGN_ONLY_FRESH_APPROVAL_REQUIRED_FOR_MODEL_CALLS"),
                "Frozen design-only plan required");
        String expectedExample = plan.at("/conditions/0/expectedSelection/0").asText();
        check(!expectedExample.isBlank() && plan.at("/conditions/1/guidelineExamplesMaxCases").asInt() == 0,
                "Expected selected-reference and no-example conditions required");

        var holdout = CasebookHoldoutCurrentPipelineTrial.prepare();
        var d05 = holdout.cases().stream().filter(c -> c.caseId().equals("D05-taekwondo-belt-generalization"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Reviewed D05 holdout missing"));
        byte[] reviewBytes = CasebookHoldoutCurrentPipelineTrial.bounded(Path.of(REVIEW_RELATIVE));
        byte[] transcriptBytes = CasebookHoldoutCurrentPipelineTrial.bounded(Path.of(TRANSCRIPT_RELATIVE));
        byte[] runtimeBytes = CasebookHoldoutCurrentPipelineTrial.bounded(Path.of(RUNTIME_RELATIVE));
        String runtimeText = new String(runtimeBytes, StandardCharsets.UTF_8);
        String implementationSha = implementationSha();

        Arm selected = capture("CURRENT_SELECTED_REFERENCE", 3, d05.review(), runtimeText);
        Arm noExamples = capture("GLOBAL_GUIDELINES_WITHOUT_EXAMPLES", 0, d05.review(), runtimeText);
        var comparisons = compare(selected, noExamples, expectedExample);

        var report = JSON.createObjectNode();
        report.put("schemaVersion", "casebook-reference-isolation-offline-preflight-1");
        report.put("state", comparisons.path("allIsolationChecksPassed").asBoolean()
                ? "OFFLINE_REQUEST_CAPTURE_PASS" : "OFFLINE_REQUEST_CAPTURE_GATE_FAILED");
        report.put("providerCalls", selected.providerCalls() + noExamples.providerCalls());
        report.put("syntheticDiscoveryResponse", true);
        report.put("syntheticResponseUse", "REQUEST_CAPTURE_ONLY_NOT_AN_ANALYSIS_OR_DETECTION_RESULT");
        report.put("mediaEgress", false);
        report.put("expectedDecisionIncludedInModelInput", false);
        report.put("fingerprintNormalization", "CANONICAL_JSON_OBJECT_KEYS; NON_JSON_SYSTEM_PREFIX_BYTE_EXACT");
        report.put("sourceReviewSha256", sha(reviewBytes));
        report.put("storedTranscriptSha256", sha(transcriptBytes));
        report.put("runtimeGuidelinesSha256", sha(runtimeBytes));
        report.put("implementationSha256", implementationSha);
        report.put("sourceSegmentCount", d05.sourceSegmentIds().size());
        report.set("conditions", JSON.valueToTree(List.of(auditArm(selected), auditArm(noExamples))));
        report.set("pairedComparison", comparisons);

        Files.createDirectories(output);
        Files.write(output.resolve("preflight.json"), JSON.writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
        System.out.println("REFERENCE_ISOLATION_PREFLIGHT state=" + report.path("state").asText()
                + " providerCalls=0 captured=" + (selected.calls().size() + noExamples.calls().size())
                + " selection=" + selected.selectedIds() + " onlyExamplesDiffer="
                + comparisons.path("onlySelectedExamplesDiffer").asBoolean());
        check(report.path("state").asText().equals("OFFLINE_REQUEST_CAPTURE_PASS"),
                "Offline isolation gate failed; no paid comparison should be run");
    }

    static Arm capture(String id, int maxExamples, ReviewInput review, String runtimeText) {
        ResourceLoader loader = new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                check(location.equals("classpath:frozen.json"), "Only the frozen local runtime is allowed");
                return new ByteArrayResource(runtimeText.getBytes(StandardCharsets.UTF_8));
            }
        };
        var library = new ReviewGuidelineLibrary(loader, true, "classpath:frozen.json", 6000,
                maxExamples, 3000, "", true, true);
        library.load();
        check("READY_WORKING_REFERENCE".equals(library.trace(TimelineEventType.SPEECH).state()),
                "Runtime guideline archive must load in both conditions");

        var client = new CaptureClient();
        var context = new AnalysisContext(Video.builder().filename("private-d05-reference-preflight").build(),
                ContentGenre.GENERAL, List.of(), List.of(), review);
        var result = CandidateReviewEngine.run(client, context,
                new CandidateReviewEngine.RunServices(null, null, library, null, null),
                new CandidateReviewEngine.RunOptions(24, 0, false,
                        new CandidateReviewEngine.VerificationFeatures(false, false, false), false));
        check(client.providerCalls == 0, "Provider call attempted during offline capture");
        check(result.diagnostics() != null && result.diagnostics().candidatePipeline() != null,
                "Candidate-review diagnostics required");
        List<String> selectedIds = result.diagnostics().candidatePipeline().contextSelections().stream()
                .flatMap(trace -> trace.exampleIds().stream()).distinct().toList();
        check(result.status() == AnalyzerStatus.SUCCESS && result.findings().isEmpty()
                        && result.diagnostics().candidatePipeline().proposed() == 0,
                "Synthetic empty discovery should only complete the capture path");
        return new Arm(id, maxExamples, selectedIds, List.copyOf(client.calls), client.providerCalls, result);
    }

    private static ObjectNode compare(Arm selected, Arm noExamples, String expectedExample) throws Exception {
        check(selected.calls().size() == 1 && noExamples.calls().size() == 1,
                "Expected exactly one engine-built discovery request in each arm");
        CapturedCall a = selected.calls().get(0), b = noExamples.calls().get(0);
        check(a.type().equals(DISCOVERY) && b.type().equals(DISCOVERY), "Discovery-only request expected");

        int aMarker = a.system().lastIndexOf("\ncontextReference=");
        int bMarker = b.system().lastIndexOf("\ncontextReference=");
        check(aMarker >= 0 && bMarker >= 0, "Split context-reference payload marker required");
        String prefixA = a.system().substring(0, aMarker);
        String prefixB = b.system().substring(0, bMarker);
        JsonNode payloadA = JSON.readTree(a.system().substring(aMarker + "\ncontextReference=".length()));
        JsonNode payloadB = JSON.readTree(b.system().substring(bMarker + "\ncontextReference=".length()));

        JsonNode bodyA = normalizedBody(a.providerBody());
        JsonNode bodyB = normalizedBody(b.providerBody());
        JsonNode inputA = JSON.readTree(a.input()), inputB = JSON.readTree(b.input());
        boolean selectedMatches = selected.selectedIds().equals(List.of(expectedExample));
        boolean contextsDiffer = !payloadA.path("referenceContexts").equals(payloadB.path("referenceContexts"));
        boolean contextsHaveExpectedCounts = payloadA.path("referenceContexts").isArray()
                && payloadA.path("referenceContexts").size() > 0
                && payloadB.path("referenceContexts").isArray()
                && payloadB.path("referenceContexts").isEmpty();
        boolean labelsAbsent = !inputA.has("expectedDecision") && !inputB.has("expectedDecision")
                && !inputA.has("caseId") && !inputB.has("caseId");
        boolean onlyExamplesDiffer = prefixA.equals(prefixB)
                && payloadA.path("reviewGuidelines").equals(payloadB.path("reviewGuidelines"))
                && inputA.equals(inputB)
                && bodyA.equals(bodyB)
                && contextsDiffer && contextsHaveExpectedCounts;

        var result = JSON.createObjectNode();
        result.put("sameUserInput", inputA.equals(inputB));
        result.put("sameSharedSystemPromptPrefix", prefixA.equals(prefixB));
        result.put("sameGlobalGuidelines", payloadA.path("reviewGuidelines").equals(payloadB.path("reviewGuidelines")));
        result.put("sameProviderRequestConfigurationOutsideSystemPrompt", bodyA.equals(bodyB));
        result.put("selectedContextPayloadsDiffer", contextsDiffer);
        result.put("selectedContextCountsAsDesigned", contextsHaveExpectedCounts);
        result.put("selectedExampleIdsMatchFrozenDesign", selectedMatches);
        result.put("expectedDecisionAbsentFromModelInput", labelsAbsent);
        result.put("onlySelectedExamplesDiffer", onlyExamplesDiffer);
        result.put("allIsolationChecksPassed", onlyExamplesDiffer && selectedMatches && labelsAbsent);
        result.put("selectedContextPayloadSha256", sha(JSON.writeValueAsBytes(payloadA.path("referenceContexts"))));
        result.put("noExampleContextPayloadSha256", sha(JSON.writeValueAsBytes(payloadB.path("referenceContexts"))));
        return result;
    }

    private static JsonNode normalizedBody(JsonNode source) {
        ObjectNode copy = (ObjectNode) source.deepCopy();
        var messages = copy.withArray("messages");
        check(messages.size() >= 2 && "system".equals(messages.get(0).path("role").asText()),
                "Expected standard system/user chat request");
        ((ObjectNode) messages.get(0)).put("content", "<SYSTEM_PROMPT_REMOVED_FOR_FACTOR_COMPARISON>");
        return copy;
    }

    private static Map<String, Object> auditArm(Arm arm) throws Exception {
        var calls = new ArrayList<Map<String, Object>>();
        for (CapturedCall call : arm.calls()) {
            int marker = call.system().lastIndexOf("\ncontextReference=");
            JsonNode payload = JSON.readTree(call.system().substring(marker + "\ncontextReference=".length()));
            String prefix = call.system().substring(0, marker);
            var row = new LinkedHashMap<String, Object>();
            row.put("type", call.type());
            row.put("userInputSha256", canonicalInputSha(call.input()));
            row.put("systemPromptSha256", canonicalSystemSha(call.system()));
            row.put("sharedSystemPromptPrefixSha256", sha(prefix.getBytes(StandardCharsets.UTF_8)));
            row.put("globalGuidelinesSha256", sha(JSON.writeValueAsBytes(payload.path("reviewGuidelines"))));
            row.put("selectedContextPayloadSha256", sha(JSON.writeValueAsBytes(payload.path("referenceContexts"))));
            row.put("normalizedProviderBodySha256", sha(JSON.writeValueAsBytes(normalizedBody(call.providerBody()))));
            row.put("selectedContextCount", payload.path("referenceContexts").size());
            calls.add(row);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("condition", arm.id());
        result.put("guidelineExamplesMaxCases", arm.maxExamples());
        result.put("selectedExampleIds", arm.selectedIds());
        result.put("capturedCalls", calls);
        result.put("syntheticDiscoveryOnly", arm.syntheticResult().diagnostics().candidatePipeline().proposed() == 0);
        return result;
    }

    private static String implementationSha() throws Exception {
        var files = List.of(
                "src/main/java/com/example/oops/analyzer/CandidateReviewEngine.java",
                "src/main/java/com/example/oops/analyzer/CandidateReviewRequestFactory.java",
                "src/main/java/com/example/oops/analyzer/ReviewGuidelineLibrary.java",
                "src/main/java/com/example/oops/analyzer/DialogueReadingPage.java",
                "src/main/java/com/example/oops/analyzer/ContextExampleSelector.java",
                "src/main/java/com/example/oops/analyzer/ContextBoundarySupplement.java",
                "src/test/java/com/example/oops/analyzer/CasebookReferenceIsolationPreflight.java",
                "src/test/java/com/example/oops/analyzer/CasebookReferenceIsolationPreflightTest.java",
                "src/test/resources/evals/casebook-reference-isolation-plan.json",
                "build.gradle");
        var hashes = new TreeMap<String, String>();
        for (String file : files) hashes.put(file, sha(Files.readAllBytes(Path.of(file))));
        return sha(JSON.writeValueAsBytes(hashes));
    }

    static String canonicalInputSha(String input) throws Exception {
        return sha(JSON.writeValueAsBytes(canonical(JSON.readTree(input))));
    }

    static String canonicalSystemSha(String system) throws Exception {
        String marker = "\ncontextReference=";
        int position = system.lastIndexOf(marker);
        check(position >= 0, "Context-reference suffix required for canonical prompt fingerprint");
        String prefix = system.substring(0, position);
        JsonNode payload = JSON.readTree(system.substring(position + marker.length()));
        String canonicalPayload = JSON.writeValueAsString(canonical(payload));
        return sha((prefix + marker + canonicalPayload).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = JSON.createObjectNode();
            var names = new ArrayList<String>();
            node.properties().forEach(entry -> names.add(entry.getKey()));
            Collections.sort(names);
            for (String name : names) result.set(name, canonical(node.get(name)));
            return result;
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node.deepCopy();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
