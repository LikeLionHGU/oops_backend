package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class IncidentSelectorImpactAuditTest {
    @Test void unchangedRuntimeProducesOfflineSelectorOnlyReport() throws Exception {
        var archive = new ContextRelationRetrievalTest().archive();
        byte[] runtime = JSON.writeValueAsBytes(archive);
        String runtimeSha = sha(runtime);
        var request = new LinkedHashMap<String, Object>();
        request.put("schemaVersion", "incident-selector-impact-request-1");
        request.put("auditBindingSha256", "b".repeat(64));
        request.put("evaluationFingerprint", "e".repeat(64));
        request.put("baselineRuntimeSha256", runtimeSha);
        request.put("candidateRuntimeSha256", runtimeSha);
        request.put("sourceCaseIdsAdded", List.of());
        request.put("sourceCaseIdsRemoved", List.of());
        request.put("evaluation", List.of(Map.of("caseId", "synthetic-query-1", "anchorId", "line-1",
                "raw", List.of(Map.of("id", "line-1", "startMs", 0, "endMs", 1000,
                        "text", "가게가 없는 상황 설명", "confidence", 1.0)))));

        var dir = Files.createTempDirectory("incident-impact-audit-test-");
        try {
            var requestPath = dir.resolve("request.json");
            var baselinePath = dir.resolve("baseline.json");
            var candidatePath = dir.resolve("candidate.json");
            Files.write(requestPath, JSON.writeValueAsBytes(request));
            Files.write(baselinePath, runtime); Files.write(candidatePath, runtime);

            var report = IncidentSelectorImpactAudit.audit(requestPath, baselinePath, candidatePath);
            assertThat(report).containsEntry("schemaVersion", "incident-selector-impact-report-1")
                    .containsEntry("providerCalls", 0).containsEntry("modelJudgmentExecuted", false)
                    .containsEntry("accuracyMeasured", false).containsEntry("retrievalImpactOnly", true)
                    .containsEntry("dynamicRouterHintsApplied", false).containsEntry("relationHintsApplied", false)
                    .containsEntry("sharedPatternUnchanged", true).containsEntry("changedQueryCount", 0)
                    .containsEntry("sourceCaseIdsAdded", List.of());
            assertThat((List<?>) report.get("cases")).hasSize(1);
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test void refusesARequestWhoseRuntimeBytesDoNotMatchPinnedHashes() throws Exception {
        byte[] runtime = JSON.writeValueAsBytes(new ContextRelationRetrievalTest().archive());
        var request = new LinkedHashMap<String, Object>();
        request.put("schemaVersion", "incident-selector-impact-request-1");
        request.put("auditBindingSha256", "b".repeat(64)); request.put("evaluationFingerprint", "e".repeat(64));
        request.put("baselineRuntimeSha256", "0".repeat(64)); request.put("candidateRuntimeSha256", sha(runtime));
        request.put("sourceCaseIdsAdded", List.of()); request.put("sourceCaseIdsRemoved", List.of());
        request.put("evaluation", List.of(Map.of("caseId", "query", "anchorId", "line",
                "raw", List.of(Map.of("id", "line", "startMs", 0, "endMs", 1, "text", "테스트")))));
        var dir = Files.createTempDirectory("incident-impact-audit-invalid-test-");
        try {
            var requestPath = dir.resolve("request.json"); var baselinePath = dir.resolve("baseline.json");
            var candidatePath = dir.resolve("candidate.json");
            Files.write(requestPath, JSON.writeValueAsBytes(request));
            Files.write(baselinePath, runtime); Files.write(candidatePath, runtime);
            assertThatThrownBy(() -> IncidentSelectorImpactAudit.audit(requestPath, baselinePath, candidatePath))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_INCIDENT_IMPACT_REQUEST");
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test void reportsWhenASelectedReferenceChangesEvenIfCaseIdsStayTheSame() throws Exception {
        var before = new ContextRelationRetrievalTest().archive();
        var examples = new ArrayList<ReviewGuidelineLibrary.ContextExample>();
        for (var example : before.examples()) {
            var context = example.context();
            if (examples.isEmpty()) {
                var flow = new ArrayList<>(context.flow()); flow.add("개발용 변경 내용: 선택 참고 본문이 바뀜");
                context = new ReviewGuidelineLibrary.Comparison(context.coverage(), flow,
                        context.criticismHypotheses(), context.missingContext(), context.sourceInterpretations(),
                        context.normalContrasts());
            }
            examples.add(new ReviewGuidelineLibrary.ContextExample(example.id(), example.familyId(),
                    example.mechanismIds(), example.channels(), context, example.retrievalView(),
                    example.routingKeys(), example.relationProfile(), example.sourceGroupId()));
        }
        var after = new ReviewGuidelineLibrary.Archive(before.schemaVersion(), before.version(), before.status(),
                before.humanValidated(), before.usage(), before.sourceBundleSha256(), before.refreshOrDeleteBy(),
                before.sourceCases(), before.guidelines(), before.contextDictionarySha256(), examples);
        byte[] beforeRaw = JSON.writeValueAsBytes(before), afterRaw = JSON.writeValueAsBytes(after);
        String beforeSha = sha(beforeRaw), afterSha = sha(afterRaw);
        String query = before.examples().get(0).retrievalView().text();
        var request = new LinkedHashMap<String, Object>();
        request.put("schemaVersion", "incident-selector-impact-request-1");
        request.put("auditBindingSha256", "b".repeat(64)); request.put("evaluationFingerprint", "e".repeat(64));
        request.put("baselineRuntimeSha256", beforeSha); request.put("candidateRuntimeSha256", afterSha);
        request.put("sourceCaseIdsAdded", List.of()); request.put("sourceCaseIdsRemoved", List.of());
        request.put("evaluation", List.of(Map.of("caseId", "selected-reference-change", "anchorId", "line-1",
                "raw", List.of(Map.of("id", "line-1", "startMs", 0, "endMs", 1000, "text", query)))));
        var dir = Files.createTempDirectory("incident-impact-audit-change-test-");
        try {
            var requestPath = dir.resolve("request.json"); var beforePath = dir.resolve("before.json");
            var afterPath = dir.resolve("after.json");
            Files.write(requestPath, JSON.writeValueAsBytes(request)); Files.write(beforePath, beforeRaw); Files.write(afterPath, afterRaw);
            var report = IncidentSelectorImpactAudit.audit(requestPath, beforePath, afterPath);
            assertThat(report).containsEntry("sharedPatternUnchanged", true).containsEntry("changedQueryCount", 1);
            var rows = (List<Map<String, Object>>) report.get("cases");
            assertThat(rows).singleElement().satisfies(row -> assertThat(row.get("changed")).isEqualTo(true));
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
