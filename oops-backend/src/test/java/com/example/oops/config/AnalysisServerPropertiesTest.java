package com.example.oops.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisServerPropertiesTest {

    @Test
    @DisplayName("화면 캡처 저장은 기본으로 꺼져 있고, true 로 줄 때만 켜진다")
    void saveFramesDefaultsOff() {
        assertThat(new AnalysisServerProperties(null, null, null, null).saveFramesOrDefault()).isFalse();
        assertThat(new AnalysisServerProperties(null, null, null, false).saveFramesOrDefault()).isFalse();
        assertThat(new AnalysisServerProperties(null, null, null, true).saveFramesOrDefault()).isTrue();
    }
}
