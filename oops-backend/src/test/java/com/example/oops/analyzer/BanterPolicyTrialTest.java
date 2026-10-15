package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static org.assertj.core.api.Assertions.*;

/** Synthetic assembly fixtures only. Never invokes a provider. */
class BanterPolicyTrialTest {
    private List<ContextPolicyTrial.Task> prepared() {
        var context = new CandidateReviewEngineTest().context("행동을 설명합니다", "그 행동을 비판합니다");
        var old = BanterPolicyTrial.REAL_IDS.stream().map(id -> ContextPolicyTrial.capture(
                new ContextPolicyTrial.Spec(id, 0, 1, "TARGET_TREATMENT", List.of(0, 1), "TEST_ONLY", "PASS"),
                context, null)).toList();
        var cases = BanterPolicyTrial.SYNTHETIC_IDS.stream().map(id -> Map.of("id", id, "sourceId", "synthetic-banter",
                "status", "DRAFT", "contextSummary", "THIS MUST NOT REACH THE MODEL", "anchorSegmentId", "s2",
                "segments", List.of(Map.of("id", "s1", "rawText", "행동을 설명합니다", "startMs", 0, "endMs", 2000),
                        Map.of("id", "s2", "rawText", "그 행동을 비판합니다", "startMs", 2000, "endMs", 4000)))).toList();
        return BanterPolicyTrial.prepare(JSON.valueToTree(Map.of("schemaVersion", "context-policy-stored-stt-1", "tasks", old)),
                JSON.valueToTree(Map.of("version", "banter-boundary-review-20261015-draft", "cases", cases)), null);
    }

    @Test void fixedInputsKeepReviewMetadataOutAndDoNotTurnVideoFeedbackIntoTextGold() {
        var tasks = prepared();
        assertThat(tasks).hasSize(12);
        assertThat(tasks.get(3).spec().expected()).isNull();
        assertThat(tasks.get(3).spec().labelStatus()).contains("TEXT_SUFFICIENCY_UNREVIEWED");
        for (var task : tasks) {
            assertThat(task.input()).doesNotContain("THIS MUST NOT REACH", "contextSummary", "expected", "labelStatus");
            assertThat(task.comparisonSystem()).isEqualTo(BanterBoundaryPolicy.replacePolicy(task.baselineSystem()));
        }
        assertThat(tasks.subList(4, 12)).allSatisfy(t -> {
            assertThat(t.spec().expected()).isNull();
            assertThat(t.spec().labelStatus()).isEqualTo("SYNTHETIC_PROPOSED_LABEL_NOT_GOLD");
        });
    }

    @Test void rejectsChangedPolicyIdentityAndLabelsInjectedIntoInput() {
        var tasks = prepared();
        var plan = JSON.valueToTree(Map.of("schemaVersion", BanterBoundaryPolicy.REVISION,
                "plannedCalls", 72, "repeats", 3, "approvedEstimatedUsd", 1));
        BanterPolicyTrial.validatePlan(plan, tasks);
        var changed = new ArrayList<>(tasks);
        var original = tasks.get(0);
        changed.set(0, new ContextPolicyTrial.Task(original.spec(), original.baselineSystem(), "changed", original.input()));
        assertThatThrownBy(() -> BanterPolicyTrial.validatePlan(plan, changed)).isInstanceOf(IllegalArgumentException.class);
        var input = (tools.jackson.databind.node.ObjectNode) JSON.readTree(original.input());
        input.put("expected", "PASS");
        changed.set(0, new ContextPolicyTrial.Task(original.spec(), original.baselineSystem(), original.comparisonSystem(), JSON.writeValueAsString(input)));
        assertThatThrownBy(() -> BanterPolicyTrial.validatePlan(plan, changed)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BanterPolicyTrial.validatePlan(plan, tasks.subList(1, 12))).isInstanceOf(IllegalArgumentException.class);
    }
}
