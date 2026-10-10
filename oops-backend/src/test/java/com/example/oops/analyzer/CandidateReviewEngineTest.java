package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import com.example.oops.dto.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CandidateReviewEngineTest {
    @Test void denseEarlySpeechDoesNotDisplaceLateContinuationFromActualVerificationRequest() {
        // Synthetic text, irregular cadence: exercise planner + view selection + expansion together.
        long[] times = {0,1500,3000,4500,6000,9000,10000,11500,12500,13500,14500,15500,16500,
                19500,20500,21000,22500,23500,24500,26000,27500,29000,30500,31500,34000,35000,
                36500,39000,40500,42500,43500,44500,47500,49000,51500,52000,53000,55500,
                58500,60000,62500,65500,67000,68000,69500,70500,72000,75000,79000,83000,
                84000,86500,88500};
        List<TranscriptSegment> lines = new ArrayList<>();
        for (int i = 0; i < times.length; i++) lines.add(new TranscriptSegment(video, times[i], times[i] + 1500,
                i == 21 ? "그 집에 대한 반응" : i == 39 ? "대화의 마지막 연결 발언" : "대화 " + i));
        var c = new AnalysisContext(video, ContentGenre.GENERAL, lines, List.of());
        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenAnswer(invocation -> {
            var json = JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            List<String> ids = new ArrayList<>(); for (var n : json.get("primaryIds")) ids.add(n.asText());
            return Optional.of(new Discovery(ids, ids.contains("stt-index-21")
                    ? List.of(proposal("stt-index-21", "그 집")) : List.of(), List.of(), false));
        });
        verification(new Verification("candidate-1", assessment("stt-index-21", "PASS", "그 집")));
        var result = run(client, c, 24);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(eq(VERIFICATION_PROMPT), prompt.capture(), eq(VerificationResult.class));
        assertThat(prompt.getValue()).contains("대화의 마지막 연결 발언");
        var trace = result.diagnostics().candidatePipeline().candidates().get(0);
        assertThat(trace.contextSegmentIds()).contains("stt-index-21", "stt-index-39");
        assertThat(trace.contextStartMs()).isGreaterThan(0);
        assertThat(trace.contextEndMs()).isGreaterThanOrEqualTo(61500);
        assertThat(trace.contextEndMs() - trace.contextStartMs()).isLessThanOrEqualTo(60000);
        assertThat(result.findings()).isEmpty(); // More context is not forced agreement with a proposal.
    }
    @Test void contextualTargetUsesRawMentionAndSeparateGroundingWithoutRequiringResolvedLabelInQuote() {
        var c = context("이 마을에 왔어요", "여기는 뒤떨어진 곳이야");
        var p = proposal("stt-index-1", "여기는 뒤떨어진 곳이야");
        var candidate = new Candidate("candidate-1", p, c.reviewInput().segments(), false, false);
        var d = new LlmDecision("stt-index-1", "REVIEW_REQUIRED", "여기는 뒤떨어진 곳이야",
                "앞서 방문한 마을을 뒤떨어진 곳으로 낮추는 평가를 검토합니다.", "BELITTLEMENT", "방문한 마을", .6,
                null, null, List.of(), List.of(new LlmEvidence("stt-index-1", "여기는 뒤떨어진 곳이야", "PRIMARY"),
                new LlmEvidence("stt-index-1", "여기", "TARGET"), new LlmEvidence("stt-index-0", "이 마을에 왔어요", "CONTEXT")),
                "REGION", "CONTEXTUAL", "앞서 이 마을에 왔다고 말한 뒤 여기라는 지칭어로 장소 평가를 이어갑니다.",
                "시설에 대한 개인 불편일 수도 있지만 원문은 장소 자체를 뒤떨어진 곳이라고 평가합니다.", "여기");
        assertThat(validate(candidate, d, c.reviewInput()).failureCode()).isNull();
        assertThat(validate(candidate, d, c.reviewInput()).observation().details().target()).isEqualTo("방문한 마을");
        discovery(List.of("stt-index-0", "stt-index-1"), p);
        verification(new Verification("candidate-1", d));
        var findings = run(client, c, 24).findings();
        assertThat(findings).singleElement().extracting(RiskFinding::getTarget).isEqualTo("방문한 마을");
        assertThat(findings.get(0).validatedSupports()).singleElement().satisfies(s -> {
            assertThat(s.anchorId()).isEqualTo("stt-index-1");
            assertThat(s.targetType()).isEqualTo("REGION");
            assertThat(s.quotes()).extracting(FindingSupport.Quote::role).containsExactly("PRIMARY", "TARGET", "CONTEXT");
            assertThat(s.quotes()).anySatisfy(q -> {
                assertThat(q.quote()).isEqualTo("이 마을에 왔어요");
                assertThat(q.segmentId()).isEqualTo("stt-index-0");
                assertThat(q.startMs()).isZero();
            });
        });
        var explicitMismatch = new LlmDecision(d.segmentId(), d.decision(), d.evidenceText(), d.reason(), d.category(),
                d.target(), d.score(), null, null, List.of(), d.evidence(), d.targetType(),
                "EXPLICIT", d.targetReason(), d.alternativeInterpretation(), d.targetMention());
        assertThat(validate(candidate, explicitMismatch, c.reviewInput()).failureCode()).isEqualTo("TARGET_MENTION_RELATION");
        var withoutContext = new LlmDecision(d.segmentId(), d.decision(), d.evidenceText(), d.reason(), d.category(),
                d.target(), d.score(), null, null, List.of(), d.evidence().subList(0, 2), d.targetType(),
                d.targetRelation(), d.targetReason(), d.alternativeInterpretation(), d.targetMention());
        assertThat(validate(candidate, withoutContext, c.reviewInput()).failureCode()).isEqualTo("TARGET_CONTEXT_EVIDENCE_REQUIRED");
        var inventedMention = new LlmDecision(d.segmentId(), d.decision(), d.evidenceText(), d.reason(), d.category(),
                d.target(), d.score(), null, null, List.of(), d.evidence(), d.targetType(),
                d.targetRelation(), d.targetReason(), d.alternativeInterpretation(), "저 동네");
        assertThat(validate(candidate, inventedMention, c.reviewInput()).failureCode()).isEqualTo("TARGET_EVIDENCE_REQUIRED");
        var fabricatedContext = new ArrayList<>(d.evidence());
        fabricatedContext.set(2, new LlmEvidence("stt-index-0", "원문에 없는 장소 설명", "CONTEXT"));
        var fabricated = new LlmDecision(d.segmentId(), d.decision(), d.evidenceText(), d.reason(), d.category(),
                d.target(), d.score(), null, null, List.of(), fabricatedContext, d.targetType(),
                d.targetRelation(), d.targetReason(), d.alternativeInterpretation(), d.targetMention());
        assertThat(validate(candidate, fabricated, c.reviewInput()).failureCode()).isEqualTo("DECISION_QUOTE_NOT_IN_RAW");
    }

    @Test void contextualMentionSurvivesJsonDeserialization() throws Exception {
        var d = JsonMapper.builder().build().readValue("{\"segmentId\":\"s\",\"targetMention\":\"여기\"}", LlmDecision.class);
        assertThat(d.targetMention()).isEqualTo("여기");
    }

    @Test void passCannotSmuggleTargetMentionIntoNonReviewOutput() {
        var c = context("그 집 이용자는 수준이 낮아");
        var candidate = new Candidate("c", proposal("stt-index-0", "그 집"), c.reviewInput().segments(), false, false);
        var base = assessment("stt-index-0", "PASS", "그 집");
        var d = new LlmDecision(base.segmentId(), base.decision(), base.evidenceText(), base.reason(), null, null,
                null, null, null, List.of(), base.evidence(), null, null, null, null, "그 집");
        assertThat(validate(candidate, d, c.reviewInput()).failureCode()).isEqualTo("NON_REVIEW_FIELDS");
    }

    @Test void discoveryChecksScreenDependencySeparatelyWithoutRequiringTextualRisk() {
        assertThat(DISCOVERY_PROMPT).contains("위험 후보 탐색과 별도로 모든 primaryIds", "'텍스트 위험 없음'은 '화면 확인 불필요'가 아니다");
    }
    @Test void missingDuplicateAndUnparsedVerificationHaveDistinctFailureCodes() {
        for (String kind : List.of("MISSING_CANDIDATE", "DUPLICATE_CANDIDATE", "NO_PARSED_RESPONSE")) {
            reset(client);
            discovery(List.of("stt-index-0"), proposal("stt-index-0", "그 집"));
            var v = new Verification("candidate-1", assessment("stt-index-0", "PASS", "그 집"));
            if (kind.equals("MISSING_CANDIDATE")) verification();
            else if (kind.equals("DUPLICATE_CANDIDATE")) {
                // Duplicate count equal to group size is possible with a two-candidate group.
                var second = new Proposal("stt-index-0", "TARGET_TREATMENT", "두 번째 독립 평가", List.of(new Quote("stt-index-0", "그 집")));
                discovery(List.of("stt-index-0"), proposal("stt-index-0", "그 집"), second);
                verification(v, v);
            } else when(client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenReturn(Optional.empty());
            var r = run(client, context("그 집 이용자들이 수준이 낮아"), 24);
            assertThat(r.diagnostics().candidatePipeline().candidates().get(0).failureCode()).isEqualTo(kind);
            assertThat(r.findings()).isEmpty();
        }
    }
    @Test void invalidQuoteRecordsSpecificEvidenceFailureRatherThanNormalPass() {
        discovery(List.of("stt-index-0"), proposal("stt-index-0", "그 집"));
        verification(new Verification("candidate-1", assessment("stt-index-0", "PASS", "없는 원문")));
        var r = run(client, context("그 집 이용자들"), 24);
        assertThat(r.diagnostics().candidatePipeline().candidates().get(0).failureCode()).isEqualTo("DECISION_QUOTE_NOT_IN_RAW");
        assertThat(r.status()).isEqualTo(AnalyzerStatus.PARTIAL);
    }
    final OpenAiClient client = mock(OpenAiClient.class);
    final Video video = Video.builder().filename("generic.mp4").build();
    AnalysisContext context(String... lines) {
        List<TranscriptSegment> segments = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) segments.add(new TranscriptSegment(video, i * 2000L, i * 2000L + 1500, lines[i]));
        return new AnalysisContext(video, ContentGenre.GENERAL, segments,
                List.of(new ScreenText(video, 0, 2000, "화면에만 있는 글자", .9, null)));
    }
    Proposal proposal(String anchor, String quote) {
        return new Proposal(anchor, "TARGET_TREATMENT", "그 집의 이용자를 음식 선택 때문에 열등한 대상으로 묘사할 가능성을 확인합니다.",
                List.of(new Quote(anchor, quote)));
    }
    void discovery(List<String> ids, Proposal... proposals) {
        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class)))
                .thenReturn(Optional.of(new Discovery(ids, List.of(proposals), List.of(), false)));
    }
    LlmDecision assessment(String anchor, String decision, String quote) {
        boolean review = decision.equals("REVIEW_REQUIRED");
        return new LlmDecision(anchor, decision, quote, "음식 평가를 넘어 그 집의 선택과 이용자를 낮춰 묘사하는 연결을 원문에서 확인합니다.",
                review ? "MOCKERY" : null, review ? "그 집" : null, review ? .6 : null,
                null, null, decision.equals("UNCERTAIN") ? List.of("인용 주체를 식별하는 추가 문맥") : List.of(),
                review ? List.of(new LlmEvidence(anchor, quote, "PRIMARY"), new LlmEvidence(anchor, "그 집", "TARGET"))
                        : List.of(new LlmEvidence(anchor, quote, "PRIMARY")),
                review ? "BUSINESS" : null, review ? "EXPLICIT" : null,
                review ? "원문이 그 집을 평가 대상으로 지칭하며 이용 경험과 연결합니다." : null,
                review ? "단순한 음식 취향 설명으로도 읽히지만 이용자를 낮추는 별도 표현은 설명하지 못합니다." : null);
    }
    void verification(Verification... items) {
        when(client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(items))));
    }
    @Test void noCandidatesMeansExploredNotPassAndMakesNoVerificationCalls() {
        discovery(List.of("stt-index-0"));
        var r = run(client, context("맛이 별로다"), 24);
        assertThat(r.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(r.findings()).isEmpty();
        assertThat(r.evaluations().get(0).observations()).isEmpty();
        assertThat(r.diagnostics().segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.NO_CANDIDATE);
        verify(client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void productionPathUsesNewStrategyWithoutLegacyDialogueCalls() {
        discovery(List.of("stt-index-0"));
        var analyzer = new SpeechReviewAnalyzer(client, true, true, 24);
        var c = context("보통 대화");
        assertThat(analyzer.analyze(c)).isEmpty();
        var r = analyzer.consumeReviewResult(c).orElseThrow();
        assertThat(r.diagnostics().candidatePipeline().revision()).isEqualTo(REVISION);
        assertThat(r.diagnostics().dialogue()).isNull();
        verify(client, never()).completeAsJson(anyString(), anyString(), eq(LlmResult.class));
    }
    @Test void reviewRequiresSeparateVerificationAndRetainsEvidenceTimeAndScorePolicy() {
        discovery(List.of("stt-index-0"), proposal("stt-index-0", "그 집"));
        verification(new Verification("candidate-1", assessment("stt-index-0", "REVIEW_REQUIRED", "그 집")));
        var c = context("그 집 이용자들은 수준이 낮다");
        var r = run(client, c, 24);
        assertThat(r.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).getStartMs()).isZero();
        assertThat(r.findings().get(0).getReason()).contains("대조 해석");
        assertThat(r.evaluations()).allSatisfy(e -> assertThat(ReviewEvidenceValidator.validate(c.reviewInput(), e)).isEmpty());
        assertThat(TimelineEventDto.from(r.findings().get(0), null, null, null).reviewPerspective()).isEqualTo(ReviewPerspective.AFFECTED_PARTY);
    }
    @Test void verifierCanRejectProposalInsteadOfAgreeing() {
        discovery(List.of("stt-index-0"), proposal("stt-index-0", "그 집"));
        verification(new Verification("candidate-1", assessment("stt-index-0", "PASS", "그 집")));
        var r = run(client, context("그 집 음식은 내 취향이 아니다"), 24);
        assertThat(r.findings()).isEmpty();
        assertThat(r.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(r.diagnostics().candidatePipeline().rejected()).isOne();
    }
    @Test void emptyFailedAndIncompleteDiscoveryAreNotSafeResults() {
        for (var response : List.of(Optional.<Discovery>empty(), Optional.of(new Discovery(List.of(), List.of(), List.of(), false)),
                Optional.of(new Discovery(List.of("invented"), List.of(), List.of(), false)),
                Optional.of(new Discovery(List.of("stt-index-0"), List.of(), List.of(), null)))) {
            when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenReturn(response);
            var r = run(client, context("일반 대화"), 24);
            assertThat(r.status()).isEqualTo(AnalyzerStatus.FAILED);
            assertThat(r.unassessedSegmentIds()).containsExactly("stt-index-0");
            assertThat(r.notice()).isNotBlank();
        }
    }
    @Test void missingReviewedIdsProducePartialEvenWithEmptyCandidates() {
        discovery(List.of("stt-index-0"));
        var r = run(client, context("첫 발언", "다음 발언"), 24);
        assertThat(r.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(r.unassessedSegmentIds()).containsExactly("stt-index-1");
    }
    @Test void uncertainDiscoveryRetainsMissingInformationWithoutCard() {
        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenReturn(Optional.of(new Discovery(
                List.of("stt-index-0"), List.of(), List.of(new UncertainSegment("stt-index-0", List.of("깨진 전사의 실제 단어"))), false)));
        var r = run(client, context("전사가 깨짐"), 24);
        assertThat(r.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(r.findings()).isEmpty();
        assertThat(r.diagnostics().segments().get(0).decisions().get(0).missingInformation()).containsExactly("깨진 전사의 실제 단어");
    }
    @Test void inventedQuoteWrongAxisOrCrossSourceProposalNeverReachesVerification() {
        for (var p : List.of(proposal("stt-index-0", "원문에 없는 발언"),
                new Proposal("stt-index-0", "UNKNOWN", "실제 문제를 설명하는 이유", List.of(new Quote("stt-index-0", "그 집"))),
                new Proposal("stt-index-0", "TARGET_TREATMENT", "실제 문제를 설명하는 이유", List.of(new Quote("ocr-index-0", "화면에만 있는 글자"))))) {
            discovery(List.of("stt-index-0"), p);
            var r = run(client, context("그 집 음식 이야기"), 24);
            assertThat(r.status()).isEqualTo(AnalyzerStatus.PARTIAL);
            assertThat(r.findings()).isEmpty();
        }
        verify(client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void verificationMissingOrDuplicatedCandidateIsPartialNotPass() {
        discovery(List.of("stt-index-0"), proposal("stt-index-0", "그 집"));
        var item = new Verification("candidate-1", assessment("stt-index-0", "PASS", "그 집"));
        for (var response : List.of(Optional.<VerificationResult>empty(), Optional.of(new VerificationResult(List.of())),
                Optional.of(new VerificationResult(List.of(item, item))),
                Optional.of(new VerificationResult(List.of(new Verification("invented", item.assessment())))))) {
            when(client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenReturn(response);
            var r = run(client, context("그 집 음식 이야기"), 24);
            assertThat(r.status()).isEqualTo(AnalyzerStatus.PARTIAL);
            assertThat(r.diagnostics().candidatePipeline().verificationFailed()).isOne();
        }
    }
    @Test void verifierCannotInventQuoteOrReplaceAnchor() {
        discovery(List.of("stt-index-0"), proposal("stt-index-0", "그 집"));
        for (var item : List.of(assessment("stt-index-0", "REVIEW_REQUIRED", "상상한 원문"), assessment("stt-index-1", "PASS", "그 집"))) {
            verification(new Verification("candidate-1", item));
            var r = run(client, context("그 집 음식 이야기"), 24);
            assertThat(r.findings()).isEmpty();
            assertThat(r.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        }
    }
    @Test void uncertaintyFromVerificationIsRetainedWithoutPublishing() {
        discovery(List.of("stt-index-0"), proposal("stt-index-0", "그 집"));
        verification(new Verification("candidate-1", assessment("stt-index-0", "UNCERTAIN", "그 집")));
        var r = run(client, context("그 집 음식 이야기"), 24);
        assertThat(r.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(r.diagnostics().segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.UNCERTAIN);
    }
    @Test void candidateBudgetIsExplicitAndValidFindingsSurvive() {
        discovery(List.of("stt-index-0", "stt-index-1"), proposal("stt-index-0", "그 집"), proposal("stt-index-1", "그 집"));
        verification(new Verification("candidate-1", assessment("stt-index-0", "REVIEW_REQUIRED", "그 집")));
        var r = run(client, context("그 집 수준이 낮다", "그 집 이용자를 조롱한다"), 1);
        assertThat(r.findings()).hasSize(1);
        assertThat(r.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(r.diagnostics().candidatePipeline().budgetSkipped()).isOne();
        assertThat(r.unassessedSegmentIds()).contains("stt-index-1");
    }
    @Test void exactDuplicateProposalIsVerifiedOnce() {
        var p = proposal("stt-index-0", "그 집");
        discovery(List.of("stt-index-0"), p, p);
        verification(new Verification("candidate-1", assessment("stt-index-0", "PASS", "그 집")));
        var r = run(client, context("그 집 음식 이야기"), 24);
        assertThat(r.diagnostics().candidatePipeline().duplicates()).isOne();
        assertThat(r.status()).isEqualTo(AnalyzerStatus.SUCCESS);
    }
    @Test void inputIsSpeechOnlyAndVerificationOmitsUnrelatedRaw() throws Exception {
        var c = context("그 집 수준이 낮다", "관련 상황", "이야기 마무리");
        discovery(List.of("stt-index-0", "stt-index-1", "stt-index-2"), proposal("stt-index-0", "그 집"));
        verification(new Verification("candidate-1", assessment("stt-index-0", "PASS", "그 집")));
        run(client, c, 24);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(eq(DISCOVERY_PROMPT), prompt.capture(), eq(Discovery.class));
        assertThat(prompt.getValue()).doesNotContain("ocr-index", "화면에만", "confidence");
        var json = JsonMapper.builder().build().readTree(prompt.getValue());
        assertThat(json.get("raw").size()).isEqualTo(3);
        assertThat(json.get("windows").size()).isEqualTo(1);
        assertThat(json.get("windows").get(0).get("anchorIds").size()).isEqualTo(3);
        verify(client).completeAsJson(eq(VERIFICATION_PROMPT), prompt.capture(), eq(VerificationResult.class));
        assertThat(prompt.getValue()).contains("hypothesisNotEvidence", "candidate-1").doesNotContain("ocr-index");
    }
    @Test void truncationNeverLooksLikeCompleteCoverage() {
        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenReturn(Optional.of(new Discovery(
                List.of("stt-index-0"), List.of(), List.of(), true)));
        var r = run(client, context("보통 발언"), 24);
        assertThat(r.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(r.notice()).contains("후보 잘림 1배치");
    }
    @Test void compactPromptsAreSmallerThanLegacyButThisIsNotATokenOrAccuracyBenchmark() {
        assertThat(DISCOVERY_PROMPT.length()).isLessThan(TextReviewEngine.CONTRACT.length());
        assertThat(VERIFICATION_PROMPT.length()).isLessThan(TextReviewEngine.CONTRACT.length());
        assertThat(DISCOVERY_PROMPT).doesNotContain("# 유형 정의", "score");
    }
    @Test void verificationIsBatchedFourAtATimeNotOneAgentCallPerCandidate() {
        String[] lines = java.util.stream.IntStream.range(0, 5).mapToObj(i -> "그 집 음식 이야기 " + i).toArray(String[]::new);
        var ids = java.util.stream.IntStream.range(0, 5).mapToObj(i -> "stt-index-" + i).toList();
        discovery(ids, ids.stream().map(id -> proposal(id, "그 집")).toArray(Proposal[]::new));
        when(client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(invocation -> {
            var json = JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            List<Verification> verified = new ArrayList<>();
            for (var node : json.get("candidates")) verified.add(new Verification(node.get("candidateId").asText(),
                    assessment(node.get("anchorId").asText(), "PASS", "그 집")));
            return Optional.of(new VerificationResult(verified));
        });
        var r = run(client, context(lines), 24);
        assertThat(r.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(r.diagnostics().candidatePipeline().verificationCalls()).isEqualTo(2);
    }
    @Test void expressionOnlyCandidateCanBeReviewedWithoutInventedTarget() {
        var quote = "사람의 살을 뜯는 맛";
        discovery(List.of("stt-index-0"), new Proposal("stt-index-0", "EXPRESSION_CONTENT", "구체적인 사람의 신체 훼손 이미지를 맛에 비유한 표현입니다.",
                List.of(new Quote("stt-index-0", quote))));
        verification(new Verification("candidate-1", new LlmDecision("stt-index-0", "REVIEW_REQUIRED", quote,
                "음식의 감각을 구체적인 인간 신체 훼손 이미지로 설명하여 표현 수위 검토가 필요합니다.",
                "GRAPHIC_METAPHOR", null, .8, null, null, List.of(), List.of(new LlmEvidence("stt-index-0", quote, "PRIMARY")),
                null, null, null, "맛을 강조하는 과장 비유지만 사람의 살을 뜯는 구체적 이미지라는 별도 검토 이유가 남습니다.")));
        var r = run(client, context(quote), 24);
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).getTarget()).isNull();
        assertThat(r.findings().get(0).getScore()).isEqualTo(ReviewScorePolicy.cap(RiskCategory.GRAPHIC_METAPHOR, .8));
    }
    @Test void verifierCannotUseRawFromOtherCandidatesWindow() {
        List<TranscriptSegment> lines = List.of(new TranscriptSegment(video, 0, 1500, "그 집 음식 이야기"),
                new TranscriptSegment(video, 60000, 61500, "먼 구간 이야기"));
        var c = new AnalysisContext(video, ContentGenre.GENERAL, lines, List.of());
        discovery(List.of("stt-index-0", "stt-index-1"), proposal("stt-index-0", "그 집"));
        var original = assessment("stt-index-0", "PASS", "그 집");
        var invalid = new LlmDecision(original.segmentId(), original.decision(), original.evidenceText(), original.reason(),
                null, null, null, null, null, List.of(), List.of(new LlmEvidence("stt-index-0", "그 집", "PRIMARY"),
                new LlmEvidence("stt-index-1", "먼 구간", "CONTEXT")), null, null, null, null);
        verification(new Verification("candidate-1", invalid));
        assertThat(run(client, c, 24).status()).isEqualTo(AnalyzerStatus.PARTIAL);
    }
    @Test void runtimeExceptionsBecomeExplicitIncompleteCoverage() {
        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenThrow(new IllegalStateException("offline"));
        assertThat(run(client, context("보통 대화"), 24).status()).isEqualTo(AnalyzerStatus.FAILED);
    }
    @Test void characterRoutingIsDerivedMetadataAndDoesNotChangeStoredFinding() {
        assertThat(ReviewPerspective.from(RiskCategory.UNFAMILIAR_CONTEXT)).isEqualTo(ReviewPerspective.COMMUNITY_CONTEXT);
        assertThat(ReviewPerspective.from(RiskCategory.PRIVACY)).isEqualTo(ReviewPerspective.EXPRESSION_SAFETY);
        assertThat(ReviewPerspective.from(RiskCategory.FACT_ERROR)).isEqualTo(ReviewPerspective.FACT_VERIFICATION);
        assertThat(ReviewPerspective.from(RiskCategory.CAPTION_MISMATCH)).isNull();
    }
    @Test void oversizedRawIsNotSilentlyTruncatedOrSentWithoutABound() {
        var r = run(client, context("가".repeat(12001)), 24);
        assertThat(r.status()).isEqualTo(AnalyzerStatus.FAILED);
        assertThat(r.unassessedSegmentIds()).containsExactly("stt-index-0");
        verifyNoInteractions(client);
    }
    @Test void missingTargetNormalAlternativeAndInvalidScoreNeverPublish() {
        discovery(List.of("stt-index-0"), proposal("stt-index-0", "그 집"));
        var good = assessment("stt-index-0", "REVIEW_REQUIRED", "그 집");
        for (var bad : List.of(
                new LlmDecision(good.segmentId(), good.decision(), good.evidenceText(), good.reason(), good.category(),
                        null, .6, null, null, List.of(), good.evidence(), null, null, null, good.alternativeInterpretation()),
                new LlmDecision(good.segmentId(), good.decision(), good.evidenceText(), good.reason(), good.category(),
                        good.target(), 1.2, null, null, List.of(), good.evidence(), good.targetType(), good.targetRelation(), good.targetReason(), good.alternativeInterpretation()),
                new LlmDecision(good.segmentId(), good.decision(), good.evidenceText(), good.reason(), good.category(),
                        good.target(), .6, null, null, List.of(), good.evidence(), good.targetType(), good.targetRelation(), good.targetReason(), null))) {
            verification(new Verification("candidate-1", bad));
            var r = run(client, context("그 집 음식 이야기"), 24);
            assertThat(r.findings()).isEmpty();
            assertThat(r.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        }
    }
    @Test void regionCanBeTheTargetWithoutInventingAnAttackOnAllResidents() {
        discovery(List.of("stt-index-0", "stt-index-1"), new Proposal("stt-index-1", "TARGET_TREATMENT",
                "시설이 부족하다는 설명을 지역 자체의 열등함과 연결하는 구체적 평가가 있습니다.",
                List.of(new Quote("stt-index-0", "B마을"), new Quote("stt-index-1", "볼품없는 동네"))));
        verification(new Verification("candidate-1", new LlmDecision("stt-index-1", "REVIEW_REQUIRED", "볼품없는 동네",
                "매장 부재를 근거로 동네 자체가 볼품없다고 평가하여 지역을 낮추는 연결이 있습니다. 주민 전체 일반화와는 별개입니다.",
                "BELITTLEMENT", "B마을", .6, null, null, List.of(), List.of(
                        new LlmEvidence("stt-index-1", "볼품없는 동네", "PRIMARY"),
                        new LlmEvidence("stt-index-0", "B마을", "TARGET")),
                "REGION", "CONTEXTUAL", "앞 발언의 B마을이 다음 동네 평가의 지시 대상이라는 연결을 제공합니다.",
                "매장이 없어 아쉽다는 리뷰일 수 있지만 동네가 볼품없다는 가치 평가는 부재 사실만 설명하는 것과 다릅니다.")));
        var r = run(client, context("B마을에는 가게가 없네", "그래서 볼품없는 동네라고 하는 거야"), 24);
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).getTarget()).isEqualTo("B마을");
        assertThat(r.findings().get(0).getCategory()).isEqualTo(RiskCategory.BELITTLEMENT);
    }
    @Test void shopAbsenceAndPersonalInconvenienceAreNotAutomaticallyPublished() {
        discovery(List.of("stt-index-0"), new Proposal("stt-index-0", "TARGET_TREATMENT", "매장 부재와 불편 표현의 지역 평가 연결을 확인합니다.",
                List.of(new Quote("stt-index-0", "매장이 없어서"))));
        verification(new Verification("candidate-1", new LlmDecision("stt-index-0", "PASS", "매장이 없어서",
                "매장 부재로 이동해야 하는 개인 불편을 설명하며 지역의 열등함이나 주민 조롱으로 연결하는 표현은 없습니다.",
                null, null, null, null, null, List.of(), List.of(new LlmEvidence("stt-index-0", "매장이 없어서", "PRIMARY")),
                null, null, null, "실제 이동의 불편을 설명하는 지역 정보로 읽힙니다.")));
        assertThat(run(client, context("매장이 없어서 옆 마을로 가야 하니 불편하다"), 24).findings()).isEmpty();
    }
    @Test void targetContextIncludesLaterQualificationAndDiagnosticShowsExactlyWhatWasSent() {
        List<TranscriptSegment> lines = new ArrayList<>();
        for (int i = 0; i < 35; i++) lines.add(new TranscriptSegment(video, i * 2000L, i * 2000L + 1500,
                i == 10 ? "그 집 음식 이야기" : i == 24 ? "주변 설명의 맥락을 보충하는 뒤 발언" : "일반적인 대화 " + i));
        var c = new AnalysisContext(video, ContentGenre.GENERAL, lines, List.of());
        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenAnswer(invocation -> {
            var json = JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            List<String> ids = new ArrayList<>(); for (var n : json.get("primaryIds")) ids.add(n.asText());
            return Optional.of(new Discovery(ids, ids.contains("stt-index-10") ? List.of(proposal("stt-index-10", "그 집")) : List.of(), List.of(), false));
        });
        verification(new Verification("candidate-1", assessment("stt-index-10", "PASS", "그 집")));
        var r = run(client, c, 24);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(eq(VERIFICATION_PROMPT), prompt.capture(), eq(VerificationResult.class));
        assertThat(prompt.getValue()).contains("주변 설명의 맥락을 보충하는 뒤 발언");
        var trace = r.diagnostics().candidatePipeline().candidates().get(0);
        assertThat(trace.contextExpanded()).isTrue();
        assertThat(trace.contextSegmentIds()).contains("stt-index-24");
        assertThat(trace.contextEndMs() - trace.contextStartMs()).isLessThanOrEqualTo(60000);
        assertThat(trace.contextLimited()).isTrue();
    }
    @Test void placePolicySeparatesGenericInformationFromValueJudgementWithoutBenchmarkKeywordRules() {
        assertThat(POLICY).contains("주민 전체 일반화는 조롱의 필수 조건이 아니다", "정보나 개인의 불편만으로 경고하지 않는다");
        assertThat(VERIFICATION_PROMPT).contains("지역 가치 절하·특정 대상 조롱", "비하를 만들지 않는다");
        assertThat(POLICY + DISCOVERY_PROMPT + VERIFICATION_PROMPT).doesNotContain("롯데리아", "영양", "피식대학");
    }
}
