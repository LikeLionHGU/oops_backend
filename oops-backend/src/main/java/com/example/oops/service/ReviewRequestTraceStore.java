package com.example.oops.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Opt-in text verification replay material. Never holds HTTP headers or credentials. */
@Component
public class ReviewRequestTraceStore {
    private static final int MAX_BYTES = 262144, MAX_REQUESTS = 32;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Entry(String system, String input, String systemSha256, String inputSha256,
                        String providerRequestBody, String providerRequestSha256, String parsedResponse, String outcome) {}
    public record Snapshot(Long videoId, Instant recordedAt, String scope, List<Entry> requests,
                           int retainedBytes, int droppedRequests, boolean truncated) {}
    private final boolean enabled;
    private final Clock clock;
    private final LinkedHashMap<Long, Snapshot> snapshots = new LinkedHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public ReviewRequestTraceStore(@Value("${oops.analysis.review-request-trace-enabled:false}") boolean enabled,
                                  org.springframework.core.env.Environment environment) {
        this(enabled && environment.matchesProfiles("local")
                && Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(environment.getProperty("server.address", ""))
                && environment.getProperty("oops.analysis.review-request-trace-token", "").length() >= 32, Clock.systemUTC());
    }
    ReviewRequestTraceStore(boolean enabled, Clock clock) { this.enabled = enabled; this.clock = clock; }
    public Session begin(Long videoId) { return new Session(videoId, enabled); }
    public synchronized Optional<Snapshot> find(Long id) { expire(); return Optional.ofNullable(snapshots.get(id)); }
    public synchronized void remove(Long id) { snapshots.remove(id); }
    private void expire() { snapshots.values().removeIf(s -> !s.recordedAt().isAfter(clock.instant().minusSeconds(3600))); }
    private synchronized void publish(Session s) {
        expire(); snapshots.remove(s.videoId);
        snapshots.put(s.videoId, new Snapshot(s.videoId, clock.instant(), "TEXT_INITIAL_VERIFICATION_ONLY_PARSED_RESPONSE",
                List.copyOf(s.entries), s.bytes, s.dropped, s.dropped > 0));
        while (snapshots.size() > 20) snapshots.remove(snapshots.keySet().iterator().next());
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public final class Session implements AutoCloseable {
        private final Long videoId;
        private final boolean active;
        private final List<Entry> entries = new ArrayList<>();
        private int bytes, dropped;
        private boolean closed;
        private Session(Long id, boolean active) { this.videoId = id; this.active = active && id != null; }
        public boolean enabled() { return active && !closed; }
        public void record(String system, String input, Object response, String outcome) {
            record(system, input, null, response, outcome);
        }
        public void record(String system, String input, Map<String, Object> providerBody, Object response, String outcome) {
            if (!active || closed) return;
            try {
                String parsed = response == null ? null : JSON.writeValueAsString(response);
                String body = providerBody == null ? null : JSON.writeValueAsString(providerBody);
                Entry entry = new Entry(system, input, hash(system), hash(input), body, body == null ? null : hash(body), parsed, outcome);
                int size = JSON.writeValueAsString(entry).getBytes(StandardCharsets.UTF_8).length;
                if (entries.size() >= MAX_REQUESTS || size > MAX_BYTES - bytes) { dropped++; return; }
                entries.add(entry); bytes += size;
            } catch (RuntimeException ignored) { dropped++; } // Debugging must not alter analysis outcomes.
        }
        @Override public void close() {
            if (!closed && active) publish(this);
            closed = true;
        }
    }
}
