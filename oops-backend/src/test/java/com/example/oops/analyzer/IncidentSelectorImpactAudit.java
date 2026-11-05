package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Offline incident before/after replay through the production context selector. No provider calls. */
public final class IncidentSelectorImpactAudit {
    private IncidentSelectorImpactAudit() {}

    public static Map<String, Object> audit(Path requestPath, Path baselinePath, Path candidatePath) throws Exception {
        var json = ReviewGuidelineLibrary.JSON;
        JsonNode request = json.readTree(read(requestPath));
        byte[] baselineBytes = read(baselinePath), candidateBytes = read(candidatePath);
        String baselineSha = sha(baselineBytes), candidateSha = sha(candidateBytes);
        String bindingSha = request.path("auditBindingSha256").asText();
        String evalSha = request.path("evaluationFingerprint").asText();
        if (!"incident-selector-impact-request-1".equals(request.path("schemaVersion").asText())
                || !bindingSha.matches("[0-9a-f]{64}") || !evalSha.matches("[0-9a-f]{64}")
                || !baselineSha.equals(request.path("baselineRuntimeSha256").asText())
                || !candidateSha.equals(request.path("candidateRuntimeSha256").asText())
                || !request.path("evaluation").isArray() || request.path("evaluation").isEmpty()
                || request.path("evaluation").size() > 200)
            throw new IllegalArgumentException("INVALID_INCIDENT_IMPACT_REQUEST");
        requireV7(baselineBytes); requireV7(candidateBytes);

        var baseline = library(baselinePath);
        var candidate = library(candidatePath);
        var beforeCases = strings(json.readTree(baselineBytes).path("sourceCases"), "caseId");
        var afterCases = strings(json.readTree(candidateBytes).path("sourceCases"), "caseId");
        var addedCases = difference(afterCases, beforeCases);
        var removedCases = difference(beforeCases, afterCases);
        if (!addedCases.equals(sortedStrings(request.path("sourceCaseIdsAdded")))
                || !removedCases.equals(sortedStrings(request.path("sourceCaseIdsRemoved"))))
            throw new IllegalArgumentException("INCIDENT_CASE_DELTA_MISMATCH");

        String patternBefore = sharedPatternFingerprint(baseline);
        String patternAfter = sharedPatternFingerprint(candidate);
        Map<String, String> beforeReferenceHashes = exampleFingerprints(baselineBytes);
        Map<String, String> afterReferenceHashes = exampleFingerprints(candidateBytes);
        Map<String, List<String>> beforeMechanisms = exampleMechanisms(baselineBytes);
        Map<String, List<String>> afterMechanisms = exampleMechanisms(candidateBytes);

        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, int[]> mechanismCounts = new TreeMap<>();
        int changedQueries = 0, queriesSelectingAdded = 0, selectedAddedCount = 0, displacedCount = 0;
        int missingSourceGroups = 0, missingEvaluationGroups = 0, missingAnyHoldout = 0;
        for (JsonNode item : request.path("evaluation")) {
            String caseId = item.path("caseId").asText();
            String sourceGroup = optionalText(item, "sourceGroupId");
            String evaluationGroup = optionalText(item, "evaluationGroupId");
            if (!caseId.matches("[A-Za-z0-9_-]{1,100}") || !seen.add(caseId)
                    || sourceGroup != null && !sourceGroup.matches("[A-Za-z0-9_-]{1,100}")
                    || evaluationGroup != null && !evaluationGroup.matches("[A-Za-z0-9_-]{1,100}")
                    || !item.path("raw").isArray() || item.path("raw").isEmpty() || item.path("raw").size() > 64)
                throw new IllegalArgumentException("INVALID_INCIDENT_EVALUATION_CASE");
            var raw = segments(item.path("raw"));
            String anchorId = item.path("anchorId").asText();
            var focus = raw.stream().filter(s -> s.id().equals(anchorId)).toList();
            if (focus.size() != 1) throw new IllegalArgumentException("INVALID_INCIDENT_EVALUATION_ANCHOR");
            var holdouts = new TreeSet<String>();
            if (sourceGroup != null) holdouts.add(sourceGroup);
            if (evaluationGroup != null) holdouts.add(evaluationGroup);
            if (sourceGroup == null) missingSourceGroups++;
            if (evaluationGroup == null) missingEvaluationGroups++;
            if (sourceGroup == null && evaluationGroup == null) missingAnyHoldout++;

            // This deliberately uses empty router/relation hints: deterministic offline selector replay,
            // not a simulation of any non-deterministic model-based routing decision.
            var beforeSelection = baseline.select(TimelineEventType.SPEECH, raw, "incident-impact-before-" + caseId,
                    focus, Set.of(), ContextRelationRetrieval.Query.empty(), true, holdouts);
            var afterSelection = candidate.select(TimelineEventType.SPEECH, raw, "incident-impact-after-" + caseId,
                    focus, Set.of(), ContextRelationRetrieval.Query.empty(), true, holdouts);
            var before = beforeSelection.trace(); var after = afterSelection.trace();
            var added = difference(after.exampleIds(), before.exampleIds());
            var removed = difference(before.exampleIds(), after.exampleIds());
            var rankAffected = CasebookImpactAudit.rankAffectedIds(before.exampleIds(), after.exampleIds());
            boolean contentChanged = CasebookImpactAudit.referenceContentChanged(before.exampleIds(), beforeReferenceHashes,
                    after.exampleIds(), afterReferenceHashes);
            var selectedNewCases = added.stream().filter(addedCases::contains).toList();
            boolean promptChanged = !beforeSelection.prompt().equals(afterSelection.prompt());
            boolean changed = promptChanged || !added.isEmpty() || !removed.isEmpty() || !rankAffected.isEmpty() || contentChanged;
            if (changed) changedQueries++;
            if (!selectedNewCases.isEmpty()) queriesSelectingAdded++;
            selectedAddedCount += selectedNewCases.size();
            displacedCount += removed.size();

            var mechanismsBefore = new TreeSet<>(before.mechanismIds());
            var mechanismsAfter = new TreeSet<>(after.mechanismIds());
            var affectedMechanisms = new TreeSet<String>();
            affectedMechanisms.addAll(mechanismsBefore); affectedMechanisms.addAll(mechanismsAfter);
            for (String mechanism : affectedMechanisms) {
                int[] counts = mechanismCounts.computeIfAbsent(mechanism, ignored -> new int[4]);
                counts[0]++;
                if (selectedNewCases.stream().anyMatch(id -> afterMechanisms.getOrDefault(id, List.of()).contains(mechanism))) counts[1]++;
                if (removed.stream().anyMatch(id -> beforeMechanisms.getOrDefault(id, List.of()).contains(mechanism))) counts[2]++;
                if (rankAffected.stream().anyMatch(id -> beforeMechanisms.getOrDefault(id, List.of()).contains(mechanism)
                        || afterMechanisms.getOrDefault(id, List.of()).contains(mechanism))) counts[3]++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", caseId); row.put("sourceGroupId", sourceGroup); row.put("evaluationGroupId", evaluationGroup);
            row.put("sourceGroupIndependence", sourceGroup == null ? "UNKNOWN_NOT_EXCLUDED" : "EXCLUDED_FROM_RETRIEVAL");
            row.put("evaluationGroupExclusion", evaluationGroup == null ? "NOT_AVAILABLE" : "EXCLUDED_WHEN_MATCHED");
            row.put("holdoutGroupsExcluded", List.copyOf(holdouts));
            row.put("selectedBefore", before.exampleIds()); row.put("selectedAfter", after.exampleIds());
            row.put("newIncidentReferencesSelected", selectedNewCases);
            row.put("otherReferencesAdded", difference(added, selectedNewCases));
            row.put("referencesRemoved", removed); row.put("rankAffectedReferences", rankAffected);
            row.put("selectedMechanismsBefore", before.mechanismIds()); row.put("selectedMechanismsAfter", after.mechanismIds());
            row.put("selectorPromptSha256Before", sha(beforeSelection.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            row.put("selectorPromptSha256After", sha(afterSelection.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            row.put("selectorPromptCodePointsBefore", codePoints(beforeSelection.prompt()));
            row.put("selectorPromptCodePointsAfter", codePoints(afterSelection.prompt()));
            row.put("contextPayloadCodePointsBefore", before.payloadCodePoints());
            row.put("contextPayloadCodePointsAfter", after.payloadCodePoints());
            row.put("changed", changed);
            rows.add(row);
        }

        Map<String, Object> mechanismSummary = new TreeMap<>();
        mechanismCounts.forEach((id, counts) -> mechanismSummary.put(id, Map.of(
                "affectedQueryCount", counts[0], "queriesSelectingNewIncidentReference", counts[1],
                "queriesWithReferenceDisplacement", counts[2], "queriesWithRankAffectedReferences", counts[3])));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "incident-selector-impact-report-1");
        result.put("auditBindingSha256", bindingSha); result.put("evaluationFingerprint", evalSha);
        result.put("baselineRuntimeSha256", baselineSha); result.put("candidateRuntimeSha256", candidateSha);
        result.put("sourceCaseIdsAdded", addedCases); result.put("sourceCaseIdsRemoved", removedCases);
        result.put("sharedPatternFingerprintBefore", patternBefore); result.put("sharedPatternFingerprintAfter", patternAfter);
        result.put("sharedPatternUnchanged", patternBefore.equals(patternAfter));
        result.put("dynamicRouterHintsApplied", false); result.put("relationHintsApplied", false);
        result.put("selectorReplayMode", "PRODUCTION_SELECTOR_CLASS_DEFAULTS_EMPTY_HINTS");
        result.put("selectorConfiguration", Map.of("guidelineBudgetCodePoints", 6000, "maxExamples", 3,
                "exampleBudgetCodePoints", 3000, "excludedFamilies", List.of(),
                "structuredContextSearchEnabled", baseline.structuredSearchActive() && candidate.structuredSearchActive(),
                "boundarySupplementEnabled", true,
                "environmentOverridesReproduced", false));
        result.put("providerCalls", 0); result.put("modelJudgmentExecuted", false);
        result.put("accuracyMeasured", false); result.put("retrievalImpactOnly", true);
        result.put("evaluationCaseCount", rows.size()); result.put("changedQueryCount", changedQueries);
        result.put("casesWithoutSourceGroup", missingSourceGroups);
        result.put("casesWithoutEvaluationGroup", missingEvaluationGroups);
        result.put("casesWithoutAnyHoldoutGroup", missingAnyHoldout);
        result.put("queriesSelectingNewIncidentReference", queriesSelectingAdded);
        result.put("newIncidentReferenceSelectionCount", selectedAddedCount);
        result.put("removedReferenceCountAcrossQueries", displacedCount);
        result.put("mechanismImpact", mechanismSummary); result.put("cases", rows);
        result.put("semanticQualityConclusion", "NOT_MEASURED");
        return result;
    }

    private static String sharedPatternFingerprint(ReviewGuidelineLibrary library) throws Exception {
        var rows = new ArrayList<Map<String, String>>();
        for (var e : library.examples(TimelineEventType.SPEECH)) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", e.id()); row.put("axis", e.axis()); row.put("condition", e.condition());
            row.put("normalContrast", e.normalContrast()); row.put("requiredEvidence", e.requiredEvidence());
            row.put("missingContext", e.missingContext()); rows.add(row);
        }
        return sha(ReviewGuidelineLibrary.JSON.writeValueAsBytes(rows));
    }

    private static Map<String, String> exampleFingerprints(byte[] runtime) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (var example : ReviewGuidelineLibrary.JSON.readTree(runtime).path("examples")) {
            String id = example.path("id").asText();
            if (id.isBlank() || result.putIfAbsent(id, sha(ReviewGuidelineLibrary.JSON.writeValueAsBytes(canonical(example)))) != null)
                throw new IllegalArgumentException("INVALID_RUNTIME_EXAMPLE_ID");
        }
        return result;
    }

    private static Map<String, List<String>> exampleMechanisms(byte[] runtime) throws Exception {
        Map<String, List<String>> result = new HashMap<>();
        for (var example : ReviewGuidelineLibrary.JSON.readTree(runtime).path("examples")) {
            List<String> ids = new ArrayList<>();
            if (example.path("mechanismIds").isArray()) for (var id : example.path("mechanismIds"))
                if (id.isTextual()) ids.add(id.asText());
            result.put(example.path("id").asText(), List.copyOf(ids));
        }
        return result;
    }

    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) { List<Object> values = new ArrayList<>(); node.forEach(v -> values.add(canonical(v))); return values; }
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.numberValue();
        return null;
    }

