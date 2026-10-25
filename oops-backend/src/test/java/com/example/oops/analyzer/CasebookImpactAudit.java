package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Offline before/after audit using the production selector. Never calls a model/provider. */
public final class CasebookImpactAudit {
    private CasebookImpactAudit() {}

    public static Map<String, Object> audit(Path requestPath, Path baselinePath, Path candidatePath) throws Exception {
        var json = ReviewGuidelineLibrary.JSON;
        var requestBytes = read(requestPath);
        var request = json.readTree(requestBytes);
        var baselineBytes = read(baselinePath);
        var candidateBytes = read(candidatePath);
        String baselineSha = sha(baselineBytes), candidateSha = sha(candidateBytes);
        String planSha = request.path("planSha256").asText();
        String evaluationFingerprint = request.path("evaluationFingerprint").asText();
        if (!"casebook-impact-request-1".equals(request.path("schemaVersion").asText())
                || !planSha.matches("[0-9a-f]{64}") || !evaluationFingerprint.matches("[0-9a-f]{64}")
                || !baselineSha.equals(request.path("intake").path("baselineRuntimeSha256").asText())
                || !candidateSha.equals(request.path("intake").path("runtimeSha256").asText())
                || !request.path("evaluation").isArray() || request.path("evaluation").isEmpty()
                || request.path("evaluation").size() > 200
                || request.path("evaluation").size() != request.path("evaluationCaseCount").asInt(-1)
                || !request.path("policyImplementationHashes").isObject())
            throw new IllegalArgumentException("INVALID_IMPACT_REQUEST");
        requireRuntimeV7(baselineBytes);
        requireRuntimeV7(candidateBytes);
        var baseline = library(baselinePath);
        var candidate = library(candidatePath);
        var baselineMechanisms = mechanismMap(baselineBytes);
        var candidateMechanisms = mechanismMap(candidateBytes);
        var baselineReferenceFingerprints = referenceFingerprints(baselineBytes);
        var candidateReferenceFingerprints = referenceFingerprints(candidateBytes);
        Set<String> caseIds = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, int[]> groupCounts = new TreeMap<>();
        Map<String, int[]> evaluationGroupCounts = new TreeMap<>();
        Map<String, int[]> mechanismCounts = new TreeMap<>();
        int missingGroups = 0, missingEvaluationGroups = 0, missingAnyHoldoutGroup = 0, changed = 0;
        for (var item : request.path("evaluation")) {
            String caseId = item.path("caseId").asText();
            String sourceGroup = item.path("sourceGroupId").isTextual() ? item.path("sourceGroupId").asText() : null;
            String evaluationGroup = item.path("evaluationGroupId").isTextual() ? item.path("evaluationGroupId").asText() : null;
            if (!caseId.matches("[A-Za-z0-9_-]{1,100}") || !caseIds.add(caseId)
                    || sourceGroup != null && !sourceGroup.matches("[A-Za-z0-9_-]{1,100}")
                    || evaluationGroup != null && !evaluationGroup.matches("[A-Za-z0-9_-]{1,100}")
                    || !item.path("raw").isArray() || item.path("raw").isEmpty() || item.path("raw").size() > 64)
                throw new IllegalArgumentException("INVALID_EVALUATION_CASE");
            var raw = segments(item.path("raw"));
            String anchorId = item.path("anchorId").asText();
            var focus = raw.stream().filter(s -> s.id().equals(anchorId)).toList();
            if (focus.size() != 1) throw new IllegalArgumentException("INVALID_EVALUATION_ANCHOR");
            var heldOutGroups = new TreeSet<String>();
            if (sourceGroup != null) heldOutGroups.add(sourceGroup);
            if (evaluationGroup != null) heldOutGroups.add(evaluationGroup);
            var before = baseline.select(TimelineEventType.SPEECH, raw, "impact-before-" + caseId,
                    focus, Set.of(), ContextRelationRetrieval.Query.empty(), true, heldOutGroups).trace();
            var after = candidate.select(TimelineEventType.SPEECH, raw, "impact-after-" + caseId,
                    focus, Set.of(), ContextRelationRetrieval.Query.empty(), true, heldOutGroups).trace();
            var added = new ArrayList<>(after.exampleIds()); added.removeAll(before.exampleIds());
            var removed = new ArrayList<>(before.exampleIds()); removed.removeAll(after.exampleIds());
            boolean rankChanged = rankingChanged(before.exampleIds(), after.exampleIds());
            boolean referenceContentChanged = referenceContentChanged(before.exampleIds(), baselineReferenceFingerprints,
                    after.exampleIds(), candidateReferenceFingerprints);
            String referenceFingerprintBefore = selectedFingerprint(before.exampleIds(), baselineReferenceFingerprints);
            String referenceFingerprintAfter = selectedFingerprint(after.exampleIds(), candidateReferenceFingerprints);
            var addedReferenceMechanisms = mechanismsFor(added, candidateMechanisms);
            var removedReferenceMechanisms = mechanismsFor(removed, baselineMechanisms);
            var beforeMechanisms = new TreeSet<>(before.mechanismIds());
            var afterMechanisms = new TreeSet<>(after.mechanismIds());
            var addedMechanisms = new TreeSet<>(afterMechanisms); addedMechanisms.removeAll(beforeMechanisms);
            var removedMechanisms = new TreeSet<>(beforeMechanisms); removedMechanisms.removeAll(afterMechanisms);
            boolean didChange = !added.isEmpty() || !removed.isEmpty() || rankChanged || referenceContentChanged;
            if (didChange) changed++;
            int excludedBefore = countSourceGroup(baselineBytes, sourceGroup);
            int excludedAfter = countSourceGroup(candidateBytes, sourceGroup);
            int excludedEvalBefore = countFamily(baselineBytes, evaluationGroup);
            int excludedEvalAfter = countFamily(candidateBytes, evaluationGroup);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", caseId); row.put("sourceGroupId", sourceGroup); row.put("evaluationGroupId", evaluationGroup);
            row.put("sourceGroupIndependence", sourceGroup == null ? "UNKNOWN_NOT_EXCLUDED" : "EXCLUDED_FROM_RETRIEVAL");
            row.put("evaluationGroupExclusion", evaluationGroup == null ? "NOT_AVAILABLE"
                    : "FAMILY_ID_EXCLUDED_WHEN_MATCHED");
            row.put("sameSourceGroupExamplesExcludedBefore", excludedBefore);
            row.put("sameSourceGroupExamplesExcludedAfter", excludedAfter);
            row.put("sameEvaluationGroupExamplesExcludedBefore", excludedEvalBefore);
            row.put("sameEvaluationGroupExamplesExcludedAfter", excludedEvalAfter);
            row.put("selectedBefore", before.exampleIds()); row.put("selectedAfter", after.exampleIds());
            row.put("added", added); row.put("removed", removed);
            row.put("rankChanged", rankChanged);
            row.put("referenceContentChanged", referenceContentChanged);
            row.put("selectedReferenceFingerprintBefore", referenceFingerprintBefore);
            row.put("selectedReferenceFingerprintAfter", referenceFingerprintAfter);
            row.put("selectedMechanismsBefore", before.mechanismIds());
            row.put("selectedMechanismsAfter", after.mechanismIds());
            row.put("addedMechanisms", List.copyOf(addedMechanisms));
            row.put("removedMechanisms", List.copyOf(removedMechanisms));
            row.put("addedReferenceMechanisms", addedReferenceMechanisms);
            row.put("removedReferenceMechanisms", removedReferenceMechanisms);
            row.put("payloadCodePointsBefore", before.payloadCodePoints());
            row.put("payloadCodePointsAfter", after.payloadCodePoints());
            row.put("changed", didChange);
            rows.add(row);
            int[] counts = groupCounts.computeIfAbsent(sourceGroup == null ? "UNASSIGNED" : sourceGroup, ignored -> new int[2]);
            counts[0]++; if (didChange) counts[1]++;
            int[] evalCounts = evaluationGroupCounts.computeIfAbsent(
                    evaluationGroup == null ? "UNASSIGNED" : evaluationGroup, ignored -> new int[2]);
            evalCounts[0]++; if (didChange) evalCounts[1]++;
            if (sourceGroup == null) missingGroups++;
            if (evaluationGroup == null) missingEvaluationGroups++;
            if (sourceGroup == null && evaluationGroup == null) missingAnyHoldoutGroup++;
            for (String mechanism : union(List.copyOf(addedMechanisms), List.copyOf(removedMechanisms),
                    addedReferenceMechanisms, removedReferenceMechanisms)) {
                int[] mechanismCount = mechanismCounts.computeIfAbsent(mechanism, ignored -> new int[5]);
                mechanismCount[0]++;
                if (addedMechanisms.contains(mechanism)) mechanismCount[1]++;
                if (removedMechanisms.contains(mechanism)) mechanismCount[2]++;
                if (addedReferenceMechanisms.contains(mechanism)) mechanismCount[3]++;
                if (removedReferenceMechanisms.contains(mechanism)) mechanismCount[4]++;
            }
        }

        JsonNode intake = request.path("intake");
        if (!"casebook-intake-query-1".equals(intake.path("schemaVersion").asText())
                || !planSha.equals(intake.path("planSha256").asText())
                || !candidateSha.equals(intake.path("runtimeSha256").asText()))
            throw new IllegalArgumentException("INVALID_INTAKE_QUERY");
        var intakeRaw = segments(intake.path("raw"));
        var intakeFocus = new ArrayList<ReviewInput.Segment>();
        for (var id : intake.path("focusIds")) {
            if (!id.isTextual()) throw new IllegalArgumentException("INVALID_INTAKE_FOCUS");
            intakeFocus.add(intakeRaw.stream().filter(s -> s.id().equals(id.asText())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("INTAKE_FOCUS_OUTSIDE_RAW")));
        }
        if (intakeFocus.isEmpty()) throw new IllegalArgumentException("INTAKE_FOCUS_REQUIRED");
        var intakeSelection = candidate.select(TimelineEventType.SPEECH, intakeRaw, "intake-self-retrieval",
                intakeFocus).trace();
        String unitId = intake.path("unitId").asText();
        boolean selfRetrieved = intakeSelection.exampleIds().contains(unitId);
        List<String> intakeMechanisms = mechanismsFor(List.of(unitId), candidateMechanisms);

        Map<String, Object> groupSummary = new TreeMap<>();
        groupCounts.forEach((group, counts) -> groupSummary.put(group,
                Map.of("caseCount", counts[0], "changedQueryCount", counts[1])));
        Map<String, Object> evaluationGroupSummary = new TreeMap<>();
        evaluationGroupCounts.forEach((group, counts) -> evaluationGroupSummary.put(group,
                Map.of("caseCount", counts[0], "changedQueryCount", counts[1])));
        Map<String, Object> mechanismSummary = new TreeMap<>();
        mechanismCounts.forEach((mechanism, counts) -> mechanismSummary.put(mechanism,
                Map.of("affectedQueryCount", counts[0], "queriesWithAddedSelectedMechanism", counts[1],
                        "queriesWithRemovedSelectedMechanism", counts[2], "queriesWithAddedReferences", counts[3],
                        "queriesWithRemovedReferences", counts[4])));
        Map<String, Object> relatedMechanismImpact = new TreeMap<>();
        for (String mechanism : intakeMechanisms)
            relatedMechanismImpact.put(mechanism, mechanismSummary.getOrDefault(mechanism,
                    Map.of("affectedQueryCount", 0, "queriesWithAddedSelectedMechanism", 0,
                            "queriesWithRemovedSelectedMechanism", 0, "queriesWithAddedReferences", 0,
                            "queriesWithRemovedReferences", 0)));
        String policyFingerprint = sha(json.writeValueAsBytes(request.path("policyImplementationHashes")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "casebook-impact-report-4");
        result.put("planSha256", planSha); result.put("evaluationFingerprint", evaluationFingerprint);
        result.put("baselineRuntimeSha256", baselineSha); result.put("candidateRuntimeSha256", candidateSha);
        result.put("policyImplementationFingerprint", policyFingerprint);
        result.put("policyHashesPinned", true); result.put("providerCalls", 0);
        result.put("modelAccuracyMeasured", false); result.put("judgmentPolicyExecuted", false);
        result.put("evaluationCaseCount", rows.size()); result.put("changedQueryCount", changed);
        result.put("casesWithoutSourceGroup", missingGroups);
        result.put("casesWithoutEvaluationGroup", missingEvaluationGroups);
        result.put("casesWithoutAnyHoldoutGroup", missingAnyHoldoutGroup);
        result.put("sourceGroupSummary", groupSummary); result.put("evaluationGroupSummary", evaluationGroupSummary);
        result.put("mechanismSelectionImpact", mechanismSummary);
        result.put("intakeRelatedMechanismImpact", relatedMechanismImpact);
        result.put("cases", rows);
        result.put("intakeUnitId", unitId); result.put("intakeRetrieved", selfRetrieved);
        result.put("intakeMechanismIds", intakeMechanisms);
        result.put("intakeSelectedIds", intakeSelection.exampleIds());
        result.put("intakePayloadCodePoints", intakeSelection.payloadCodePoints());
        result.put("retrievalOnlyImpact", true);
        result.put("semanticQualityConclusion", "NOT_MEASURED");
        return result;
    }

    private static Map<String, List<String>> mechanismMap(byte[] runtime) throws Exception {
        Map<String, List<String>> result = new HashMap<>();
        for (var example : ReviewGuidelineLibrary.JSON.readTree(runtime).path("examples")) {
            List<String> mechanisms = new ArrayList<>();
            var ids = example.path("mechanismIds");
            if (ids.isArray()) for (var id : ids) if (id.isTextual()) mechanisms.add(id.asText());
            result.put(example.path("id").asText(), List.copyOf(mechanisms));
        }
        return result;
    }

    private static Map<String, String> referenceFingerprints(byte[] runtime) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (var example : ReviewGuidelineLibrary.JSON.readTree(runtime).path("examples")) {
            String id = example.path("id").asText();
            String fingerprint = sha(ReviewGuidelineLibrary.JSON.writeValueAsBytes(canonical(example)));
            if (id.isBlank() || result.putIfAbsent(id, fingerprint) != null)
                throw new IllegalArgumentException("INVALID_RUNTIME_EXAMPLE_ID");
        }
        return result;
    }

