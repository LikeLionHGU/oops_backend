package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline retrieval snapshot replay, gated by source provenance. Results are observations
 * for a pinned runtime hash, not relevance labels or a measure of model judgment/generalization.
 */
class SourceExcerptRetrievalBaselineTest {
    private static final String SOURCE_ID = ResearchCaseAliases.value("sourceExcerptId", "synthetic-source");
    private static final String RESEARCH_RECORD_FILENAME =
            "cross-case-practice-standard-review-20261026.json";
    private static final String PROVENANCE_METADATA_RELATIVE_PATH =
            "source-provenance/" + SOURCE_ID + ".json";

    @Test
    void replaysHashPinnedSourceProjectionAndObservedSelectorResults() throws Exception {
        String runtimePath = System.getenv("OOPS_CASEBOOK_AUDIT_RUNTIME");
        String recordPath = System.getenv("OOPS_CASEBOOK_AUDIT_RECORD");
        if (blank(runtimePath) && blank(recordPath)) {
            Assumptions.assumeTrue(false,
                    "Set both OOPS_CASEBOOK_AUDIT_RUNTIME and OOPS_CASEBOOK_AUDIT_RECORD for this private-data replay");
            return;
        }
        assertThat(runtimePath).as("Both audit inputs must be configured").isNotBlank();
        assertThat(recordPath).as("Both audit inputs must be configured").isNotBlank();
        Assumptions.assumeTrue(sourceUseAuthorized(Path.of(recordPath)),
                "Source use is blocked by provenance policy. Only metadata was read; sourced text was not opened.");
        audit(Path.of(runtimePath), Path.of(recordPath));
    }

