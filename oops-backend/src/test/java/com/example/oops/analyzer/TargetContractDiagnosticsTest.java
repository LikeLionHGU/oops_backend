package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Structural diagnostics, not ground truth or real-model accuracy tests. */
class TargetContractDiagnosticsTest {
    private LlmDecision fields(LlmDecision d, String target, String mention, String reason,
                               String relation, List<LlmEvidence> evidence) {
        return new LlmDecision(d.segmentId(), d.decision(), d.evidenceText(), d.reason(), d.category(),
                target, d.score(), d.context(), d.reading(), d.missingInformation(), evidence,
                d.targetType(), relation, reason, d.alternativeInterpretation(), mention);
    }

    @Test void detailedConditionsRetainHistoricFailureCodesAndNeverAutoCorrectOutput() {
        var f = new CandidateReviewEngineTest(); var input = f.context("그 집 이용자는 수준이 낮아");
        var d = f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집");
        var c = new Candidate("c", f.proposal("stt-index-0", "그 집"), input.reviewInput().segments(), false, false);
        Map<String, LlmDecision> cases = new LinkedHashMap<>();
        cases.put("TARGET_NAME_INVALID", fields(d, "x".repeat(201), "그 집", d.targetReason(), "EXPLICIT", d.evidence()));
        cases.put("TARGET_MENTION_INVALID", fields(d, d.target(), " ", d.targetReason(), "EXPLICIT", d.evidence()));
        cases.put("TARGET_REASON_INVALID", fields(d, d.target(), "그 집", null, "EXPLICIT", d.evidence()));
        cases.put("TARGET_ROLE_MISSING", fields(d, d.target(), "그 집", d.targetReason(), "EXPLICIT",
                d.evidence().stream().filter(e -> !e.role().equals("TARGET")).toList()));
        cases.put("TARGET_MENTION_NOT_IN_TARGET_RAW", fields(d, d.target(), "없는지칭어", d.targetReason(), "CONTEXTUAL", d.evidence()));
        cases.put("TARGET_RELATION_MISMATCH", fields(d, "그 집 이용자", "그 집", d.targetReason(), "EXPLICIT", d.evidence()));
        cases.put("TARGET_SEPARATE_CONTEXT_MISSING", fields(d, "그 집 이용자", "그 집", d.targetReason(), "CONTEXTUAL", d.evidence()));
        cases.forEach((detail, assessment) -> {
            assertThat(inspectTarget(assessment).failureDetail()).as(detail).isEqualTo(detail);
            String legacyCode = switch (detail) {
                case "TARGET_RELATION_MISMATCH" -> "TARGET_MENTION_RELATION";
                case "TARGET_SEPARATE_CONTEXT_MISSING" -> "TARGET_CONTEXT_EVIDENCE_REQUIRED";
                default -> "TARGET_EVIDENCE_REQUIRED";
            };
            assertThat(validate(c, assessment, input.reviewInput()).failureCode()).as(detail).isEqualTo(legacyCode);
            assertThat(validate(c, assessment, input.reviewInput()).observation()).isNull();
        });
        assertThat(validate(c, d, input.reviewInput()).failureCode()).isNull();
        assertThat(inspectTarget(d).explicitMentionProvided()).isFalse(); // Legacy direct target fallback remains supported.
    }

    @Test void initialAndRepairDiagnosticsUseServerFilledRawAndSendOnlyTheFailedCondition() {
        var f = new CandidateReviewEngineTest(); var d = f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집");
        var bad = fields(d, d.target(), "그 집", d.targetReason(), "EXPLICIT",
                List.of(new LlmEvidence("stt-index-0", null, "PRIMARY")));
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1", bad)))))
                .thenAnswer(invocation -> {
                    var request = JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
                    assertThat(request.get("repair").get("failureCode").asText()).isEqualTo("TARGET_EVIDENCE_REQUIRED");
                    assertThat(request.get("repair").get("failureDetail").asText()).isEqualTo("TARGET_ROLE_MISSING");
                    return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",
                            fields(d, d.target(), "그 집", d.targetReason(), "EXPLICIT", List.of(
                                    new LlmEvidence("stt-index-0", null, "PRIMARY"),
                                    new LlmEvidence("stt-index-0", null, "TARGET")))))));
                });
        var result = run(f.client, f.context("그 집 이용자는 수준이 낮아"), 24);
        var counts = result.diagnostics().candidatePipeline().evidenceCounts();
        assertThat(counts).hasSize(2);
        assertThat(counts.get(0).targetContract().failureDetail()).isEqualTo("TARGET_ROLE_MISSING");
        assertThat(counts.get(0).targetContract().targetRoleCount()).isZero();
        assertThat(counts.get(1).targetContract().failureDetail()).isNull();
        assertThat(counts.get(1).targetContract().matchingTargetRoleCount()).isOne();
        assertThat(result.findings()).hasSize(1);
        assertThat(JsonMapper.builder().build().writeValueAsString(counts)).doesNotContain("그 집", d.targetReason());
    }

    @Test void oldEvidenceCountJsonWithoutTargetContractRemainsReadable() {
        var mapper = JsonMapper.builder().build();
        var old = mapper.readValue("{\"candidateId\":\"c\",\"attempt\":\"INITIAL\",\"evidenceCount\":2,"
                + "\"uniqueSegments\":1,\"uniqueSegmentRoles\":2,\"duplicateRoleEntries\":0,"
                + "\"invalidEntries\":0,\"configuredLimit\":0,\"validationFailure\":\"TARGET_EVIDENCE_REQUIRED\"}",
                CandidateReviewDiagnostics.EvidenceCountTrace.class);
        assertThat(old.targetContract()).isNull();
    }

    @Test void targetExamplesFollowTheIdRoleOnlyOutputContract() {
        var examples = VERIFICATION_PROMPT.substring(VERIFICATION_PROMPT.indexOf("# 대상 필드 형식 예시"));
        assertThat(examples).contains("\"role\":\"TARGET\"", "\"targetMention\":\"여기는\"").doesNotContain("\"quote\":");
        assertThat(TARGET_REPAIR_PROMPT).contains("raw.text").doesNotContain("TARGET.quote");
    }
}
