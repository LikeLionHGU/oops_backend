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
    private LlmDecision direct(String target, String mention, String relation, List<LlmEvidence> evidence) {
        return new LlmDecision("stt-index-1", "REVIEW_REQUIRED", null,
                "합성 직접 요청의 대상 연결을 검사합니다.", "SEXUAL", target, .5, null, null, List.of(), evidence,
                "PERSON", relation, "당신은 이 요청을 받은 상대라는 원문 지칭의 풀이입니다.",
                "뜻풀이가 아니라 실제 상대에게 요청하는 대화라는 합성 조건입니다.", mention);
    }

    private List<LlmEvidence> directEvidence() {
        return List.of(new LlmEvidence("stt-index-1", null, "PRIMARY"),
                new LlmEvidence("stt-index-0", null, "TARGET"));
    }

    @Test void directPronounKeepsLiteralTargetAndExplanationWithoutInventingSeparateContext() {
        var f=new CandidateReviewEngineTest();
        var input=f.context("지금 당신이 입은 속옷 색을 알고 싶어요.", "제게 한번 보여 주세요.").reviewInput();
        var proposal=new Proposal("stt-index-1","TARGET_TREATMENT","직접 요청의 대상 확인",
                List.of(new Quote("stt-index-0",null),new Quote("stt-index-1",null)));
        var candidate=new Candidate("c",proposal,input.segments(),false,false);
        var original=direct("질문을 받은 상대","당신","EXPLICIT",directEvidence());
        assertThat(validate(candidate,original,input).failureCode()).isEqualTo("TARGET_MENTION_RELATION");
        var canonical=direct("당신","당신","EXPLICIT",directEvidence());
        var result=validate(candidate,canonical,input);
        assertThat(result.failureCode()).isNull();
        assertThat(canonical.targetReason()).contains("요청을 받은 상대");
        assertThat(original.target()).isEqualTo("질문을 받은 상대"); // No automatic rewriting.
        assertThat(original.decision()).isEqualTo(canonical.decision());
        assertThat(canonical.evidence()).noneMatch(e -> "CONTEXT".equals(e.role()));
    }

    @Test void canonicalFieldNamesDoNotBypassRawGroundingOrMissingTargetRole() {
        var f=new CandidateReviewEngineTest();
        var input=f.context("지금 당신이 입은 속옷 색을 알고 싶어요.","제게 한번 보여 주세요.").reviewInput();
        var candidate=new Candidate("c",new Proposal("stt-index-1","TARGET_TREATMENT","합성 대상 연결",
                List.of(new Quote("stt-index-0",null),new Quote("stt-index-1",null))),input.segments(),false,false);
        assertThat(validate(candidate,direct("다른 사람","다른 사람","EXPLICIT",directEvidence()),input).failureCode())
                .isEqualTo("TARGET_EVIDENCE_REQUIRED");
        assertThat(validate(candidate,direct("당신","당신","EXPLICIT",
                List.of(new LlmEvidence("stt-index-1",null,"PRIMARY"))),input).failureCode())
                .isEqualTo("TARGET_EVIDENCE_REQUIRED");
        assertThat(validate(candidate,direct("질문을 받은 상대","당신","CONTEXTUAL",directEvidence()),input).failureCode())
                .isEqualTo("TARGET_CONTEXT_EVIDENCE_REQUIRED");
    }

    @Test void contextualInterpretationStillRequiresRealSeparateRawEvidence() {
        var d=direct("앞서 소개한 사람","그분","CONTEXTUAL",List.of(
                new LlmEvidence("a","그분을 이야기합니다.","TARGET"),
                new LlmEvidence("b","다음으로 이 사람을 소개합니다.","CONTEXT")));
        assertThat(inspectTarget(d).failureDetail()).isNull();
        var missing=direct(d.target(),d.targetMention(),d.targetRelation(),
                List.of(new LlmEvidence("a","그분을 이야기합니다.","TARGET"),
                        new LlmEvidence("a","그분을 이야기합니다.","CONTEXT")));
        assertThat(inspectTarget(missing).failureDetail()).isEqualTo("TARGET_SEPARATE_CONTEXT_MISSING");
        // Structural acceptance is not a semantic proof that two people are the same person.
    }

    @Test void initialAndRepairInstructionsUseTheSameDirectReferentConvention() {
        for(String prompt:List.of(VERIFICATION_PROMPT,TARGET_REPAIR_PROMPT)) {
            assertThat(prompt).contains("target='당신', targetMention='당신'", "targetReason", "직접 지칭이면");
        }
        assertThat(VERIFICATION_PROMPT).contains("부사·행동어를 사람 지칭어로 쓰거나", "다른 사람을 같은 대상으로 바꾸지 않는다");
        assertThat(TARGET_REPAIR_PROMPT).contains("다른 사람을 연결해 필드를 채우지 않는다");
    }

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
