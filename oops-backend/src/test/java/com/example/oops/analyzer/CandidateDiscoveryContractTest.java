package com.example.oops.analyzer;

import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.stream.IntStream;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Routing/contract tests with synthetic raw and mocked responses, not controversy accuracy. */
class CandidateDiscoveryContractTest {
    @Test void missingAnchorQuoteCanRecoverThenIndependentlyPass() {
        var f = new CandidateReviewEngineTest();
        var broken = new Proposal("stt-index-1", "TARGET_TREATMENT", "두 문장의 연결 확인",
                List.of(new Quote("stt-index-0", "그 집")));
        f.discovery(List.of("stt-index-0", "stt-index-1"), broken);
        var repaired = new Proposal(broken.anchorId(), broken.axis(), broken.reason(),
                List.of(new Quote("stt-index-0", "그 집"), new Quote("stt-index-1", "내 취향은 아니야")));
        when(f.client.completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class))).thenAnswer(invocation -> {
            assertThat((String) invocation.getArgument(1)).contains("PROPOSAL_ANCHOR_EVIDENCE_REQUIRED", "내 취향은 아니야");
            return Optional.of(new ProposalRepairResult(repaired));
        });
        f.verification(new Verification("candidate-1", f.assessment(broken.anchorId(), "PASS", "내 취향은 아니야")));
        var r = run(f.client, f.context("그 집 메뉴", "내 취향은 아니야"), 24);
        assertThat(r.findings()).isEmpty();
        assertThat(r.diagnostics().candidatePipeline().discoveryRepairCalls()).isOne();
        assertThat(r.diagnostics().candidatePipeline().invalidProposals()).isZero();
        verify(f.client).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void missingAnchorRepairCannotInventQuotesOrLoopIfStillMissing() {
        for (String quote : List.of("허구 발언", "그 집")) {
            var f = new CandidateReviewEngineTest();
            var broken = new Proposal("stt-index-1", "TARGET_TREATMENT", "두 문장의 연결 확인",
                    List.of(new Quote("stt-index-0", "그 집")));
            f.discovery(List.of("stt-index-0", "stt-index-1"), broken);
            var response = quote.equals("그 집") ? broken : new Proposal(broken.anchorId(), broken.axis(), broken.reason(),
                    List.of(new Quote("stt-index-1", quote)));
            when(f.client.completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class)))
                    .thenReturn(Optional.of(new ProposalRepairResult(response)));
            var r = run(f.client, f.context("그 집 메뉴", "내 취향은 아니야"), 24);
            assertThat(r.findings()).isEmpty();
            assertThat(r.diagnostics().candidatePipeline().invalidProposals()).isOne();
            assertThat(r.diagnostics().candidatePipeline().repairs()).singleElement()
                    .extracting(CandidateReviewDiagnostics.RepairTrace::state).isEqualTo("FAILED");
            verify(f.client, times(1)).completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class));
            verify(f.client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
        }
    }
    @Test void multipleMissingAnchorsStillShareOneDiscoveryRepairBudget() {
        var f = new CandidateReviewEngineTest();
        var first = new Proposal("stt-index-1", "TARGET_TREATMENT", "첫 연결 확인",
                List.of(new Quote("stt-index-0", "그 집")));
        var second = new Proposal("stt-index-2", "TARGET_TREATMENT", "다음 연결 확인",
                List.of(new Quote("stt-index-0", "그 집")));
        f.discovery(List.of("stt-index-0", "stt-index-1", "stt-index-2"), first, second);
        when(f.client.completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class)))
                .thenReturn(Optional.of(new ProposalRepairResult(null)));
        var d = run(f.client, f.context("그 집 메뉴", "내 취향은 아니야", "다른 것도 있어"), 24)
                .diagnostics().candidatePipeline();
        assertThat(d.discoveryRepairCalls()).isOne();
        assertThat(d.invalidProposals()).isEqualTo(2);
        assertThat(d.repairs()).hasSize(2).last().extracting(CandidateReviewDiagnostics.RepairTrace::state)
                .isEqualTo("NOT_ATTEMPTED_BUDGET");
        verify(f.client, times(1)).completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class));
    }
    void batchDiscovery(CandidateReviewEngineTest f, Proposal p) {
        when(f.client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenAnswer(invocation -> {
            var tree = JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            List<String> ids = new ArrayList<>(); tree.get("primaryIds").forEach(n -> ids.add(n.asText()));
            return Optional.of(new Discovery(ids, ids.contains(p.anchorId()) ? List.of(p) : List.of(), List.of(), false));
        });
    }
    @Test void fortySecondChainIsAdvertisedBeforeValidationAndAllQuotesReachVerifier() {
        var f = new CandidateReviewEngineTest();
        String[] lines = IntStream.range(0, 21).mapToObj(i -> "그 집 선택지 " + i).toArray(String[]::new);
        var p = new Proposal("stt-index-10", "TARGET_TREATMENT", "앞 상황과 후속 평가의 연결 검토",
                List.of(new Quote("stt-index-0", lines[0]), new Quote("stt-index-10", lines[10]), new Quote("stt-index-20", lines[20])));
        batchDiscovery(f, p);
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(invocation -> {
            var tree = JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            assertThat(tree.get("raw").toString()).contains(lines[0], lines[10], lines[20]);
            assertThat(tree.get("candidates").get(0).get("proposedEvidence").size()).isEqualTo(3);
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1", f.assessment(p.anchorId(), "PASS", lines[10])))));
        });
        var c = f.context(lines);
        var first = TextReviewBatchPlanner.plan(c.reviewInput(), TimelineEventType.SPEECH, 3).get(0);
        assertThat(ReviewUnit.all(first).stream().filter(u -> u.anchorId().equals(p.anchorId())))
                .noneMatch(u -> u.segmentIds().containsAll(p.evidence().stream().map(Quote::segmentId).toList()));
        assertThat(CandidateContextWindow.discoveryUnits(first)).anySatisfy(u -> {
            assertThat(u.anchorId()).isEqualTo(p.anchorId());
            assertThat(u.segmentIds()).contains("stt-index-0", "stt-index-10", "stt-index-20");
        });
        var r = run(f.client, c, 24);
        assertThat(r.diagnostics().candidatePipeline().invalidProposals()).isZero();
        assertThat(r.diagnostics().candidatePipeline().discoveryRepairCalls()).isZero();
        assertThat(r.findings()).isEmpty(); // More context never forces a warning.
        verify(f.client).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void targetWindowsStayInsideBatchRawAndTimeCountCharacterGapBounds() {
        var helper = new CandidateContextWindowTest();
        for (var text : List.of("짧은 대화", "😀".repeat(300))) {
            var lines = IntStream.range(0, 80).mapToObj(i -> helper.speech(i, i * 1000L, text)).toList();
            for (var batch : TextReviewBatchPlanner.plan(new ReviewInput(lines), TimelineEventType.SPEECH, 3)) {
                Set<String> supplied = new HashSet<>(); batch.primary().forEach(s -> supplied.add(s.id())); batch.context().forEach(s -> supplied.add(s.id()));
                assertThat(CandidateContextWindow.discoveryUnits(batch)).allSatisfy(u -> {
                    assertThat(u.segmentIds()).contains(u.anchorId()).hasSizeLessThanOrEqualTo(48);
                    assertThat(supplied).containsAll(u.segmentIds());
                    assertThat(u.endMs() - u.startMs()).isLessThanOrEqualTo(60000);
                    assertThat(u.segmentIds().stream().map(id -> lines.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow())
                            .mapToInt(s -> s.text().codePointCount(0, s.text().length())).sum()).isLessThanOrEqualTo(6000);
                });
            }
        }
        var a = helper.speech(0, 0, "첫 대화"); var b = helper.speech(1, 10000, "다른 대화");
        assertThat(CandidateContextWindow.discoveryUnits(new TextReviewBatchPlanner.Batch(List.of(a, b), List.of(), false, false)))
                .allSatisfy(u -> assertThat(u.segmentIds()).hasSize(1));
    }
    @Test void malformedEvidenceCanRecoverButStillRequiresIndependentVerification() {
        var f = new CandidateReviewEngineTest(); var good = f.proposal("stt-index-0", "그 집");
        f.discovery(List.of(good.anchorId()), new Proposal(good.anchorId(), good.axis(), good.reason(), List.of()));
        when(f.client.completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class))).thenAnswer(invocation -> {
            assertThat((String) invocation.getArgument(0)).contains(DISCOVERY_REPAIR_PROMPT);
            assertThat((String) invocation.getArgument(1)).contains("allowedWindows", "PROPOSAL_EVIDENCE_SHAPE");
            return Optional.of(new ProposalRepairResult(good));
        });
        f.verification(new Verification("candidate-1", f.assessment(good.anchorId(), "PASS", "그 집")));
        var r = run(f.client, f.context("그 집 메뉴는 내 취향이 아니야"), 24);
        var d = r.diagnostics().candidatePipeline();
        assertThat(r.findings()).isEmpty();
        assertThat(d.discoveryRepairCalls()).isOne(); assertThat(d.discoveryCalls()).isEqualTo(2);
        assertThat(d.invalidProposals()).isZero();
        assertThat(d.repairs()).singleElement().extracting(CandidateReviewDiagnostics.RepairTrace::state).isEqualTo("RECOVERED");
        verify(f.client).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void repairCannotChangeAxisOrUseUnprovidedQuote() {
        for (var returned : List.of(new Proposal("stt-index-0", "EXPRESSION_CONTENT", "축 변경 가설", List.of(new Quote("stt-index-0", "그 집"))),
                new Proposal("stt-index-0", "TARGET_TREATMENT", "허구 인용 가설", List.of(new Quote("stt-index-0", "그 집은 우주에 있다"))))) {
            var f = new CandidateReviewEngineTest();
            f.discovery(List.of("stt-index-0"), new Proposal("stt-index-0", "TARGET_TREATMENT", "고정 후보 가설", List.of()));
            when(f.client.completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class)))
                    .thenReturn(Optional.of(new ProposalRepairResult(returned)));
            var r = run(f.client, f.context("그 집 이야기"), 24);
            assertThat(r.findings()).isEmpty();
            assertThat(r.diagnostics().candidatePipeline().repairs()).singleElement().satisfies(t -> {
                assertThat(t.state()).isEqualTo("FAILED");
                assertThat(t.finalFailureCode()).isIn("PROPOSAL_REPAIR_SCOPE_CHANGED", "PROPOSAL_QUOTE_NOT_IN_RAW");
            });
            verify(f.client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
        }
    }
    @Test void sixtySecondWindowStillCannotJoinWholeMinuteAcrossAnchor() {
        var f = new CandidateReviewEngineTest();
        String[] lines = IntStream.range(0, 40).mapToObj(i -> "일반 대화 " + i).toArray(String[]::new);
        var p = new Proposal("stt-index-10", "TARGET_TREATMENT", "먼 발언을 섞은 가설",
                List.of(new Quote("stt-index-0", lines[0]), new Quote("stt-index-10", lines[10]), new Quote("stt-index-35", lines[35])));
        // Supply all raw explicitly so the 60s bound, not batch omission, is exercised too.
        var input = f.context(lines).reviewInput();
        var fullBatch = new TextReviewBatchPlanner.Batch(input.segments(), List.of(), false, false);
        assertThat(CandidateContextWindow.discoveryUnits(fullBatch).stream().filter(u -> u.anchorId().equals(p.anchorId())))
                .noneMatch(u -> u.segmentIds().containsAll(p.evidence().stream().map(Quote::segmentId).toList()));
        batchDiscovery(f, p);
        var r = run(f.client, f.context(lines), 24);
        assertThat(r.findings()).isEmpty();
        assertThat(r.diagnostics().candidatePipeline().invalidProposals()).isOne();
        verify(f.client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void failedRepairIsOnceAndCannotChangeAnchorOrPublish() {
        for (var response : List.of(new ProposalRepairResult(null), new ProposalRepairResult(
                new Proposal("other-anchor", "TARGET_TREATMENT", "다른 후보", List.of(new Quote("other-anchor", "다른 원문")))))) {
            var f = new CandidateReviewEngineTest();
            f.discovery(List.of("stt-index-0"), new Proposal("stt-index-0", "TARGET_TREATMENT", "고정 후보 가설", List.of()));
            when(f.client.completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class))).thenReturn(Optional.of(response));
            var r = run(f.client, f.context("그 집 이야기"), 24);
            assertThat(r.findings()).isEmpty();
            assertThat(r.diagnostics().candidatePipeline().invalidProposals()).isOne();
            assertThat(r.diagnostics().candidatePipeline().repairs()).singleElement()
                    .extracting(CandidateReviewDiagnostics.RepairTrace::state).isEqualTo("FAILED");
            verify(f.client, times(1)).completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class));
            verify(f.client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
        }
    }
    @Test void oversizedEvidenceWithInventedIdDoesNotBecomeAnEligibleShapeRepair() {
        var f = new CandidateReviewEngineTest();
        var quotes = new ArrayList<Quote>(); for (int i = 0; i < 9; i++) quotes.add(new Quote("invented", "허구"));
        f.discovery(List.of("stt-index-0"), new Proposal("stt-index-0", "TARGET_TREATMENT", "비판 가설", quotes));
        var r = run(f.client, f.context("그 집 이야기"), 24);
        assertThat(r.findings()).isEmpty();
        assertThat(r.diagnostics().candidatePipeline().discoveryRepairCalls()).isZero();
        assertThat(r.diagnostics().candidatePipeline().candidates()).singleElement()
                .extracting(CandidateReviewDiagnostics.Trace::failureCode).isEqualTo("PROPOSAL_UNKNOWN_EVIDENCE_ID");
        verify(f.client, never()).completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class));
    }
    @Test void expressionAxisCannotBorrowSixtySecondTargetWindow() {
        var f = new CandidateReviewEngineTest();
        String[] lines = IntStream.range(0, 21).mapToObj(i -> "일반 대화 " + i).toArray(String[]::new);
        var p = new Proposal("stt-index-10", "EXPRESSION_CONTENT", "표현 내용 가설",
                List.of(new Quote("stt-index-0", lines[0]), new Quote("stt-index-10", lines[10]), new Quote("stt-index-20", lines[20])));
        batchDiscovery(f, p);
        var r = run(f.client, f.context(lines), 24);
        assertThat(r.findings()).isEmpty();
        assertThat(r.diagnostics().candidatePipeline().candidates()).singleElement()
                .extracting(CandidateReviewDiagnostics.Trace::failureCode).isEqualTo("PROPOSAL_WINDOW_MISMATCH");
        verify(f.client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void nullRepairDoesNotLoopAndDiscoveryAndVerificationShareTotalBudget() {
        var f = new CandidateReviewEngineTest();
        var ids = List.of("stt-index-0", "stt-index-1", "stt-index-2");
        var broken = new Proposal(ids.get(0), "TARGET_TREATMENT", "최초 형식 오류 가설", List.of());
        f.discovery(ids, broken, f.proposal(ids.get(1), "그 집"), f.proposal(ids.get(2), "그 집"));
        when(f.client.completeAsJson(anyString(), anyString(), eq(ProposalRepairResult.class))).thenReturn(Optional.of(new ProposalRepairResult(null)));
        var helper = new TargetGroundingRepairTest();
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(invocation -> {
            var tree = JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            List<Verification> responses = new ArrayList<>();
            for (var c : tree.get("candidates")) responses.add(new Verification(c.get("candidateId").asText(),
                    helper.missingTargetEvidence(f.assessment(c.get("anchorId").asText(), "REVIEW_REQUIRED", "그 집"))));
            return Optional.of(new VerificationResult(responses));
        });
        var d = run(f.client, f.context("그 집 이야기", "그 집 이용자", "그 집 선택지"), 24).diagnostics().candidatePipeline();
        assertThat(d.discoveryRepairCalls()).isOne(); assertThat(d.repairCalls()).isOne();
        assertThat(d.repairs()).hasSize(3).last().extracting(CandidateReviewDiagnostics.RepairTrace::state).isEqualTo("NOT_ATTEMPTED_BUDGET");
    }
}
