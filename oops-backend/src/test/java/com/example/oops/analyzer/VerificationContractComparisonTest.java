package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static org.assertj.core.api.Assertions.*;

/** Free instruction assembly, not evidence that a model will comply more often. */
class VerificationContractComparisonTest {
    @Test void changesOnlyThreeOutputSectionsAndPreservesPolicyInputsAndFrozenReferences() {
        for (String original : List.of(CandidateReviewPrompts.VERIFICATION_PROMPT,
                SourceRelationPrompts.verification(0), SourceRelationPrompts.verification(8))) {
            String reference = "\ncontextReference={\"frozen\":true}";
            String baseline = original + reference;
            String updated = VerificationContractComparison.replaceContract(baseline);
            assertThat(updated).startsWith(CandidateReviewPrompts.POLICY).endsWith(reference);
            String restored = updated.replace(VerificationContractComparison.CURRENT_REPLACEMENT, VerificationContractComparison.CURRENT_CONTRAST)
                    .replace(VerificationContractComparison.RELATION, VerificationContractComparison.OLD_RELATION)
                    .replace(VerificationContractComparison.JSON_INTRO, VerificationContractComparison.OLD_JSON_INTRO);
            assertThat(restored).isEqualTo(baseline);
            assertThat(updated).contains("reason에 이미 설명했어도 이 필드는 null/빈 문자열이면 안 된다",
                    "의미가 같은지 여부로 정하지 않는다", "다른 segmentId의 실제 CONTEXT",
                    "출력 계약을 맞추려고 판정을 바꾸거나 없는 근거를 만들지 않는다");
        }
    }

    @Test void refusesUnknownDiscoveryAndPriorPolicyArmsAndRepeatedApplication() {
        for (String invalid : List.of("unknown", SourceRelationPrompts.DISCOVERY,
                ContextJudgmentPolicy.replacePolicy(SourceRelationPrompts.verification(0)),
                BanterBoundaryPolicy.replacePolicy(SourceRelationPrompts.verification(0)),
                VerificationContractComparison.replaceContract(SourceRelationPrompts.verification(0)))) {
            assertThatThrownBy(() -> VerificationContractComparison.replaceContract(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> VerificationContractComparison.replaceContract(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VerificationContractComparison.replaceOnce("duplicate duplicate", "duplicate", "replacement"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void preparationKeepsSameCandidateAndLabelMetadataOutOfTheModelInput() {
        var ids = new ArrayList<>(BanterPolicyTrial.REAL_IDS);
        BanterPolicyTrial.SYNTHETIC_IDS.forEach(id -> ids.add("synthetic-" + id));
        var fixture = new CandidateReviewEngineTest();
        var captured = ContextPolicyTrial.capture(new ContextPolicyTrial.Spec("synthetic", 0, 1,
                "TARGET_TREATMENT", List.of(0, 1), "SYNTHETIC_PROPOSED_LABEL_NOT_GOLD", null),
                fixture.context("상황 설명", "평가 표현"), null);
        var tasks = ids.stream().map(id -> new ContextPolicyTrial.Task(
                new ContextPolicyTrial.Spec(id, 0, 1, "TARGET_TREATMENT", List.of(0, 1),
                        "SYNTHETIC_PROPOSED_LABEL_NOT_GOLD", null), captured.baselineSystem(),
                BanterBoundaryPolicy.replacePolicy(captured.baselineSystem()), captured.input())).toList();
        var source = JSON.valueToTree(Map.of("schemaVersion", BanterBoundaryPolicy.REVISION,
                "plannedCalls", 72, "repeats", 3, "approvedEstimatedUsd", 1, "tasks", tasks));
        var before = source.deepCopy();
        var result = JSON.valueToTree(VerificationContractComparison.prepare(source));
        assertThat(source).isEqualTo(before);
        assertThat(result.path("providerCalls").asInt()).isZero();
        assertThat(result.path("liveApprovalRequired").asBoolean()).isTrue();
        assertThat(result.path("tasks").size()).isEqualTo(12);
        for (var t : result.path("tasks")) {
            assertThat(t.path("input").asText()).isEqualTo(captured.input());
            assertThat(t.path("baselineSystem").asText()).isEqualTo(captured.baselineSystem());
            assertThat(t.path("input").asText()).doesNotContain("expected", "labelStatus");
        }
        assertThat(result.has("results")).isFalse();
    }
}
