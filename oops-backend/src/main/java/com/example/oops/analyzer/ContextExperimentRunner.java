package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.service.ReviewRequestTraceStore;
import java.util.*;

/**
 * Experiment A: for each target, the selector picks context, then the full pipeline runs on that
 * one-target batch. C0 isolation is fixed here: no case library, no visual stage, no router.
 * Each target gets its own uncapped request trace, stored with the record. BUDGET_INFEASIBLE
 * selections are recorded without calling the model.
 */
final class ContextExperimentRunner {
    static final String BUDGET_INFEASIBLE = "BUDGET_INFEASIBLE";

    record TargetRecord(String runId, String conditionId, int repeat, Long videoId, String targetId, String selectorId,
                        ContextSelection.Status selectionStatus, List<String> contextSegmentIds, String targetState,
                        String resultStatus, String pipelineRevision, CandidateReviewDiagnostics.RepairUsage repairUsage,
                        List<String> failureCodes, List<ReviewRequestTraceStore.Entry> requests, int droppedRequests) {}

    private final OpenAiClient client;
    private final ReviewGuidelineLibrary guidelines;
    private final CandidateReviewEngine.RunOptions options;
    private final ExperimentResultStore store;

    ContextExperimentRunner(OpenAiClient client, ReviewGuidelineLibrary guidelines, CandidateReviewEngine.RunOptions options,
                            ExperimentResultStore store) {
        this.client = Objects.requireNonNull(client);
        this.guidelines = guidelines;
        this.options = Objects.requireNonNull(options);
        this.store = Objects.requireNonNull(store);
    }

    void run(AnalysisContext context, String conditionId, ContextSelector selector, List<String> targetIds, int repeat) {
        if (conditionId == null || conditionId.isBlank()) throw new IllegalArgumentException("Condition ID required");
        if (repeat < 0) throw new IllegalArgumentException("Repeat must be non-negative");
        var transcript = CandidateReviewEngine.speechInput(context);
        if (new HashSet<>(targetIds).size() != targetIds.size()) throw new IllegalArgumentException("Duplicate target IDs");
        List<ReviewInput.Segment> targets = targetIds.stream().map(id -> transcript.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown speech target: " + id))).toList();
        for (String id : targetIds)
            if (store.contains(conditionId, repeat, id)) throw new IllegalArgumentException("Already recorded: " + id);
        for (var target : targets) store.append(runTarget(context, conditionId, selector, target, transcript, repeat));
    }

    private TargetRecord runTarget(AnalysisContext context, String conditionId, ContextSelector selector,
                                   ReviewInput.Segment target, ReviewInput transcript, int repeat) {
        var selection = selector.select(target, transcript);
        var batch = selection.toBatch(target, transcript);
        if (selection.status() == ContextSelection.Status.BUDGET_INFEASIBLE)
            return record(conditionId, repeat, context, target, selection, BUDGET_INFEASIBLE, null, null, null, List.of(), List.of(), 0);
        var trace = ReviewRequestTraceStore.experimentalSession();
        var services = new CandidateReviewEngine.RunServices(null, null, guidelines, trace, null);
        var result = CandidateReviewEngine.runExperimental(client, context, services, options, batch);
        trace.close();
        var pipeline = result.diagnostics().candidatePipeline();
        String state = result.diagnostics().segments().stream().filter(s -> s.segmentId().equals(target.id()))
                .map(s -> s.state().name()).findFirst().orElseThrow();
        var failures = pipeline.candidates().stream().map(CandidateReviewDiagnostics.Trace::failureCode).filter(Objects::nonNull).toList();
        return record(conditionId, repeat, context, target, selection, state, result.status().name(), pipeline.revision(),
                pipeline.repairUsage(), failures, trace.entries(), trace.dropped());
    }

    private TargetRecord record(String conditionId, int repeat, AnalysisContext context, ReviewInput.Segment target,
                                ContextSelection selection, String state, String status, String revision,
                                CandidateReviewDiagnostics.RepairUsage usage, List<String> failures,
                                List<ReviewRequestTraceStore.Entry> requests, int dropped) {
        Long videoId = context.video() == null ? null : context.video().getId();
        return new TargetRecord(store.runId(), conditionId, repeat, videoId, target.id(), selection.selectorId(),
                selection.status(), selection.contextSegmentIds(), state, status, revision, usage, failures, requests, dropped);
    }
}
