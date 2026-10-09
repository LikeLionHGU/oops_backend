package com.example.oops.analyzer;

import com.example.oops.domain.RiskCategory;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/** Offline prompt contracts, not a measurement of model classification accuracy. */
class SpeechTypePromptTest {
    private String definitions() throws Exception {
        var field = SpeechReviewAnalyzer.class.getDeclaredField("SYSTEM_PROMPT");
        field.setAccessible(true);
        String system = (String) field.get(null);
        assertThat(system).startsWith(ReviewJudgmentPolicy.PROMPT);
        return system.substring(ReviewJudgmentPolicy.PROMPT.length());
    }

    @Test void everyAllowedCategoryHasExactlyOneDefinitionAndInclusionExclusionBoundaries() throws Exception {
        String prompt = definitions();
        var field = SpeechReviewAnalyzer.class.getDeclaredField("ALLOWED_CATEGORIES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var allowed = (Set<RiskCategory>) field.get(null);
        var sections = Pattern.compile("(?m)^## ([A-Z_]+) —").matcher(prompt);
        var names = new java.util.ArrayList<String>();
        while (sections.find()) names.add(sections.group(1));
        assertThat(names).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(allowed.stream().map(Enum::name).toList());
        for (String section : prompt.split("(?m)^## ")) {
            if (section.startsWith("# 발언 입력")) continue;
            assertThat(section).contains("포함:", "제외:", "경계:");
        }
    }

    @Test void transcriptionBoundariesAndFactCheckLimitsAreExplicitWithoutOutputDuplication() throws Exception {
        assertThat(definitions())
                .contains("짧은 전사 구간이 완결된 문장이나 화자 전환을 의미하지는 않는다")
                .contains("화자가 직접 말한 내용으로 바꾸지 않는다")
                .contains("중립적인 집단 특성 설명")
                .contains("별도 사실 대조 기능의 역할이다")
                .contains("공통 판단 기준으로 검토 필요 여부를 결정한 후 유형을 선택한다")
                .doesNotContain("JSON", "segmentId", "unitId", "# 설명 작성", "피식", "롯데리아", "영양");
    }
}
