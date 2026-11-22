package com.example.oops.analyzer;

import java.util.*;

/**
 * A4-T: (gold decisive context ∪ gold risk evidence) minus the target. Gold labels only choose
 * which segments are supplied; the model sees them as ordinary CONTEXT_ONLY rows. The whole set is
 * budget-checked once: over budget is BUDGET_INFEASIBLE with nothing removed.
 */
final class OracleContextSelector implements ContextSelector {
    private static final String POLICY = "oracle-context-evidence-v1";

    record OracleLabel(List<String> goldContextSegmentIds, List<String> evidenceSegmentIds) {
        OracleLabel {
            goldContextSegmentIds = List.copyOf(goldContextSegmentIds);
            evidenceSegmentIds = List.copyOf(evidenceSegmentIds);
        }
    }

    private final Map<String, OracleLabel> labels;
    private final String labelVersion;
    private final ContextTokenBudget budget;

    OracleContextSelector(Map<String, OracleLabel> labels, String labelVersion, ContextTokenBudget budget) {
        this.labels = Map.copyOf(labels);
        if (labelVersion == null || labelVersion.isBlank()) throw new IllegalArgumentException("Label version required");
        this.labelVersion = labelVersion;
        this.budget = Objects.requireNonNull(budget);
    }

    String id() {
        return "A4-T:" + POLICY + ":labels=" + labelVersion + ":" + ContextTokenBudget.COUNTER_ID + ":B=" + budget.budget();
    }

    @Override
    public ContextSelection select(ReviewInput.Segment target, ReviewInput transcript) {
        var context = required(target, transcript);
        boolean exceeded = measure(target, transcript, context).exceeded();
        return new ContextSelection(context.stream().map(ReviewInput.Segment::id).toList(), id(),
                exceeded ? ContextSelection.Status.BUDGET_INFEASIBLE : ContextSelection.Status.SELECTED);
    }

    /** Gold-context rendering cost for the target; the basis of the pre-registered budget-feasible set. */
    ContextTokenBudget.Measurement measure(ReviewInput.Segment target, ReviewInput transcript) {
        return measure(target, transcript, required(target, transcript));
    }

    private ContextTokenBudget.Measurement measure(ReviewInput.Segment target, ReviewInput transcript,
                                                   List<ReviewInput.Segment> context) {
        List<ReviewInput.Segment> raw = new ArrayList<>(context);
        raw.add(target);
        return budget.measure(new ExperimentalBatchRendering(transcript, Set.of(target.id())), raw);
    }

    private List<ReviewInput.Segment> required(ReviewInput.Segment target, ReviewInput transcript) {
        var label = labels.get(target.id());
        if (label == null) throw new IllegalArgumentException("No oracle label for target: " + target.id());
        Set<String> required = new LinkedHashSet<>(label.goldContextSegmentIds());
        required.addAll(label.evidenceSegmentIds());
        required.remove(target.id());
        List<ReviewInput.Segment> context = new ArrayList<>();
        for (String id : required) context.add(transcript.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Oracle ID outside transcript: " + id)));
        var order = transcript.segments();
        context.sort(Comparator.comparingInt(order::indexOf));
        return context;
    }
}
