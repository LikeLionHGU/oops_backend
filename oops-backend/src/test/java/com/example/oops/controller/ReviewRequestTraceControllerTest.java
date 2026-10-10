package com.example.oops.controller;

import com.example.oops.service.ReviewRequestTraceStore;
import com.example.oops.repository.VideoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ReviewRequestTraceControllerTest {
    @Test void httpAccessRejectsExternalPeerBeforeRepositoryLookup() throws Exception {
        var env = new MockEnvironment().withProperty("server.address", "127.0.0.1"); env.setActiveProfiles("local");
        var videos = mock(VideoRepository.class);
        var controller = new ReviewRequestTraceController(new ReviewRequestTraceStore(false, env), videos, "x".repeat(32));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new com.example.oops.config.LocalDiagnosticsAccess(env)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/videos/1/analysis/request-traces").header("X-Review-Trace-Token", "x".repeat(32))
                .with(r -> { r.setRemoteAddr("192.168.1.2"); return r; }))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        verifyNoInteractions(videos);
    }
    @Test void endpointRequiresBothExplicitOptionAndLocalProfile() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(ReviewRequestTraceController.class)
                .withBean(ReviewRequestTraceStore.class, () -> mock(ReviewRequestTraceStore.class))
                .withBean(VideoRepository.class, () -> mock(VideoRepository.class));
        runner.run(c -> assertThat(c).doesNotHaveBean(ReviewRequestTraceController.class));
        runner.withPropertyValues("oops.analysis.review-request-trace-enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(ReviewRequestTraceController.class));
        runner.withPropertyValues("spring.profiles.active=local", "oops.analysis.review-request-trace-enabled=true")
                .run(c -> assertThat(c).hasSingleBean(ReviewRequestTraceController.class));
    }
    @Test void tokenIsRequiredBeforeLookingUpVideo() {
        var videos = mock(VideoRepository.class);
        var store = new ReviewRequestTraceStore(false, new MockEnvironment());
        var controller = new ReviewRequestTraceController(store, videos, "x".repeat(32));
        assertThatThrownBy(() -> controller.trace("1", null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> controller.trace("1", "bad")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> new ReviewRequestTraceController(store, videos, "short").trace("1", "short"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(videos);
        when(videos.existsById(1L)).thenReturn(true);
        assertThat(controller.trace("1", "x".repeat(32)).data().available()).isFalse();
    }
}
