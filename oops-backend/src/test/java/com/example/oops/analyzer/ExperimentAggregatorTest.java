package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ExperimentAggregator.Analysis.*;
import static com.example.oops.analyzer.ExperimentAggregator.Label.*;
import static com.example.oops.analyzer.ExperimentAggregator.Outcome;
import static org.assertj.core.api.Assertions.*;

/** Outcome classes, controlled vs operational sets, cluster- and case-weighted estimates. Synthetic records only. */
class ExperimentAggregatorTest {
    private static ContextExperimentRunner.TargetRecord record(String condition, int repeat, long video, String target, String state) {
        return new ContextExperimentRunner.TargetRecord("run-1", condition, repeat, video, target, "TEST",
                ContextSelection.Status.SELECTED, List.of(), state, "SUCCESS", "rev", null, List.of(), List.of(), 0, List.of());
    }

    private static ExperimentAggregator.GoldCase gold(long video, String target, ExperimentAggregator.Label label, String cluster) {
        return new ExperimentAggregator.GoldCase(video, target, label, cluster);
    }

    private static ExperimentAggregator.CaseRef ref(long video, String target) {
        return new ExperimentAggregator.CaseRef(video, target);
    }

    /** video-1 has three cases, video-2 one. */
    private final List<ExperimentAggregator.GoldCase> gold = List.of(
            gold(1, "stt-index-0", RISK, "video-1"),
            gold(1, "stt-index-1", NO_RISK, "video-1"),
            gold(1, "stt-index-2", NO_RISK, "video-1"),
            gold(2, "stt-index-0", RISK, "video-2"));
    private final Set<ExperimentAggregator.CaseRef> allFeasible = Set.of(
            ref(1, "stt-index-0"), ref(1, "stt-index-1"), ref(1, "stt-index-2"), ref(2, "stt-index-0"));

    private ExperimentAggregator aggregator(Set<ExperimentAggregator.CaseRef> feasible) {
        return new ExperimentAggregator(gold, feasible, 2_000, 7L);
    }

    private List<ContextExperimentRunner.TargetRecord> all(String condition, String... states) {
        return List.of(record(condition, 0, 1, "stt-index-0", states[0]), record(condition, 0, 1, "stt-index-1", states[1]),
                record(condition, 0, 1, "stt-index-2", states[2]), record(condition, 0, 2, "stt-index-0", states[3]));
    }

