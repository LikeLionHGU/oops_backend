package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Contract tests with simulated responses; NOT evidence of model detection accuracy. */
class ContextualReviewTest {
    private final OpenAiClient client = mock(OpenAiClient.class);
    private final Video video = Video.builder().filename("offline.mp4").build();
    private final SpeechReviewAnalyzer analyzer = new SpeechReviewAnalyzer(client, false); // segment-scope regression
    private AnalysisContext context(String... lines) {
        List<TranscriptSegment> transcript = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) transcript.add(new TranscriptSegment(video, i * 2_000, i * 2_000 + 1_000, lines[i]));
        return new AnalysisContext(video, ContentGenre.GENERAL, transcript, null);
    }
    private LlmDecision pass(int id, String quote) {
        return new LlmDecision("stt-index-" + id, "PASS", quote, "단독 경고가 아닌 문맥의 보조 발언으로 검토한 구간입니다.",
                null, null, null, null, null, List.of());
    }
    private LlmDecision risk(List<LlmEvidence> evidence, String type, String relation, String reason) {
        return new LlmDecision("stt-index-2", "REVIEW_REQUIRED", "이걸로 대신 먹는 거야",
                "지역 주민과 음식을 선택지가 없어 먹는 열등한 대체재로 연결하는 발언입니다.",
                "BELITTLEMENT", "지역 주민과 현지 음식", 0.6, null, null, List.of(),
                evidence, type, relation, reason, "지역 음식의 유래를 설명하는 농담일 가능성도 있어 어조 확인이 필요합니다.");
    }
    private List<LlmEvidence> evidence() {
        return List.of(new LlmEvidence("stt-index-2", "이걸로 대신 먹는 거야", "PRIMARY"),
                new LlmEvidence("stt-index-1", "젊은 아들이 햄버거 먹고 싶은데", "TARGET"),
                new LlmEvidence("stt-index-0", "여기가 롯데리아가 없다", "CONTEXT"));
    }
    private void response(LlmDecision risk) {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenReturn(Optional.of(new LlmResult(List.of(
                pass(0, "롯데리아"), pass(1, "젊은 아들이"), risk))));
    }
    private AnalysisContext sample() { return context("여기가 롯데리아가 없다", "젊은 아들이 햄버거 먹고 싶은데", "이걸로 대신 먹는 거야"); }

    @Test
    void linkedConversationCreatesOneAnchoredCardWithGroundedTargetAndSeparateCoverage() {
        response(risk(evidence(), "RESIDENT_GROUP", "CONTEXTUAL", "지역에 매장이 없다는 발언 뒤 주민이 대체 음식을 먹는다는 연결이 나옵니다."));
        var context = sample();
        var findings = analyzer.analyze(context);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getTarget()).isEqualTo("지역 주민과 현지 음식");
        assertThat(findings.get(0).getStartMs()).isEqualTo(4_000);
        assertThat(findings.get(0).getReason()).contains("대상 연결(문맥 추론)");
        var result = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(result.unassessedSegmentIds()).isEmpty();
        var evaluation = result.evaluations().get(0);
        assertThat(ReviewEvidenceValidator.validate(context.reviewInput(), evaluation)).isEmpty();
        assertThat(evaluation.observations().get(2).evidence()).hasSize(3);
        assertThat(evaluation.observations().get(2).details().alternativeInterpretation()).contains("어조 확인");
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(anyString(), prompts.capture(), eq(LlmResult.class));
        assertThat(prompts.getValue()).contains("reviewUnits", "segmentIds", "stt-index-2");
    }

    @Test
    void evidenceDoesNotMarkSupportingSegmentAsAssessedWhenItsDecisionIsMissing() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenReturn(Optional.of(new LlmResult(List.of(
                risk(evidence(), "RESIDENT_GROUP", "CONTEXTUAL", "지역 주민을 대체 음식을 먹는 대상으로 연결하는 발언이 확인됩니다.")))));
        var context = sample();
        assertThat(analyzer.analyze(context)).hasSize(1);
        assertThat(analyzer.consumeReviewResult(context).orElseThrow().unassessedSegmentIds())
                .containsExactly("stt-index-0", "stt-index-1");
    }

    @Test
    void rejectsUngroundedInventedAndOutOfWindowTargetEvidenceWithoutAssumingPass() {
        var noTarget = List.of(evidence().get(0), evidence().get(2));
        var invented = List.of(evidence().get(0), new LlmEvidence("stt-index-1", "주민은 열등하다", "TARGET"));
        var unknown = List.of(evidence().get(0), new LlmEvidence("unknown", "주민", "TARGET"));
        for (var evidence : List.of(noTarget, invented, unknown)) {
            response(risk(evidence, "RESIDENT_GROUP", "CONTEXTUAL", "지역 주민이 대체 음식을 먹는 대상으로 연결되어 있습니다."));
            var context = sample();
            assertThat(analyzer.analyze(context)).isEmpty();
            assertThat(analyzer.consumeReviewResult(context).orElseThrow().unassessedSegmentIds()).contains("stt-index-2");
        }
        for (String relation : List.of("UNKNOWN", "", "DIRECT_EVENT")) {
            response(risk(evidence(), "RESIDENT_GROUP", relation, "대상 연결의 근거를 설명하는 구체적인 문장입니다."));
            assertThat(analyzer.analyze(sample())).isEmpty();
        }
    }

    @Test
    void normalQuestionsPreferencesAndSelfReflectionRemainExplicitPasses() {
        var context = context("롯데리아 없나?", "제 입에는 별로였어요", "코미디언으로서 한계를 느꼈다", "근데 좀");
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenReturn(Optional.of(new LlmResult(List.of(
                pass(0, "롯데리아"), pass(1, "제 입에는"), pass(2, "한계를 느꼈다"), pass(3, "근데 좀")))));
        assertThat(analyzer.analyze(context)).isEmpty();
        var result = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(result.unassessedSegmentIds()).isEmpty();
    }

    @Test
    void originalQuotesWithEmojiKeepCodePointOffsetsAndDoNotCopyInterpretationIntoEvidence() {
        var context = context("여기가 롯데리아가 없다", "😀젊은 아들이 햄버거 먹고 싶은데", "이걸로 대신 먹는 거야");
        response(risk(evidence(), "RESIDENT_GROUP", "CONTEXTUAL", "주민이 대신 먹는다는 연결 발언으로 지역 주민을 대상으로 해석합니다."));
        analyzer.analyze(context);
        var observation = analyzer.consumeReviewResult(context).orElseThrow().evaluations().get(0).observations().get(2);
        assertThat(observation.evidence().get(1).start()).isOne();
        assertThat(observation.evidence().get(1).quote()).isEqualTo("젊은 아들이 햄버거 먹고 싶은데");
    }

    @Test
    void rejectsDistantEvidenceEvenWhenItIsPresentElsewhereInTheBatch() {
        var context = new AnalysisContext(video, null, List.of(
                new TranscriptSegment(video, 0, 1_000, "여기가 롯데리아가 없다"),
                new TranscriptSegment(video, 2_000, 3_000, "젊은 아들이 햄버거 먹고 싶은데"),
                new TranscriptSegment(video, 60_000, 61_000, "이걸로 대신 먹는 거야")), null);
        response(risk(evidence(), "RESIDENT_GROUP", "CONTEXTUAL", "지역 주민과 대체 음식의 관계를 연결하는 근거입니다."));
        assertThat(analyzer.analyze(context)).isEmpty();
        assertThat(analyzer.consumeReviewResult(context).orElseThrow().unassessedSegmentIds()).contains("stt-index-2");
    }

    @Test
    void rejectsDuplicateEvidenceMissingPrimaryAndUnsupportedTargetType() {
        var duplicate = new ArrayList<>(evidence());
        duplicate.add(evidence().get(0));
        for (var raw : List.of(duplicate, evidence().subList(1, 3))) {
            response(risk(raw, "RESIDENT_GROUP", "CONTEXTUAL", "지역 주민을 대체 음식을 먹는 대상으로 연결하는 설명입니다."));
            assertThat(analyzer.analyze(sample())).isEmpty();
        }
        response(risk(evidence(), "POLITICAL_ORIENTATION", "EXPLICIT", "영상의 화자에게 정치 성향을 추정하는 잘못된 설명입니다."));
        assertThat(analyzer.analyze(sample())).isEmpty();
    }

    @Test
    void rawLegacyRiskWithTargetButNoGroundingFailsVisiblyRatherThanBecomingPass() {
        response(new LlmDecision("stt-index-2", "REVIEW_REQUIRED", "이걸로 대신 먹는 거야",
                "주민을 결핍 때문에 대체 음식을 먹는 대상으로 묘사하는 발언입니다.",
                "BELITTLEMENT", "롯데리아", 0.6, null, null, List.of()));
        var context = sample();
        assertThat(analyzer.analyze(context)).isEmpty();
        var result = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(result.unassessedSegmentIds()).contains("stt-index-2");
        assertThat(result.notice()).contains("응답 검증 실패", "미판정 1구간");
    }

    @Test
    void contextualReasonFitsExistingDatabaseColumnWithoutTruncatingInternalEvidence() {
        var longReason = "지역 주민이 대체 음식을 먹는다는 문맥에서 대상을 연결합니다.".repeat(60);
        response(risk(evidence(), "RESIDENT_GROUP", "CONTEXTUAL", longReason));
        var context = sample();
        var finding = analyzer.analyze(context).get(0);
        assertThat(finding.getReason()).hasSizeLessThanOrEqualTo(1000).endsWith("[설명 일부 생략]");
        var evaluation = analyzer.consumeReviewResult(context).orElseThrow().evaluations().get(0);
        assertThat(evaluation.observations().get(2).details().targetGrounding().reason()).isEqualTo(longReason);
    }

    @Test
    void multiSpanContractDeserializesFromActualJsonWithoutNetwork() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var result = mapper.readValue("""
                {"evaluations":[{"segmentId":"stt-index-2","decision":"REVIEW_REQUIRED",
                "evidenceText":"이걸로 대신 먹는 거야","reason":"대체재로 낮춰 설명하는 대목입니다.",
                "category":"BELITTLEMENT","target":"지역 주민","score":0.6,
                "missingInformation":[],"evidence":[
                  {"segmentId":"stt-index-2","quote":"이걸로 대신 먹는 거야","role":"PRIMARY"},
                  {"segmentId":"stt-index-1","quote":"젊은 아들이 햄버거 먹고 싶은데","role":"TARGET"}],
                "targetType":"RESIDENT_GROUP","targetRelation":"CONTEXTUAL",
                "targetReason":"인접한 발언에 나온 주민을 대체 음식을 먹는 대상으로 연결합니다.",
                "alternativeInterpretation":"음식의 유래를 설명하는 농담일 수도 있습니다."}]}
                """, LlmResult.class);
        response(result.evaluations().get(0));
        assertThat(analyzer.analyze(sample())).hasSize(1);
    }

    @Test
    void nearbyOtherSourceCanGroundTargetWithoutPromotingBackgroundOcrToReviewTarget() {
        var caption = new ScreenText(video, 4_000, 5_000, "현지 음식", 0.9, null);
        caption.classify(ScreenTextRole.BACKGROUND, "배경 추정");
        var base = sample();
        var context = new AnalysisContext(video, null, base.transcript(), List.of(caption));
        response(risk(List.of(evidence().get(0), new LlmEvidence("ocr-index-0", "현지 음식", "TARGET")),
                "PRODUCT", "CONTEXTUAL", "동시점의 화면 음식 설명을 대체재라는 발언과 문맥적으로 연결합니다."));
        assertThat(analyzer.analyze(context)).hasSize(1);
        var evaluation = analyzer.consumeReviewResult(context).orElseThrow().evaluations().get(0);
        assertThat(evaluation.reviewedSegmentIds()).doesNotContain("ocr-index-0");
        assertThat(evaluation.suppliedSegmentIds()).contains("ocr-index-0");
        assertThat(ReviewEvidenceValidator.validate(context.reviewInput(), evaluation)).isEmpty();
        assertThat(context.reviewInput().find("ocr-index-0").orElseThrow().reviewTarget()).isFalse();
    }
}
