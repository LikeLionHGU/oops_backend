package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static org.assertj.core.api.Assertions.*;

/** Authored fixtures reproduce failure mechanisms, not private source text or approved semantic gold. */
class OfflineDecisionContractAuditTest {
    private UtteranceDirectionTrial.Task directTask(VerificationFactorialPlan.Request r) {
        return new UtteranceDirectionTrial.Task(r.caseId(),r.labelStatus(),r.expectedDecision(),null,null,r.system(),r.system(),r.input());
    }

    @Test void targetIdCopyReproducesGroundingFailureWithoutRepairingOriginalOrCertifyingReferent() {
        var task=directTask(probeRequest("TARGET_TREATMENT",1,"저는 제 이야기를 하는 중입니다.","제가 보여드릴까요?","형만 이야기합니다."));
        var response=targetProbeOutput("stt-index-1","제가 보여드릴까요?",0);var d=assessment(response);
        d.put("target","형");d.put("targetMention","형");
        d.set("evidence",JSON.valueToTree(List.of(new TextReviewEngine.LlmEvidence("stt-index-1",null,"PRIMARY"),
                new TextReviewEngine.LlmEvidence("stt-index-1",null,"TARGET"))));
        var before=response.deepCopy();
        var original=UtteranceDirectionTrial.assess(task,JSON.treeToValue(response,VerificationResult.class));
        assertThat(original).containsEntry("failureCode","TARGET_EVIDENCE_REQUIRED");
        var provenance=OfflineDecisionContractAudit.targetProvenance(task,response);
        assertThat(provenance.get("literalMatchingAllowedIds")).isEqualTo(List.of("stt-index-2"));
        assertThat(OfflineDecisionContractAudit.targetIdProbes(task,response,"TARGET_EVIDENCE_REQUIRED"))
                .singleElement().satisfies(p -> assertThat(p).containsEntry("changeKind","REPOINT_TARGET_ID")
                        .containsEntry("contractAccepted",true).containsEntry("originalDecisionPreserved",true)
                        .containsEntry("semanticCorrectnessEstablished",false));
        assertThat(response).isEqualTo(before);
    }

    @Test void addingMissingTargetRoleDoesNotHideNextContextRequirement() {
        var task=directTask(probeRequest("TARGET_TREATMENT",1,"지금 당신이 입은 속옷 색을 알고 싶어요.","제게 한번 보여 주세요."));
        var response=targetProbeOutput("stt-index-1","제게 한번 보여 주세요.",0);var d=assessment(response);
        d.put("targetRelation","CONTEXTUAL");
        d.set("evidence",JSON.valueToTree(List.of(new TextReviewEngine.LlmEvidence("stt-index-1",null,"PRIMARY"),
                new TextReviewEngine.LlmEvidence("stt-index-0",null,"CONTEXT"))));
        assertThat(UtteranceDirectionTrial.assess(task,JSON.treeToValue(response,VerificationResult.class)))
                .containsEntry("failureCode","TARGET_EVIDENCE_REQUIRED");
        assertThat(OfflineDecisionContractAudit.targetIdProbes(task,response,"TARGET_EVIDENCE_REQUIRED"))
                .singleElement().satisfies(p -> assertThat(p).containsEntry("changeKind","APPEND_TARGET_ROLE")
                        .containsEntry("contractAccepted",false).containsEntry("failureCode","TARGET_CONTEXT_EVIDENCE_REQUIRED"));
    }

    @Test void absentProposalCitationDoesNotMeanRawContextWasNotSentOrSemanticallyIgnored() {
        var r=probeRequest("TARGET_TREATMENT",1,"그 말이 무슨 뜻인가요?","줄임말을 풀이합니다.");
        var input=(ObjectNode)JSON.readTree(r.input());
        ((ObjectNode)input.path("candidates").get(0)).set("proposedEvidence",JSON.valueToTree(List.of(new Quote("stt-index-1",null))));
        var task=new UtteranceDirectionTrial.Task("provenance","DRAFT",null,null,null,"unused","unused",input.toString());
        var response=output(new CandidateReviewEngineTest().assessment("stt-index-1","PASS","줄임말을 풀이합니다."));
        var provenance=OfflineDecisionContractAudit.targetProvenance(task,response);
        assertThat(provenance.get("allowedButNotProposedIds")).isEqualTo(List.of("stt-index-0"));
        assertThat(provenance).containsEntry("semanticCorrectnessEstablished",false);
        assertThat(JSON.readTree(task.input()).path("raw").size()).isEqualTo(2);
    }

