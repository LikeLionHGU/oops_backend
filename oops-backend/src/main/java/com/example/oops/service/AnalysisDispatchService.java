package com.example.oops.service;

import com.example.oops.config.AsyncConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CompletableFuture;

/** Reserves bounded executor capacity before commit; never analyses rolled-back input. */
@Service
public class AnalysisDispatchService {
    private final TaskExecutor executor;
    private final AnalysisPipeline pipeline;

    public AnalysisDispatchService(@Qualifier(AsyncConfig.ANALYSIS_EXECUTOR) TaskExecutor executor,
                                   AnalysisPipeline pipeline) {
        this.executor = executor;
        this.pipeline = pipeline;
    }

    public void submit(Long jobId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Analysis admission requires a transaction");
        }
        var committed = new CompletableFuture<Boolean>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                committed.complete(status == STATUS_COMMITTED);
            }
        });
        // This executor must reject, not run inline: inline execution would wait
        // for the transaction executing submit(). No second/unbounded queue.
        executor.execute(() -> {
            if (committed.join()) pipeline.run(jobId);
        });
    }
}
