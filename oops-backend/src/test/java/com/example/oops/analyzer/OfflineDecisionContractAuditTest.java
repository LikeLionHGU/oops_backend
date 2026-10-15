package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static org.assertj.core.api.Assertions.*;

/** Authored fixtures reproduce failure mechanisms, not private source text or approved semantic gold. */
class OfflineDecisionContractAuditTest {
    private ContextPolicyTrial.Task task(String expected, String labelStatus) {
        var f = new CandidateReviewEngineTest();
        return ContextPolicyTrial.capture(new ContextPolicyTrial.Spec("synthetic-test", 0, 1,
                "TARGET_TREATMENT", List.of(0, 1), labelStatus, expected),
                f.context("가: 얼굴 이야기는 그만해 줘.", "나: 네 얼굴이 역겨워서 다들 피하는 거야."), null);
    }

    private ObjectNode output(TextReviewEngine.LlmDecision decision) {
        return JSON.valueToTree(new VerificationResult(List.of(new Verification("candidate-1", decision))));
    }

    private ObjectNode passOutput() {
        var f = new CandidateReviewEngineTest();
        return output(f.linkedPass("stt-index-1", "나: 네 얼굴이 역겨워서 다들 피하는 거야.",
                "stt-index-0", "가: 얼굴 이야기는 그만해 줘."));
    }

    private ObjectNode assessment(ObjectNode output) {
        return (ObjectNode) output.path("verifications").get(0).path("assessment");
    }

    @Test void matchingPassDecisionDoesNotHideMissingMandatoryContrastField() {
        var task = task("PASS", "USER_REVIEWED_DEVELOPMENT_FLOW");
        var response = passOutput();
        assessment(response).putNull("alternativeInterpretation");
        var before = response.deepCopy();
        var result = OfflineDecisionContractAudit.inspect(task, response);
        assertThat(result).containsEntry("parsedModelDecision", "PASS")
                .containsEntry("developmentLabelAgreement", "MATCH")
                .containsEntry("contractAccepted", false)
                .containsEntry("contractFailureCode", "LINKED_PASS_CONTRAST_REQUIRED")
                .containsEntry("evidenceMeaningReview", "NOT_AUTOMATICALLY_ASSESSED");
        assertThat(response).isEqualTo(before); // No filling the missing field from reason.
        assessment(response).put("alternativeInterpretation", "구체적 행동에 대한 비판이라는 정상 해석과 비교한다.");
        assertThat(OfflineDecisionContractAudit.inspect(task, response)).containsEntry("contractAccepted", true);
    }

    @Test void contractAcceptedCanStillDisagreeWithDeclaredDevelopmentDecision() {
        var result = OfflineDecisionContractAudit.inspect(task("REVIEW_REQUIRED", "USER_REVIEWED_DEVELOPMENT_FLOW"), passOutput());
        assertThat(result).containsEntry("contractAccepted", true)
                .containsEntry("parsedModelDecision", "PASS").containsEntry("developmentLabelAgreement", "MISMATCH");
        // These are artificial declarations: MATCH/MISMATCH never proves the reason is semantically sound.
    }

    @Test void targetRelationFailureRemainsSeparateFromTheReviewDecision() {
        var f = new CandidateReviewEngineTest();
        var response = output(f.assessment("stt-index-1", "REVIEW_REQUIRED", "나: 네 얼굴이 역겨워서 다들 피하는 거야."));
        var d = assessment(response);
        d.put("reason", "외모 이야기를 중단해 달라는 요청 뒤 상대의 외모를 직접 폄하한다.");
        d.put("target", "상대의 얼굴"); d.put("targetMention", "네 얼굴");
        d.put("targetType", "PERSON"); d.put("targetRelation", "EXPLICIT");
        d.put("targetReason", "앞 문장의 중단 요청과 뒤 문장의 네 얼굴이라는 지칭이 연결된다.");
        d.set("evidence", JSON.valueToTree(List.of(
                Map.of("segmentId", "stt-index-1", "role", "PRIMARY"),
                Map.of("segmentId", "stt-index-1", "role", "TARGET"),
                Map.of("segmentId", "stt-index-0", "role", "CONTEXT"))));
        var task = task("REVIEW_REQUIRED", "USER_REVIEWED_DEVELOPMENT_FLOW");
        var result = OfflineDecisionContractAudit.inspect(task, response);
        assertThat(result).containsEntry("parsedModelDecision", "REVIEW_REQUIRED")
                .containsEntry("developmentLabelAgreement", "MATCH")
                .containsEntry("contractFailureCode", "TARGET_MENTION_RELATION");
        // Only a test copy changes the contract declaration; production/output artifacts stay untouched.
        d.put("targetRelation", "CONTEXTUAL");
        var corrected = OfflineDecisionContractAudit.inspect(task, response);
        assertThat(corrected).containsEntry("contractAccepted", true).containsEntry("parsedModelDecision", "REVIEW_REQUIRED");
        // Even CONTEXTUAL is rejected when its separate supporting context is removed.
        d.set("evidence", JSON.valueToTree(List.of(Map.of("segmentId", "stt-index-1", "role", "PRIMARY"),
                Map.of("segmentId", "stt-index-1", "role", "TARGET"))));
        assertThat(OfflineDecisionContractAudit.inspect(task, response))
                .containsEntry("contractFailureCode", "TARGET_CONTEXT_EVIDENCE_REQUIRED");
    }

