package com.example.oops.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ReviewRequestTraceStoreTest {
    @Test void retainsExactAnalyzerInputAndParsedResponseWithHashes() {
        var store = new ReviewRequestTraceStore(true, Clock.systemUTC());
        try (var session = store.begin(1L)) {
            session.record("system", "{\"candidates\":[1,2]}", Map.of("decision", "PASS"), "PARSED_RESPONSE_NOT_YET_VALIDATED");
            assertThat(store.find(1L)).isEmpty();
        }
        var snapshot = store.find(1L).orElseThrow();
        assertThat(snapshot.requests()).singleElement().satisfies(e -> {
            assertThat(e.system()).isEqualTo("system");
            assertThat(e.input()).isEqualTo("{\"candidates\":[1,2]}");
            assertThat(e.inputSha256()).hasSize(64);
            assertThat(e.parsedResponse()).contains("PASS");
        });
        assertThat(snapshot.truncated()).isFalse();
    }
    @Test void disabledAndUnsafeConfigurationsRetainNothing() {
        var env = new MockEnvironment().withProperty("server.address", "127.0.0.1")
                .withProperty("oops.analysis.review-request-trace-token", "x".repeat(32));
        for (var store : new ReviewRequestTraceStore[]{new ReviewRequestTraceStore(false, Clock.systemUTC()),
                new ReviewRequestTraceStore(true, env)}) {
            try (var s = store.begin(1L)) { s.record("s", "i", null, "NO_PARSED_RESPONSE"); }
            assertThat(store.find(1L)).isEmpty();
        }
        env.setActiveProfiles("local");
        var store = new ReviewRequestTraceStore(true, env);
        try (var s = store.begin(1L)) { s.record("s", "i", null, "NO_PARSED_RESPONSE"); }
        assertThat(store.find(1L)).isPresent();
    }
    @Test void oversizedEntriesAreDroppedWithoutSlicingReplayData() {
        var store = new ReviewRequestTraceStore(true, Clock.systemUTC());
        try (var s = store.begin(1L)) {
            s.record("s", "한".repeat(262144), null, "NO_PARSED_RESPONSE");
            s.record("s", "small", null, "NO_PARSED_RESPONSE");
        }
        var snapshot = store.find(1L).orElseThrow();
        assertThat(snapshot.truncated()).isTrue();
        assertThat(snapshot.droppedRequests()).isOne();
        assertThat(snapshot.requests()).hasSize(1);
        assertThat(snapshot.retainedBytes()).isLessThanOrEqualTo(262144);
    }
    @Test void requestCountVideoCountExpiryAndRemovalAreBounded() {
        var clock = new ReviewDiagnosticsStoreTest.MutableClock();
        var store = new ReviewRequestTraceStore(true, clock);
        for (long i = 1; i <= 21; i++) try (var s = store.begin(i)) {
            for (int j = 0; j < 33; j++) s.record("s", "i", null, "NO_PARSED_RESPONSE");
        }
        assertThat(store.find(1L)).isEmpty();
        assertThat(store.find(21L).orElseThrow().requests()).hasSize(32);
        assertThat(store.find(21L).orElseThrow().droppedRequests()).isOne();
        store.remove(20L); assertThat(store.find(20L)).isEmpty();
        clock.now = clock.now.plusSeconds(3600);
        assertThat(store.find(21L)).isEmpty();
    }
}
