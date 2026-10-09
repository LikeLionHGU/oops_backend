package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Offline instruction contracts; does not assert that a real LLM follows the policy. */
class ReviewJudgmentPolicyTest {
    private String system(Class<?> type) throws Exception {
        var field = type.getDeclaredField("SYSTEM_PROMPT");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test void speechAndScreenTextShareJudgmentBeforeOutputContracts() throws Exception {
        for (var type : new Class<?>[]{SpeechReviewAnalyzer.class, ScreenTextReviewAnalyzer.class}) {
            String prompt = system(type);
            assertThat(prompt).startsWith(ReviewJudgmentPolicy.PROMPT)
                    .contains("표현이 단정적이거나 이유 설명이 짧다는 것만으로 폄하가 되지는 않는다")
                    .doesNotContain("반대로 단정적 무가치 평가", "단정적 무가치 평가·폄하가 원문에 있으면");
        }
    }

    @Test void judgmentIsOrderedAndHasNoJsonOrRequestScopeInstructions() {
        String policy = ReviewJudgmentPolicy.PROMPT;
        assertThat(policy.indexOf("1. 검토 대상"))
                .isLessThan(policy.indexOf("2. 리뷰와 폄하 구분"));
        assertThat(policy.indexOf("2. 리뷰와 폄하 구분"))
                .isLessThan(policy.indexOf("3. 문맥 해석"));
        assertThat(policy.indexOf("3. 문맥 해석"))
                .isLessThan(policy.indexOf("4. 결정 기준"));
        assertThat(policy).contains("강한 비판 자체는 경고하지 않는다", "REVIEW_REQUIRED:", "PASS:", "UNCERTAIN:")
                .doesNotContain("JSON", "segmentId", "unitId", "피식", "롯데리아", "영양");
    }

    @Test void speculationNeedsDevaluationAndTargetEvidenceMustIdentifyTarget() {
        assertThat(ReviewJudgmentPolicy.PROMPT)
                .contains("그 설명에서 무엇을 어떻게 낮춰 보는지 근거가 필요하다")
                .contains("상황 설명이나 브랜드 언급을 대상 식별 근거로 대신하지 않는다")
                .contains("'비하인지 확인이 필요하다'만으로는 충분하지 않다");
    }

    @Test void supportedInferenceIsAllowedAndUncertaintyRequiresEssentialMissingInformation() {
        assertThat(ReviewJudgmentPolicy.PROMPT)
                .contains("문맥에서 뒷받침되는 추론은 허용하되 관측된 내용과 추론을 구분한다")
                .contains("단순히 해석이 여러 개이거나 억양을 모른다는 이유만으로 보류하지 않는다");
    }
}
