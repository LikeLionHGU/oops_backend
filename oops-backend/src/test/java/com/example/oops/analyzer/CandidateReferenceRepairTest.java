package com.example.oops.analyzer;

import com.example.oops.domain.AnalyzerStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mock-only routing/repair checks. No provider requests or accuracy claims. */
class CandidateReferenceRepairTest {
    LlmDecision badId(LlmDecision d) {
        var evidence = new ArrayList<>(d.evidence());
        evidence.add(new LlmEvidence("invented-id", "없는 근거", "CONTEXT"));
        return new LlmDecision(d.segmentId(), d.decision(), d.evidenceText(), d.reason(), d.category(),
                d.target(), d.score(), d.context(), d.reading(), d.missingInformation(),
                evidence,
                d.targetType(), d.targetRelation(), d.targetReason(), d.alternativeInterpretation(), d.targetMention());
    }
    @Test void unknownIdIsReassessedOnceAndCanRecoverToPassWithoutCopyingInvalidOutput() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        var good = f.assessment("stt-index-0", "PASS", "그 집");
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1", badId(good))))))
                .thenAnswer(invocation -> {
                    assertThat((String) invocation.getArgument(0)).contains(ID_REPAIR_PROMPT);
                    assertThat((String) invocation.getArgument(1)).contains("DECISION_UNKNOWN_EVIDENCE_ID", "stt-index-0")
                            .doesNotContain("invented-id", "없는 근거");
                    return Optional.of(new VerificationResult(List.of(new Verification("candidate-1", good))));
                });
        var result = run(f.client, f.context("그 집은 조용하다"), 24);
        assertThat(result.findings()).isEmpty();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        var d = result.diagnostics().candidatePipeline();
        assertThat(d.repairCalls()).isOne(); assertThat(d.verificationCalls()).isEqualTo(2);
        assertThat(d.verificationFailed()).isZero();
        assertThat(d.repairs()).singleElement().satisfies(t -> {
            assertThat(t.initialFailureCode()).isEqualTo("DECISION_UNKNOWN_EVIDENCE_ID");
            assertThat(t.state()).isEqualTo("RECOVERED");
        });
        assertThat(d.candidates()).singleElement().extracting(CandidateReviewDiagnostics.Trace::state).isEqualTo("PASS");
    }
    @Test void secondInvalidResponseStaysIncompleteAndDoesNotLoop() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        f.verification(new Verification("candidate-1", badId(f.assessment("stt-index-0", "PASS", "그 집"))));
        var result = run(f.client, f.context("그 집은 조용하다"), 24);
        assertThat(result.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().repairs()).singleElement()
                .extracting(CandidateReviewDiagnostics.RepairTrace::state).isEqualTo("FAILED");
        verify(f.client, times(2)).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void recoveredReviewStillRequiresTargetQuoteAndPublicationValidation() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        var good = f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집");
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1", badId(good))))),
                        Optional.of(new VerificationResult(List.of(new Verification("candidate-1", good)))));
        var result = run(f.client, f.context("그 집 이용자는 수준이 낮아"), 24);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.diagnostics().candidatePipeline().verificationFailed()).isZero();
        assertThat(result.diagnostics().candidatePipeline().repairs()).singleElement()
                .extracting(CandidateReviewDiagnostics.RepairTrace::state).isEqualTo("RECOVERED");
    }
    @Test void repairCannotReplaceUnknownIdWithFabricatedQuoteAndStillPublish() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        var good = f.assessment("stt-index-0", "PASS", "그 집");
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1", badId(good))))),
                        Optional.of(new VerificationResult(List.of(new Verification("candidate-1",
                                f.assessment("stt-index-0", "PASS", "허구 인용"))))));
        var result = run(f.client, f.context("그 집은 조용하다"), 24);
        assertThat(result.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().repairs()).singleElement().satisfies(t -> {
            assertThat(t.state()).isEqualTo("FAILED");
            assertThat(t.finalFailureCode()).isEqualTo("DECISION_QUOTE_NOT_IN_RAW");
        });
    }
    @Test void fabricatedQuoteIsNotAutoRepairedAndProviderFailureDoesNotLoop() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        f.verification(new Verification("candidate-1", f.assessment("stt-index-0", "PASS", "허구 인용")));
        var r = run(f.client, f.context("그 집은 조용하다"), 24);
        assertThat(r.diagnostics().candidatePipeline().repairCalls()).isZero();
        verify(f.client).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
        reset(f.client);
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenThrow(new IllegalStateException());
        assertThat(run(f.client, f.context("그 집은 조용하다"), 24).diagnostics().candidatePipeline().repairCalls()).isZero();
    }
    @Test void videoRepairBudgetIsTwoEvenWithThreeBrokenCandidates() {
        var f = new CandidateReviewEngineTest();
        var ids = List.of("stt-index-0", "stt-index-1", "stt-index-2");
        f.discovery(ids, ids.stream().map(id -> f.proposal(id, "그 집")).toArray(Proposal[]::new));
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(invocation -> {
            var tree = JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            List<Verification> responses = new ArrayList<>();
            for (var n : tree.get("candidates")) responses.add(new Verification(n.get("candidateId").asText(),
                    badId(f.assessment(n.get("anchorId").asText(), "PASS", "그 집"))));
            return Optional.of(new VerificationResult(responses));
        });
        var r = run(f.client, f.context("그 집 첫째", "그 집 둘째", "그 집 셋째"), 24);
        var d = r.diagnostics().candidatePipeline();
        assertThat(d.repairCalls()).isEqualTo(2); assertThat(d.verificationFailed()).isEqualTo(3);
        assertThat(d.repairs()).hasSize(3).last().extracting(CandidateReviewDiagnostics.RepairTrace::state)
                .isEqualTo("NOT_ATTEMPTED_BUDGET");
        verify(f.client, times(3)).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    Archive distinctExamples() {
        var a = new ContextExampleSelectionTest().archive(2);
        var examples = List.of(
                new ContextExample("source-0", "family-0", List.of("mechanism-1"), List.of("SPEECH", "CAPTION"),
                        new Comparison("REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT", List.of("공공시설이 없는 생활조건"),
                                List.of("생활조건을 낮추는 연결"), List.of("원영상 없음"), List.of())),
                new ContextExample("source-1", "family-1", List.of("mechanism-1"), List.of("SPEECH", "CAPTION"),
                        new Comparison("REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT", List.of("메뉴 선택권을 무시하는 설명"),
                                List.of("제공자의 선택권을 무시"), List.of("원영상 없음"), List.of())));
        return new Archive(a.schemaVersion(), a.version(), a.status(), false, a.usage(), a.sourceBundleSha256(),
                a.refreshOrDeleteBy(), a.sourceCases(), a.guidelines(), a.contextDictionarySha256(), examples);
    }
    @Test void candidateQuoteWinsOverBroadWindowAndExclusionsRemainEffective() {
        var helper = new ContextExampleSelectionTest();
        var focus = helper.query("공공시설이 없는 생활조건");
        var broad = helper.query("메뉴 선택권을 무시하는 설명 제공자의 선택권을 무시 메뉴 선택권");
        var library = helper.library(distinctExamples(), 1, 3000, "");
        assertThat(library.select(com.example.oops.domain.TimelineEventType.SPEECH, broad, "b", focus).trace().exampleIds())
                .containsExactly("source-0");
        assertThat(helper.library(distinctExamples(), 1, 3000, "family-0")
                .select(com.example.oops.domain.TimelineEventType.SPEECH, broad, "b", focus).trace().exampleIds())
                .containsExactly("source-1");
    }
    @Test void differentCandidateReferencesDoNotShareUnionWindowRouting() {
        var f = new CandidateReviewEngineTest(); var helper = new ContextExampleSelectionTest();
        String first = "공공시설이 없는 생활조건", second = "메뉴 선택권을 무시하는 설명";
        f.discovery(List.of("stt-index-0", "stt-index-1"), f.proposal("stt-index-0", first), f.proposal("stt-index-1", second));
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(invocation -> {
            var tree = JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            assertThat(tree.get("candidates").size()).isOne();
            var c = tree.get("candidates").get(0); String id = c.get("candidateId").asText();
            String system = invocation.getArgument(0);
            assertThat(system).contains(id.equals("candidate-1") ? first : second)
                    .doesNotContain(id.equals("candidate-1") ? second : first);
            return Optional.of(new VerificationResult(List.of(new Verification(id,
                    f.assessment(c.get("anchorId").asText(), "PASS", id.equals("candidate-1") ? first : second)))));
        });
        var r = run(f.client, f.context(first, second), 24, null, null, helper.library(distinctExamples(), 1, 3000, ""));
        var d = r.diagnostics().candidatePipeline();
        assertThat(d.verificationCalls()).isEqualTo(2);
        assertThat(d.contextSelections().stream().filter(s -> s.requestKey().startsWith("verification-")))
                .extracting(SelectionTrace::exampleIds).containsExactly(List.of("source-0"), List.of("source-1"));
    }
}
