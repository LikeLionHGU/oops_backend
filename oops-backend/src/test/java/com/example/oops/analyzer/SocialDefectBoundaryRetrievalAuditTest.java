package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Development-only retrieval probe for one approved REVIEW case and its user-approved synthetic PASS contrast. */
class SocialDefectBoundaryRetrievalAuditTest {
    private static final String UNIT_ID = "podcast-dev#podcast-social-defect";
    private static final String POSITIVE_CASE_ID = "social-defect-boundary#approved-review-124";
    private static final String NORMAL_CASE_ID = "social-defect-boundary#approved-synthetic-pass";

    @Test
    void auditApprovedReviewAndNormalContrastAgainstCurrentSelector() throws Exception {
        Path root = Path.of("../datasets/controversy");
        var json = ReviewGuidelineLibrary.JSON;
        Path sourcePath = root.resolve("context-sources/podcast-social-defect-20261024.json");
        Path unitPath = root.resolve("context-units/podcast-social-defect.json");
        Path positiveApprovalPath = root.resolve("experiments/release-14-16-foundation-20261025/podcast-social-defect-approval-binding.json");
        Path normalApprovalPath = root.resolve("experiments/social-defect-boundary-review-20261025/human-review.json");
        Path positiveResultPath = root.resolve("experiments/podcast-Duvb0IftlbI-20261024/result-124.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(sourcePath) && Files.exists(unitPath)
                && Files.exists(positiveApprovalPath) && Files.exists(normalApprovalPath) && Files.exists(positiveResultPath),
                "Private casebook evaluation material is not present in this checkout");
        byte[] sourceBytes = Files.readAllBytes(sourcePath);
        byte[] unitBytes = Files.readAllBytes(unitPath);
        JsonNode source = json.readTree(sourceBytes);
        JsonNode unit = json.readTree(unitBytes);
        JsonNode positiveApproval = json.readTree(Files.readAllBytes(positiveApprovalPath));
        JsonNode normalApproval = json.readTree(Files.readAllBytes(normalApprovalPath));
        assertThat(sha256(sourceBytes)).isEqualTo(unit.at("/source/sha256").asText());
        assertThat(sha256(Files.readAllBytes(positiveResultPath)))
                .isEqualTo(positiveApproval.at("/source/sha256").asText());
        assertThat(positiveApproval.path("reviews").get(0).path("decision").asText()).isEqualTo("REVIEW_REQUIRED");
        List<String> positiveEventIds = new ArrayList<>();
        positiveApproval.path("reviews").get(0).path("eventIds")
                .forEach(eventId -> positiveEventIds.add(eventId.asText()));
        assertThat(positiveEventIds).containsExactly("197", "203");
        assertThat(unit.path("id").asText()).isEqualTo(UNIT_ID);
        assertThat(unit.path("developmentDecision").asText()).isEqualTo("REVIEW_REQUIRED");

        Map<String, JsonNode> sourceById = new LinkedHashMap<>();
        source.path("segments").forEach(segment -> sourceById.put(segment.path("id").asText(), segment));
        List<ReviewInput.Segment> positiveRaw = new ArrayList<>();
        unit.path("utteranceIds").forEach(id -> {
            JsonNode segment = sourceById.get(id.asText());
            assertThat(segment).as("source segment %s", id.asText()).isNotNull();
            positiveRaw.add(toSegment(segment));
        });

        JsonNode contrast = unit.path("normalContrasts").get(0);
        assertThat(contrast.path("kind").asText()).isEqualTo("SYNTHETIC_DEVELOPMENT_CONTROL");
        String contrastText = contrast.path("text").asText();
        assertThat(normalApproval.path("casebookUnitId").asText()).isEqualTo(UNIT_ID);
        assertThat(normalApproval.at("/source/sha256").asText()).isEqualTo(sha256(unitBytes));
        assertThat(normalApproval.at("/review/decision").asText()).isEqualTo("PASS");
        assertThat(normalApproval.at("/review/contrastIndex").asInt()).isZero();
        assertThat(normalApproval.at("/review/textSha256").asText()).isEqualTo(sha256(contrastText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        List<ReviewInput.Segment> normalRaw = List.of(new ReviewInput.Segment(
                "synthetic-social-control-1", TimelineEventType.SPEECH, 0, 5000, contrastText, null));

        var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                root.resolve("guidelines/runtime.json").toAbsolutePath().toUri().toString(), 6000, 3, 3000, "", true);
        library.load();
        assertThat(library.trace(TimelineEventType.SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE");

        List<Map<String, Object>> results = new ArrayList<>();
        results.add(auditOne(library, POSITIVE_CASE_ID, "REVIEW_REQUIRED", "USER_SUPPLIED_SCRIPT_NOT_AUDIO_VERIFIED",
                source.path("sourceGroupId").asText(), true, positiveRaw, contrastText));
        results.add(auditOne(library, NORMAL_CASE_ID, "PASS", "SYNTHETIC_DEVELOPMENT_CONTROL",
                "synthetic-social-defect-control-20261025", false, normalRaw, contrastText));
        assertThat(results).hasSize(2);

        String output = System.getenv("SOCIAL_DEFECT_AUDIT_OUTPUT");
        if (output != null && !output.isBlank()) {
            Path destination = Path.of(output).toAbsolutePath().normalize();
            Path experiments = root.resolve("experiments").toAbsolutePath().normalize();
            assertThat(destination.startsWith(experiments)).isTrue();
            Files.createDirectories(destination.getParent());
            var report = Map.of("schemaVersion", "social-defect-boundary-retrieval-audit-1",
                    "providerCalls", 0, "candidateDiscoveryExecuted", false,
                    "judgmentModelExecuted", false, "runtimeChanged", false,
                    "sourceGroupIndependence", "NOT_ESTABLISHED", "results", results);
            Files.writeString(destination, json.writeValueAsString(report), StandardOpenOption.CREATE_NEW);
        }
    }

    private static ReviewInput.Segment toSegment(JsonNode segment) {
        return new ReviewInput.Segment(segment.path("id").asText(), TimelineEventType.SPEECH,
                segment.path("startMs").asLong(), segment.path("endMs").asLong(),
                segment.path("text").asText(), null);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, Object> auditOne(ReviewGuidelineLibrary library, String caseId,
                                                 String approvedDevelopmentDecision, String sourceKind,
                                                 String sourceGroupId, boolean sameSourceReplay,
                                                 List<ReviewInput.Segment> raw, String approvedNormalContrast) {
        var selected = library.select(TimelineEventType.SPEECH, raw, caseId);
        assertThat(selected.trace().state()).isEqualTo("READY_WORKING_REFERENCE");
        assertThat(selected.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
        assertThat(selected.trace().exampleIds()).hasSizeLessThanOrEqualTo(3);
        assertThat(selected.prompt()).doesNotContain("expectedDecision", "userAnswer", "labelScope");

        var result = new LinkedHashMap<String, Object>();
        result.put("caseId", caseId);
        result.put("approvedDevelopmentDecisionNotSentToModel", approvedDevelopmentDecision);
        result.put("sourceKind", sourceKind);
        result.put("sourceGroupId", sourceGroupId);
        result.put("sameSourceReplay", sameSourceReplay);
        result.put("inputSegmentCount", raw.size());
        result.put("selectedReferenceIds", selected.trace().exampleIds());
        result.put("representativeMechanismRetrieved", selected.trace().exampleIds().contains(UNIT_ID));
        result.put("normalContrastIncludedInSelectedPrompt", selected.prompt().contains(approvedNormalContrast));
        result.put("payloadCodePoints", selected.trace().payloadCodePoints());
        result.put("selectedPromptUtf8Bytes", selected.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        return result;
    }
}
