package com.example.oops.analyzer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** Offline checks only: no provider call, no accuracy claim. */
class RecallFirstDiscoveryTrialTest {
    private static byte[] plannedBody(String text) {
        var client = new RecallFirstDiscoveryTrial.TrialClient(RestClient.create(), null, "BASE", true, null, null, "offline-no-key");
        return JSON.writeValueAsBytes(client.replayRequestBody("system", "{\"raw\":[\"" + text + "\"]}"));
    }

    @Test void guardSendsOnlyThePlannedBodyWithinRunTotalAndBudgetCaps() {
        var guard = new RecallFirstDiscoveryTrial.TrialGuard(3, 2, 1.0);
        String endpoint = ConfrontationStageTrial.ENDPOINT;
        guard.beginRun();
        guard.begin(plannedBody("a"));
        assertThatThrownBy(() -> guard.accept(plannedBody("other"), endpoint)).hasMessageContaining("Unplanned");
        assertThat(guard.blocked).isTrue();

        var fresh = new RecallFirstDiscoveryTrial.TrialGuard(3, 2, 1.0);
        fresh.beginRun();
        for (int i = 0; i < 2; i++) { fresh.begin(plannedBody("a")); fresh.accept(plannedBody("a"), endpoint); }
        fresh.begin(plannedBody("a"));
        assertThatThrownBy(() -> fresh.accept(plannedBody("a"), endpoint)).hasMessageContaining("cap");
        assertThat(fresh.attempts).isEqualTo(2);

        var poor = new RecallFirstDiscoveryTrial.TrialGuard(3, 2, 0.000001);
        poor.beginRun(); poor.begin(plannedBody("a"));
        assertThatThrownBy(() -> poor.accept(plannedBody("a"), endpoint)).hasMessageContaining("cap");
        assertThat(poor.reserved).isZero();
    }

    @Test void repairPromptWithInstructionsAfterReferencePayloadIsCapturedNotRejected() {
        var raw = List.of(new ReviewInput.Segment("s", com.example.oops.domain.TimelineEventType.SPEECH, 0, 1000, "원문", null));
        var c = new ConfrontationStageTrial.Condition("r", "SYNTHETIC", "PASS", raw,
                new ConfrontationStageTrial.Seed("s", "EXPRESSION_CONTENT", "diagnostic only", List.of("s")));
        var client = new RecallFirstDiscoveryTrial.TrialClient(RestClient.create(), c, "RECALL_FIRST", true, null, null, "offline-no-key");
        String repair = CandidateReviewEngine.verificationPrompt(3) + "\ncontextReference={\"b\":1,\"a\":2}\n이전 응답을 고친다.";
        assertThat(client.completeAsJson(repair, "{\"raw\":[]}", CandidateReviewEngine.VerificationResult.class)).isEmpty();
        assertThat(client.captured).hasSize(1);
        assertThat(client.captured.get(0).system()).endsWith("contextReference={\"a\":2,\"b\":1}\n이전 응답을 고친다.");
        assertThat(client.harnessError).isNull();

        assertThatThrownBy(() -> client.completeAsJson("p\ncontextReference=broken", "{}", CandidateReviewEngine.VerificationResult.class))
                .isInstanceOf(RuntimeException.class);
        assertThat(client.harnessError).isNotBlank();
    }

    @Test void validationOnlyPartialIsRecordedButProviderHarnessOrBudgetFailureStops() {
        assertThat(RecallFirstDiscoveryTrial.continueAfter("SUCCESS", false, 0, null)).isTrue();
        assertThat(RecallFirstDiscoveryTrial.continueAfter("PARTIAL", false, 0, null)).isTrue();
        assertThat(RecallFirstDiscoveryTrial.continueAfter("PARTIAL", false, 1, null)).isFalse();
        assertThat(RecallFirstDiscoveryTrial.continueAfter("PARTIAL", true, 0, null)).isFalse();
        assertThat(RecallFirstDiscoveryTrial.continueAfter("PARTIAL", false, 0, "IllegalArgumentException: x")).isFalse();
        assertThat(RecallFirstDiscoveryTrial.continueAfter("FAILED", false, 0, null)).isFalse();
    }

