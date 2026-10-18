package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.*;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SourceRelationExtractionTrialTest {
    @TempDir Path temporary;

    SourceRelationExtractionTrial.Plan plan() throws Exception {
        var conditions = new ArrayList<SourceRelationExtractionTrial.Condition>();
        var requests = new ArrayList<SourceRelationExtractionTrial.Request>();
        var expected = SourceRelationExtractionTrial.expected();
        for (String id : List.of("R01-166", "R04", "R05", "R06", "R08")) {
            String anchor = id.equals("R01-166") ? "groq-v4-166" : switch (id) {
                case "R04", "R05" -> "stt-index-1";
                default -> "stt-index-0";
            };
            var segments = new ArrayList<SourceRelationExtractionTrial.Segment>();
            if (id.equals("R01-166")) {
                for (int i = 156; i <= 187; i++) segments.add(new SourceRelationExtractionTrial.Segment(
                        "groq-v4-" + i, i * 1000L, i * 1000L + 500, i == 165 ? "오펜무가 뭐예요?" : "검수된 대화"));
            } else {
                int count = id.equals("R05") || id.equals("R08") ? 3 : 2;
                for (int i = 0; i < count; i++) segments.add(new SourceRelationExtractionTrial.Segment(
                        "stt-index-" + i, i * 2000L, i * 2000L + 1500, "합성 대화 " + i));
            }
            var c = new SourceRelationExtractionTrial.Condition(id, "USER_APPROVED_TEST_FIXTURE", anchor,
                    List.copyOf(segments), expected.get(id));
            conditions.add(c);
        }
        var byId = new HashMap<String, SourceRelationExtractionTrial.Condition>();
        conditions.forEach(c -> byId.put(c.conditionId(), c));
        var orders = List.of(List.of("R01-166", "R04", "R05", "R06", "R08"),
                List.of("R08", "R06", "R05", "R04", "R01-166"),
                List.of("R05", "R06", "R08", "R01-166", "R04"));
        for (int repeat = 1; repeat <= 3; repeat++) for (String id : orders.get(repeat - 1)) {
            var c = byId.get(id);
            String input = JSON.writeValueAsString(Map.of("focusId", c.anchorId(), "segments", c.segments()));
            requests.add(new SourceRelationExtractionTrial.Request(c.conditionId(), repeat,
                    SourceRelationExtractionTrial.SYSTEM, input, c.expected()));
        }
        double reservation = SourceRelationExtractionTrial.reservation(requests, .10, .50);
        var p = new SourceRelationExtractionTrial.Plan("source-relation-extraction-2", "gpt-6-luna", "none",
                ReviewGuidelineLibrary.retrievalHash(SourceRelationExtractionTrial.OUTPUT_FORMAT),
                SourceRelationExtractionTrial.SOURCE_SHA, SourceRelationExtractionTrial.LABEL_SHA,
                "5_FIXED_CONDITIONS_X_3_REPEATS_TEXT_ONLY_EXTRACTION_NOT_RISK_JUDGMENT", false, false,
                15, 1024, reservation, .10, .50, SourceRelationExtractionTrial.SYSTEM,
                List.copyOf(conditions), List.copyOf(requests));
        SourceRelationExtractionTrial.validate(p);
        return p;
    }

    @Test void freezesExactlyFiveConditionsAndFifteenTextOnlyRequests() throws Exception {
        var p = plan();
        assertThat(p.conditions()).extracting(SourceRelationExtractionTrial.Condition::conditionId)
                .containsExactly("R01-166", "R04", "R05", "R06", "R08");
        assertThat(p.requests()).hasSize(15);
        assertThat(p.estimatedCapUsd()).isLessThan(.50);
        for (var request : p.requests()) {
            JsonNode body = JSON.valueToTree(SourceRelationExtractionTrial.requestBody(request));
            assertThat(body.path("model").asText()).isEqualTo("gpt-6-luna");
            assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(1024);
            assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
            assertThat(body.at("/response_format/json_schema/strict").asBoolean()).isTrue();
            assertThat(body.toString()).doesNotContain("expectedDecision", "labelStatus", "contextReference", "OPENAI_API_KEY");
            assertThat(request.system()).contains("\"observations\"", "\"anchorId\"", "\"function\"", "\"contentUse\"");
            assertThat(request.input()).doesNotContain("expectedDecision", "labelStatus");
        }
    }

    @Test void scoresOnlyOfflineRelationsAndRejectsOutOfWindowEvidence() throws Exception {
        var p = plan(); var req = p.requests().stream().filter(r -> r.conditionId().equals("R05")).findFirst().orElseThrow();
        var observation = new SourceRelationExtractionTrial.Observation("stt-index-1", "DEFINITION_OR_EXPANSION",
                "MENTIONED_PHRASE", req.expected().links(), List.of("stt-index-0", "stt-index-1", "stt-index-2"));
        var assessment = SourceRelationExtractionTrial.assess(req, new SourceRelationExtractionTrial.RelationResult(List.of(observation)));
        assertThat(assessment).containsEntry("accepted", true).containsEntry("allExpectedRelationsMatch", true);
        var bad = new SourceRelationExtractionTrial.Observation("stt-index-1", "DEFINITION_OR_EXPANSION",
                "MENTIONED_PHRASE", List.of(new SourceRelationExtractionTrial.Link("not-in-source", "ANSWERS_MEANING_QUESTION")),
                List.of("stt-index-1"));
        assertThat(SourceRelationExtractionTrial.assess(req, new SourceRelationExtractionTrial.RelationResult(List.of(bad))))
                .containsEntry("accepted", false).containsEntry("failure", "LINK_ID_SCOPE");
    }

    @Test void missingObservationIsReportedAsContractFailureNotAuditCrash() throws Exception {
        var p = plan(); var req = p.requests().get(0);
        assertThat(SourceRelationExtractionTrial.assess(req, new SourceRelationExtractionTrial.RelationResult(null)))
                .containsEntry("accepted", false).containsEntry("failure", "ONE_OBSERVATION_REQUIRED");
    }

    @Test void auditsFullySpentButUnscorableStoppedResponsesWithoutCallingThemComplete() throws Exception {
        var p = plan(); var rows = new ArrayList<Map<String, Object>>();
        for (var req : p.requests()) {
            var output = new SourceRelationExtractionTrial.RelationResult(null);
            rows.add(Map.of("conditionId", req.conditionId(), "repeat", req.repeat(), "system", req.system(),
                    "input", JSON.readTree(req.input()), "output", output,
                    "assessment", SourceRelationExtractionTrial.assess(req, output), "httpAttempts", 1));
        }
        JsonNode saved = JSON.valueToTree(Map.of("state", "STOPPED", "httpAttempts", 15, "results", rows));
        var report = SourceRelationExtractionTrial.audit(p, saved);
        assertThat(report).containsEntry("state", "STOPPED").containsEntry("calls", 15)
                .containsEntry("structurallyAccepted", 0).containsEntry("exactExpectedRelationMatches", 0)
                .containsEntry("rawProviderResponsePreserved", false);
    }

    @Test void auditsThreeRepeatsWithoutTreatingThemAsIndependentCases() throws Exception {
        var p = plan(); var results = new ArrayList<Map<String, Object>>();
        for (var req : p.requests()) {
            var observation = new SourceRelationExtractionTrial.Observation(
                    SourceRelationExtractionTrial.focusId(req.input()), req.expected().function(), req.expected().contentUse(),
                    req.expected().links(), List.of(SourceRelationExtractionTrial.focusId(req.input())));
            var output = new SourceRelationExtractionTrial.RelationResult(List.of(observation));
            results.add(Map.of("conditionId", req.conditionId(), "repeat", req.repeat(), "system", req.system(),
                    "input", JSON.readTree(req.input()), "output", output,
                    "assessment", SourceRelationExtractionTrial.assess(req, output), "httpAttempts", 1));
        }
        JsonNode saved = JSON.valueToTree(Map.of("state", "COMPLETE", "httpAttempts", 15, "results", results));
        var report = SourceRelationExtractionTrial.audit(p, saved);
        assertThat(report).containsEntry("calls", 15).containsEntry("structurallyAccepted", 15)
                .containsEntry("exactExpectedRelationMatches", 15).containsEntry("independentGold", false);
        assertThat(JSON.valueToTree(report).at("/repeatStability/R01-166/stableAcross3").asBoolean()).isTrue();
    }

    @Test void requestGuardAllowsOnlyOneExactPostPerRequest() throws Exception {
        var req = plan().requests().get(0);
        var guard = new SourceRelationExtractionTrial.Guard();
        byte[] bytes = JSON.writeValueAsBytes(SourceRelationExtractionTrial.requestBody(req));
        guard.begin(bytes);
        assertThat(guard.accept(bytes, SourceRelationExtractionTrial.ENDPOINT)).containsExactly(bytes);
        assertThatThrownBy(() -> guard.accept(bytes, SourceRelationExtractionTrial.ENDPOINT))
                .hasMessageContaining("duplicate HTTP");
        guard.begin(bytes);
        assertThatThrownBy(() -> guard.accept(bytes, "https://example.com/chat/completions"))
                .hasMessageContaining("Unexpected endpoint");
    }

    @Test void newlyApprovedBudgetCannotBeExceededOrReusedPastFifteenCalls() throws Exception {
        Path dir = temporary.resolve("output"); java.nio.file.Files.createDirectory(dir);
        var budget = new SourceRelationExtractionTrial.TrialBudget(.50, .10, .50, dir);
        for (int i = 0; i < 15; i++) budget.reserve(1000, 1024);
        assertThat(budget.attempts).isEqualTo(15);
        assertThat(budget.reserved).isLessThan(.50);
        assertThatThrownBy(() -> budget.reserve(1000, 1024)).hasMessageContaining("cap reached");
        assertThat(budget.attempts).isEqualTo(15);
    }

    @Test void actualHttpPathSendsSchemaAndKeepsOriginalResponseBeforeParsing() throws Exception {
        var request = plan().requests().get(0);
        var output = new SourceRelationExtractionTrial.RelationResult(List.of(new SourceRelationExtractionTrial.Observation(
                SourceRelationExtractionTrial.focusId(request.input()), request.expected().function(), request.expected().contentUse(),
                request.expected().links(), List.of(SourceRelationExtractionTrial.focusId(request.input())))));
        String content = JSON.writeValueAsString(output);
        String raw = JSON.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", content))), "usage", Map.of("prompt_tokens", 100, "completion_tokens", 50)));
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SourceRelationExtractionTrial.ENDPOINT))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.observations.items.properties.links.items.required[0]").value("segmentId"))
                .andExpect(jsonPath("$.max_completion_tokens").value(1024))
                .andRespond(withSuccess(raw, MediaType.APPLICATION_JSON));
        Path saved = temporary.resolve("raw-response.json");
        String received = SourceRelationExtractionTrial.postAndPreserve(builder.build(), request, saved);
        assertThat(java.nio.file.Files.readString(saved)).isEqualTo(raw);
        var parsed = JSON.readValue(JSON.readTree(received).at("/choices/0/message/content").asText(),
                SourceRelationExtractionTrial.RelationResult.class);
        assertThat(SourceRelationExtractionTrial.assess(request, parsed)).containsEntry("accepted", true);
        server.verify();
    }

    @Test void malformedResponsesAreRetainedAndFailBeforeTheNextCall() throws Exception {
        var request = plan().requests().get(0);
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SourceRelationExtractionTrial.ENDPOINT)).andRespond(withSuccess("invalid JSON", MediaType.APPLICATION_JSON));
        Path saved = temporary.resolve("invalid-response.json");
        String received = SourceRelationExtractionTrial.postAndPreserve(builder.build(), request, saved);
        assertThatThrownBy(() -> JSON.readTree(received)).isInstanceOf(RuntimeException.class);
        assertThat(java.nio.file.Files.readString(saved)).isEqualTo("invalid JSON");
        assertThatThrownBy(() -> SourceRelationExtractionTrial.requireValidResponse(Map.of("accepted", false), null))
                .hasMessageContaining("stop before next provider call");
        server.verify();
    }

    @Test void aClaimedPlanCannotBeExecutedAgainWithAnotherDestination() throws Exception {
        Path plan = temporary.resolve("plan.json"); java.nio.file.Files.writeString(plan, "fixture");
        SourceRelationExtractionTrial.claimPlan(plan, temporary.resolve("first"), "frozen");
        assertThatThrownBy(() -> SourceRelationExtractionTrial.claimPlan(plan, temporary.resolve("second"), "frozen"))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }
}
