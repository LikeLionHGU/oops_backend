package com.example.oops.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisCoverageMessageTest {
    @Test void boundedContextWarnsWithoutPretendingTheResponseIsMissing() {
        var coverage = AnalysisCoverage.of(null, CoverageStep.SPEECH_REVIEW, AnalyzerStatus.SUCCESS, null);
        coverage.markContextLimited("정해진 문맥 창 안에서 검토했습니다.");
        assertThat(coverage.getStatus()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(coverage.needsWarning()).isTrue();
        assertThat(coverage.warningCode()).isEqualTo("SPEECH_REVIEW_CONTEXT_LIMITED");
        assertThat(com.example.oops.dto.AnalysisWarningDto.from(coverage).message()).contains("문맥 창");
    }

    @Test void realFailureAndOrdinarySuccessKeepTheirOriginalMeaning() {
        var failure = AnalysisCoverage.of(null, CoverageStep.SPEECH_REVIEW, AnalyzerStatus.PARTIAL, "응답 누락");
        failure.markContextLimited("문맥 제한");
        assertThat(failure.warningCode()).isEqualTo("SPEECH_REVIEW_PARTIAL");
        assertThat(failure.getMessage()).contains("응답 누락", "문맥 제한");
        assertThat(AnalysisCoverage.of(null, CoverageStep.STT, AnalyzerStatus.SUCCESS, "저장 전사 재사용").needsWarning()).isFalse();
    }
    @Test void combinedDialogueNoticeAndApiFailureFitExistingColumn() {
        var coverage = AnalysisCoverage.of(null, CoverageStep.SPEECH_REVIEW, AnalyzerStatus.PARTIAL,
                "대화 묶음 안내입니다. ".repeat(40) + "외부 API 호출 실패 안내");
        assertThat(coverage.getMessage().length()).isLessThanOrEqualTo(300);
        assertThat(coverage.getMessage()).endsWith("… (일부 생략)");
        assertThat(coverage.needsWarning()).isTrue();
    }
    @Test void unicodeIsNotCutInHalfAndShortOrNullMessagesAreUnchanged() {
        var message = AnalysisCoverage.of(null, CoverageStep.SPEECH_REVIEW, AnalyzerStatus.PARTIAL,
                "😀".repeat(250)).getMessage();
        assertThat(message.length()).isLessThanOrEqualTo(300);
        for (int i = 0; i < message.length(); i++) {
            if (Character.isHighSurrogate(message.charAt(i))) {
                assertThat(Character.isLowSurrogate(message.charAt(++i))).isTrue();
            } else assertThat(Character.isLowSurrogate(message.charAt(i))).isFalse();
        }
        assertThat(AnalysisCoverage.of(null, CoverageStep.SPEECH_REVIEW, AnalyzerStatus.SUCCESS, null).getMessage()).isNull();
        assertThat(AnalysisCoverage.of(null, CoverageStep.SPEECH_REVIEW, AnalyzerStatus.PARTIAL, "짧은 안내").getMessage()).isEqualTo("짧은 안내");
    }
}