    @Test void unreviewedAndVideoLevelFeedbackAreNotTurnedIntoTextGold() {
        for (String status : List.of("SYNTHETIC_PROPOSED_LABEL_NOT_GOLD",
                "USER_VIDEO_FLOW_PASS_TEXT_SUFFICIENCY_UNREVIEWED")) {
            assertThat(OfflineDecisionContractAudit.inspect(task("REVIEW_REQUIRED", status), passOutput()))
                    .containsEntry("developmentLabelAgreement", "UNLABELED").containsEntry("developmentExpected", null);
        }
    }

    @Test void malformedOrWrongCandidateResponsesDoNotBecomePass() {
        var task = task("PASS", "USER_REVIEWED_DEVELOPMENT_FLOW");
        for (var invalid : List.of(JSON.createObjectNode(), JSON.createArrayNode())) {
            assertThat(OfflineDecisionContractAudit.inspect(task, invalid))
                    .containsEntry("decisionReadable", false).containsEntry("developmentLabelAgreement", "UNREADABLE")
                    .containsEntry("contractAccepted", false);
        }
        var wrong = passOutput();
        ((ObjectNode) wrong.path("verifications").get(0)).put("candidateId", "wrong-candidate");
        assertThat(OfflineDecisionContractAudit.inspect(task, wrong))
                .containsEntry("parsedModelDecision", null).containsEntry("contractFailureCode", "TRIAL_RESPONSE_SHAPE");
    }

    @Test void wholeReplayRejectsDuplicateTuplesChangedInputsAndHistoricalOutcomeDrift() {
        var original = task(null, "SYNTHETIC_PROPOSED_LABEL_NOT_GOLD");
        var ids = new ArrayList<>(BanterPolicyTrial.REAL_IDS);
        BanterPolicyTrial.SYNTHETIC_IDS.forEach(id -> ids.add("synthetic-" + id));
        var tasks = ids.stream().map(id -> new ContextPolicyTrial.Task(
                new ContextPolicyTrial.Spec(id, 0, 1, "TARGET_TREATMENT", List.of(0, 1),
                        "SYNTHETIC_PROPOSED_LABEL_NOT_GOLD", null), original.baselineSystem(),
                BanterBoundaryPolicy.replacePolicy(original.baselineSystem()), original.input())).toList();
        var plan = JSON.valueToTree(Map.of("schemaVersion", BanterBoundaryPolicy.REVISION,
                "plannedCalls", 72, "repeats", 3, "approvedEstimatedUsd", 1, "tasks", tasks));
        var saved = JSON.createObjectNode();
        var rows = saved.putArray("results");
        var response = JSON.treeToValue(passOutput(), VerificationResult.class);
        for (var t : tasks) for (String arm : List.of("BASELINE", "COMPARISON")) for (int repeat = 1; repeat <= 3; repeat++) {
            var row = rows.addObject(); row.put("caseId", t.spec().id()); row.put("arm", arm); row.put("repeat", repeat);
            row.set("assessments", JSON.valueToTree(ContextPolicyTrial.assess(t, response)));
            var call = row.putArray("calls").addObject();
            call.put("system", arm.equals("BASELINE") ? t.baselineSystem() : t.comparisonSystem());
            call.set("input", JSON.readTree(t.input())); call.set("output", passOutput()); call.putNull("failureCode");
        }
        var result = OfflineDecisionContractAudit.audit(plan, saved);
        assertThat(result).containsEntry("providerCalls", 0).containsEntry("semanticCorrectnessAutomaticallyEstablished", false);
        assertThat((List<?>) result.get("rows")).hasSize(72);
        var duplicate = saved.deepCopy(); duplicate.withArray("results").set(1, duplicate.path("results").get(0));
        assertThatThrownBy(() -> OfflineDecisionContractAudit.audit(plan, duplicate)).isInstanceOf(IllegalArgumentException.class);
        var changed = saved.deepCopy();
        ((ObjectNode) changed.path("results").get(0).path("calls").get(0)).set("input", JSON.createObjectNode());
        assertThatThrownBy(() -> OfflineDecisionContractAudit.audit(plan, changed)).isInstanceOf(IllegalArgumentException.class);
        var drift = saved.deepCopy();
        ((ObjectNode) drift.path("results").get(0).path("assessments").get(0)).put("accepted", false);
        assertThatThrownBy(() -> OfflineDecisionContractAudit.audit(plan, drift)).isInstanceOf(IllegalArgumentException.class);
        var decisionDrift = saved.deepCopy();
        ((ObjectNode) decisionDrift.path("results").get(0).path("assessments").get(0).path("observation"))
                .put("decision", "REVIEW_REQUIRED");
        assertThatThrownBy(() -> OfflineDecisionContractAudit.audit(plan, decisionDrift)).isInstanceOf(IllegalArgumentException.class);
    }
}
