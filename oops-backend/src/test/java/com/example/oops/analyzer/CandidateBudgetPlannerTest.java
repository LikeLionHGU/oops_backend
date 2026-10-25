package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CandidateBudgetPlannerTest {
    private record Item(String id, long time, String kind) {}

    @Test void roundRobinGivesLateWindowsAnOpportunityBeforeDenseEarlyWindowsUseTheBudget() {
        var input = List.of(new Item("early-1", 0, "speech"), new Item("early-2", 60_000, "speech"),
                new Item("early-3", 120_000, "speech"), new Item("late-1", 360_000, "speech"),
                new Item("late-2", 420_000, "speech"));

        var result = CandidateBudgetPlanner.select(input, 3, Item::time, Item::kind, Map.of());

        assertThat(result.selected()).extracting(Item::id).containsExactly("early-1", "late-1", "early-2");
        assertThat(result.skipped()).extracting(Item::id).containsExactly("early-3", "late-2");
    }

    @Test void visualCeilingAndSharedGlobalCeilingAreBothEnforced() {
        var input = List.of(new Item("v1", 0, "visual"), new Item("s1", 1, "speech"),
                new Item("v2", 360_000, "visual"), new Item("s2", 360_001, "speech"));

        var result = CandidateBudgetPlanner.select(input, 3, Item::time, Item::kind, Map.of("visual", 1));

        assertThat(result.selected()).extracting(Item::id).containsExactly("v1", "s2", "s1");
        assertThat(result.skipped()).extracting(Item::id).containsExactly("v2");
    }

    @Test void zeroBudgetAndNegativeBudgetAreHandledExplicitly() {
        var input = List.of(new Item("a", 0, "speech"));
        assertThat(CandidateBudgetPlanner.select(input, 0, Item::time, Item::kind, Map.of()).skipped()).containsExactlyElementsOf(input);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                CandidateBudgetPlanner.select(input, -1, Item::time, Item::kind, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
