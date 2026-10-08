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
                    .contains("정상 리뷰와 구별되는 추가 맥락")
                    .doesNotContain("반대로 단정적 무가치 평가", "단정적 무가치 평가·폄하가 원문에 있으면");
        }
    }

    @Test void judgmentIsOrderedAndHasNoJsonOrRequestScopeInstructions() {
        String policy = ReviewJudgmentPolicy.PROMPT;
        assertThat(policy.indexOf("1. 무엇을 검토하는가"))
                .isLessThan(policy.indexOf("2. 정상 리뷰와 어떻게 구분하는가"));
        assertThat(policy.indexOf("2. 정상 리뷰와 어떻게 구분하는가"))
                .isLessThan(policy.indexOf("3. 어떤 근거로 결정하는가"));
        assertThat(policy).contains("강한 비판, 단정적 문장만으로 경고하지 마라", "REVIEW_REQUIRED:", "PASS:", "UNCERTAIN:")
                .doesNotContain("JSON", "segmentId", "unitId", "피식", "롯데리아", "영양");
    }
}
