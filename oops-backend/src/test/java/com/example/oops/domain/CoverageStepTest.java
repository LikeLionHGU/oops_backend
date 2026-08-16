package com.example.oops.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageStepTest {

    @Test
    @DisplayName("룰 분석기와 LLM 분석기는 같은 단계로 묶인다")
    void groupsRuleAndLlmAnalyzers() {
        assertThat(CoverageStep.of("subtitle")).isEqualTo(CoverageStep.SPEECH_REVIEW);
        assertThat(CoverageStep.of("speech-review")).isEqualTo(CoverageStep.SPEECH_REVIEW);

        assertThat(CoverageStep.of("screen-text")).isEqualTo(CoverageStep.SCREEN_TEXT_REVIEW);
        assertThat(CoverageStep.of("screen-text-review")).isEqualTo(CoverageStep.SCREEN_TEXT_REVIEW);
    }

    @Test
    @DisplayName("외부 검색을 쓰는 분석기는 각자 단계를 가진다")
    void externalSearchAnalyzers() {
        assertThat(CoverageStep.of("entity-check")).isEqualTo(CoverageStep.FACT_ENTITY);
        assertThat(CoverageStep.of("context-check")).isEqualTo(CoverageStep.CONTEXT_REFERENCE);
    }

    @Test
    @DisplayName("보고 대상이 아닌 분석기는 null")
    void unreportedAnalyzers() {
        // 꺼져 있거나 사용자에게 알릴 필요가 없는 것들.
        // null 을 받으면 파이프라인이 기록을 건너뛴다.
        assertThat(CoverageStep.of("caption-mismatch")).isNull();
        assertThat(CoverageStep.of("monetization")).isNull();
        assertThat(CoverageStep.of("처음보는키")).isNull();
    }
}
