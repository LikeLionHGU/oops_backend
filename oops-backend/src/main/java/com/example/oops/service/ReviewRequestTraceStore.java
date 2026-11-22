package com.example.oops.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Opt-in text verification replay material. Never holds HTTP headers or credentials. */
@Component
public class ReviewRequestTraceStore {
    private static final int MAX_BYTES = 262144, MAX_REQUESTS = 128;
    private static final int EXPERIMENT_MAX_BYTES = 8 * 1024 * 1024, EXPERIMENT_MAX_REQUESTS = 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Entry(String stage, String system, String input, String systemSha256, String inputSha256,
                        String providerRequestBody, String providerRequestSha256, String parsedResponse, String outcome) {}
    public record Snapshot(Long videoId, Instant recordedAt, String scope, List<Entry> requests,
                           int retainedBytes, int droppedRequests, boolean truncated) {}
    private final boolean enabled;
    private final Clock clock;
    private final int maxRequests, maxBytes;
    private final LinkedHashMap<Long, Snapshot> snapshots = new LinkedHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public ReviewRequestTraceStore(@Value("${oops.analysis.review-request-trace-enabled:false}") boolean enabled,
                                  org.springframework.core.env.Environment environment) {
        this(enabled && environment.matchesProfiles("local")
                && Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(environment.getProperty("server.address", ""))
                && environment.getProperty("oops.analysis.review-request-trace-token", "").length() >= 32, Clock.systemUTC());
    }
    ReviewRequestTraceStore(boolean enabled, Clock clock) { this(enabled, clock, MAX_REQUESTS, MAX_BYTES); }
    private ReviewRequestTraceStore(boolean enabled, Clock clock, int maxRequests, int maxBytes) {
        this.enabled = enabled; this.clock = clock; this.maxRequests = maxRequests; this.maxBytes = maxBytes;
    }
    public Session begin(Long videoId) { return new Session(videoId, enabled, true); }
    /** Offline only: larger, finite per-target caps; callers must inspect dropped() for replay completeness. */
    public static Session experimentalSession() {
        return new ReviewRequestTraceStore(true, Clock.systemUTC(), EXPERIMENT_MAX_REQUESTS, EXPERIMENT_MAX_BYTES)
                .new Session(0L, true, false);
    }
    public synchronized Optional<Snapshot> find(Long id) { expire(); return Optional.ofNullable(snapshots.get(id)); }
    public synchronized void remove(Long id) { snapshots.remove(id); }
    private void expire() { snapshots.values().removeIf(s -> !s.recordedAt().isAfter(clock.instant().minusSeconds(3600))); }
    private synchronized void publish(Session s) {
        expire(); snapshots.remove(s.videoId);
        snapshots.put(s.videoId, new Snapshot(s.videoId, clock.instant(), "TEXT_DISCOVERY_ROUTING_VERIFICATION_AND_REPAIR; VISUAL_PAYLOAD_EXCLUDED",
                List.copyOf(s.entries), s.bytes, s.dropped, s.dropped > 0));
        while (snapshots.size() > 20) snapshots.remove(snapshots.keySet().iterator().next());
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String boundedJson(Object value, int limit) {
        var buffer = new ByteArrayOutputStream(Math.min(limit, 4096));
        JSON.writeValue(new LimitedOutput(buffer, limit), value);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** Count the exact serialized UTF-8 bytes without materializing another entry-sized String/byte[]. */
    private static int serializedSize(Entry entry, int limit) {
        var output = new LimitedOutput(OutputStream.nullOutputStream(), limit);
        JSON.writeValue(output, entry);
        return output.count;
    }

    private static final class LimitedOutput extends OutputStream {
        private final OutputStream delegate;
        private final int limit;
        private int count;
        LimitedOutput(OutputStream delegate, int limit) { this.delegate = delegate; this.limit = limit; }
        @Override public void write(int value) throws IOException {
            reserve(1);
            delegate.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            reserve(length);
            delegate.write(bytes, offset, length);
        }
        private void reserve(int length) throws IOException {
            if (length > limit - count) throw new IOException("Request trace byte limit exceeded");
            count += length;
        }
    }
    public final class Session implements AutoCloseable {
        private final Long videoId;
        private final boolean active;
        private final List<Entry> entries = new ArrayList<>();
        private final boolean publishOnClose;
        private int bytes, dropped;
        private boolean closed;
        private Session(Long id, boolean active, boolean publishOnClose) {
            this.videoId = id; this.active = active && id != null; this.publishOnClose = publishOnClose;
        }
        public boolean enabled() { return active && !closed; }
        public List<Entry> entries() { return List.copyOf(entries); }
        public int dropped() { return dropped; }
        public void record(String system, String input, Object response, String outcome) {
            record("TEXT_INITIAL_VERIFICATION", system, input, null, response, outcome);
        }
        public void record(String system, String input, Map<String, Object> providerBody, Object response, String outcome) {
            record("TEXT_INITIAL_VERIFICATION", system, input, providerBody, response, outcome);
        }
        public void record(String stage, String system, String input, Map<String, Object> providerBody,
                           Object response, String outcome) {
            if (!active || closed) return;
            if (entries.size() >= maxRequests || bytes >= maxBytes) { dropped++; return; }
            try {
                int remaining = maxBytes - bytes;
                // Cheap lower bound; oversized raw text must not allocate hashes or serialized copies.
                long textLength = (long) system.length() + input.length()
                        + (stage == null ? 0 : stage.length()) + (outcome == null ? 0 : outcome.length());
                if (textLength > remaining) { dropped++; return; }
                String parsed = response == null ? null : boundedJson(response, remaining);
                String body = providerBody == null ? null : boundedJson(providerBody, remaining);
                Entry entry = new Entry(stage, system, input, hash(system), hash(input), body,
                        body == null ? null : hash(body), parsed, outcome);
                int size = serializedSize(entry, remaining);
                entries.add(entry); bytes += size;
            } catch (RuntimeException ignored) { dropped++; } // Debugging must not alter analysis outcomes.
        }
        @Override public void close() {
            if (!closed && active && publishOnClose) publish(this);
            closed = true;
        }
    }
}
