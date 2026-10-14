package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExpressionTargetScopeTest {
    @Test void graphicMetaphorWithoutAttackTargetSurvivesIncorrectDiscoveryAxis() {
        var f = new CandidateReviewEngineTest();
        String raw = "살을 벗겨 먹는 것처럼 표현한다";
        var c = f.context(raw);
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", raw));
        var d = new LlmDecision("stt-index-0", "REVIEW_REQUIRED", raw,
                "음식의 감각을 구체적인 신체 훼손 이미지에 연결하는 표현 자체를 검토합니다.",
                "GRAPHIC_METAPHOR", null, .6, null, null, List.of(),
                List.of(new LlmEvidence("stt-index-0", raw, "PRIMARY")), null, null, null,
                "맛을 과장하는 농담일 수 있으나 구체적인 신체 훼손 이미지가 별도로 남습니다.");
        f.verification(new Verification("candidate-1", d));
        var result = run(f.client, c, 24, null, null, null, null, 0);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).getTarget()).isNull();
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isZero();
        assertThat(result.diagnostics().candidatePipeline().verificationFailed()).isZero();
        verify(f.client, times(1)).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void targetCriticismStillRequiresGroundedTargetAndNoCategoryCanInventSource() {
        var f = new CandidateReviewEngineTest(); var c = f.context("그 가게의 이용자는 한심하다");
        var p = f.proposal("stt-index-0", "그 가게");
        var candidate = new Candidate("candidate-1", p, c.reviewInput().segments(), false, false);
        for (var category : List.of("MOCKERY", "BELITTLEMENT", "STRONG_NEGATIVE_REVIEW")) {
            var d = new LlmDecision("stt-index-0", "REVIEW_REQUIRED", "그 가게", "가게 이용자의 선택을 낮추는 발언입니다.",
                    category, null, .6, null, null, List.of(),
                    List.of(new LlmEvidence("stt-index-0", "그 가게", "PRIMARY")), null, null, null,
                    "상품 평가와 구별되는 사람의 선택을 낮추는 실제 표현을 검토합니다.");
            assertThat(validate(candidate, d, c.reviewInput(), 0).failureCode()).isEqualTo("TARGET_REQUIRED");
        }
        assertThat(VERIFICATION_PROMPT).contains("메뉴·서비스에 대한 후보를", "지역 비하가 없다는 이유만으로 PASS 처리하지 않는다");
    }
}
