package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ExperimentAggregator.Label.*;
import static org.assertj.core.api.Assertions.*;

/** Per-case accuracy over repeats, NOT_EVALUATED counted wrong, cluster bootstrap CIs. Synthetic records only. */
class ExperimentAggregatorTest {
    private static ContextExperimentRunner.TargetRecord record(String condition, int repeat, long video, String target, String state) {
        return new ContextExperimentRunner.TargetRecord("run-1", condition, repeat, video, target, "TEST",
                ContextSelection.Status.SELECTED, List.of(), state, "SUCCESS", "rev", null, List.of(), List.of(), 0);
    }

    private static ExperimentAggregator.GoldCase gold(long video, String target, ExperimentAggregator.Label label, String cluster) {
        return new ExperimentAggregator.GoldCase(video, target, label, cluster);
    }

    private final List<ExperimentAggregator.GoldCase> gold = List.of(
            gold(1, "stt-index-0", RISK, "video-1"),
            gold(1, "stt-index-1", NO_RISK, "video-1"),
            gold(2, "stt-index-0", RISK, "video-2"));

    private ExperimentAggregator aggregator() {
        return new ExperimentAggregator(gold, 2_000, 7L);
    }

    @Test void mapsStatesToPredictionsAndTreatsEverythingElseAsNotEvaluated() {
        assertThat(ExperimentAggregator.prediction("REVIEW_REQUIRED")).contains(RISK);
        assertThat(ExperimentAggregator.prediction("PASS")).contains(NO_RISK);
        assertThat(ExperimentAggregator.prediction("NO_CANDIDATE")).contains(NO_RISK);
        for (String s : List.of("UNCERTAIN", "NOT_RETURNED", "CALL_FAILED", "REJECTED", "CONFLICT", "NOT_SELECTED",
                ContextExperimentRunner.BUDGET_INFEASIBLE))
            assertThat(ExperimentAggregator.prediction(s)).isEmpty();
        assertThatThrownBy(() -> ExperimentAggregator.prediction("SOMETHING_NEW")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void averagesRepeatsPerCaseThenCasesAndCountsNotEvaluatedAsWrong() {
        var records = List.of(
                record("A1-T", 0, 1, "stt-index-0", "REVIEW_REQUIRED"), record("A1-T", 1, 1, "stt-index-0", "PASS"),
                record("A1-T", 0, 1, "stt-index-1", "NO_CANDIDATE"), record("A1-T", 1, 1, "stt-index-1", "NOT_RETURNED"),
                record("A1-T", 0, 2, "stt-index-0", ContextExperimentRunner.BUDGET_INFEASIBLE),
                record("A5-T", 0, 1, "stt-index-0", "PASS"));
        var summary = aggregator().summarize(records, "A1-T");
        assertThat(summary.cases()).isEqualTo(3);
        assertThat(summary.runs()).isEqualTo(5);
        assertThat(summary.accuracy()).isCloseTo((0.5 + 0.5 + 0.0) / 3, within(1e-12));
        assertThat(summary.notEvaluatedRate()).isCloseTo(2.0 / 5, within(1e-12));
        assertThat(summary.budgetInfeasibleRuns()).isEqualTo(1);
        assertThat(summary.stateCounts()).containsEntry("PASS", 1).containsEntry("NOT_RETURNED", 1).containsEntry("BUDGET_INFEASIBLE", 1);
        assertThat(summary.lower()).isLessThanOrEqualTo(summary.accuracy());
        assertThat(summary.upper()).isGreaterThanOrEqualTo(summary.accuracy());
    }

    @Test void bootstrapResamplesClustersAndIsReproducibleBySeed() {
        var allRight = List.of(record("C", 0, 1, "stt-index-0", "REVIEW_REQUIRED"), record("C", 0, 1, "stt-index-1", "PASS"),
                record("C", 0, 2, "stt-index-0", "REVIEW_REQUIRED"));
        var perfect = aggregator().summarize(allRight, "C");
        assertThat(List.of(perfect.accuracy(), perfect.lower(), perfect.upper())).containsOnly(1.0);
        var mixed = List.of(record("C", 0, 1, "stt-index-0", "REVIEW_REQUIRED"), record("C", 0, 1, "stt-index-1", "PASS"),
                record("C", 0, 2, "stt-index-0", "PASS"));
        var first = aggregator().summarize(mixed, "C");
        assertThat(aggregator().summarize(mixed, "C")).isEqualTo(first);
        // Clusters are {video-1: 1.0, 1.0} and {video-2: 0.0}; resampled accuracies are k/3-style mixes, never per-case draws.
        assertThat(first.lower()).isEqualTo(0.0);
        assertThat(first.upper()).isEqualTo(1.0);
    }

    @Test void comparesConditionsOnTheSameResampledClusters() {
        var records = new ArrayList<ContextExperimentRunner.TargetRecord>();
        for (String c : List.of("A0-T", "A1-T")) {
            records.add(record(c, 0, 1, "stt-index-0", "REVIEW_REQUIRED"));
            records.add(record(c, 0, 1, "stt-index-1", "PASS"));
        }
        records.add(record("A0-T", 0, 2, "stt-index-0", "PASS"));
        records.add(record("A1-T", 0, 2, "stt-index-0", "REVIEW_REQUIRED"));
        var diff = aggregator().compare(records, "A1-T", "A0-T");
        assertThat(diff.difference()).isCloseTo(1.0 / 3, within(1e-12));
        assertThat(diff.lower()).isGreaterThanOrEqualTo(0.0);
        assertThat(diff.upper()).isLessThanOrEqualTo(1.0);
        var same = aggregator().compare(records, "A1-T", "A1-T");
        assertThat(List.of(same.difference(), same.lower(), same.upper())).containsOnly(0.0);
    }

    @Test void rejectsIncompleteOrUnlabelledRuns() {
        var missingCase = List.of(record("A1-T", 0, 1, "stt-index-0", "PASS"));
        assertThatThrownBy(() -> aggregator().summarize(missingCase, "A1-T")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing");
        var unlabelled = List.of(record("A1-T", 0, 1, "stt-index-0", "PASS"), record("A1-T", 0, 1, "stt-index-1", "PASS"),
                record("A1-T", 0, 2, "stt-index-0", "PASS"), record("A1-T", 0, 3, "stt-index-0", "PASS"));
        assertThatThrownBy(() -> aggregator().summarize(unlabelled, "A1-T")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No gold");
        assertThatThrownBy(() -> new ExperimentAggregator(List.of(gold.get(0), gold.get(0)), 100, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExperimentAggregator(gold, 0, 1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
