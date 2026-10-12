package com.example.oops.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisDispatchServiceTest {
    @Test void requiresTransactionBeforeEnqueueing() {
        var executor = mock(TaskExecutor.class);
        var pipeline = mock(AnalysisPipeline.class);
        assertThatThrownBy(() -> new AnalysisDispatchService(executor, pipeline).submit(1L))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(executor, pipeline);
    }

    @Test void waitsForCommit() throws Exception { checkCompletion(TransactionSynchronization.STATUS_COMMITTED, true); }
    @Test void rollbackNeverRuns() throws Exception { checkCompletion(TransactionSynchronization.STATUS_ROLLED_BACK, false); }
    @Test void unknownCompletionNeverRuns() throws Exception { checkCompletion(TransactionSynchronization.STATUS_UNKNOWN, false); }

    @Test void rejectionPropagatesBeforeCommitAndNeverRunsPipeline() {
        var executor = mock(TaskExecutor.class);
        var pipeline = mock(AnalysisPipeline.class);
        doThrow(new org.springframework.core.task.TaskRejectedException("full or shutting down"))
                .when(executor).execute(any(Runnable.class));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> new AnalysisDispatchService(executor, pipeline).submit(1L))
                    .isInstanceOf(org.springframework.core.task.TaskRejectedException.class);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
                    sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            verifyNoInteractions(pipeline);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private void checkCompletion(int status, boolean expectedRun) throws Exception {
        var pipeline = mock(AnalysisPipeline.class);
        var task = new AtomicReference<Runnable>();
        TaskExecutor executor = task::set;
        var worker = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        Future<?> running = null;
        try {
            new AnalysisDispatchService(executor, pipeline).submit(1L);
            var entered = new CountDownLatch(1);
            var future = worker.submit(() -> { entered.countDown(); task.get().run(); });
            running = future;
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> runningGet(future)).isInstanceOf(TimeoutException.class);
            verifyNoInteractions(pipeline);
        } finally {
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            try { if (running != null) running.get(2, TimeUnit.SECONDS); }
            finally { worker.shutdownNow(); }
        }
        if (expectedRun) verify(pipeline).run(1L);
        else verifyNoInteractions(pipeline);
    }

    private void runningGet(Future<?> future) throws Exception { future.get(100, TimeUnit.MILLISECONDS); }
}
