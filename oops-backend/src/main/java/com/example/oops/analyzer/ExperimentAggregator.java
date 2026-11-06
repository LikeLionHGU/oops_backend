package com.example.oops.analyzer;

import java.util.*;

/**
 * Experiment A scoring. A run is correct when its prediction matches the gold label; any state
 * without a prediction (NOT_EVALUATED, including BUDGET_INFEASIBLE) is wrong in the primary metric.
 * Repeats are averaged per case, then cases are averaged. 95% intervals use a percentile cluster
 * bootstrap that resamples whole clusters (video or incident), seeded for reproducibility.
 */
final class ExperimentAggregator {
    enum Label { RISK, NO_RISK }

    record GoldCase(Long videoId, String targetId, Label expected, String clusterId) {}

    record ConditionSummary(String conditionId, int cases, int runs, double accuracy, double lower, double upper,
                            double notEvaluatedRate, int budgetInfeasibleRuns, Map<String, Integer> stateCounts) {}

    record Comparison(String conditionId, String baselineId, double difference, double lower, double upper) {}

    private static final Set<String> NOT_EVALUATED = Set.of("UNCERTAIN", "NOT_RETURNED", "CALL_FAILED", "REJECTED",
            "CONFLICT", "NOT_SELECTED", ContextExperimentRunner.BUDGET_INFEASIBLE);

    private final Map<String, GoldCase> gold = new LinkedHashMap<>();
    private final List<String> clusters;
    private final int samples;
    private final long seed;

    ExperimentAggregator(List<GoldCase> goldCases, int samples, long seed) {
        if (samples < 1) throw new IllegalArgumentException("Bootstrap samples must be positive");
        for (var g : goldCases) {
            Objects.requireNonNull(g.expected(), "expected");
            if (g.clusterId() == null || g.clusterId().isBlank()) throw new IllegalArgumentException("Cluster ID required");
            if (gold.put(key(g.videoId(), g.targetId()), g) != null)
                throw new IllegalArgumentException("Duplicate gold case: " + g.videoId() + "/" + g.targetId());
        }
        this.clusters = gold.values().stream().map(GoldCase::clusterId).distinct().sorted().toList();
        this.samples = samples;
        this.seed = seed;
    }

    static Optional<Label> prediction(String targetState) {
        if ("REVIEW_REQUIRED".equals(targetState)) return Optional.of(Label.RISK);
        if ("PASS".equals(targetState) || "NO_CANDIDATE".equals(targetState)) return Optional.of(Label.NO_RISK);
        if (NOT_EVALUATED.contains(targetState)) return Optional.empty();
        throw new IllegalArgumentException("Unknown target state: " + targetState);
    }

    ConditionSummary summarize(List<ContextExperimentRunner.TargetRecord> records, String conditionId) {
        var runs = runs(records, conditionId);
        var accuracy = caseAccuracy(runs);
        double[] interval = interval(r -> mean(accuracy, r));
        int notEvaluated = 0, infeasible = 0;
        Map<String, Integer> states = new TreeMap<>();
        for (var r : runs) {
            if (prediction(r.targetState()).isEmpty()) notEvaluated++;
            if (ContextExperimentRunner.BUDGET_INFEASIBLE.equals(r.targetState())) infeasible++;
            states.merge(r.targetState(), 1, Integer::sum);
        }
        return new ConditionSummary(conditionId, accuracy.size(), runs.size(), mean(accuracy, gold.keySet()),
                interval[0], interval[1], (double) notEvaluated / runs.size(), infeasible, Map.copyOf(states));
    }

    /** Paired difference (condition minus baseline) on the same resampled clusters. */
    Comparison compare(List<ContextExperimentRunner.TargetRecord> records, String conditionId, String baselineId) {
        var a = caseAccuracy(runs(records, conditionId));
        var b = caseAccuracy(runs(records, baselineId));
        double[] interval = interval(keys -> mean(a, keys) - mean(b, keys));
        return new Comparison(conditionId, baselineId, mean(a, gold.keySet()) - mean(b, gold.keySet()), interval[0], interval[1]);
    }

    private List<ContextExperimentRunner.TargetRecord> runs(List<ContextExperimentRunner.TargetRecord> records, String conditionId) {
        var runs = records.stream().filter(r -> r.conditionId().equals(conditionId)).toList();
        Set<String> seen = new HashSet<>();
        for (var r : runs) {
            String key = key(r.videoId(), r.targetId());
            if (!gold.containsKey(key)) throw new IllegalArgumentException("No gold label for " + r.videoId() + "/" + r.targetId());
            seen.add(key);
        }
        var missing = gold.keySet().stream().filter(k -> !seen.contains(k)).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing runs for " + conditionId + ": " + missing);
        return runs;
    }

    private Map<String, Double> caseAccuracy(List<ContextExperimentRunner.TargetRecord> runs) {
        Map<String, double[]> totals = new HashMap<>();
        for (var r : runs) {
            String key = key(r.videoId(), r.targetId());
            boolean correct = prediction(r.targetState()).map(p -> p == gold.get(key).expected()).orElse(false);
            var t = totals.computeIfAbsent(key, k -> new double[2]);
            t[0] += correct ? 1 : 0;
            t[1]++;
        }
        Map<String, Double> accuracy = new HashMap<>();
        totals.forEach((k, t) -> accuracy.put(k, t[0] / t[1]));
        return accuracy;
    }

    private double[] interval(java.util.function.ToDoubleFunction<List<String>> statistic) {
        Map<String, List<String>> casesByCluster = new HashMap<>();
        gold.forEach((key, g) -> casesByCluster.computeIfAbsent(g.clusterId(), c -> new ArrayList<>()).add(key));
        var random = new Random(seed);
        double[] values = new double[samples];
        for (int i = 0; i < samples; i++) {
            List<String> drawn = new ArrayList<>();
            for (int j = 0; j < clusters.size(); j++) drawn.addAll(casesByCluster.get(clusters.get(random.nextInt(clusters.size()))));
            values[i] = statistic.applyAsDouble(drawn);
        }
        Arrays.sort(values);
        return new double[]{values[(int) Math.floor(0.025 * (samples - 1))], values[(int) Math.ceil(0.975 * (samples - 1))]};
    }

    private static double mean(Map<String, Double> accuracy, Collection<String> keys) {
        return keys.stream().mapToDouble(accuracy::get).average().orElseThrow();
    }

    private static String key(Long videoId, String targetId) {
        return videoId + "\0" + targetId;
    }
}