    @Test void separatesAbstentionTechnicalFailureAndBudgetInfeasibility() {
        assertThat(ExperimentAggregator.outcome("REVIEW_REQUIRED")).isEqualTo(Outcome.RISK);
        assertThat(ExperimentAggregator.outcome("PASS")).isEqualTo(Outcome.NO_RISK);
        assertThat(ExperimentAggregator.outcome("NO_CANDIDATE")).isEqualTo(Outcome.NO_RISK);
        assertThat(ExperimentAggregator.outcome("UNCERTAIN")).isEqualTo(Outcome.ABSTAIN);
        for (String s : List.of("NOT_RETURNED", "CALL_FAILED", "REJECTED", "CONFLICT", "NOT_SELECTED"))
            assertThat(ExperimentAggregator.outcome(s)).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(ExperimentAggregator.outcome(ContextExperimentRunner.BUDGET_INFEASIBLE)).isEqualTo(Outcome.BUDGET_INFEASIBLE);
        assertThatThrownBy(() -> ExperimentAggregator.outcome("SOMETHING_NEW")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void primaryEstimateWeightsClustersEquallyAndSecondaryWeightsCases() {
        var records = all("A1-T", "REVIEW_REQUIRED", "PASS", "NO_CANDIDATE", "PASS");
        var s = aggregator(allFeasible).summarize(records, "A1-T", CONTROLLED);
        assertThat(s.clusterWeighted().value()).isCloseTo((1.0 + 0.0) / 2, within(1e-12));
        assertThat(s.caseWeighted().value()).isCloseTo(3.0 / 4, within(1e-12));
        assertThat(s.cases()).isEqualTo(4);
        assertThat(s.clusters()).isEqualTo(2);
    }

    @Test void averagesRepeatsPerCaseAndCountsAbstainAndFailuresWrongWhileReportingThemApart() {
        var records = new ArrayList<>(all("A1-T", "REVIEW_REQUIRED", "UNCERTAIN", "NOT_RETURNED", "REVIEW_REQUIRED"));
        records.add(record("A1-T", 1, 1, "stt-index-0", "PASS"));
        var s = aggregator(allFeasible).summarize(records, "A1-T", CONTROLLED);
        assertThat(s.runs()).isEqualTo(5);
        assertThat(s.caseWeighted().value()).isCloseTo((0.5 + 0 + 0 + 1) / 4, within(1e-12));
        assertThat(s.coverage()).isCloseTo(3.0 / 5, within(1e-12));
        assertThat(s.selectiveAccuracy()).isCloseTo(2.0 / 3, within(1e-12));
        assertThat(s.abstainRate()).isCloseTo(1.0 / 5, within(1e-12));
        assertThat(s.notEvaluatedRate()).isCloseTo(1.0 / 5, within(1e-12));
        assertThat(s.stateCounts()).containsEntry("UNCERTAIN", 1).containsEntry("NOT_RETURNED", 1);
        var none = aggregator(allFeasible).summarize(all("A1-T", "UNCERTAIN", "UNCERTAIN", "CALL_FAILED", "UNCERTAIN"), "A1-T", CONTROLLED);
        assertThat(none.selectiveAccuracy()).isNull();
        assertThat(none.coverage()).isZero();
    }

    @Test void controlledComparisonUsesOnlyThePreRegisteredBudgetFeasibleCases() {
        var feasible = Set.of(ref(1, "stt-index-0"), ref(1, "stt-index-1"), ref(1, "stt-index-2"));
        var oracle = all("A4-T", "REVIEW_REQUIRED", "PASS", "PASS", ContextExperimentRunner.BUDGET_INFEASIBLE);
        var controlled = aggregator(feasible).summarize(oracle, "A4-T", CONTROLLED);
        assertThat(controlled.cases()).isEqualTo(3);
        assertThat(controlled.clusterWeighted().value()).isEqualTo(1.0);
        assertThat(controlled.budgetExcludedCases()).isEqualTo(1);
        assertThat(controlled.budgetInfeasibleRate()).isZero();
        var operational = aggregator(feasible).summarize(oracle, "A4-T", OPERATIONAL);
        assertThat(operational.cases()).isEqualTo(4);
        assertThat(operational.clusterWeighted().value()).isCloseTo(0.5, within(1e-12));
        assertThat(operational.budgetInfeasibleRate()).isCloseTo(0.25, within(1e-12));
        assertThat(operational.budgetExcludedCases()).isEqualTo(1);
        var inconsistent = all("A4-T", ContextExperimentRunner.BUDGET_INFEASIBLE, "PASS", "PASS", "PASS");
        assertThatThrownBy(() -> aggregator(feasible).summarize(inconsistent, "A4-T", CONTROLLED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("feasible");
    }

    @Test void bootstrapResamplesClustersAndIsReproducibleBySeed() {
        var perfect = aggregator(allFeasible).summarize(all("C", "REVIEW_REQUIRED", "PASS", "PASS", "REVIEW_REQUIRED"), "C", CONTROLLED);
        assertThat(List.of(perfect.clusterWeighted().lower(), perfect.clusterWeighted().upper(),
                perfect.caseWeighted().lower(), perfect.caseWeighted().upper())).containsOnly(1.0);
        var mixed = all("C", "REVIEW_REQUIRED", "PASS", "PASS", "PASS");
        var first = aggregator(allFeasible).summarize(mixed, "C", CONTROLLED);
        assertThat(aggregator(allFeasible).summarize(mixed, "C", CONTROLLED)).isEqualTo(first);
        assertThat(first.clusterWeighted().lower()).isEqualTo(0.0);
        assertThat(first.clusterWeighted().upper()).isEqualTo(1.0);
    }

    @Test void comparesConditionsOnTheSameResampledClusters() {
        var records = new ArrayList<ContextExperimentRunner.TargetRecord>();
        records.addAll(all("A0-T", "REVIEW_REQUIRED", "PASS", "PASS", "PASS"));
        records.addAll(all("A1-T", "REVIEW_REQUIRED", "PASS", "PASS", "REVIEW_REQUIRED"));
        var diff = aggregator(allFeasible).compare(records, "A1-T", "A0-T", CONTROLLED);
        assertThat(diff.clusterWeighted().value()).isCloseTo(0.5, within(1e-12));
        assertThat(diff.caseWeighted().value()).isCloseTo(0.25, within(1e-12));
        assertThat(diff.clusterWeighted().lower()).isGreaterThanOrEqualTo(0.0);
        assertThat(diff.clusterWeighted().upper()).isLessThanOrEqualTo(1.0);
        var same = aggregator(allFeasible).compare(records, "A1-T", "A1-T", CONTROLLED);
        assertThat(List.of(same.clusterWeighted().value(), same.clusterWeighted().lower(), same.clusterWeighted().upper()))
                .containsOnly(0.0);
    }

    @Test void rejectsIncompleteUnlabelledOrInconsistentInputs() {
        var missingCase = List.of(record("A1-T", 0, 1, "stt-index-0", "PASS"));
        assertThatThrownBy(() -> aggregator(allFeasible).summarize(missingCase, "A1-T", CONTROLLED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing");
        var unlabelled = new ArrayList<>(all("A1-T", "PASS", "PASS", "PASS", "PASS"));
        unlabelled.add(record("A1-T", 0, 3, "stt-index-0", "PASS"));
        assertThatThrownBy(() -> aggregator(allFeasible).summarize(unlabelled, "A1-T", CONTROLLED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No gold");
        assertThatThrownBy(() -> new ExperimentAggregator(List.of(gold.get(0), gold.get(0)), allFeasible, 100, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExperimentAggregator(gold, Set.of(ref(9, "x")), 100, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExperimentAggregator(gold, allFeasible, 0, 1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
