package com.example.oops.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NaverPropertiesTest {

    @Test
    void 키가_있어도_켜지_않으면_쓰지_않는다() {
        assertThat(new NaverProperties(null, "id", "secret").isConfigured()).isFalse();
        assertThat(new NaverProperties(false, "id", "secret").isConfigured()).isFalse();
    }

    @Test
    void 켜고_키가_있을_때만_쓴다() {
        assertThat(new NaverProperties(true, "id", "secret").isConfigured()).isTrue();
        assertThat(new NaverProperties(true, "", "secret").isConfigured()).isFalse();
    }
}