    /** Canonicalize object-key order so formatting alone cannot appear as a content change. */
    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> ordered = new ArrayList<>(); node.forEach(value -> ordered.add(canonical(value)));
            return ordered;
        }
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.numberValue();
        return null;
    }

    private static String selectedFingerprint(List<String> ids, Map<String, String> fingerprints) throws Exception {
        List<Map<String, String>> selected = new ArrayList<>();
        for (String id : ids) {
            String fingerprint = fingerprints.get(id);
            if (fingerprint == null) throw new IllegalArgumentException("SELECTED_REFERENCE_MISSING");
            selected.add(Map.of("id", id, "sha256", fingerprint));
        }
        return sha(ReviewGuidelineLibrary.JSON.writeValueAsBytes(selected));
    }

    static boolean rankingChanged(List<String> before, List<String> after) {
        Set<String> beforeSet = new HashSet<>(before), afterSet = new HashSet<>(after);
        return !before.stream().filter(afterSet::contains).toList()
                .equals(after.stream().filter(beforeSet::contains).toList());
    }

    static boolean referenceContentChanged(List<String> beforeIds, Map<String, String> before,
                                           List<String> afterIds, Map<String, String> after) {
        Set<String> afterSet = new HashSet<>(afterIds);
        return beforeIds.stream().filter(afterSet::contains)
                .anyMatch(id -> !Objects.equals(before.get(id), after.get(id)));
    }

    private static List<String> mechanismsFor(List<String> exampleIds, Map<String, List<String>> mechanismMap) {
        var result = new TreeSet<String>();
        for (String id : exampleIds) result.addAll(mechanismMap.getOrDefault(id, List.of()));
        return List.copyOf(result);
    }

    private static Set<String> union(List<String> first, List<String> second, List<String> third, List<String> fourth) {
        var result = new TreeSet<>(first);
        result.addAll(second);
        result.addAll(third);
        result.addAll(fourth);
        return result;
    }

    private static ReviewGuidelineLibrary library(Path runtime) {
        var result = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                runtime.toAbsolutePath().toUri().toString(), 6000, 3, 3000, "", true);
        result.load();
        if (!"READY_WORKING_REFERENCE".equals(result.trace(TimelineEventType.SPEECH).state()))
            throw new IllegalArgumentException("RUNTIME_NOT_READY");
        return result;
    }

    private static void requireRuntimeV7(byte[] bytes) throws Exception {
        var tree = ReviewGuidelineLibrary.JSON.readTree(bytes);
        if (!"review-guidelines-7".equals(tree.path("schemaVersion").asText()))
            throw new IllegalArgumentException("V7_REQUIRED");
    }

    private static int countSourceGroup(byte[] runtime, String group) throws Exception {
        if (group == null) return 0;
        int count = 0;
        for (var example : ReviewGuidelineLibrary.JSON.readTree(runtime).path("examples"))
            if (group.equals(example.path("sourceGroupId").asText(null))) count++;
        return count;
    }

    private static int countFamily(byte[] runtime, String family) throws Exception {
        if (family == null) return 0;
        int count = 0;
        for (var example : ReviewGuidelineLibrary.JSON.readTree(runtime).path("examples"))
            if (family.equals(example.path("familyId").asText(null))) count++;
        return count;
    }

    private static List<ReviewInput.Segment> segments(JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 64) throw new IllegalArgumentException("INVALID_RAW");
        List<ReviewInput.Segment> result = new ArrayList<>(); Set<String> ids = new HashSet<>(); long lastStart = -1;
        for (var row : rows) {
            String id = row.path("id").asText(), text = row.path("text").asText();
            long start = row.path("startMs").asLong(-1), end = row.path("endMs").asLong(-1);
            if (!id.matches("[A-Za-z0-9_-]{1,100}") || !ids.add(id) || text.isBlank()
                    || text.codePointCount(0, text.length()) > 350 || start < lastStart || end < start)
                throw new IllegalArgumentException("INVALID_RAW_SEGMENT");
            result.add(new ReviewInput.Segment(id, TimelineEventType.SPEECH, start, end, text, null));
            lastStart = start;
        }
        return result;
    }

    private static byte[] read(Path path) throws Exception {
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw new IllegalArgumentException("LOCAL_FILE_TOO_LARGE");
            return bytes;
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Request, baseline runtime and candidate runtime required");
        var result = audit(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        System.out.println("CASEBOOK_IMPACT_AUDIT=" + ReviewGuidelineLibrary.JSON.writeValueAsString(result));
        if (!Boolean.TRUE.equals(result.get("intakeRetrieved")))
            throw new IllegalArgumentException("EXPECTED_REFERENCE_NOT_RETRIEVED");
    }
}
