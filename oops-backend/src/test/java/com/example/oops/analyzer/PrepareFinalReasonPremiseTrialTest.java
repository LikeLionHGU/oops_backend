package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** Offline request checks only; expected audit routes are developer drafts, not human gold. */
class PrepareFinalReasonPremiseTrialTest {
    JsonNode bundle() {
        var bundle = JSON.createObjectNode().put("version", FinalReasonPremiseAudit.VERSION).put("system", FinalReasonPremiseAudit.SYSTEM);
        bundle.set("responseFormat", FinalReasonPremiseAudit.responseFormat());
        var rows = bundle.putArray("requests");
        for (int i = 0; i < 30; i++) {
            var row = rows.addObject().put("sourceRequestIndex", i).put("caseId", RelationGuidedVerificationTrial.IDS.get(i % 5));
            row.set("input", new FinalReasonPremiseAuditTest().source());
            ((tools.jackson.databind.node.ObjectNode) row.path("input")).putObject("judgmentToCheck")
                    .put("decision", "PASS").put("reason", "원래 모의 이유");
            row.putNull("auditOutput"); row.put("auditState", "NOT_RUN");
        }
        return bundle;
    }
    @Test void freezesThirtySavedSixContrastOneCompatibilityRequestsWithoutLabelEgress() {
        var plan = PrepareFinalReasonPremiseTrial.build(bundle());
        PrepareFinalReasonPremiseTrial.validate(plan);
        assertThat(plan.requests()).hasSize(37);
        assertThat(plan.requests()).filteredOn(r -> r.origin().startsWith("SAVED_MODEL_REASON:")).hasSize(30);
        assertThat(plan.requests()).filteredOn(r -> r.origin().startsWith("DRAFT_SAME_RAW_")).hasSize(6);
        assertThat(plan.reservationEstimatedUsd()).isLessThan(.50);
        for (var r : plan.requests()) {
            assertThat(r.body().at("/response_format/json_schema/strict").asBoolean()).isTrue();
            assertThat(r.body().path("max_completion_tokens").asInt()).isEqualTo(2048);
            assertThat(r.body().toString()).doesNotContain("expectedRouteDraft", "DEVELOPER_DRAFT", "sourceArm", "caseId");
        }
    }
    @Test void contrastChangesJudgmentOnlyWhileOriginalSavedInputsRemainIntact() {
        var source = bundle(); String before = source.toString();
        var plan = PrepareFinalReasonPremiseTrial.build(source);
        for (var r : plan.requests()) if (r.origin().startsWith("DRAFT_SAME_RAW_")) {
            assertThat(r.input().path("raw")).isEqualTo(source.at("/requests/0/input/raw"));
            assertThat(r.input().path("anchorId")).isEqualTo(source.at("/requests/0/input/anchorId"));
        }
        assertThat(source.toString()).isEqualTo(before);
        assertThat(plan.executionApproved()).isFalse(); assertThat(plan.runtimeConnected()).isFalse();
        assertThat(plan.independentGold()).isFalse();
    }
    @Test void rejectsChangedPromptsAndExecutedOrIncompleteSourceBundles() {
        var changed = bundle(); ((tools.jackson.databind.node.ObjectNode) changed).put("system", "변경된 지침");
        assertThatThrownBy(() -> PrepareFinalReasonPremiseTrial.build(changed)).hasMessageContaining("Known thirty-input");
        var done = bundle(); ((tools.jackson.databind.node.ObjectNode) done.path("requests").get(0)).put("auditState", "COMPLETE");
        assertThatThrownBy(() -> PrepareFinalReasonPremiseTrial.build(done)).hasMessageContaining("Unexecuted");
    }
    @Test void malformedAuditsRemainIncompleteAndSavedRoutingCanBeReplayed() {
        var plan = PrepareFinalReasonPremiseTrial.build(bundle());
        var results = JSON.createObjectNode().put("state", "COMPLETE").put("httpAttempts", 37);
        var rows = results.putArray("results");
        for (int i = 0; i < 37; i++) {
            var request = plan.requests().get(i); var missing = JSON.createObjectNode();
            var row = rows.addObject().put("index", i).put("id", request.id()); row.set("output", missing);
            var routed = FinalReasonPremiseTrial.assess(request.input(), missing);
            assertThat(routed.route()).isEqualTo(FinalReasonPremiseAudit.Route.INCOMPLETE_AUDIT);
            assertThat(routed.publicationAuthorized()).isFalse(); row.set("routing", JSON.valueToTree(routed));
        }
        var audit = JSON.valueToTree(FinalReasonPremiseTrial.audit(plan, results));
        assertThat(audit.path("humanGold").asBoolean()).isFalse();
        assertThat(audit.path("publicationAuthorized").asBoolean()).isFalse();
        assertThat(audit.at("/totals/SAVED_MODEL_REASON:R01-166/INCOMPLETE_AUDIT").asInt()).isEqualTo(6);
        ((tools.jackson.databind.node.ObjectNode) rows.get(0)).put("id", "changed");
        assertThatThrownBy(() -> FinalReasonPremiseTrial.audit(plan, results)).hasMessageContaining("Result order mismatch");
    }
}
