package com.example.oops.analyzer;

import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * Experiment A scoring against binary gold (RISK/NO_RISK). The three-way protocol gold
 * (PASS, REVIEW_REQUIRED, intrinsically UNCERTAIN) is managed apart: intrinsically uncertain
 * cases are never mapped to NO_RISK here.
 * <p>
 * Outcomes keep their meaning: ABSTAIN (model withheld judgement), NOT_EVALUATED (no valid
 * judgement completed), BUDGET_INFEASIBLE (required context did not fit B). All three are wrong in
 * the primary metric. CONTROLLED analysis scores only the pre-registered budget-feasible cases,
 * fixed from gold-context rendering tokens before any result is seen; OPERATIONAL scores every case.
 * <p>
 * Repeats are averaged per case. Primary estimate weights clusters (video or incident) equally;
 * the secondary estimate weights cases equally. 95% intervals use a seeded percentile cluster
 * bootstrap; comparisons are paired on the same resampled clusters.
 */
final class ExperimentAggregator {
    enum Label { RISK, NO_RISK }

    enum Outcome { RISK, NO_RISK, ABSTAIN, NOT_EVALUATED, BUDGET_INFEASIBLE }

    enum Analysis { CONTROLLED, OPERATIONAL }

    record CaseRef(Long videoId, String targetId) {}

    record GoldCase(Long videoId, String targetId, Label expected, String clusterId) {
        CaseRef ref() {
            return new CaseRef(videoId, targetId);
        }
    }

    record Estimate(double value, double lower, double upper) {}

    record ConditionSummary(String conditionId, Analysis analysis, int cases, int clusters, int runs,
                            Estimate clusterWeighted, Estimate caseWeighted, double coverage, Double selectiveAccuracy,
                            double abstainRate, double notEvaluatedRate, double budgetInfeasibleRate,
                            int budgetExcludedCases, Map<String, Integer> stateCounts) {}

    record Comparison(String conditionId, String baselineId, Analysis analysis, Estimate clusterWeighted, Estimate caseWeighted) {}

    private static final Set<String> NOT_EVALUATED = Set.of("NOT_RETURNED", "CALL_FAILED", "REJECTED", "CONFLICT", "NOT_SELECTED");

    private final Map<CaseRef, GoldCase> gold = new LinkedHashMap<>();
    private final Set<CaseRef> budgetFeasible;
    private final int samples;
    private final long seed;

    ExperimentAggregator(List<GoldCase> goldCases, Set<CaseRef> budgetFeasible, int samples, long seed) {
        if (samples < 1) throw new IllegalArgumentException("Bootstrap samples must be positive");
        for (var g : goldCases) {
            Objects.requireNonNull(g.expected(), "expected");
            if (g.clusterId() == null || g.clusterId().isBlank()) throw new IllegalArgumentException("Cluster ID required");
            if (gold.put(g.ref(), g) != null) throw new IllegalArgumentException("Duplicate gold case: " + g.ref());
        }
        if (!gold.keySet().containsAll(budgetFeasible))
            throw new IllegalArgumentException("Budget-feasible set must be a subset of the gold cases");
        this.budgetFeasible = Set.copyOf(budgetFeasible);
        this.samples = samples;
        this.seed = seed;
    }

    static Outcome outcome(String targetState) {
        if ("REVIEW_REQUIRED".equals(targetState)) return Outcome.RISK;
        if ("PASS".equals(targetState) || "NO_CANDIDATE".equals(targetState)) return Outcome.NO_RISK;
        if ("UNCERTAIN".equals(targetState)) return Outcome.ABSTAIN;
        if (ContextExperimentRunner.BUDGET_INFEASIBLE.equals(targetState)) return Outcome.BUDGET_INFEASIBLE;
        if (NOT_EVALUATED.contains(targetState)) return Outcome.NOT_EVALUATED;
        throw new IllegalArgumentException("Unknown target state: " + targetState);
    }

    ConditionSummary summarize(List<ContextExperimentRunner.TargetRecord> records, String conditionId, Analysis analysis) {
        var cases = cases(analysis);
        var runs = runs(records, conditionId, cases, analysis);
        var accuracy = caseAccuracy(runs);
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        Map<String, Integer> states = new TreeMap<>();
        int decidedCorrect = 0;
        for (var r : runs) {
            var o = outcome(r.targetState());
            outcomes.merge(o, 1, Integer::sum);
            states.merge(r.targetState(), 1, Integer::sum);
            if (correct(r)) decidedCorrect++;
        }
        int decided = outcomes.getOrDefault(Outcome.RISK, 0) + outcomes.getOrDefault(Outcome.NO_RISK, 0);
        double n = runs.size();
        return new ConditionSummary(conditionId, analysis, cases.size(), clusters(cases).size(), runs.size(),
                estimate(cases, keys -> clusterMean(accuracy, keys)), estimate(cases, keys -> caseMean(accuracy, keys)),
                decided / n, decided == 0 ? null : (double) decidedCorrect / decided,
                outcomes.getOrDefault(Outcome.ABSTAIN, 0) / n, outcomes.getOrDefault(Outcome.NOT_EVALUATED, 0) / n,
                outcomes.getOrDefault(Outcome.BUDGET_INFEASIBLE, 0) / n, gold.size() - budgetFeasible.size(), Map.copyOf(states));
    }

