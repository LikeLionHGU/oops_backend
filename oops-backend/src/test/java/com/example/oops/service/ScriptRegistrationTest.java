package com.example.oops.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptRegistrationTest {

    @Test
    @DisplayName("링크 칸에 붙여넣은 값이 링크인지 스크립트인지 가린다")
    void urlOrScript() {
        assertThat(VideoService.looksLikeUrl("https://www.youtube.com/watch?v=abc")).isTrue();
        assertThat(VideoService.looksLikeUrl("youtu.be/abc")).isTrue();
        assertThat(VideoService.looksLikeUrl("0:00 아 아니 여기 중국 아니에요?")).isFalse();
        assertThat(VideoService.looksLikeUrl("0:00\n안녕")).isFalse();
    }
}