    @Test
    void provenancePolicyRequiresSourceBoundPermissionAndMatchingHashes(@TempDir Path tempDir) throws Exception {
        Path researchDirectory = tempDir.resolve("research");
        Path provenanceDirectory = researchDirectory.resolve("source-provenance");
        Path evidencePath = provenanceDirectory.resolve("permissions/permission-proof.json");
        Files.createDirectories(evidencePath.getParent());

        Path recordPath = researchDirectory.resolve(RESEARCH_RECORD_FILENAME);
        byte[] recordBytes = "synthetic test record".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(recordPath, recordBytes);
        byte[] evidenceBytes = "synthetic test permission evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(evidencePath, evidenceBytes);

        Path policyPath = provenanceDirectory.resolve(SOURCE_ID + ".json");
        savePolicy(policyPath, authorizedTestPolicy(sha256(recordBytes), sha256(evidenceBytes)));
        assertThat(sourceUseAuthorized(recordPath)).isTrue();

        Files.writeString(policyPath, Files.readString(policyPath) + " ");
        assertThat(sourceUseAuthorized(recordPath)).as("Policy metadata must match its detached digest").isFalse();

        savePolicy(policyPath, authorizedTestPolicy(sha256(recordBytes), sha256(evidenceBytes)));
        Files.writeString(evidencePath, "tampered evidence");
        assertThat(sourceUseAuthorized(recordPath)).as("Evidence bytes must match the pinned digest").isFalse();

        Files.write(evidencePath, evidenceBytes);
        Files.writeString(recordPath, "changed synthetic record");
        assertThat(sourceUseAuthorized(recordPath)).as("Only the pinned research record may be replayed").isFalse();

        savePolicy(policyPath, authorizedTestPolicy("0".repeat(64), sha256(evidenceBytes)));
        Files.write(recordPath, recordBytes);
        assertThat(sourceUseAuthorized(recordPath)).as("An altered record digest must remain blocked").isFalse();

        savePolicy(policyPath, authorizedTestPolicy(sha256(recordBytes), sha256(evidenceBytes))
                .replace("\"blockedUses\":[]", "\"blockedUses\":[\"RETRIEVAL\"]"));
        assertThat(sourceUseAuthorized(recordPath)).as("An explicit blocked-use entry overrides an allow entry").isFalse();

        savePolicy(policyPath, authorizedTestPolicy(sha256(recordBytes), sha256(evidenceBytes))
                .replace("permissions/permission-proof.json", "../outside-proof.json"));
        assertThat(sourceUseAuthorized(recordPath)).as("Evidence paths cannot escape the provenance directory").isFalse();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected paths: <runtime.json> <source-audit-record.json>");
        }
        audit(Path.of(args[0]), Path.of(args[1]));
    }

    private static void audit(Path runtimePath, Path recordPath) throws Exception {
        runtimePath = runtimePath.toAbsolutePath().normalize();
        recordPath = recordPath.toAbsolutePath().normalize();
        if (!sourceUseAuthorized(recordPath)) {
            throw new AssertionError("Source use is blocked by provenance policy; research content was not opened");
        }
        if (!Files.isRegularFile(runtimePath) || !Files.isRegularFile(recordPath)) {
            throw new AssertionError("Configured audit input is missing; check both paths");
        }

        byte[] runtimeBytes = Files.readAllBytes(runtimePath);
        byte[] recordBytes = Files.readAllBytes(recordPath);
        Path recordDigestPath = recordPath.resolveSibling(recordPath.getFileName() + ".sha256");
        if (!Files.isRegularFile(recordDigestPath)) {
            throw new AssertionError("Detached audit-record digest is missing; regenerate the reviewed snapshot digest");
        }
        String expectedRecordSha = Files.readString(recordDigestPath).trim();
        assertThat(expectedRecordSha).as("Detached audit record digest").matches("[0-9a-f]{64}");
        assertThat(sha256(recordBytes))
                .as("Research record changed; re-audit it and intentionally refresh its detached digest")
                .isEqualTo(expectedRecordSha);

        JsonNode record = ReviewGuidelineLibrary.JSON.readTree(recordBytes);
        JsonNode impact = record.path("retrievalImpactPreview");
        JsonNode retrievalPreview = record.path("retrievalPreview");
        String expectedRuntimeSha = impact.path("baselineRuntimeSha256").asText("");
        assertThat(expectedRuntimeSha).as("Audit record must pin its input runtime").matches("[0-9a-f]{64}");
        String actualRuntimeSha = sha256(runtimeBytes);
        assertThat(actualRuntimeSha)
                .as("Runtime changed since this observed snapshot; re-audit and update the record intentionally")
                .isEqualTo(expectedRuntimeSha);
        assertThat(retrievalPreview.path("runtimeSha256").asText())
                .as("Both recorded previews must refer to the same runtime snapshot")
                .isEqualTo(actualRuntimeSha);

        Path moduleRoot = locateBackendModule();
        assertPinnedFileHash(moduleRoot.resolve("src/main/java/com/example/oops/analyzer/ContextExampleSelector.java"),
                retrievalPreview.path("selectorSha256").asText(""), "selector source");
        var structuredSearch = StructuredContextSearch.load();
        assertThat(structuredSearch.catalogSha256())
                .as("Hash of the exact search catalog loaded from the runtime classpath")
                .isEqualTo(retrievalPreview.path("structuredSearchCatalogSha256").asText(""));

        var archive = ReviewGuidelineLibrary.JSON.readValue(runtimeBytes, ReviewGuidelineLibrary.Archive.class);
        JsonNode projection = record.path("candidateProjection");
        JsonNode runtimeProjection = projection.path("runtimeBoundary").path("runtimeProjection");
        validateSourceProjection(record, projection, runtimeProjection);

        var baselineSelector = new ContextExampleSelector(archive.examples(), archive.guidelines(), structuredSearch);
        String candidateId = projection.path("caseId").asText("");
        assertThat(candidateId).as("Candidate example ID must come from the research record").isNotBlank();
        var existing = archive.examples().stream().filter(example -> example.id().equals(candidateId))
                .findFirst().orElseThrow(() -> new AssertionError("Pinned runtime example is missing"));
        String candidateText = projection.path("candidateRetrievalIndexText").asText("");
        assertThat(candidateText).isNotBlank();

        var context = new ReviewGuidelineLibrary.Comparison(
                runtimeProjection.path("coverage").asText(),
                strings(runtimeProjection.path("flow")),
                strings(runtimeProjection.path("criticismHypotheses")),
                strings(runtimeProjection.path("missingContext")),
                sourceInterpretations(runtimeProjection.path("sourceInterpretations")),
                strings(runtimeProjection.path("normalContrasts")));
        var retrievalView = new ReviewGuidelineLibrary.RetrievalView(
                projection.path("candidateRetrievalView").path("version").asText(),
                projection.path("candidateRetrievalView").path("basis").asText(),
                candidateText, ReviewGuidelineLibrary.retrievalHash(candidateText));
        var candidateExample = new ReviewGuidelineLibrary.ContextExample(
                existing.id(), existing.familyId(), existing.mechanismIds(), existing.channels(), context,
                retrievalView, existing.routingKeys(), existing.relationProfile(), existing.sourceGroupId());
        var candidateExamples = archive.examples().stream().map(example -> example.id().equals(candidateId)
                ? candidateExample : example).toList();
        assertThat(ReviewGuidelineLibrary.valid(archive))
                .as("Current runtime archive is valid before the research-only ablation")
                .isTrue();
        var candidateWithRuntimeRetrievalView = new ReviewGuidelineLibrary.ContextExample(
                existing.id(), existing.familyId(), existing.mechanismIds(), existing.channels(), context,
                existing.retrievalView(), existing.routingKeys(), existing.relationProfile(), existing.sourceGroupId());
        var contextOnlyCandidateExamples = archive.examples().stream().map(example -> example.id().equals(candidateId)
                ? candidateWithRuntimeRetrievalView : example).toList();
        assertThat(ReviewGuidelineLibrary.valid(archiveWithExamples(archive, contextOnlyCandidateExamples)))
                .as("Existing runtime retrieval contract accepts the projected context fields")
                .isTrue();
        assertThat(ReviewGuidelineLibrary.valid(archiveWithExamples(archive, candidateExamples)))
                .as("Only the offline source-quote retrieval view keeps this preview out of the operational archive")
                .isFalse();
        assertThat(projection.path("runtimeBoundary").path("runtimeRetrievalContractSupported").asBoolean())
                .as("The source-quote candidate is only an offline selector ablation")
                .isFalse();
        String modelContextPayload = ReviewGuidelineLibrary.JSON.writeValueAsString(context);
        for (JsonNode quote : projection.path("sourceReportedQuotes")) {
            assertThat(modelContextPayload).doesNotContain(quote.path("text").asText());
        }
        var candidateSelector = new ContextExampleSelector(candidateExamples, archive.guidelines(), structuredSearch);

        JsonNode queries = impact.path("queries");
        assertThat(queries.isArray()).isTrue();
        assertThat(queries.size()).as("At least one observed query must be present").isPositive();
        Set<String> queryIds = new HashSet<>();
        for (JsonNode query : queries) {
            String id = query.path("id").asText("");
            String queryText = query.path("queryText").asText("");
            assertThat(id).isNotBlank();
            assertThat(queryIds.add(id)).as("Observed query IDs must be unique").isTrue();
            assertThat(queryText).isNotBlank();
            assertThat(query.path("resultKind").asText()).isEqualTo("OBSERVED_RETRIEVAL_NOT_GOLD");
            assertThat(query.path("baseline").isArray()).isTrue();
            assertThat(query.path("candidate").isArray()).isTrue();

            var segment = new ReviewInput.Segment(id, TimelineEventType.SPEECH, 0, 10_000, queryText, null);
            Set<String> excludedFamilies = Set.copyOf(strings(query.path("excludedFamilyIds")));
            List<String> baselineIds = ids(baselineSelector.rank(
                    TimelineEventType.SPEECH, List.of(segment), excludedFamilies));
            List<String> candidateIds = ids(candidateSelector.rank(
                    TimelineEventType.SPEECH, List.of(segment), excludedFamilies));

            assertThat(baselineIds).as(id + " baseline observation for pinned runtime").isEqualTo(strings(query.path("baseline")));
            assertThat(candidateIds).as(id + " candidate observation for pinned runtime").isEqualTo(strings(query.path("candidate")));
            System.out.println(id + " baseline/candidate=" + baselineIds + "/" + candidateIds);
        }
        System.out.println("PASS: hash-pinned offline selector snapshot; no verdict/model quality claim");
    }

    private static void validateSourceProjection(JsonNode record, JsonNode projection, JsonNode runtimeProjection) {
        assertThat(record.path("status").asText()).isEqualTo("DEVELOPMENT_REVIEWED_NOT_RUNTIME");
        assertThat(projection.path("status").asText()).isEqualTo("RESEARCH_CANDIDATE_NOT_RUNTIME");
        JsonNode boundary = projection.path("runtimeBoundary");
        assertThat(boundary.path("modelPayloadContractUnchanged").asBoolean()).isTrue();
        assertThat(boundary.path("runtimeRetrievalContractSupported").asBoolean()).isFalse();
        assertThat(projection.path("promotion").path("runtimeEligible").asBoolean()).isFalse();

        String sourceUrl = null;
        for (JsonNode review : record.path("reviews")) {
            if (review.path("caseId").asText().equals(projection.path("caseId").asText())) {
                sourceUrl = review.path("source").path("url").asText(null);
                break;
            }
        }
        assertThat(sourceUrl).as("Candidate quotes must resolve to the reviewed source record").isNotBlank();
        JsonNode quotes = projection.path("sourceReportedQuotes");
        assertThat(quotes.isArray()).isTrue();
        assertThat(quotes.size()).isPositive();
        List<String> quoteTexts = new ArrayList<>();
        for (JsonNode quote : quotes) {
            assertThat(quote.path("kind").asText()).isEqualTo("SOURCE_REPORTED_QUOTE_NOT_TRANSCRIPT");
            assertThat(quote.path("sourceUrl").asText()).isEqualTo(sourceUrl);
            assertThat(quote.path("videoTimestampMs").isNull()).isTrue();
            assertThat(quote.path("text").asText()).isNotBlank();
            quoteTexts.add(quote.path("text").asText());
        }
        String expectedIndexText = String.join(". ", quoteTexts) + ".";
        assertThat(projection.path("candidateRetrievalIndexText").asText()).isEqualTo(expectedIndexText);
        assertThat(projection.path("candidateRetrievalView").path("version").asText())
                .isEqualTo("offline-source-quote-preview-1");
        assertThat(projection.path("candidateRetrievalView").path("basis").asText())
                .isEqualTo("RESEARCH_ONLY_NOT_RUNTIME_RETRIEVAL_CONTRACT");

        assertThat(runtimeProjection.path("coverage").asText())
                .isEqualTo("REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT");
        assertThat(strings(runtimeProjection.path("flow"))).isNotEmpty();
        assertThat(strings(runtimeProjection.path("criticismHypotheses"))).isNotEmpty();
        assertThat(strings(runtimeProjection.path("missingContext"))).isNotEmpty();
        assertThat(runtimeProjection.path("sourceReportedQuoteIdsIncludedInModelPayload").size()).isZero();
        String serializedProjection = ReviewGuidelineLibrary.JSON.writeValueAsString(runtimeProjection);
        for (JsonNode quote : quotes) assertThat(serializedProjection).doesNotContain(quote.path("text").asText());

        for (JsonNode interpretation : runtimeProjection.path("sourceInterpretations")) {
            assertThat(interpretation.path("statementKind").asText())
                    .isIn("AUTHOR_INTERPRETATION", "REPORTED_AUDIENCE_REACTION");
            assertThat(interpretation.path("excerpt").asText()).isNotBlank();
        }
    }

    private static List<ReviewGuidelineLibrary.SourceInterpretation> sourceInterpretations(JsonNode values) {
        List<ReviewGuidelineLibrary.SourceInterpretation> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(new ReviewGuidelineLibrary.SourceInterpretation(
                    value.path("statementKind").asText(), value.path("excerpt").asText()));
        }
        return result;
    }

    private static ReviewGuidelineLibrary.Archive archiveWithExamples(
            ReviewGuidelineLibrary.Archive archive,
            List<ReviewGuidelineLibrary.ContextExample> examples) {
        return new ReviewGuidelineLibrary.Archive(archive.schemaVersion(), archive.version(), archive.status(),
                archive.humanValidated(), archive.usage(), archive.sourceBundleSha256(), archive.refreshOrDeleteBy(),
                archive.sourceCases(), archive.guidelines(), archive.contextDictionarySha256(), examples);
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) result.add(value.asText());
        return result;
    }

    private static List<String> ids(List<ContextExampleSelector.Match> matches) {
        return matches.stream().map(match -> match.example().id()).toList();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static boolean sourceUseAuthorized(Path recordPath) {
        Path normalizedRecordPath = recordPath.toAbsolutePath().normalize();
        Path provenancePath = normalizedRecordPath.getParent().resolve(PROVENANCE_METADATA_RELATIVE_PATH);
        Path policyDigestPath = provenancePath.resolveSibling(provenancePath.getFileName() + ".sha256");
        if (Files.isSymbolicLink(provenancePath) || !Files.isRegularFile(provenancePath)
                || Files.isSymbolicLink(policyDigestPath) || !Files.isRegularFile(policyDigestPath)) return false;
        try {
            byte[] policyBytes = Files.readAllBytes(provenancePath);
            if (!sha256(policyBytes).equals(Files.readString(policyDigestPath).trim())) return false;
            JsonNode policy = ReviewGuidelineLibrary.JSON.readTree(policyBytes);
            if (!isAuthorizedForAiRetrieval(policy)) return false;

            Path provenanceDirectory = provenancePath.getParent().toAbsolutePath().normalize();
            JsonNode evidence = policy.path("permissionEvidence");
            Path evidenceRelativePath = Path.of(evidence.path("evidencePath").asText());
            if (evidenceRelativePath.isAbsolute()) return false;
            Path evidenceFile = provenanceDirectory.resolve(evidenceRelativePath).normalize();
            if (!evidenceFile.startsWith(provenanceDirectory) || Files.isSymbolicLink(evidenceFile)
                    || !Files.isRegularFile(evidenceFile)) return false;
            if (!sha256(Files.readAllBytes(evidenceFile)).equals(evidence.path("evidenceSha256").asText())) return false;

            JsonNode protectedRecord = policy.path("protectedResearchRecord");
            String relativeRecordPath = protectedRecord.path("path").asText();
            if (!RESEARCH_RECORD_FILENAME.equals(relativeRecordPath)
                    || !normalizedRecordPath.equals(normalizedRecordPath.getParent().resolve(relativeRecordPath).normalize())
                    || Files.isSymbolicLink(normalizedRecordPath)
                    || !Files.isRegularFile(normalizedRecordPath)) return false;
            return sha256(Files.readAllBytes(normalizedRecordPath))
                    .equals(protectedRecord.path("sha256").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isAuthorizedForAiRetrieval(JsonNode policy) {
        JsonNode evidence = policy.path("permissionEvidence");
        JsonNode reuseDecision = policy.path("reuseDecision");
        JsonNode retentionDecision = policy.path("retentionDecision");
        String sourceId = policy.path("sourceId").asText();
        boolean retrievalAllowed = false;
        boolean retrievalBlocked = false;
        for (JsonNode allowedUse : policy.path("authorizedUses")) {
            if ("RETRIEVAL".equals(allowedUse.asText())) retrievalAllowed = true;
        }
        for (JsonNode blockedUse : policy.path("blockedUses")) {
            if ("RETRIEVAL".equals(blockedUse.asText())) retrievalBlocked = true;
        }
        return "AUTHORIZED_FOR_AI_PROCESSING".equals(policy.path("rightsReviewStatus").asText())
                && SOURCE_ID.equals(sourceId)
                && retrievalAllowed
                && !retrievalBlocked
                && !policy.path("sourceExcerptsQuarantined").asBoolean(true)
                && "USER_APPROVED_RELEASE_FOR_RETRIEVAL".equals(retentionDecision.path("status").asText())
                && !blank(retentionDecision.path("reviewedBy").asText())
                && !blank(retentionDecision.path("reviewedOn").asText())
                && evidence.path("documented").asBoolean(false)
                && sourceId.equals(evidence.path("sourceId").asText())
                && strings(evidence.path("scope")).contains("RETRIEVAL")
                && !blank(evidence.path("reviewedBy").asText())
                && !blank(evidence.path("reviewedOn").asText())
                && !blank(evidence.path("evidencePath").asText())
                && evidence.path("evidenceSha256").asText().matches("[0-9a-f]{64}")
                && "APPROVED_FOR_AI_RETRIEVAL".equals(reuseDecision.path("status").asText())
                && sourceId.equals(reuseDecision.path("sourceId").asText())
                && !blank(reuseDecision.path("reviewedBy").asText())
                && !blank(reuseDecision.path("reviewedOn").asText())
                && RESEARCH_RECORD_FILENAME.equals(policy.path("protectedResearchRecord").path("path").asText())
                && policy.path("protectedResearchRecord").path("sha256").asText().matches("[0-9a-f]{64}");
    }

    private static String authorizedTestPolicy(String recordSha256, String evidenceSha256) {
        return """
                {
                  "sourceId":"%s",
                  "rightsReviewStatus":"AUTHORIZED_FOR_AI_PROCESSING",
                  "authorizedUses":["RETRIEVAL"],
                  "blockedUses":[],
                  "sourceExcerptsQuarantined":false,
                  "retentionDecision":{"status":"USER_APPROVED_RELEASE_FOR_RETRIEVAL","reviewedBy":"test-reviewer","reviewedOn":"2026-10-27"},
                  "permissionEvidence":{"documented":true,"sourceId":"%s","scope":["RETRIEVAL"],"reviewedBy":"test-reviewer","reviewedOn":"2026-10-27","evidencePath":"permissions/permission-proof.json","evidenceSha256":"%s"},
                  "reuseDecision":{"status":"APPROVED_FOR_AI_RETRIEVAL","sourceId":"%s","reviewedBy":"test-reviewer","reviewedOn":"2026-10-27"},
                  "protectedResearchRecord":{"path":"%s","sha256":"%s"}
                }
                """.formatted(SOURCE_ID, SOURCE_ID, evidenceSha256, SOURCE_ID,
                RESEARCH_RECORD_FILENAME, recordSha256);
    }

    private static void savePolicy(Path policyPath, String policyText) throws Exception {
        Files.writeString(policyPath, policyText);
        Files.writeString(policyPath.resolveSibling(policyPath.getFileName() + ".sha256"),
                sha256(Files.readAllBytes(policyPath)));
    }

    private static void assertPinnedFileHash(Path file, String expectedHash, String label) throws Exception {
        assertThat(expectedHash).as("Audit record must pin " + label).matches("[0-9a-f]{64}");
        if (!Files.isRegularFile(file)) {
            throw new AssertionError("Pinned " + label + " file is missing: " + file);
        }
        assertThat(sha256(Files.readAllBytes(file)))
                .as(label + " changed since this observed snapshot; re-audit and update the record intentionally")
                .isEqualTo(expectedHash);
    }

    private static Path locateBackendModule() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("src/main/java/com/example/oops/analyzer/ContextExampleSelector.java"))) {
                return cursor;
            }
            Path nestedModule = cursor.resolve("oops-backend");
            if (Files.isRegularFile(nestedModule.resolve("src/main/java/com/example/oops/analyzer/ContextExampleSelector.java"))) {
                return nestedModule;
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("Could not locate the oops-backend module from the current working directory");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
