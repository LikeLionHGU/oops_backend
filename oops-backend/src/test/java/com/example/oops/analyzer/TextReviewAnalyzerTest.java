package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.stream.IntStream;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TextReviewAnalyzerTest {
    private final OpenAiClient client = mock(OpenAiClient.class);
    private final Video video = Video.builder().filename("offline.mp4").build();

    private ContentAnalyzer analyzer(TimelineEventType type) {
        return type == TimelineEventType.SPEECH ? new SpeechReviewAnalyzer(client, false) : new ScreenTextReviewAnalyzer(client);
    }

    private AnalysisContext context(TimelineEventType type) {
        return type == TimelineEventType.SPEECH ? new AnalysisContext(video, ContentGenre.GENERAL,
                List.of(new TranscriptSegment(video, 1_000, 2_000, "😀그 집 음식은 돈이 아깝다")),
                List.of(new ScreenText(video, 1_000, 2_000, "함께 보이는 자막", 0.4, null)))
                : new AnalysisContext(video, ContentGenre.GENERAL,
                List.of(new TranscriptSegment(video, 1_000, 2_000, "함께 들리는 발언")),
                List.of(editorial("그 집 음식은 돈이 아갑다")));
    }

    private ScreenText editorial(String text) {
        var result = new ScreenText(video, 1_000, 2_000, text, 0.4, null);
        result.classify(ScreenTextRole.EDITORIAL, "테스트용 편집 텍스트");
        return result;
    }

    private String id(TimelineEventType type) { return type == TimelineEventType.SPEECH ? "stt-index-0" : "ocr-index-0"; }
    private LlmDecision decision(String id, String decision, String quote, String category, List<String> missing) {
        return new LlmDecision(id, decision, quote, "소개 중인 가게의 메뉴에 대한 강하고 단정적인 평가입니다.",
                category, "그 집", 0.9, null, null, missing,
                "REVIEW_REQUIRED".equals(decision)
                        ? List.of(new LlmEvidence(id, quote, "PRIMARY"), new LlmEvidence(id, "그 집", "TARGET"))
                        : List.of(new LlmEvidence(id, quote, "PRIMARY")),
                "BUSINESS", "EXPLICIT", "원문에서 그 집의 음식이나 가격을 평가 대상으로 직접 지칭합니다.", null);
    }
    private void response(LlmDecision... decisions) {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(List.of(decisions))));
    }

    @ParameterizedTest
    @EnumSource(value = TimelineEventType.class, names = {"SPEECH", "CAPTION"})
    void explicitPassIsRecordedButNotPublishedAndContextComesFromOtherSource(TimelineEventType type) {
        response(decision(id(type), "PASS", "그 집", null, List.of()));
        var analyzer = analyzer(type);
        var context = context(type);
        assertThat(analyzer.analyze(context)).isEmpty();
        var run = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(run.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(run.evaluations().get(0).observations().get(0).decision()).isEqualTo(ReviewEvaluation.Decision.PASS);
        assertThat(analyzer.consumeReviewResult(context)).isEmpty();
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(anyString(), prompt.capture(), eq(LlmResult.class));
        assertThat(prompt.getValue()).contains("primary", "context", "confidence", "stt-index-0", "ocr-index-0");
    }

    @ParameterizedTest
    @EnumSource(value = TimelineEventType.class, names = {"SPEECH", "CAPTION"})
    void validReviewIsProjectedWithExistingScoreCapAndSourceTime(TimelineEventType type) {
        response(decision(id(type), "REVIEW_REQUIRED", "그 집", "STRONG_NEGATIVE_REVIEW", List.of()));
        var analyzer = analyzer(type);
        var context = context(type);
        var finding = analyzer.analyze(context).get(0);
        assertThat(finding.getScore()).isEqualTo(0.39);
        assertThat(finding.getStartMs()).isEqualTo(1_000);
        assertThat(finding.getEndMs()).isEqualTo(2_000);
        assertThat(finding.getEventType()).isEqualTo(type);
        var run = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(run.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(ReviewEvidenceValidator.validate(context.reviewInput(), run.evaluations().get(0))).isEmpty();
        if (type == TimelineEventType.SPEECH) {
            assertThat(run.evaluations().get(0).observations().get(0).evidence().get(0).start()).isOne();
        }
    }

    @Test
    void uncertainDecisionKeepsMissingInformationInsteadOfBecomingRiskCard() {
        response(decision("stt-index-0", "UNCERTAIN", "그 집", null, List.of("구체적인 가게의 식별 정보")));
        var analyzer = analyzer(TimelineEventType.SPEECH);
        var context = context(TimelineEventType.SPEECH);
        assertThat(analyzer.analyze(context)).isEmpty();
        assertThat(analyzer.consumeCoverageNotice(context)).hasValueSatisfying(s -> assertThat(s).contains("판단 보류 1건"));
        var run = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(run.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(run.evaluations().get(0).observations().get(0).missingInformation()).hasSize(1);
    }

    @Test
    void emptyFailedAndMalformedResponsesNeverBecomePass() {
        var context = context(TimelineEventType.SPEECH);
        var analyzer = analyzer(TimelineEventType.SPEECH);
        for (var value : List.of(Optional.<LlmResult>empty(), Optional.of(new LlmResult(List.of())),
                Optional.of(new LlmResult(null)))) {
            when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenReturn(value);
            assertThat(analyzer.analyze(context)).isEmpty();
            var run = analyzer.consumeReviewResult(context).orElseThrow();
            assertThat(run.status()).isEqualTo(AnalyzerStatus.FAILED);
            assertThat(run.notice()).contains("미판정 1구간");
            assertThat(run.evaluations()).allSatisfy(e -> assertThat(e.observations()).isEmpty());
        }
    }

    @Test
    void rejectsInventedNormalizedEvidenceWrongIdCategoryDecisionAndIncompleteUncertainty() {
        var context = context(TimelineEventType.SPEECH);
        var analyzer = analyzer(TimelineEventType.SPEECH);
        for (var invalid : List.of(decision("stt-index-0", "REVIEW_REQUIRED", "사기다", "BELITTLEMENT", List.of()),
                decision("stt-index-0", "REVIEW_REQUIRED", "그집", "BELITTLEMENT", List.of()),
                decision("ocr-index-0", "PASS", "함께", null, List.of()),
                decision("stt-index-0", "REVIEW_REQUIRED", "그 집", "NOT_A_CATEGORY", List.of()),
                decision("stt-index-0", "SAFE", "그 집", null, List.of()),
                decision("stt-index-0", "UNCERTAIN", "그 집", null, List.of()))) {
            response(invalid);
            assertThat(analyzer.analyze(context)).isEmpty();
            var run = analyzer.consumeReviewResult(context).orElseThrow();
            assertThat(run.status()).isEqualTo(AnalyzerStatus.FAILED);
            assertThat(run.notice()).contains("응답 검증 실패 2건", "누락 재검토 1배치");
        }
    }

    @Test
    void conflictingPassAndRiskInSameBatchAreUnassessedRatherThanSilentlySelected() {
        response(decision("stt-index-0", "PASS", "그 집", null, List.of()),
                decision("stt-index-0", "REVIEW_REQUIRED", "그 집", "STRONG_NEGATIVE_REVIEW", List.of()));
        var analyzer = analyzer(TimelineEventType.SPEECH);
        var context = context(TimelineEventType.SPEECH);
        assertThat(analyzer.analyze(context)).isEmpty();
        var run = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(run.status()).isEqualTo(AnalyzerStatus.FAILED);
        assertThat(run.conflictingSegmentIds()).containsExactly("stt-index-0");
        assertThat(run.evaluations().get(0).observations()).hasSize(2);
    }

    @Test
    void missingPrimaryDecisionIsReportedButOtherValidFindingSurvives() {
        response(decision("stt-index-0", "REVIEW_REQUIRED", "그 집", "STRONG_NEGATIVE_REVIEW", List.of()));
        var context = new AnalysisContext(video, null, List.of(
                new TranscriptSegment(video, 0, 1_000, "그 집 음식은 돈이 아깝다"),
                new TranscriptSegment(video, 2_000, 3_000, "일반 대화")), null);
        var analyzer = analyzer(TimelineEventType.SPEECH);
        assertThat(analyzer.analyze(context)).hasSize(1);
        var run = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(run.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(run.notice()).contains("대상 2구간, 유효 판정 1구간, 미판정 1구간");
        assertThat(run.unassessedSegmentIds()).containsExactly("stt-index-1");
    }

    @Test
    void repairsOnlyMissingPrimaryAndRecordsExplicitPassWithoutAssumingSafety() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenReturn(
                Optional.of(new LlmResult(List.of(decision("stt-index-0", "PASS", "롯데리아", null, List.of())))),
                Optional.of(new LlmResult(List.of(decision("stt-index-1", "PASS", "한계를", null, List.of())))));
        var context = new AnalysisContext(video, null, List.of(
                new TranscriptSegment(video, 0, 1_000, "롯데리아 없나?"),
                new TranscriptSegment(video, 2_000, 3_000, "한계를 느꼈다")), null);
        var analyzer = analyzer(TimelineEventType.SPEECH);
        assertThat(analyzer.analyze(context)).isEmpty();
        var result = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(result.unassessedSegmentIds()).isEmpty();
        assertThat(result.evaluations()).hasSize(2);
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).completeAsJson(anyString(), prompts.capture(), eq(LlmResult.class));
        var retry = tools.jackson.databind.json.JsonMapper.builder().build().readTree(prompts.getAllValues().get(1));
        assertThat(retry.get("primary").size()).isOne();
        assertThat(retry.get("primary").get(0).get("id").asString()).isEqualTo("stt-index-1");
    }

    @Test
    void repairBudgetIsBoundedAndRemainingSegmentsNeverBecomeImplicitPass() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(List.of(decision("unknown", "PASS", "대화", null, List.of())))));
        var context = new AnalysisContext(video, null, IntStream.range(0, 100)
                .mapToObj(i -> new TranscriptSegment(video, i * 1_000, i * 1_000 + 500, "일반 대화" + i)).toList(), null);
        var analyzer = analyzer(TimelineEventType.SPEECH);
        assertThat(analyzer.analyze(context)).isEmpty();
        var result = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.FAILED);
        assertThat(result.unassessedSegmentIds()).hasSize(100);
        assertThat(result.notice()).contains("누락 재검토 6배치", "복구 0구간");
        verify(client, times(12)).completeAsJson(anyString(), anyString(), eq(LlmResult.class));
    }

    @Test
    void overlappingOriginalBatchCanRecoverMissingIdWithoutRedundantRepair() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenAnswer(invocation -> {
            boolean first = calls.getAndIncrement() == 0;
            var tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            List<LlmDecision> decisions = new ArrayList<>();
            tree.get("primary").forEach(node -> {
                String id = node.get("id").asString();
                if (!(first && id.equals("stt-index-17"))) decisions.add(decision(id, "PASS", "그 집", null, List.of()));
            });
            return Optional.of(new LlmResult(decisions));
        });
        var context = new AnalysisContext(video, null, IntStream.range(0, 25)
                .mapToObj(i -> new TranscriptSegment(video, i * 1_000, i * 1_000 + 500, "그 집 음식" + i)).toList(), null);
        var analyzer = analyzer(TimelineEventType.SPEECH);
        assertThat(analyzer.analyze(context)).isEmpty();
        var result = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(result.unassessedSegmentIds()).isEmpty();
        assertThat(result.notice()).contains("누락 재검토 0배치");
        verify(client, times(2)).completeAsJson(anyString(), anyString(), eq(LlmResult.class));
    }

    @Test
    void rawOcrAndInferredReadingStaySeparateInIntermediateResult() {
        var item = new LlmDecision("ocr-index-0", "REVIEW_REQUIRED", "아갑다", "소개 중인 가게의 메뉴를 평가하는 대목입니다.",
                "STRONG_NEGATIVE_REVIEW", "그 집", 0.9, null, "그 집 음식은 돈이 아깝다", List.of(),
                List.of(new LlmEvidence("ocr-index-0", "아갑다", "PRIMARY"), new LlmEvidence("ocr-index-0", "그 집", "TARGET")),
                "BUSINESS", "EXPLICIT", "화면의 그 집이라는 지칭이 음식 평가의 대상을 직접 나타냅니다.", null);
        response(item);
        var analyzer = analyzer(TimelineEventType.CAPTION);
        var context = context(TimelineEventType.CAPTION);
        assertThat(analyzer.analyze(context).get(0).getCaptionText()).contains("아갑다", "(해석:");
        var observation = analyzer.consumeReviewResult(context).orElseThrow().evaluations().get(0).observations().get(0);
        assertThat(observation.evidence().get(0).quote()).isEqualTo("아갑다");
        assertThat(observation.details().reading()).contains("아깝다");
    }

    @Test
    void overlappingBatchResponsesAreDeduplicatedBySegmentNotStartTime() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenAnswer(invocation -> {
            var tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            List<LlmDecision> decisions = new ArrayList<>();
            tree.get("primary").forEach(node -> decisions.add(decision(node.get("id").asString(),
                    "REVIEW_REQUIRED", "그 집", "STRONG_NEGATIVE_REVIEW", List.of())));
            return Optional.of(new LlmResult(decisions));
        });
        var context = new AnalysisContext(video, null, IntStream.range(0, 25)
                .mapToObj(i -> new TranscriptSegment(video, i * 1_000, i * 1_000 + 500, "그 집 음식" + i)).toList(), null);
        var analyzer = analyzer(TimelineEventType.SPEECH);
        assertThat(analyzer.analyze(context)).hasSize(25);
        var run = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(run.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(run.notice()).contains("미판정 0구간", "주변 문맥 제한");
        verify(client, times(2)).completeAsJson(anyString(), anyString(), eq(LlmResult.class));
    }

    @Test
    void newRunDoesNotInheritFailedOrUncertainStateAndBlankInputIsNotSupported() {
        var analyzer = analyzer(TimelineEventType.SPEECH);
        var context = context(TimelineEventType.SPEECH);
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenReturn(Optional.empty());
        analyzer.analyze(context);
        response(decision("stt-index-0", "PASS", "그 집", null, List.of()));
        analyzer.analyze(context);
        assertThat(analyzer.consumeCoverageNotice(context)).isEmpty();
        assertThat(analyzer.consumeReviewResult(context).orElseThrow().status()).isEqualTo(AnalyzerStatus.SUCCESS);
        when(client.isEnabled()).thenReturn(true);
        assertThat(analyzer.supports(new AnalysisContext(video, null,
                List.of(new TranscriptSegment(video, 0, 1, " ")), null))).isFalse();
    }

    @Test
    void contradictoryOverlapDecisionsAreRetainedButNotProjected() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenAnswer(invocation -> {
            boolean first = calls.getAndIncrement() == 0;
            var tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree((String) invocation.getArgument(1));
            List<LlmDecision> decisions = new ArrayList<>();
            tree.get("primary").forEach(node -> decisions.add(decision(node.get("id").asString(),
                    first ? "REVIEW_REQUIRED" : "PASS", "그 집", first ? "STRONG_NEGATIVE_REVIEW" : null, List.of())));
            return Optional.of(new LlmResult(decisions));
        });
        var context = new AnalysisContext(video, null, IntStream.range(0, 25)
                .mapToObj(i -> new TranscriptSegment(video, i * 1_000, i * 1_000 + 500, "그 집 음식" + i)).toList(), null);
        var analyzer = analyzer(TimelineEventType.SPEECH);
        assertThat(analyzer.analyze(context)).hasSize(17);
        var run = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(run.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(run.conflictingSegmentIds()).containsExactly("stt-index-17", "stt-index-18", "stt-index-19");
        assertThat(run.unassessedSegmentIds()).containsExactly("stt-index-17", "stt-index-18", "stt-index-19");
        assertThat(run.evaluations()).hasSize(2);
        assertThat(run.evaluations().get(0).observations()).hasSize(20);
        assertThat(run.evaluations().get(1).observations()).hasSize(8);
    }

    @Test
    void differentRawSegmentsAtSameTimestampAreNotDeduplicatedPrematurely() {
        response(decision("stt-index-0", "REVIEW_REQUIRED", "그 집", "STRONG_NEGATIVE_REVIEW", List.of()),
                decision("stt-index-1", "REVIEW_REQUIRED", "그 집", "STRONG_NEGATIVE_REVIEW", List.of()));
        var context = new AnalysisContext(video, null, List.of(new TranscriptSegment(video, 0, 500, "그 집 음식"),
                new TranscriptSegment(video, 0, 500, "그 집 가격")), null);
        var analyzer = analyzer(TimelineEventType.SPEECH);
        assertThat(analyzer.analyze(context)).extracting(RiskFinding::getText).containsExactly("그 집 음식", "그 집 가격");
    }

    @Test
    void newResponseContractDeserializesWithoutNetwork() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var result = mapper.readValue("""
                {"evaluations":[{"segmentId":"stt-index-0","decision":"PASS","evidenceText":"원문",
                "reason":"일반적인 상황 설명입니다.","category":null,"target":null,"score":null,
                "context":null,"reading":null,"missingInformation":[]}]}
                """, LlmResult.class);
        assertThat(result.evaluations().get(0).decision()).isEqualTo("PASS");
    }

    @Test
    void unclassifiedAndBackgroundOcrAreNotPrimaryOrImplicitlySafe() {
        var unknown = new ScreenText(video, 0, 1_000, "메뉴판 9000원", 0.9, null);
        var background = new ScreenText(video, 0, 1_000, "가게 이름", 0.9, null);
        background.classify(ScreenTextRole.BACKGROUND, "배경 추정");
        var context = new AnalysisContext(video, null, null, List.of(unknown, background));
        var analyzer = analyzer(TimelineEventType.CAPTION);
        assertThat(analyzer.analyze(context)).isEmpty();
        var run = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(run.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(run.unassessedSegmentIds()).hasSize(2);
        assertThat(run.notice()).contains("편집 텍스트 추정 0구간", "출처 불확실");
        verifyNoInteractions(client);
    }
}
