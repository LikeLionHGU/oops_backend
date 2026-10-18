package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import java.util.*;
import static com.example.oops.analyzer.FinalReasonPremiseAudit.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** Injected audit findings exercise routing, not the model's ability to discover contradictions. */
class FinalReasonPremiseAuditTest {
    JsonNode source() {
        return JSON.readTree("""
                {"anchorId":"b","raw":[{"id":"a","text":"그 약어의 뜻이 뭔가요?","startMs":0,"endMs":1000},
                  {"id":"b","text":"오늘 기분은 어때라는 뜻입니다.","startMs":1000,"endMs":2000},
                  {"id":"c","text":"제가 보여드릴까요?","startMs":2000,"endMs":3000}]}
                """);
    }
    PremiseCheck finding(String dimension, String status) {
        return new PremiseCheck(dimension, status.equals("NOT_APPLICABLE") ? "" : "상대에게 직접 질문했다",
                status, status.equals("NOT_APPLICABLE") ? List.of() : List.of(new Evidence("a", "그 약어의 뜻이 뭔가요?")),
                "제공 원문과 주장을 대조한 모의 결과");
    }
    Result result(String first) {
        return new Result("b", List.of(finding("ANCHOR_FUNCTION", first),
                finding("ACTION_DIRECTION", "NOT_APPLICABLE"), finding("CROSS_UTTERANCE_LINK", "NOT_APPLICABLE")));
    }

    @Test void recordedContradictionOrMissingSupportRequiresRecheckNeverPass() {
        for (String status : List.of("CONTRADICTED", "NOT_ESTABLISHED")) {
            var routed = route(source(), result(status));
            assertThat(routed.route()).isEqualTo(Route.RECHECK_REQUIRED);
            assertThat(routed.publicationAuthorized()).isFalse();
        }
    }
    @Test void supportedAndInapplicableFindingsDoNotAuthorizePublication() {
        for (String status : List.of("SUPPORTED", "NOT_APPLICABLE")) {
            assertThat(route(source(), result(status)).route()).isEqualTo(Route.NO_RECORDED_CONFLICT);
            assertThat(route(source(), result(status)).publicationAuthorized()).isFalse();
        }
    }
    @Test void missingOrDuplicateDimensionIsIncompleteEvenIfAnotherCheckFoundAConflict() {
        assertThat(route(source(), new Result("b", List.of(finding("ANCHOR_FUNCTION", "CONTRADICTED")))).route())
                .isEqualTo(Route.INCOMPLETE_AUDIT);
        assertThat(route(source(), new Result("b", Collections.nCopies(3, finding("ANCHOR_FUNCTION", "SUPPORTED")))).failureCode())
                .isEqualTo("DIMENSION_INVALID_OR_DUPLICATE");
        assertThat(route(source(), null).route()).isEqualTo(Route.INCOMPLETE_AUDIT);
    }
    @Test void foreignIdsInventedQuotesAndAnchorChangesAreRejected() {
        for (Evidence evidence : List.of(new Evidence("outside", "그 약어"), new Evidence("a", "상대에게 답을 요구했다"))) {
            var bad = new PremiseCheck("ANCHOR_FUNCTION", "주장", "CONTRADICTED", List.of(evidence), "설명");
            var output = new Result("b", List.of(bad, finding("ACTION_DIRECTION", "NOT_APPLICABLE"), finding("CROSS_UTTERANCE_LINK", "NOT_APPLICABLE")));
            assertThat(route(source(), output).failureCode()).isEqualTo("EVIDENCE_OUTSIDE_SOURCE");
        }
        assertThat(route(source(), new Result("outside", result("SUPPORTED").checks())).failureCode()).isEqualTo("ANCHOR_MISMATCH");
    }
    @Test void missingEvidenceAndUnknownStatusesCannotBecomeNoConflict() {
        var checks = new ArrayList<>(result("SUPPORTED").checks());
        checks.set(0, new PremiseCheck("ANCHOR_FUNCTION", "주장", "SUPPORTED", List.of(), "설명"));
        assertThat(route(source(), new Result("b", checks)).failureCode()).isEqualTo("SOURCE_EVIDENCE_REQUIRED");
        checks.set(0, finding("ANCHOR_FUNCTION", "PASS"));
        assertThat(route(source(), new Result("b", checks)).failureCode()).isEqualTo("STATUS_INVALID");
    }
    @Test void sourceEnvelopeRetainsWholeRawButExcludesHypothesisDictionaryAndLabels() {
        var before = source(); String original = before.toString();
        var verificationInput = before.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) verificationInput).putArray("candidates").addObject().put("anchorId", "b")
                .put("hypothesisNotEvidence", "DO_NOT_COPY_HYPOTHESIS").put("expectedDecision", "DO_NOT_COPY_LABEL");
        var output = JSON.readTree("""
                {"verifications":[{"assessment":{"segmentId":"b","decision":"REVIEW_REQUIRED",
                "reason":"상대에게 질문했다는 검증 대상 주장","targetReason":"대상 설명","alternativeInterpretation":"다른 해석"}}]}
                """);
        String saved = output.toString();
        var input = input(verificationInput, output);
        assertThat(input.path("raw")).isEqualTo(before.path("raw"));
        assertThat(input.toString()).doesNotContain("DO_NOT_COPY", "expectedDecision", "proposedEvidence", "contextReference");
        assertThat(input.at("/judgmentToCheck/decision").asText()).isEqualTo("REVIEW_REQUIRED");
        route(input, result("CONTRADICTED"));
        assertThat(output.toString()).isEqualTo(saved);
        assertThat(before.toString()).isEqualTo(original);
    }
    @Test void strictSchemaHasThreeChecksAndNoRiskDecisionOutput() {
        var format = responseFormat();
        assertThat(format.at("/json_schema/strict").asBoolean()).isTrue();
        assertThat(format.at("/json_schema/schema/properties/checks/minItems").asInt()).isEqualTo(3);
        assertThat(format.at("/json_schema/schema/properties/checks/maxItems").asInt()).isEqualTo(3);
        assertThat(format.toString()).doesNotContain("REVIEW_REQUIRED", "PASS", "category", "score");
        assertThat(SYSTEM).contains("judgmentToCheck는 검증할 모델 주장이지 사실이 아니다", "정보 부재를 반증으로 바꾸지 않는다");
    }
}
