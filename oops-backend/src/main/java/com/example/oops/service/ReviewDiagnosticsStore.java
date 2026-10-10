package com.example.oops.service;

import com.example.oops.analyzer.ReviewDiagnostics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;

/** Local debugging only: bounded, temporary, no raw transcript or model payload. */
@Component
public class ReviewDiagnosticsStore {
    public record Snapshot(Long videoId, Long analysisJobId, Instant recordedAt,
                           String configuredModel, List<ReviewDiagnostics> analyzers, String textReviewPromptRevision,
                           Map<String, String> promptRevisions) {
        public Snapshot { analyzers = List.copyOf(analyzers); }
    }
    private final boolean enabled;
    private final Clock clock;
    private final LinkedHashMap<Long, Snapshot> snapshots = new LinkedHashMap<>();

    @Autowired
    public ReviewDiagnosticsStore(@Value("${oops.analysis.review-diagnostics-enabled:false}") boolean enabled) {
        this(enabled, Clock.systemUTC());
    }
    ReviewDiagnosticsStore(boolean enabled, Clock clock) { this.enabled = enabled; this.clock = clock; }

    public void recordAfterCommit(Long videoId, Long jobId, String model, List<ReviewDiagnostics> analyzers) {
        if (!enabled) return;
        Map<String, String> revisions = new LinkedHashMap<>();
        analyzers.forEach(a -> revisions.put(a.evaluatorId(), a.candidatePipeline() == null
                ? com.example.oops.analyzer.TextReviewEngine.PROMPT_REVISION : a.candidatePipeline().revision()));
        var snapshot = new Snapshot(videoId, jobId, clock.instant(), model, analyzers,
                revisions.getOrDefault("speech-review", com.example.oops.analyzer.TextReviewEngine.PROMPT_REVISION),
                Map.copyOf(revisions));
        afterCommit(() -> put(snapshot));
    }
    public void removeAfterCommit(Long videoId) {
        if (enabled) afterCommit(() -> remove(videoId));
    }
    public synchronized Optional<Snapshot> find(Long videoId) {
        expire();
        return Optional.ofNullable(snapshots.get(videoId));
    }
    private synchronized void put(Snapshot snapshot) {
        expire();
        snapshots.remove(snapshot.videoId());
        snapshots.put(snapshot.videoId(), snapshot);
        while (snapshots.size() > 20) snapshots.remove(snapshots.keySet().iterator().next());
    }
    private synchronized void remove(Long videoId) { snapshots.remove(videoId); }
    private void expire() {
        Instant cutoff = clock.instant().minus(Duration.ofHours(1));
        snapshots.values().removeIf(s -> !s.recordedAt().isAfter(cutoff));
    }
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }
}
