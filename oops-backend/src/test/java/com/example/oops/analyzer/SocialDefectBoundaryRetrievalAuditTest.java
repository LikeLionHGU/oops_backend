package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.TimelineEventType;
import com.example.oops.domain.Video;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        // Exercise the current discovery -> verification -> card projection with deterministic
        // model responses. This proves code/data wiring only; it is not a live model-quality test.
        results.add(auditPipeline(library, "positive-current-pipeline", positiveRaw, true,
                "본인은 이제 갓 20대를 벗어나는 사람이 당신은 왕따예요. [웃음] 너한테 문제가 있는 거야. 당신",
                "stt-index-458"));
        results.add(auditPipeline(library, "normal-current-pipeline", normalRaw, false, contrastText,
                "synthetic-social-control-1"));

        String output = System.getenv("SOCIAL_DEFECT_AUDIT_OUTPUT");
        if (output != null && !output.isBlank()) {
            Path destination = Path.of(output).toAbsolutePath().normalize();
            Path experiments = root.resolve("experiments").toAbsolutePath().normalize();
            assertThat(destination.startsWith(experiments)).isTrue();
            Files.createDirectories(destination.getParent());
            var report = Map.of("schemaVersion", "social-defect-boundary-pipeline-audit-2",
                    "providerCalls", 0, "modelResponses", "DETERMINISTIC_TEST_FIXTURES",
                    "candidatePipelineExecuted", true, "liveModelJudgmentExecuted", false, "runtimeChanged", false,
                    "sourceGroupIndependence", "NOT_ESTABLISHED", "results", results);
            Files.writeString(destination, json.writeValueAsString(report), StandardOpenOption.CREATE_NEW);
        }
    }

    private static Map<String, Object> auditPipeline(ReviewGuidelineLibrary library, String caseId,
                                                      List<ReviewInput.Segment> raw, boolean review,
                                                      String quote, String anchorId) {
        OpenAiClient client = mock(OpenAiClient.class);
        List<String> reviewedIds = raw.stream().map(ReviewInput.Segment::id).toList();
        var proposal = new CandidateReviewEngine.Proposal(anchorId, "TARGET_TREATMENT",
                "친구 관계가 적다는 상황을 개인의 결함으로 연결하는지 확인합니다.",
                List.of(new CandidateReviewEngine.Quote(anchorId, quote)));
        when(client.completeAsJson(anyString(), anyString(), eq(CandidateReviewEngine.Discovery.class)))
                .thenReturn(java.util.Optional.of(new CandidateReviewEngine.Discovery(reviewedIds,
                        List.of(proposal), List.of(), false)));
        var evidence = review
                ? List.of(new TextReviewEngine.LlmEvidence(anchorId, quote, "PRIMARY"),
                        new TextReviewEngine.LlmEvidence(anchorId, "당신", "TARGET"),
                        new TextReviewEngine.LlmEvidence("stt-index-454", raw.get(0).text(), "CONTEXT"))
                : List.of(new TextReviewEngine.LlmEvidence(anchorId, quote, "PRIMARY"));
        var decision = review
                ? new TextReviewEngine.LlmDecision(anchorId, "REVIEW_REQUIRED", quote,
                        "친구가 없다는 상황을 왕따와 개인 문제에 연결해 상대를 낮추는 표현입니다.",
                        "BELITTLEMENT", "당신", .6, null, null, List.of(), evidence,
                        "PERSON", "EXPLICIT", "‘당신’이 발언에서 직접 지칭된 사람입니다.",
                        "친한 사이의 농담일 수 있지만, 현재 입력에는 개인 문제로 단정하는 표현이 남습니다.", "당신")
                : new TextReviewEngine.LlmDecision(anchorId, "PASS", quote,
                        "관계의 빈도와 개인 선호를 설명하는 내용이며 상대의 결함으로 연결하지 않습니다.",
                        null, null, null, null, null, List.of(), evidence,
                        null, null, null, null, null);
        when(client.completeAsJson(anyString(), anyString(), eq(CandidateReviewEngine.VerificationResult.class)))
                .thenReturn(java.util.Optional.of(new CandidateReviewEngine.VerificationResult(List.of(
                        new CandidateReviewEngine.Verification("candidate-1", decision)))));

        var context = new AnalysisContext(Video.builder().filename("private-evaluation-input").build(),
                ContentGenre.GENERAL, List.of(), List.of(), new ReviewInput(raw));
        var result = CandidateReviewEngine.run(client, context, 24, null, null, library);

        ArgumentCaptor<String> discoverySystem = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> discoveryInput = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).completeAsJson(discoverySystem.capture(), discoveryInput.capture(), eq(CandidateReviewEngine.Discovery.class));
        ArgumentCaptor<String> verificationSystem = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> verificationInput = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).completeAsJson(verificationSystem.capture(), verificationInput.capture(), eq(CandidateReviewEngine.VerificationResult.class));
        List<String> requests = new ArrayList<>(discoverySystem.getAllValues());
        requests.addAll(discoveryInput.getAllValues());
        requests.addAll(verificationSystem.getAllValues());
        requests.addAll(verificationInput.getAllValues());
        String referenceMarker = "친구 없음→왕따→네 문제라는 원문 연결";
        if (review) assertThat(requests).anyMatch(request -> request.contains(referenceMarker));
        assertThat(result.status()).isEqualTo(com.example.oops.domain.AnalyzerStatus.SUCCESS);
        assertThat(result.findings()).hasSize(review ? 1 : 0);
        if (review) {
            var finding = result.findings().get(0);
            assertThat(finding.getStartMs()).isEqualTo(2_828_000L);
            assertThat(finding.getReason()).contains("왕따");
        }
        return Map.ofEntries(Map.entry("caseId", caseId),
                Map.entry("pipelineRevision", result.diagnostics().candidatePipeline().revision()),
                Map.entry("mockDiscoveryCalls", discoveryInput.getAllValues().size()),
                Map.entry("mockVerificationCalls", verificationInput.getAllValues().size()),
                Map.entry("selectedReferenceInRequests", requests.stream().anyMatch(request -> request.contains(referenceMarker))),
                Map.entry("providerCalls", 0), Map.entry("responseMode", "DETERMINISTIC_TEST_FIXTURE"),
                Map.entry("retrievedReferenceCarriesHumanReviewRationale", review),
                Map.entry("formalGoldLabelFieldAttached", false), Map.entry("finalFindingCount", result.findings().size()),
                Map.entry("status", result.status().name()), Map.entry("semanticModelQualityMeasured", false));
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
        result.put("approvedDecisionForAuditOnly", approvedDevelopmentDecision);
        result.put("selectedReferenceContainsHumanReviewRationale",
                selected.prompt().contains("사용자 승인 개발 검토 경계"));
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
