package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;
import static org.assertj.core.api.Assertions.assertThat;

/** Offline paired request capture for adding one reviewed context case. */
@Tag("dataset-backed")
class D05CasebookAdditionPreflightTest {
    private static final Path DATASET = Path.of("../datasets/controversy");
    private static final Path BASELINE = DATASET.resolve("guidelines/runtime.json");
    private static final Path CANDIDATE = DATASET.resolve(
            "experiments/d05-taekwondo-belt-standard-devaluation-preview-20261026/runtime.json");
    private static final String D05_ID = "podcast-dev#d05-taekwondo-belt-standard-devaluation";

    @Test void onlyApprovedD05ExampleChangesDiscoveryContextForPositiveAndSyntheticNormalControl() throws Exception {
        byte[] baselineBytes = Files.readAllBytes(BASELINE);
        byte[] candidateBytes = Files.readAllBytes(CANDIDATE);
        JsonNode baselineJson = JSON.readTree(baselineBytes);
        JsonNode candidateJson = JSON.readTree(candidateBytes);

        assertThat(baselineJson.path("schemaVersion").asText()).isEqualTo("review-guidelines-7");
        assertThat(candidateJson.path("schemaVersion").asText()).isEqualTo("review-guidelines-7");
        assertThat(baselineJson.path("examples").size()).isEqualTo(64);
        assertThat(candidateJson.path("examples").size()).isEqualTo(65);
        assertExistingRecordsUnchanged(baselineJson.path("examples"), candidateJson.path("examples"), "id");
        assertExistingRecordsUnchanged(baselineJson.path("sourceCases"), candidateJson.path("sourceCases"), "caseId");
        assertOnlyDerivedD05ReferencesChanged(baselineJson, candidateJson);
        assertThat(exampleIds(candidateJson))
                .containsExactlyInAnyOrderElementsOf(
                        concat(exampleIds(baselineJson), List.of(D05_ID)));

        Path reviewPath = DATASET.resolve("research/d05-taekwondo-belt-standard-devaluation-review-20261026.json");
        Path intakePath = DATASET.resolve("research/d05-taekwondo-belt-standard-devaluation-intake-20261026.json");
        JsonNode reviewRecord = JSON.readTree(Files.readAllBytes(reviewPath));
        JsonNode intake = JSON.readTree(Files.readAllBytes(intakePath));
        JsonNode approved = reviewRecord.path("reviews").get(0);
        JsonNode spec = intake;
        assertThat(approved.path("decision").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(approved.path("approvalBasis").asText()).isEqualTo("USER_APPROVED_DEVELOPMENT_BOUNDARY");
        assertThat(approved.path("runtimePromoted").asBoolean()).isFalse();
        assertThat(reviewRecord.path("trainingUseAuthorized").asBoolean()).isFalse();
        assertThat(intake.path("id").asText()).isEqualTo(D05_ID);

        var reviewedCases = CasebookHoldoutCurrentPipelineTrial.prepare();
        var positive = reviewedCases.cases().stream()
                .filter(c -> c.caseId().equals("D05-taekwondo-belt-generalization"))
                .findFirst().orElseThrow().review();
        String normalText = spec.path("normalContrasts").get(0).path("text").asText();
        assertThat(normalText).isNotBlank();
        var normal = new ReviewInput(List.of(new ReviewInput.Segment("synthetic-d05-normal-control",
                TimelineEventType.SPEECH, 0, 1_000, normalText, null)));

        String baselineText = new String(baselineBytes, StandardCharsets.UTF_8);
        String candidateText = new String(candidateBytes, StandardCharsets.UTF_8);
        var rows = new ArrayList<Map<String, Object>>();
        for (var sample : List.of(Map.entry("D05_POSITIVE", positive), Map.entry("D05_SYNTHETIC_NORMAL", normal))) {
            var before = CasebookReferenceIsolationPreflight.capture("BASELINE_" + sample.getKey(), 3,
                    sample.getValue(), baselineText);
            var after = CasebookReferenceIsolationPreflight.capture("CANDIDATE_" + sample.getKey(), 3,
                    sample.getValue(), candidateText);
            var pair = compare(sample.getKey(), before, after);
            if (sample.getKey().equals("D05_POSITIVE")) {
                assertThat(before.selectedIds()).doesNotContain(D05_ID);
                assertThat(after.selectedIds()).contains(D05_ID);
            }
            assertThat(before.providerCalls() + after.providerCalls()).isZero();
            rows.add(pair);
        }

        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", "d05-casebook-addition-preflight-2");
        report.put("state", "OFFLINE_REQUEST_CAPTURE_PASS");
        report.put("baselineRuntimeSha256", sha(baselineBytes));
        report.put("candidateRuntimeSha256", sha(candidateBytes));
        report.put("reviewSha256", sha(Files.readAllBytes(reviewPath)));
        report.put("intakeSha256", sha(Files.readAllBytes(intakePath)));
        report.put("providerCalls", 0);
        report.put("mediaEgress", false);
        report.put("expectedDecisionsIncludedInModelInputs", false);
        report.put("candidateRuntimeDiff", "ONE_D05_CASE_PLUS_DERIVED_INDEX_REFERENCES_NO_POLICY_OR_EXISTING_CASE_EDITS");
        report.put("pairs", rows);
        Path output = DATASET.resolve(
                "experiments/d05-taekwondo-belt-standard-devaluation-preview-20261026/paired-asset-preflight-20261026-v3.json");
        assertThat(rows).allSatisfy(row -> assertThat(row.get("allNonContextInputsEqual")).isEqualTo(true));
        assertThat(rows).filteredOn(row -> row.get("caseId").equals("D05_POSITIVE"))
                .singleElement().satisfies(row -> assertThat(row.get("referenceContextPayloadChanged")).isEqualTo(true));
        assertThat(rows).filteredOn(row -> row.get("caseId").equals("D05_POSITIVE"))
                .singleElement().satisfies(row -> assertThat(row.get("candidateSelectedD05")).isEqualTo(true));
        byte[] reportBytes = JSON.writeValueAsBytes(report);
        if (Files.exists(output)) assertThat(JSON.readTree(Files.readAllBytes(output))).isEqualTo(JSON.readTree(reportBytes));
        else Files.write(output, reportBytes, java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private static Map<String, Object> compare(String caseId, CasebookReferenceIsolationPreflight.Arm before,
                                                CasebookReferenceIsolationPreflight.Arm after) throws Exception {
        check(before.calls().size() == 1 && after.calls().size() == 1, "One discovery request per arm required");
        var oldCall = before.calls().get(0);
        var newCall = after.calls().get(0);
        String marker = "\ncontextReference=";
        int oldMarker = oldCall.system().lastIndexOf(marker), newMarker = newCall.system().lastIndexOf(marker);
        check(oldMarker >= 0 && newMarker >= 0, "Reference payload marker required");
        String oldPrefix = oldCall.system().substring(0, oldMarker);
        String newPrefix = newCall.system().substring(0, newMarker);
        JsonNode oldPayload = JSON.readTree(oldCall.system().substring(oldMarker + marker.length()));
        JsonNode newPayload = JSON.readTree(newCall.system().substring(newMarker + marker.length()));
        check(oldCall.input().equals(newCall.input()), "Paired model inputs must be byte-identical");
        check(oldPrefix.equals(newPrefix), "Shared prompt must remain byte-identical");
        check(oldPayload.path("reviewGuidelines").equals(newPayload.path("reviewGuidelines")),
                "Global guideline content must remain identical");
        check(normalizedBody(oldCall.providerBody()).equals(normalizedBody(newCall.providerBody())),
                "Provider configuration must remain identical");
        boolean contextChanged = !oldPayload.path("referenceContexts").equals(newPayload.path("referenceContexts"));
        return Map.ofEntries(
                Map.entry("caseId", caseId),
                Map.entry("inputSha256", CasebookReferenceIsolationPreflight.canonicalInputSha(oldCall.input())),
                Map.entry("sharedPromptPrefixSha256", sha(oldPrefix.getBytes(StandardCharsets.UTF_8))),
                Map.entry("globalGuidelinesSha256", CasebookReferenceIsolationPreflight.canonicalInputSha(
                        JSON.writeValueAsString(oldPayload.path("reviewGuidelines")))),
                Map.entry("baselineSelectedExampleIds", before.selectedIds()),
                Map.entry("candidateSelectedExampleIds", after.selectedIds()),
                Map.entry("candidateSelectedD05", after.selectedIds().contains(D05_ID)),
                Map.entry("baselineReferenceContextSha256", CasebookReferenceIsolationPreflight.canonicalInputSha(
                        JSON.writeValueAsString(oldPayload.path("referenceContexts")))),
                Map.entry("candidateReferenceContextSha256", CasebookReferenceIsolationPreflight.canonicalInputSha(
                        JSON.writeValueAsString(newPayload.path("referenceContexts")))),
                Map.entry("referenceContextPayloadChanged", contextChanged),
                Map.entry("allNonContextInputsEqual", true),
                Map.entry("providerCalls", before.providerCalls() + after.providerCalls()));
    }

    private static JsonNode normalizedBody(JsonNode source) {
        var copy = (ObjectNode) source.deepCopy();
        var messages = copy.withArray("messages");
        check(messages.size() >= 2, "Provider chat messages required");
        ((ObjectNode) messages.get(0)).put("content", "<SYSTEM_PROMPT_REMOVED_FOR_FACTOR_COMPARISON>");
        return copy;
    }

    private static void assertExistingRecordsUnchanged(JsonNode before, JsonNode after, String idField) {
        var oldById = new TreeMap<String, JsonNode>();
        var newById = new TreeMap<String, JsonNode>();
        for (var row : before) oldById.put(row.path(idField).asText(), row);
        for (var row : after) newById.put(row.path(idField).asText(), row);
        check(newById.keySet().containsAll(oldById.keySet()), "Existing case records must remain present");
        for (var id : oldById.keySet()) check(oldById.get(id).equals(newById.get(id)), "Existing case changed: " + id);
        check(newById.keySet().stream().filter(id -> !oldById.containsKey(id)).toList().equals(List.of(D05_ID)),
                "Only the approved D05 record may be added");
    }

    private static void assertOnlyDerivedD05ReferencesChanged(JsonNode before, JsonNode after) {
        var oldOther = (ObjectNode) before.deepCopy();
        var newOther = (ObjectNode) after.deepCopy();
        for (String key : List.of("examples", "sourceCases", "contextDictionarySha256", "sourceBundleSha256", "guidelines")) {
            oldOther.remove(key); newOther.remove(key);
        }
        check(oldOther.equals(newOther), "Top-level runtime policy/settings changed");
        var oldGuidelines = (tools.jackson.databind.node.ArrayNode) before.path("guidelines").deepCopy();
        var newGuidelines = (tools.jackson.databind.node.ArrayNode) after.path("guidelines").deepCopy();
        Set<String> sourceRefsAdded = new TreeSet<>();
        for (int i = 0; i < oldGuidelines.size(); i++) {
            ObjectNode oldRow = (ObjectNode) oldGuidelines.get(i);
            ObjectNode newRow = (ObjectNode) newGuidelines.get(i);
            var oldRefs = new TreeSet<String>(); oldRow.path("sourceCaseIds").forEach(v -> oldRefs.add(v.asText()));
            var newRefs = new TreeSet<String>(); newRow.path("sourceCaseIds").forEach(v -> newRefs.add(v.asText()));
            oldRow.remove("sourceCaseIds"); newRow.remove("sourceCaseIds");
            check(oldRow.equals(newRow), "Guideline policy content changed");
            check(newRefs.containsAll(oldRefs), "Existing guideline case references were removed");
            for (String id : newRefs) if (!oldRefs.contains(id)) sourceRefsAdded.add(id);
        }
        check(sourceRefsAdded.equals(Set.of(D05_ID)), "Only the D05-derived guideline reference may be added");
    }

    private static List<String> concat(List<String> left, List<String> right) {
        var out = new ArrayList<>(left); out.addAll(right); return out;
    }

    private static List<String> exampleIds(JsonNode runtime) {
        var ids = new ArrayList<String>();
        for (var example : runtime.path("examples")) ids.add(example.path("id").asText());
        return ids;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
