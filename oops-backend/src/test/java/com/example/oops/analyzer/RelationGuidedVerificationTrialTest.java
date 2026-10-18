package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class RelationGuidedVerificationTrialTest {
    record Fixture(JsonNode baseline, JsonNode extraction) {}
    Fixture fixture() throws Exception {
        var source = new SourceRelationExtractionTrialTest().plan();
        var baseline = JSON.createObjectNode(); var requests = baseline.putArray("requests");
        var extraction = JSON.createObjectNode().put("state", "COMPLETE").put("httpAttempts", 15);
        var rows = extraction.putArray("results");
        for (var r : source.requests()) {
            JsonNode rawInput = JSON.readTree(r.input());
            var links = r.expected().links();
            if (r.conditionId().equals("R04")) links = List.of(new SourceRelationExtractionTrial.Link("stt-index-0",
                    r.repeat() == 1 ? "OTHER" : "CONTINUES_REQUEST"));
            var output = new SourceRelationExtractionTrial.RelationResult(List.of(new SourceRelationExtractionTrial.Observation(
                    rawInput.path("focusId").asText(), r.expected().function(), r.expected().contentUse(), links,
                    List.of(rawInput.path("focusId").asText()))));
            var row = rows.addObject().put("conditionId", r.conditionId()).put("repeat", r.repeat());
            row.set("input", rawInput); row.set("output", JSON.valueToTree(output));
            row.put("providerContent", JSON.writeValueAsString(output)); row.putObject("assessment").put("accepted", true);
            if (r.repeat() != 1) continue;
            var input = JSON.createObjectNode().put("promptRevision", CandidateReviewEngine.REVISION);
            input.set("raw", rawInput.path("segments")); var c = input.putArray("candidates").addObject();
            c.put("candidateId", "candidate-1"); c.put("anchorId", rawInput.path("focusId").asText()); c.put("axis", "TARGET_TREATMENT");
            c.put("hypothesisNotEvidence", "원문 확인"); c.put("contextExpanded", false); c.put("contextLimited", false);
            var ids = c.putArray("segmentIds"); rawInput.path("segments").forEach(s -> ids.add(s.path("id").asText()));
            c.putArray("proposedEvidence").addObject().put("segmentId", rawInput.path("focusId").asText()).put("quote", "검수된 대화");
            String expected = Set.of("R04", "R08").contains(r.conditionId()) ? "REVIEW_REQUIRED" : "PASS";
            requests.add(JSON.valueToTree(new DefaultVerificationPlan.Request(r.conditionId(), "USER_APPROVED_DEVELOPMENT_NOT_INDEPENDENT_GOLD",
                    expected, "BASELINE", 1, CandidateReviewEngine.verificationPrompt(0)
                    + "\n# 범용 맥락 참고 기준\ncontextReference={}", input.toString())));
        }
        return new Fixture(baseline, extraction);
    }

    @Test void onlyAdvisoryReadingChangesAcrossThirtyPairedRequests() throws Exception {
        var f = fixture(); var plan = RelationGuidedVerificationTrial.build(f.baseline(), f.extraction());
        RelationGuidedVerificationTrial.validate(plan);
        assertThat(plan.requests()).hasSize(30); assertThat(plan.reservationEstimatedUsd()).isLessThan(.50);
        assertThat(plan.fixedReadings().get("R04").at("/observations/0/links/0/relation").asText()).isEqualTo("OTHER");
        for (int i = 0; i < 30; i += 2) {
            var a = plan.requests().get(i); var b = plan.requests().get(i + 1);
            assertThat(a.system()).isEqualTo(b.system());
            var with = JSON.readTree(a.arm().equals("WITH") ? a.input() : b.input());
            var without = JSON.readTree(a.arm().equals("WITHOUT") ? a.input() : b.input());
            assertThat(with.path(RelationGuidedVerificationTrial.FIELD).path("advisoryOnly").asBoolean()).isTrue();
            ((ObjectNode) with).remove(RelationGuidedVerificationTrial.FIELD); assertThat(with).isEqualTo(without);
            assertThat(DefaultVerificationPlan.assess(a, new CandidateReviewEngine.VerificationResult(List.of())))
                    .isEqualTo(DefaultVerificationPlan.assess(b, new CandidateReviewEngine.VerificationResult(List.of())));
        }
        assertThat(plan.requestBodies()).allSatisfy(body -> {
            assertThat(body.at("/response_format/type").asText()).isEqualTo("json_object");
            assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(8000);
            assertThat(body.toString()).doesNotContain("USER_APPROVED", "expectedDecision", "labelStatus");
        });
    }

    @Test void rejectsReadingFromAnotherSourceOrAnchorBeforeAnyProviderCall() throws Exception {
        var f = fixture();
        ((ObjectNode) f.extraction().at("/results/0/input/segments/0")).put("text", "변경된 전사");
        assertThatThrownBy(() -> RelationGuidedVerificationTrial.build(f.baseline(), f.extraction()))
                .hasMessageContaining("Reading/raw snapshot mismatch");
        var changed = fixture();
        ((ObjectNode) changed.extraction().at("/results/0/output/observations/0")).put("anchorId", "not-the-focus");
        // Re-serialize the tampered observation to ensure the explicit anchor check catches it too.
        ((ObjectNode) changed.extraction().path("results").get(0)).put("providerContent", changed.extraction().at("/results/0/output").toString());
        assertThatThrownBy(() -> RelationGuidedVerificationTrial.build(changed.baseline(), changed.extraction()))
                .hasMessageContaining("Reading output anchor mismatch");
    }

    @Test void frozenSourceDigestsCannotBeReplacedWithSyntheticOrEditedData() throws Exception {
        var f = fixture();
        assertThatThrownBy(() -> RelationGuidedVerificationTrial.prepare(JSON.writeValueAsBytes(f.baseline()),
                JSON.writeValueAsBytes(f.extraction()))).hasMessageContaining("Exact frozen");
    }
}