    @Test void summaryCountsRecallAnchorHitsAndFalseAlarmsPerMode() {
        var rows = List.<Map<String, Object>>of(
                row("p1", false, "REVIEW_REQUIRED", "PASS", false),
                row("p1", true, "REVIEW_REQUIRED", "REVIEW_REQUIRED", true),
                row("n1", false, "PASS", "PASS", false),
                row("n1", true, "PASS", "REVIEW_REQUIRED", false));
        var summary = RecallFirstDiscoveryTrial.summarize(rows);
        assertThat(summary.get("BASE")).containsEntry("positives", 1).containsEntry("positivesFlagged", 0)
                .containsEntry("positivesWithAnchorCandidate", 0).containsEntry("negatives", 1).containsEntry("negativesFlagged", 0);
        assertThat(summary.get("RECALL_FIRST")).containsEntry("positivesFlagged", 1)
                .containsEntry("positivesWithAnchorCandidate", 1).containsEntry("negativesFlagged", 1)
                .containsEntry("partialRuns", 1);
        assertThat(summary.get("BASE")).containsEntry("partialRuns", 0);
    }

    private static Map<String, Object> row(String id, boolean recall, String expected, String outcome, boolean anchor) {
        var row = new LinkedHashMap<String, Object>();
        row.put("caseId", id); row.put("mode", RecallFirstDiscoveryTrial.mode(recall));
        row.put("expectedDecision", expected); row.put("outcome", outcome); row.put("anchorCandidate", anchor);
        row.put("candidateCount", anchor ? 1 : 0);
        row.put("status", recall && id.equals("p1") ? "PARTIAL" : "SUCCESS");
        return row;
    }

    @Test void preparationFreezesPairedRequestsThatDifferOnlyInDiscoveryContract() throws Exception {
        Path suite = Path.of("../datasets/controversy/evaluation/active-context-regression-20261018");
        Path review = Path.of("../datasets/controversy/research/video-142-human-review-20261102.json");
        Assumptions.assumeTrue(Files.isRegularFile(suite.resolve("inputs.json")) && Files.isRegularFile(review));
        var plan = RecallFirstDiscoveryTrial.prepare(suite.resolve("inputs.json"), suite.resolve("labels.json"), review);
        assertThat(plan.cases()).hasSize(13);
        assertThat(plan.cases().stream().filter(c -> c.expectedDecision().equals("REVIEW_REQUIRED")).count()).isEqualTo(5);
        assertThat(plan.runs()).hasSize(26);
        assertThat(plan.maxReservation()).isLessThanOrEqualTo(RecallFirstDiscoveryTrial.BUDGET);
        for (int i = 0; i < plan.runs().size(); i += 2) {
            var base = plan.runs().get(i); var recall = plan.runs().get(i + 1);
            assertThat(base.caseId()).isEqualTo(recall.caseId());
            assertThat(base.recall()).isFalse(); assertThat(recall.recall()).isTrue();
            assertThat(base.initial().system()).startsWith(CandidateReviewEngine.DISCOVERY_PROMPT);
            assertThat(recall.initial().system()).startsWith(CandidateReviewPrompts.RECALL_DISCOVERY_PROMPT);
            assertThat(base.initial().system().substring(CandidateReviewEngine.DISCOVERY_PROMPT.length()))
                    .isEqualTo(recall.initial().system().substring(CandidateReviewPrompts.RECALL_DISCOVERY_PROMPT.length()));
            var baseInput = (tools.jackson.databind.node.ObjectNode) JSON.readTree(base.initial().input());
            var recallInput = (tools.jackson.databind.node.ObjectNode) JSON.readTree(recall.initial().input());
            assertThat(recallInput.path("promptRevision").asText()).endsWith(CandidateReviewEngine.RECALL_DISCOVERY_REVISION);
            baseInput.remove("promptRevision"); recallInput.remove("promptRevision");
            assertThat(recallInput).isEqualTo(baseInput);
            assertThat(base.initial().input()).doesNotContain("expectedDecision");
        }
        assertThat(new String(JSON.writeValueAsBytes(plan), StandardCharsets.UTF_8)).doesNotContain("OPENAI_API_KEY");
    }
}
