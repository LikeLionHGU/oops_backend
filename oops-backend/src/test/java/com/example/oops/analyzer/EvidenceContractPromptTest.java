package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline instruction/compatibility checks, not semantic target or model accuracy validation. */
class EvidenceContractPromptTest {
    @Test void targetIdentificationIsSeparateFromSituationAndTargetlessExpression() {
        assertThat(TextReviewEngine.EVIDENCE_CONTRACT).contains(
                "TARGET: 실제 평가받거나 노출되는 대상을 식별하는 표현이다.",
                "CONTEXT: 상황·비교·인용·반박",
                "상황 설명이나 단순 브랜드 언급을 실제 대상의 TARGET 근거로 대신하지 않는다.",
                "표현 자체나 정보 노출이 검토 이유인 경우 공격 대상을 억지로 만들지 않는다.",
                "대표 PRIMARY 자체에서 대상 연결이 드러나면 EXPLICIT",
                "허용된 주변 원문을 연결해야 식별되면 CONTEXTUAL");
    }

    @Test void canonicalFieldsAndExactQuotesAreExplicitWithoutDuplicatedJudgmentExamples() {
        String prompt = TextReviewEngine.EVIDENCE_CONTRACT;
        assertThat(prompt).contains(
                "PASS: category, target, score, targetType, targetRelation, targetReason은 null",
                "UNCERTAIN: category, target, score, targetType, targetRelation, targetReason은 null이다.",
                "우선순위를 제시할 근거가 없으면 score는 null로 둔다.",
                "evidenceText에 그 quote를 글자·공백·문장부호까지 동일하게 재사용한다.",
                "OCR 요청은 CAPTION 원문만 사용하고 SPEECH를 근거로 연결하지 않는다.",
                "GRAPHIC_METAPHOR의 REVIEW_REQUIRED에는 alternativeInterpretation이 필수다.",
                "그 해석으로 설명되는 범위 및 별도의 검토 이유가 남는지를 짧게 적는다.")
                .doesNotContain("{{TARGET_TYPES}}", "반대로 결핍을 이용해", "모르는 은어라는 이유로");
        for (var type : ReviewEvaluation.TargetType.values()) {
            assertThat(prompt).contains(type.name());
        }
        assertThat(TextReviewEngine.CONTRACT).contains(TextReviewEngine.EVIDENCE_CONTRACT)
                .doesNotContain("\"score\":0.4");
        assertThat(TextReviewEngine.dialogueEvidenceContract()).isEqualTo(prompt);
    }

    private TextReviewEngine.Result run(String decision, String category, String target,
                                      Double score, List<String> missing) {
        var video = Video.builder().filename("offline.mp4").build();
        var context = new AnalysisContext(video, ContentGenre.GENERAL,
                List.of(new TranscriptSegment(video, 0, 1000, "그 가게는 별로다")), List.of());
        var evidence = new ArrayList<>(List.of(
                new TextReviewEngine.LlmEvidence("stt-index-0", "그 가게는 별로다", "PRIMARY")));
        if (target != null) evidence.add(new TextReviewEngine.LlmEvidence("stt-index-0", "그 가게", "TARGET"));
        var item = new TextReviewEngine.LlmDecision("stt-index-0", decision, "그 가게는 별로다",
                "주어진 표현의 상품 평가와 대상 연결을 검토한 모의 응답입니다.",
                category, target, score, null, null, missing, evidence,
                target == null ? null : "BUSINESS", target == null ? null : "EXPLICIT",
                target == null ? null : "대표 표현의 그 가게가 평가받는 가게를 직접 지칭합니다.", null);
        var client = mock(OpenAiClient.class);
        when(client.completeAsJson(anyString(), anyString(), eq(TextReviewEngine.LlmResult.class)))
                .thenReturn(Optional.of(new TextReviewEngine.LlmResult(List.of(item))));
        var analyzer = new SpeechReviewAnalyzer(client, false);
        analyzer.analyze(context);
        return analyzer.consumeReviewResult(context).orElseThrow();
    }

    @Test void canonicalPassAndUncertainFieldsRemainAcceptedWithoutRiskPublication() {
        var pass = run("PASS", null, null, null, List.of());
        assertThat(pass.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(pass.findings()).isEmpty();
        var uncertain = run("UNCERTAIN", null, null, null, List.of("평가가 가리키는 구체적인 대상"));
        assertThat(uncertain.findings()).isEmpty();
        assertThat(uncertain.unassessedSegmentIds()).isEmpty();
        assertThat(uncertain.evaluations().get(0).observations().get(0).missingInformation())
                .containsExactly("평가가 가리키는 구체적인 대상");
    }

    @Test void reviewWithNullPriorityUsesExistingFallbackAndScoreCap() {
        // Simulated positive response only: the fixture is not a ground-truth risk label.
        var result = run("REVIEW_REQUIRED", "STRONG_NEGATIVE_REVIEW", "그 가게", null, List.of());
        assertThat(result.findings()).singleElement().satisfies(f -> assertThat(f.getScore()).isEqualTo(0.39));
        assertThat(result.evaluations().get(0).observations().get(0).details().score()).isNull();
    }
}
