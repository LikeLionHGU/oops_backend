package com.example.oops.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewDiagnosticsStoreTest {
    @Test void reportsActualSpeechPipelineRevisionAndSeparateOcrRevision() {
        var candidate=org.mockito.Mockito.mock(com.example.oops.analyzer.CandidateReviewDiagnostics.class);
        org.mockito.Mockito.when(candidate.revision()).thenReturn("candidate-revision-test");
        var speech=new com.example.oops.analyzer.ReviewDiagnostics("speech-review",
                com.example.oops.domain.AnalyzerStatus.SUCCESS, 1, false, java.util.Map.of(), List.of(), null, candidate);
        var ocr=new com.example.oops.analyzer.ReviewDiagnostics("screen-text-review",
                com.example.oops.domain.AnalyzerStatus.SUCCESS, 1, false, java.util.Map.of(), List.of());
        var store=new ReviewDiagnosticsStore(true);
        store.recordAfterCommit(1L, 2L, "test", List.of(speech, ocr));
        var snapshot=store.find(1L).orElseThrow();
        assertThat(snapshot.textReviewPromptRevision()).isEqualTo("candidate-revision-test");
        assertThat(snapshot.promptRevisions()).containsEntry("speech-review", "candidate-revision-test")
                .containsEntry("screen-text-review", com.example.oops.analyzer.TextReviewEngine.PROMPT_REVISION);
    }
    @Test void disabledDoesNotRetainData() {
        var store = new ReviewDiagnosticsStore(false);
        store.recordAfterCommit(1L, 2L, "test", List.of());
        assertThat(store.find(1L)).isEmpty();
    }
    @Test void boundedLatestSnapshotsAndDeletion() {
        var store = new ReviewDiagnosticsStore(true);
        for (long i = 1; i <= 21; i++) store.recordAfterCommit(i, i, "test", List.of());
        assertThat(store.find(1L)).isEmpty();
        store.recordAfterCommit(2L, 99L, "new-model", List.of());
        assertThat(store.find(2L).orElseThrow().analysisJobId()).isEqualTo(99L);
        assertThat(store.find(2L).orElseThrow().textReviewPromptRevision())
                .isEqualTo(com.example.oops.analyzer.TextReviewEngine.PROMPT_REVISION);
        store.removeAfterCommit(2L);
        assertThat(store.find(2L)).isEmpty();
    }
    @Test void expiresAtOneHour() {
        var clock = new MutableClock();
        var store = new ReviewDiagnosticsStore(true, clock);
        store.recordAfterCommit(1L, 2L, "test", List.of());
        clock.now = clock.now.plusSeconds(3599);
        assertThat(store.find(1L)).isPresent();
        clock.now = clock.now.plusSeconds(1);
        assertThat(store.find(1L)).isEmpty();
    }
    @Test void publishedOnlyAfterCommitAndNotOnRollback() {
        var store = new ReviewDiagnosticsStore(true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            store.recordAfterCommit(1L, 2L, "test", List.of());
            assertThat(store.find(1L)).isEmpty();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(store.find(1L)).isPresent();
        } finally { TransactionSynchronizationManager.clear(); }
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            store.recordAfterCommit(1L, 3L, "test", List.of());
            store.removeAfterCommit(1L);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(store.find(1L).orElseThrow().analysisJobId()).isEqualTo(2L);
        } finally { TransactionSynchronizationManager.clear(); }
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-08T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
