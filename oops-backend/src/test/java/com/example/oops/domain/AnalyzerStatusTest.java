package com.example.oops.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 단계를 분석기 둘이 나눠 맡을 때 어느 상태로 보고할지.
 *
 * 이게 뒤집히면 "절반만 봤는데 다 봤다" 고 보고하게 된다.
 * 검수 도구에서 가장 위험한 종류의 버그다.
 */
class AnalyzerStatusTest {

    @Test
    @DisplayName("룰은 성공했지만 AI 가 실패하면 실패로 보고한다")
    void failureWinsOverSuccess() {
        assertThat(AnalyzerStatus.FAILED.worseOf(AnalyzerStatus.SUCCESS))
                .isEqualTo(AnalyzerStatus.FAILED);
        assertThat(AnalyzerStatus.SUCCESS.worseOf(AnalyzerStatus.FAILED))
                .isEqualTo(AnalyzerStatus.FAILED);
    }

    @Test
    @DisplayName("건너뛴 것도 성공보다 우선한다")
    void skippedWinsOverSuccess() {
        assertThat(AnalyzerStatus.SUCCESS.worseOf(AnalyzerStatus.SKIPPED))
                .isEqualTo(AnalyzerStatus.SKIPPED);
    }

    @Test
    @DisplayName("실패가 건너뜀보다 우선한다")
    void failureWinsOverSkipped() {
        assertThat(AnalyzerStatus.SKIPPED.worseOf(AnalyzerStatus.FAILED))
                .isEqualTo(AnalyzerStatus.FAILED);
    }

    @Test
    @DisplayName("미사용은 무엇에도 지지 않는다 (가장 약하다)")
    void notEnabledIsWeakest() {
        assertThat(AnalyzerStatus.NOT_ENABLED.worseOf(AnalyzerStatus.SUCCESS))
                .isEqualTo(AnalyzerStatus.SUCCESS);
    }

    @Test
    @DisplayName("상대가 없으면 자기 자신")
    void handlesNull() {
        assertThat(AnalyzerStatus.SUCCESS.worseOf(null)).isEqualTo(AnalyzerStatus.SUCCESS);
    }
}