    /** Paired difference (condition minus baseline) on the same resampled clusters. */
    Comparison compare(List<ContextExperimentRunner.TargetRecord> records, String conditionId, String baselineId, Analysis analysis) {
        var cases = cases(analysis);
        var a = caseAccuracy(runs(records, conditionId, cases, analysis));
        var b = caseAccuracy(runs(records, baselineId, cases, analysis));
        return new Comparison(conditionId, baselineId, analysis,
                estimate(cases, keys -> clusterMean(a, keys) - clusterMean(b, keys)),
                estimate(cases, keys -> caseMean(a, keys) - caseMean(b, keys)));
    }

    private List<CaseRef> cases(Analysis analysis) {
        return gold.keySet().stream().filter(k -> analysis == Analysis.OPERATIONAL || budgetFeasible.contains(k)).toList();
    }

    private List<ContextExperimentRunner.TargetRecord> runs(List<ContextExperimentRunner.TargetRecord> records, String conditionId,
                                                            List<CaseRef> cases, Analysis analysis) {
        Set<CaseRef> included = new HashSet<>(cases), seen = new HashSet<>();
        List<ContextExperimentRunner.TargetRecord> runs = new ArrayList<>();
        for (var r : records) {
            if (!r.conditionId().equals(conditionId)) continue;
            var ref = new CaseRef(r.videoId(), r.targetId());
            if (!gold.containsKey(ref)) throw new IllegalArgumentException("No gold label for " + ref);
            if (!included.contains(ref)) continue;
            if (analysis == Analysis.CONTROLLED && outcome(r.targetState()) == Outcome.BUDGET_INFEASIBLE)
                throw new IllegalArgumentException("BUDGET_INFEASIBLE run for a pre-registered budget-feasible case: " + ref);
            seen.add(ref);
            runs.add(r);
        }
        var missing = cases.stream().filter(k -> !seen.contains(k)).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing runs for " + conditionId + ": " + missing);
        return runs;
    }

    private boolean correct(ContextExperimentRunner.TargetRecord r) {
        var expected = gold.get(new CaseRef(r.videoId(), r.targetId())).expected();
        var o = outcome(r.targetState());
        return o == Outcome.RISK && expected == Label.RISK || o == Outcome.NO_RISK && expected == Label.NO_RISK;
    }

    private Map<CaseRef, Double> caseAccuracy(List<ContextExperimentRunner.TargetRecord> runs) {
        Map<CaseRef, double[]> totals = new HashMap<>();
        for (var r : runs) {
            var t = totals.computeIfAbsent(new CaseRef(r.videoId(), r.targetId()), k -> new double[2]);
            t[0] += correct(r) ? 1 : 0;
            t[1]++;
        }
        Map<CaseRef, Double> accuracy = new HashMap<>();
        totals.forEach((k, t) -> accuracy.put(k, t[0] / t[1]));
        return accuracy;
    }

    private Map<String, List<CaseRef>> clusters(List<CaseRef> cases) {
        Map<String, List<CaseRef>> byCluster = new TreeMap<>();
        cases.forEach(k -> byCluster.computeIfAbsent(gold.get(k).clusterId(), c -> new ArrayList<>()).add(k));
        return byCluster;
    }

    /** Point estimate over the analysis set, plus a percentile interval over resampled cluster lists. */
    private Estimate estimate(List<CaseRef> cases, ToDoubleFunction<List<List<CaseRef>>> statistic) {
        var byCluster = new ArrayList<>(clusters(cases).values());
        double point = statistic.applyAsDouble(byCluster);
        var random = new Random(seed);
        double[] values = new double[samples];
        for (int i = 0; i < samples; i++) {
            List<List<CaseRef>> drawn = new ArrayList<>();
            for (int j = 0; j < byCluster.size(); j++) drawn.add(byCluster.get(random.nextInt(byCluster.size())));
            values[i] = statistic.applyAsDouble(drawn);
        }
        Arrays.sort(values);
        return new Estimate(point, values[(int) Math.floor(0.025 * (samples - 1))], values[(int) Math.ceil(0.975 * (samples - 1))]);
    }

    private static double clusterMean(Map<CaseRef, Double> accuracy, List<List<CaseRef>> clusters) {
        return clusters.stream().mapToDouble(c -> c.stream().mapToDouble(accuracy::get).average().orElseThrow()).average().orElseThrow();
    }

    private static double caseMean(Map<CaseRef, Double> accuracy, List<List<CaseRef>> clusters) {
        return clusters.stream().flatMap(List::stream).mapToDouble(accuracy::get).average().orElseThrow();
    }
}
