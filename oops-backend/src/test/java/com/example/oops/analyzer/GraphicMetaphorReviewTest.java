package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import com.example.oops.fusion.FindingFusionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.stream.IntStream;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Simulated output/contract tests. These do not measure the model's semantic accuracy. */
class GraphicMetaphorReviewTest {
    private final OpenAiClient client = mock(OpenAiClient.class);
    private final Video video = Video.builder().filename("metaphor.mp4").build();
    private final String[] text = {"이 음식은 집에서 먹던 맛이네", "사람의 살을 씹는 듯한 식감이야"};
    private static final String ALTERNATIVE = "음식의 식감을 과장하는 비유이며 실제 사람을 해치겠다는 위협이나 행위 서술은 아닙니다.";

    private AnalysisContext context(String... lines) {
        return new AnalysisContext(video, ContentGenre.GENERAL, IntStream.range(0, lines.length)
                .mapToObj(i -> new TranscriptSegment(video, i * 2000, i * 2000 + 1000, lines[i])).toList(), null);
    }
    private LlmDecision pass(int i, String quote) {
        return new LlmDecision("stt-index-" + i, "PASS", quote,
                "이 구간은 문맥 설명으로 검토했으며 별도 후보는 연결된 표현의 대표 구간에서 판단합니다.",
                null, null, null, null, null, List.of());
    }
    private LlmDecision graphic(String id, String quote, String alternative, List<LlmEvidence> evidence) {
        return new LlmDecision(id, "REVIEW_REQUIRED", quote,
                "음식의 식감을 사람의 살을 씹는 이미지에 직접 빗대어 표현 자체를 검토할 근거가 있습니다.",
                "GRAPHIC_METAPHOR", null, 0.95, null, null, List.of(), evidence,
                null, null, null, alternative);
    }
    private List<LlmEvidence> evidence() {
        return List.of(new LlmEvidence("stt-index-1", text[1], "PRIMARY"),
                new LlmEvidence("stt-index-0", text[0], "CONTEXT"));
    }
    private Result run(AnalysisContext c) {
        var analyzer = new SegmentReviewFixture(client);
        analyzer.analyze(c);
        return analyzer.consumeReviewResult(c).orElseThrow();
    }
    private void response(List<LlmDecision> segments) {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(segments)));
    }

    @Test void segmentExpressionCanPublishWithoutInventingAnAttackedPersonOrActualViolence() {
        response(List.of(pass(0, text[0]), graphic("stt-index-1", text[1], ALTERNATIVE, evidence())));
        var result = run(context(text));
        assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(result.findings()).hasSize(1);
        var f = result.findings().get(0);
        assertThat(f.getCategory()).isEqualTo(RiskCategory.GRAPHIC_METAPHOR);
        assertThat(f.getTarget()).isNull();
        assertThat(f.getStartMs()).isEqualTo(2000);
        assertThat(f.getScore()).isEqualTo(0.59);
        assertThat(f.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(f.getReason()).contains(text[0], ALTERNATIVE);
        verify(client).completeAsJson(argThat(s -> s.contains(TextReviewEngine.EVIDENCE_CONTRACT)), anyString(), eq(LlmResult.class));
    }

    @ParameterizedTest @ValueSource(strings = {"", "정상입니다", "NULL"})
    void graphicCandidateNeedsUsefulAlternativeInterpretation(String alternative) {
        response(List.of(pass(0, text[0]), graphic("stt-index-1", text[1],
                "NULL".equals(alternative) ? null : alternative, evidence())));
        var result = run(context(text));
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().failureCounts()).containsKey(ReviewDiagnostics.Failure.MISSING_ALTERNATIVE_INTERPRETATION);
        assertThat(result.unassessedSegmentIds()).contains("stt-index-1");
    }

    @Test void unquotedInventedBodyImageIsStillRejected() {
        response(List.of(pass(0, text[0]), graphic("stt-index-1", "원문에 없는 훼손 표현", ALTERNATIVE,
                List.of(new LlmEvidence("stt-index-1", "원문에 없는 훼손 표현", "PRIMARY")))));
        var result = run(context(text));
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().failureCounts()).containsKey(ReviewDiagnostics.Failure.QUOTE_NOT_IN_RAW);
        assertThat(result.diagnostics().toString()).doesNotContain("원문에 없는 훼손 표현");
    }

    @Test void optionalTargetOnGraphicCandidateStillNeedsTargetEvidence() {
        var risk = new LlmDecision("stt-index-1", "REVIEW_REQUIRED", text[1],
                "모델이 특정 대상을 주장하더라도 그 대상의 원문 연결을 별도로 검증해야 합니다.",
                "GRAPHIC_METAPHOR", "이 음식", 0.6, null, null, List.of(), evidence(),
                "PRODUCT", "CONTEXTUAL", "앞선 음식 지시어를 표현 대상으로 연결한다고 반환했습니다.", ALTERNATIVE);
        response(List.of(pass(0, text[0]), risk));
        var result = run(context(text));
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().failureCounts()).containsKey(ReviewDiagnostics.Failure.MISSING_TARGET_EVIDENCE);
    }

    @Test void realViolenceCandidateIsNotAutomaticallyRelabeledOrScoreCappedAsAMetaphor() {
        String line = "그 사람을 실제로 때리겠다고 위협했어";
        var risk = new LlmDecision("stt-index-0", "REVIEW_REQUIRED", line,
                "실제 사람에게 가할 폭력을 명시적으로 위협하는 발언이라고 모델이 반환했습니다.",
                "VIOLENCE", null, 0.8, null, null, List.of(),
                List.of(new LlmEvidence("stt-index-0", line, "PRIMARY")), null, null, null, null);
        response(List.of(risk));
        assertThat(run(context(line)).findings()).singleElement().satisfies(f -> {
            assertThat(f.getCategory()).isEqualTo(RiskCategory.VIOLENCE);
            assertThat(f.getScore()).isEqualTo(0.8);
            assertThat(f.getSeverity()).isEqualTo(Severity.HIGH);
        });
    }

    @ParameterizedTest @ValueSource(strings = {
            "할머니가 해준 음식이 생각나|할머니의 정성이 느껴지는 맛이야",
            "버터가 부드럽네|입에서 녹는 맛이야",
            "고기를 익혔어|껍질은 바삭하고 살은 촉촉해",
            "이 표현은 쓰지 말자|사람 살을 씹는다는 비유는 음식 설명에서 빼자",
            "오늘 해부학 수업이야|피부와 근육의 구조를 설명할게",
            "제 입에는 조금 짜네|제 취향에는 별로야"})
    void simulatedNormalAndCriticalQuotationPassesAreNotPromotedByBodyOrFamilyWords(String sample) {
        String[] lines = sample.split("\\|");
        var c = context(lines);
        var assessment = new LlmDecision("stt-index-1", "PASS", lines[1],
                "원문은 기억이나 취향·식재료 설명 또는 비판적 인용으로 제공되었으며 표현 자체의 후보를 만들지 않습니다.",
                null, null, null, null, null, List.of(), List.of(
                        new LlmEvidence("stt-index-1", lines[1], "PRIMARY"),
                        new LlmEvidence("stt-index-0", lines[0], "CONTEXT")), null, null, null, null);
        response(List.of(pass(0, lines[0]), assessment));
        var result = run(c);
        assertThat(result.findings()).isEmpty();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
    }

    @Test void editorialOcrUsesSameExpressionContractWithoutEnablingDialogueOrReconstructingRawText() {
        var caption = new ScreenText(video, 1000, 2000, text[1], 0.8, null);
        caption.classify(ScreenTextRole.EDITORIAL, "테스트용 편집 자막");
        var c = new AnalysisContext(video, ContentGenre.GENERAL, List.of(), List.of(caption));
        response(List.of(graphic("ocr-index-0", text[1], ALTERNATIVE,
                List.of(new LlmEvidence("ocr-index-0", text[1], "PRIMARY")))));
        var analyzer = new ScreenTextReviewAnalyzer(client);
        assertThat(analyzer.analyze(c)).singleElement().satisfies(f -> {
            assertThat(f.getCategory()).isEqualTo(RiskCategory.GRAPHIC_METAPHOR);
            assertThat(f.getCaptionText()).isEqualTo(text[1]);
            assertThat(f.getTarget()).isNull();
        });
        assertThat(analyzer.consumeReviewResult(c).orElseThrow().diagnostics().dialogue()).isNull();
    }

    @Test void graphicMetaphorIsNotMergedIntoAnActualViolenceCandidateEvenWithSameRawText() {
        var c = context(text);
        response(List.of(pass(0, text[0]), graphic("stt-index-1", text[1], ALTERNATIVE, evidence())));
        var graphic = run(c).findings().get(0);
        var violence = RiskFinding.builder().video(video).eventType(TimelineEventType.SPEECH)
                .category(RiskCategory.VIOLENCE).source(EvidenceSource.SUBTITLE).score(0.8)
                .startMs(2000).endMs(3000).text(text[1]).reason("별도 실제 폭력 근거를 가정한 병합 계약 테스트입니다.").build();
        assertThat(new FindingFusionService().fuse(List.of(graphic, violence))).hasSize(2);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(mapper.readValue(mapper.writeValueAsString(RiskCategory.GRAPHIC_METAPHOR), RiskCategory.class))
                .isEqualTo(RiskCategory.GRAPHIC_METAPHOR);
    }
}
