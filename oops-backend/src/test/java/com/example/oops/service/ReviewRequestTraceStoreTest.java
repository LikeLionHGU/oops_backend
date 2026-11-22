package com.example.oops.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;
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
            assertThat(e.stage()).isEqualTo("TEXT_INITIAL_VERIFICATION");
            assertThat(e.system()).isEqualTo("system");
            assertThat(e.input()).isEqualTo("{\"candidates\":[1,2]}");
            assertThat(e.inputSha256()).hasSize(64);
            assertThat(e.parsedResponse()).contains("PASS");
        });
        assertThat(snapshot.truncated()).isFalse();
    }
    @Test void experimentalSessionsKeepNormalSizedRunsWithLargerCaps() {
        var session = ReviewRequestTraceStore.experimentalSession();
        assertThat(session.enabled()).isTrue();
        String large = "x".repeat(4_000);
        for (int i = 0; i < 200; i++) session.record("CANDIDATE_DISCOVERY", "system", large + i, null, null, "PARSED");
        session.close();
        assertThat(session.entries()).hasSize(200);
        assertThat(session.dropped()).isZero();
        assertThat(session.entries().get(199).input()).endsWith("199");
    }
    @Test void fullSessionsDoNotSerializeFurtherResponses() {
        var store = new ReviewRequestTraceStore(true, Clock.systemUTC());
        try (var session = store.begin(3L)) {
            for (int i = 0; i < 128; i++) session.record("system", "input", null, "PARSED");
            var response = new CountingResponse();
            for (int i = 0; i < 500; i++) session.record("system", "input", response, "PARSED");
            assertThat(response.reads.get()).isZero();
            assertThat(session.dropped()).isEqualTo(500);
            assertThat(session.entries()).hasSize(128);
        }
    }
    @Test void oversizedRawInputIsRejectedBeforeSerializingTheResponse() {
        var store = new ReviewRequestTraceStore(true, Clock.systemUTC());
        var response = new CountingResponse();
        try (var session = store.begin(4L)) {
            session.record("system", "x".repeat(262145), response, "PARSED");
            assertThat(response.reads.get()).isZero();
            assertThat(session.dropped()).isOne();
        }
    }
    @Test void oversizedProviderBodiesAndResponsesLeaveRoomForSmallerEntries() {
        var store = new ReviewRequestTraceStore(true, Clock.systemUTC());
        try (var session = store.begin(5L)) {
            session.record("s", "i", Map.of("large", "x".repeat(300_000)), null, "PARSED");
            session.record("s", "i", Map.of("large", "한".repeat(100_000)), "PARSED");
            session.record("s", "small", Map.of("decision", "PASS"), "PARSED");
            assertThat(session.dropped()).isEqualTo(2);
        }
        assertThat(store.find(5L).orElseThrow().requests()).hasSize(1);
    }
    @Test void retainedBytesMatchActualUtf8SerializationIncludingEscapes() {
        var store = new ReviewRequestTraceStore(true, Clock.systemUTC());
        try (var session = store.begin(6L)) {
            session.record("판단", "한글😀\n\"quote\"", Map.of("input", "한글😀"), Map.of("value", "\\\n"), "PARSED");
        }
        var snapshot = store.find(6L).orElseThrow();
        var json = JsonMapper.builder().build();
        int actual = snapshot.requests().stream().mapToInt(e -> json.writeValueAsString(e)
                .getBytes(StandardCharsets.UTF_8).length).sum();
        assertThat(snapshot.retainedBytes()).isEqualTo(actual);
        assertThat(snapshot.truncated()).isFalse();
    }
    @Test void experimentalSessionsHaveFiniteCountAndByteLimits() {
        try (var session = ReviewRequestTraceStore.experimentalSession()) {
            for (int i = 0; i < 1_100; i++) session.record("s", "i", null, "PARSED");
            assertThat(session.entries()).hasSize(1024);
            assertThat(session.dropped()).isEqualTo(76);
        }
        try (var session = ReviewRequestTraceStore.experimentalSession()) {
            String input = "x".repeat(65_536);
            for (int i = 0; i < 200; i++) session.record("s", input, null, "PARSED");
            assertThat(session.entries().size()).isBetween(1, 127);
            assertThat(session.dropped()).isEqualTo(200 - session.entries().size());
        }
    }

    public static final class CountingResponse {
        final AtomicInteger reads = new AtomicInteger();
        public String getValue() { reads.incrementAndGet(); return "response"; }
    }
    @Test void productionSessionsKeepTheirCaps() {
        var store = new ReviewRequestTraceStore(true, Clock.systemUTC());
        try (var session = store.begin(3L)) {
            for (int i = 0; i < 130; i++) session.record("CANDIDATE_DISCOVERY", "system", "raw" + i, null, null, "PARSED");
        }
        assertThat(store.find(3L).orElseThrow().droppedRequests()).isEqualTo(2);
    }
    @Test void keepsStageLabelsForBoundedMultiStageReplay() {
        var store = new ReviewRequestTraceStore(true, Clock.systemUTC());
        try (var session = store.begin(2L)) {
            session.record("CANDIDATE_DISCOVERY", "discover", "raw", null, Map.of("candidates", List.of()), "PARSED");
            session.record("CANDIDATE_VERIFICATION_REPAIR", "repair", "raw", null, null, "NO_RESPONSE");
        }
        var snapshot = store.find(2L).orElseThrow();
        assertThat(snapshot.scope()).contains("DISCOVERY", "VERIFICATION", "VISUAL_PAYLOAD_EXCLUDED");
        assertThat(snapshot.requests()).extracting(ReviewRequestTraceStore.Entry::stage)
                .containsExactly("CANDIDATE_DISCOVERY", "CANDIDATE_VERIFICATION_REPAIR");
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
            for (int j = 0; j < 129; j++) s.record("s", "i", null, "NO_PARSED_RESPONSE");
        }
        assertThat(store.find(1L)).isEmpty();
        assertThat(store.find(21L).orElseThrow().requests()).hasSize(128);
        assertThat(store.find(21L).orElseThrow().droppedRequests()).isOne();
        store.remove(20L); assertThat(store.find(20L)).isEmpty();
        clock.now = clock.now.plusSeconds(3600);
        assertThat(store.find(21L)).isEmpty();
    }
}