    private static ReviewGuidelineLibrary library(Path runtime) {
        var result = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                runtime.toAbsolutePath().toUri().toString(), 6000, 3, 3000, "", true);
        result.load();
        if (!"READY_WORKING_REFERENCE".equals(result.trace(TimelineEventType.SPEECH).state()))
            throw new IllegalArgumentException("RUNTIME_NOT_READY");
        if (result.relationArchive() && !result.structuredSearchActive())
            throw new IllegalArgumentException("STRUCTURED_SEARCH_UNAVAILABLE");
        return result;
    }

    private static void requireV7(byte[] bytes) throws Exception {
        if (!"review-guidelines-7".equals(ReviewGuidelineLibrary.JSON.readTree(bytes).path("schemaVersion").asText()))
            throw new IllegalArgumentException("V7_REQUIRED");
    }

    private static List<String> strings(JsonNode rows, String field) {
        List<String> values = new ArrayList<>();
        if (!rows.isArray()) throw new IllegalArgumentException("RUNTIME_CASES_REQUIRED");
        for (var row : rows) {
            String value = row.path(field).asText();
            if (value.isBlank()) throw new IllegalArgumentException("RUNTIME_CASE_ID_INVALID");
            values.add(value);
        }
        return values;
    }

    private static List<String> sortedStrings(JsonNode rows) {
        if (!rows.isArray()) throw new IllegalArgumentException("CASE_DELTA_REQUIRED");
        List<String> values = new ArrayList<>(); rows.forEach(v -> { if (!v.isTextual()) throw new IllegalArgumentException("CASE_DELTA_INVALID"); values.add(v.asText()); });
        return values;
    }

    private static List<String> difference(List<String> left, List<String> right) {
        Set<String> rightSet = new HashSet<>(right);
        return left.stream().filter(v -> !rightSet.contains(v)).toList();
    }

    private static String optionalText(JsonNode row, String key) {
        return row.path(key).isTextual() ? row.path(key).asText() : null;
    }

    private static List<ReviewInput.Segment> segments(JsonNode rows) {
        List<ReviewInput.Segment> result = new ArrayList<>(); Set<String> ids = new HashSet<>(); long lastStart = -1;
        for (var row : rows) {
            String id = row.path("id").asText(), text = row.path("text").asText();
            long start = row.path("startMs").asLong(-1), end = row.path("endMs").asLong(-1);
            if (!id.matches("[A-Za-z0-9_-]{1,100}") || !ids.add(id) || text.isBlank() || codePoints(text) > 350
                    || start < lastStart || end < start) throw new IllegalArgumentException("INVALID_RAW_SEGMENT");
            result.add(new ReviewInput.Segment(id, TimelineEventType.SPEECH, start, end, text, null)); lastStart = start;
        }
        return result;
    }

    private static int codePoints(String value) { return value.codePointCount(0, value.length()); }
    private static byte[] read(Path path) throws Exception {
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(8_388_609);
            if (bytes.length > 8_388_608) throw new IllegalArgumentException("LOCAL_FILE_TOO_LARGE");
            return bytes;
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Request, baseline runtime and candidate runtime required");
        var result = audit(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        System.out.println("INCIDENT_SELECTOR_IMPACT=" + ReviewGuidelineLibrary.JSON.writeValueAsString(result));
    }
}
