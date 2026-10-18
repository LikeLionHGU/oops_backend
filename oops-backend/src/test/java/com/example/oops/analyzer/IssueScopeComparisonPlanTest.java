package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** Synthetic assembly fixtures, not evidence of model judgment quality. */
class IssueScopeComparisonPlanTest {
    JsonNode source() throws Exception {
        var fixture = new RelationGuidedVerificationTrialTest().fixture();
        var source = JSON.createObjectNode(); var requests = source.putArray("requests");
        for (int repeat = 1; repeat <= 3; repeat++) for (JsonNode row : fixture.baseline().path("requests")) {
            var r = JSON.treeToValue(row, DefaultVerificationPlan.Request.class);
            String before = r.system().replace(IssueScopeComparisonPlan.ADDED, "")
                    .replace("대상 취급 후보에서는 매장", IssueScopeComparisonPlan.OLD + "대상 취급 후보에서는 매장");
            requests.add(JSON.valueToTree(new DefaultVerificationPlan.Request(r.caseId(), r.labelStatus(),
                    r.expectedDecision(), "WITHOUT", repeat, before, r.input())));
        }
        return source;
    }

    @Test void thirtyRequestsChangeOnlyGuidanceAndPreserveInputsReferencesAndBudgets() throws Exception {
        var plan = IssueScopeComparisonPlan.build(source());
        RelationGuidedVerificationTrial.validate(plan);
        assertThat(plan.requests()).hasSize(30);
        assertThat(plan.reservationEstimatedUsd()).isLessThan(.50);
        assertThat(plan.fixedReadings()).isEmpty();
        for (int i = 0; i < 30; i += 2) {
            var a = plan.requests().get(i); var b = plan.requests().get(i + 1);
            assertThat(a.input()).isEqualTo(b.input());
            assertThat(DefaultVerificationPlan.reference(a.system())).isEqualTo(DefaultVerificationPlan.reference(b.system()));
            assertThat(JSON.readTree(a.input()).has(RelationGuidedVerificationTrial.FIELD)).isFalse();
            assertThat(plan.requestBodies().get(i).at("/max_completion_tokens").asInt()).isEqualTo(8000);
        }
        assertThat(IssueScopeComparisonPlan.CONSENT).isNotEqualTo(RelationGuidedVerificationTrial.CONSENT);
    }

    @Test void rejectsUnknownGuidanceAndDoesNotModifyDictionarySuffixes() {
        assertThatThrownBy(() -> IssueScopeComparisonPlan.upgrade("unknown prompt")).hasMessageContaining("Known verification");
        String suffix = "# 대상 필드 형식 예시\n참고: " + IssueScopeComparisonPlan.OLD;
        String before = IssueScopeComparisonPlan.OLD + IssueScopeComparisonPlan.INSERT_BEFORE + "\n" + suffix;
        assertThat(IssueScopeComparisonPlan.upgrade(before)).endsWith(suffix);
        assertThatThrownBy(() -> IssueScopeComparisonPlan.upgrade(IssueScopeComparisonPlan.upgrade(before)))
                .hasMessageContaining("Exact pre-change");
    }
}