    @Test void directWritingAuditReplaysAll56RowsAndRejectsHistoricalResultOrOrderChanges() throws Exception {
        var i=new DirectTargetWritingComparisonTest().inputs();var plan=UtteranceDirectionTrial.directPlan(i.factorial(),i.history());
        var saved=JSON.createObjectNode();saved.put("schemaVersion",DirectTargetWritingComparison.VERSION);
        saved.put("state","COMPLETE");saved.put("httpAttempts",56);var rows=saved.putArray("results");
        for(int repeat=1;repeat<=2;repeat++) for(var task:plan.tasks())
            for(String arm:repeat==1?List.of("BASELINE","COMPARISON"):List.of("COMPARISON","BASELINE")) {
                var response=new VerificationResult(List.of());var row=rows.addObject();
                row.put("caseId",task.id());row.put("repeat",repeat);row.put("arm",arm);row.put("labelStatus",task.labelStatus());
                row.set("expectedDecision",JSON.valueToTree(task.expectedDecision()));
                row.set("assessment",JSON.valueToTree(UtteranceDirectionTrial.assess(task,response)));
                var call=row.putArray("calls").addObject();call.put("system",arm.equals("BASELINE")?task.baselineSystem():task.comparisonSystem());
                call.set("input",JSON.readTree(task.input()));call.putNull("failureCode");call.set("output",JSON.valueToTree(response));
            }
        var result=OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(plan),saved);
        assertThat(result).containsEntry("providerCalls",0).containsEntry("historicalContractDrift",0);
        assertThat((List<?>)result.get("rows")).hasSize(56);
        var changed=saved.deepCopy();((ObjectNode)changed.path("results").get(0).path("assessment")).put("accepted",true);
        assertThatThrownBy(() -> OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(plan),changed)).hasMessageContaining("replay drift");
        var duplicate=saved.deepCopy();duplicate.withArray("results").set(1,duplicate.path("results").get(0));
        assertThatThrownBy(() -> OfflineDecisionContractAudit.auditDirectWriting(JSON.valueToTree(plan),duplicate)).hasMessageContaining("ordered direct-writing tuple");
    }

    private VerificationFactorialPlan.Request probeRequest(String axis, int anchor, String... lines) {
        var input=new CandidateReviewEngineTest().context(lines).reviewInput();
        var speech=input.segments().stream().filter(s -> s.type()==com.example.oops.domain.TimelineEventType.SPEECH).toList();
        var ids=speech.stream().map(ReviewInput.Segment::id).toList();
        String body=JSON.writeValueAsString(Map.of("raw",speech,"candidates",List.of(Map.of(
                "candidateId","candidate-1","anchorId",ids.get(anchor),"axis",axis,"hypothesisNotEvidence","연결 확인용 합성 후보",
                "segmentIds",ids,"proposedEvidence",ids.stream().map(id -> new Quote(id,null)).toList(),
                "contextExpanded",false,"contextLimited",false))));
        return new VerificationFactorialPlan.Request("synthetic-probe",VerificationFactorialPlan.Arm.BASELINE,"UNLABELED",null,"unused",body);
    }

    private ObjectNode targetProbeOutput(String anchor,String targetText,int contextIndex) {
        var f=new CandidateReviewEngineTest();var response=output(f.assessment(anchor,"REVIEW_REQUIRED",targetText));
        var d=assessment(response);d.put("category","SEXUAL");d.put("target","질문을 받은 상대");
        d.put("targetMention","당신");d.put("targetType","PERSON");d.put("targetRelation","EXPLICIT");
        d.put("targetReason","원문 당신이 요청을 받는 대상을 가리킨다는 합성 계약 예시다.");
        d.put("alternativeInterpretation","일반적인 질문일 가능성과 비교하는 합성 대조 설명이다.");
        d.set("evidence",JSON.valueToTree(List.of(new TextReviewEngine.LlmEvidence(anchor,null,"PRIMARY"),
                new TextReviewEngine.LlmEvidence("stt-index-0",null,"TARGET"),
                new TextReviewEngine.LlmEvidence("stt-index-"+contextIndex,null,"CONTEXT"))));
        return response;
    }

    @Test void factorialCopiesIdentifyLiteralNameConstraintWithoutChangingOriginalOrDecision() {
        var request=probeRequest("TARGET_TREATMENT",0,"지금 당신이 입은 속옷 색을 알려 주세요.","그 질문에는 대답하고 싶지 않아요.");
        var response=targetProbeOutput("stt-index-0","지금 당신이 입은 속옷 색을 알려 주세요.",1);var before=response.deepCopy();
        var original=VerificationFactorialPlan.assess(request,JSON.treeToValue(response,VerificationResult.class));
        assertThat(original).containsEntry("failureCode","TARGET_MENTION_RELATION");
        var probes=OfflineDecisionContractAudit.factorialProbes(request,response,"TARGET_MENTION_RELATION");
        assertThat(probes).hasSize(2).allSatisfy(p -> assertThat(p).containsEntry("contractAccepted",true)
                .containsEntry("originalDecisionPreserved",true).containsEntry("semanticCorrectnessEstablished",false));
        assertThat(response).isEqualTo(before);
    }

    @Test void changingRelationDoesNotInventSeparateContextEvidence() {
        var request=probeRequest("TARGET_TREATMENT",1,"지금 당신이 입은 속옷 색을 알고 싶어요.","제게 한번 보여 주세요.");
        var response=targetProbeOutput("stt-index-1","제게 한번 보여 주세요.",0);
        var probes=OfflineDecisionContractAudit.factorialProbes(request,response,"TARGET_MENTION_RELATION");
        assertThat(probes).anySatisfy(p -> assertThat(p).containsEntry("changedField","target").containsEntry("contractAccepted",true));
        assertThat(probes).anySatisfy(p -> assertThat(p).containsEntry("changedField","targetRelation")
                .containsEntry("contractAccepted",false).containsEntry("failureCode","TARGET_CONTEXT_EVIDENCE_REQUIRED"));
    }

    @Test void syntheticContrastPresenceProbeNeverCertifiesMeaningOrFillsSavedPass() {
        var request=probeRequest("TARGET_TREATMENT",0,"그 집 가게가 없어요.","제가 직접 만들었어요.");
        var f=new CandidateReviewEngineTest();var response=output(f.linkedPass("stt-index-0","그 집 가게가 없어요.","stt-index-1","제가 직접 만들었어요."));
        assessment(response).putNull("alternativeInterpretation");var before=response.deepCopy();
        var original=VerificationFactorialPlan.assess(request,JSON.treeToValue(response,VerificationResult.class));
        assertThat(original).containsEntry("accepted",false).containsEntry("failureCode","LINKED_PASS_CONTRAST_REQUIRED");
        var probes=OfflineDecisionContractAudit.factorialProbes(request,response,"LINKED_PASS_CONTRAST_REQUIRED");
        assertThat(probes).singleElement().satisfies(p -> assertThat(p).containsEntry("contractAccepted",true)
                .containsEntry("originalDecisionPreserved",true).containsEntry("semanticCorrectnessEstablished",false));
        assertThat(response).isEqualTo(before);
        assertThat(OfflineDecisionContractAudit.factorialProbes(request,response,"TARGET_EVIDENCE_REQUIRED")).isEmpty();
    }

    @Test void factorialAuditReplaysAll112RowsAndRejectsDriftWithoutAProvider() throws Exception {
        var i=new VerificationFactorialPlanTest().inputs();var plan=VerificationFactorialPlan.prepare(i.source(),i.human(),i.mappings());
        var saved=JSON.createObjectNode();saved.put("schemaVersion","verification-factorial-results-1");saved.put("state","COMPLETE");saved.put("httpAttempts",112);
        var rows=saved.putArray("results");
        for(var slot:VerificationFactorialTrial.schedule(plan)) {
            var request=slot.request();var response=new VerificationResult(List.of());var replay=VerificationFactorialPlan.assess(request,response);
            var row=rows.addObject();row.put("caseId",request.caseId());row.put("arm",request.arm().name());row.put("repeat",slot.repeat());
            row.set("expectedDecision",JSON.valueToTree(request.expectedDecision()));row.set("assessment",JSON.valueToTree(replay));
            row.set("boundaryEvaluation",JSON.valueToTree(VerificationFactorialTrial.score(request,response,replay)));
            var call=row.putArray("calls").addObject();call.put("system",request.system());call.set("input",JSON.readTree(request.input()));
            call.putNull("failureCode");call.set("output",JSON.valueToTree(response));
        }
        var planNode=JSON.valueToTree(plan);var result=OfflineDecisionContractAudit.auditFactorial(planNode,saved);
        assertThat(result).containsEntry("providerCalls",0).containsEntry("historicalContractDrift",0);
        assertThat((List<?>)result.get("rows")).hasSize(112);
        var changed=saved.deepCopy();((ObjectNode)changed.path("results").get(0).path("assessment")).put("accepted",true);
        assertThatThrownBy(() -> OfflineDecisionContractAudit.auditFactorial(planNode,changed)).hasMessageContaining("replay drift");
        var duplicate=saved.deepCopy();duplicate.withArray("results").set(1,duplicate.path("results").get(0));
        assertThatThrownBy(() -> OfflineDecisionContractAudit.auditFactorial(planNode,duplicate)).hasMessageContaining("ordered study tuple");
    }

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
