package com.example.oops.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisCoverageMessageTest {
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
