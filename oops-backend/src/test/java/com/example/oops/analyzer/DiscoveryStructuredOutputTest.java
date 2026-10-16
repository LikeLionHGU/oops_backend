package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Mock transport only: cannot prove model/provider schema support or better judgments. */
class DiscoveryStructuredOutputTest {
    private OpenAiProperties properties() {
        return new OpenAiProperties("offline-test-key",null,null,"https://example.invalid","gpt-6-luna","none",Duration.ofSeconds(2),null);
    }
    private String response(String content, String finish, String refusal) {
        var message = new LinkedHashMap<String,Object>(); message.put("content",content); message.put("refusal",refusal);
        return JSON.writeValueAsString(Map.of("model","gpt-6-luna","choices",List.of(Map.of("finish_reason",finish,"message",message)),
                "usage",Map.of("prompt_tokens",100,"completion_tokens",20,"total_tokens",120)));
    }
    private String emptyDiscovery(String ids, String truncated) {
        return "{\"reviewedSegmentIds\":"+ids+",\"candidates\":[],\"uncertainSegments\":[],\"visualCandidates\":[]"
                +(truncated==null?"":",\"truncated\":"+truncated)+"}";
    }
    @Test void actualHttpRequestsStrictSchemaAndValidFalseCanPassWithoutWarning() {
        var builder=RestClient.builder().baseUrl("https://example.invalid"); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.invalid/chat/completions"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.truncated.type").value("boolean"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.required").isArray())
                .andRespond(withSuccess(response(emptyDiscovery("[\"stt-index-0\"]","false"),"stop",null),MediaType.APPLICATION_JSON));
        var client=new OpenAiClient(builder.build(),properties(),true); client.beginVideo(1L);
        try {
            var r=CandidateReviewEngine.run(client,new CandidateReviewEngineTest().context("이 상품은 파란색입니다"),24);
            assertThat(r.status().name()).isEqualTo("SUCCESS"); assertThat(r.findings()).isEmpty(); assertThat(r.unassessedSegmentIds()).isEmpty();
            assertThat(client.videoUsage().calls()).isOne();
        } finally { client.endVideo(); }
        server.verify();
    }
    @Test void malformedProviderOutputIsNotCoercedToFalseOrPassedEvenWithStrictRequested() {
        for (String value:Arrays.asList(null,"null")) {
            var builder=RestClient.builder().baseUrl("https://example.invalid"); var server=MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://example.invalid/chat/completions"))
                    .andRespond(withSuccess(response(emptyDiscovery("[\"stt-index-0\"]",value),"stop",null),MediaType.APPLICATION_JSON));
            var client=new OpenAiClient(builder.build(),properties(),true); client.beginVideo(1L);
            try {
                var r=CandidateReviewEngine.run(client,new CandidateReviewEngineTest().context("이 상품은 파란색입니다"),24);
                assertThat(r.status().name()).isEqualTo("FAILED"); assertThat(r.unassessedSegmentIds()).containsExactly("stt-index-0");
                assertThat(r.diagnostics().candidatePipeline().candidates()).anySatisfy(c -> {
                    assertThat(c.state()).isEqualTo("INVALID_DISCOVERY"); assertThat(c.failureCode()).isEqualTo("DISCOVERY_TRUNCATED_REQUIRED");
                });
                assertThat(client.videoUsage().calls()).isOne();
            } finally { client.endVideo(); }
            server.verify();
        }
    }
    @Test void structurallyValidButUnknownIdsAreStillRejected() {
        var builder=RestClient.builder().baseUrl("https://example.invalid"); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.invalid/chat/completions"))
                .andRespond(withSuccess(response(emptyDiscovery("[\"made-up-id\"]","false"),"stop",null),MediaType.APPLICATION_JSON));
        var client=new OpenAiClient(builder.build(),properties(),true); client.beginVideo(1L);
        try {
            var r=CandidateReviewEngine.run(client,new CandidateReviewEngineTest().context("정상적인 상품 안내"),24);
            assertThat(r.status().name()).isEqualTo("FAILED"); assertThat(r.unassessedSegmentIds()).isNotEmpty();
        } finally { client.endVideo(); }
        server.verify();
    }
    @Test void unsupportedSchemaDoesNotSilentlyFallBackOrRetryAsJsonMode() {
        var builder=RestClient.builder().baseUrl("https://example.invalid"); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.invalid/chat/completions"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));
        var client=new OpenAiClient(builder.build(),properties(),true); client.beginVideo(1L);
        try {
            assertThat(client.completeAsJson("JSON","raw",CandidateReviewEngine.Discovery.class)).isEmpty();
            assertThat(client.failureCode()).contains("HTTP_FAILED");
        } finally { client.endVideo(); }
        server.verify();
    }
    @Test void refusalAndLengthRemainFailuresNotEmptySafeResults() {
        for (boolean refusal:List.of(true,false)) {
            var builder=RestClient.builder().baseUrl("https://example.invalid"); var server=MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://example.invalid/chat/completions"))
                    .andRespond(withSuccess(response(emptyDiscovery("[\"stt-index-0\"]","false"),refusal?"stop":"length",refusal?"refused":null),MediaType.APPLICATION_JSON));
            var client=new OpenAiClient(builder.build(),properties(),true); client.beginVideo(1L);
            try {
                assertThat(client.completeAsJson("JSON","raw",CandidateReviewEngine.Discovery.class)).isEmpty();
                assertThat(client.failureCode()).contains(refusal?"MODEL_REFUSAL":"INCOMPLETE_RESPONSE");
                assertThat(client.videoUsage().calls()).isOne();
            } finally { client.endVideo(); }
            server.verify();
        }
    }
    @Test void optInIsDtoScopedAndHistoricalConstructorRetainsOldFormat() {
        var on=new OpenAiClient(RestClient.create(),properties(),true);
        var off=new OpenAiClient(RestClient.create(),properties());
        assertThat(JSON.readTree(JSON.writeValueAsString(on.replayRequestBody("JSON","raw",CandidateReviewEngine.Discovery.class)))
                .path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(JSON.readTree(JSON.writeValueAsString(on.replayRequestBody("JSON","raw",CandidateReviewEngine.VerificationResult.class)))
                .path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(off.replayRequestBody("JSON","raw",CandidateReviewEngine.Discovery.class)).isEqualTo(off.replayRequestBody("JSON","raw"));
    }
    private void assertRequiredObjects(tools.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            if ("object".equals(node.path("type").asText())) {
                assertThat(node.path("additionalProperties").asBoolean(true)).isFalse();
                var required=new HashSet<String>();node.path("required").forEach(n -> required.add(n.asText()));
                var properties=new HashSet<String>();node.path("properties").properties().forEach(e -> properties.add(e.getKey()));
                assertThat(required).isEqualTo(properties);
            }
            node.properties().forEach(e -> assertRequiredObjects(e.getValue()));
        } else if(node.isArray()) node.forEach(this::assertRequiredObjects);
    }
    @Test void bothSchemaProposalShapesDeserializeWithoutInventingQuotesOrRelations() {
        var builder=RestClient.builder().baseUrl("https://example.invalid"); var server=MockRestServiceServer.bindTo(builder).build();
        String content="""
                {"reviewedSegmentIds":["s"],"truncated":false,"uncertainSegments":[],"visualCandidates":[],"candidates":[
                  {"anchorId":"s","axis":"EXPRESSION_CONTENT","reason":"원문 확인","evidence":[{"segmentId":"s"}]},
                  {"anchorId":"s","axis":"TARGET_TREATMENT","evidence":[{"segmentId":"s"}],"sourceRelations":{
                    "situationIds":[],"subjectIds":[],"actionIds":[],"evaluationIds":["s"],"referentIds":[]}}
                ]}
                """;
        server.expect(requestTo("https://example.invalid/chat/completions"))
                .andRespond(withSuccess(response(content,"stop",null),MediaType.APPLICATION_JSON));
        var client=new OpenAiClient(builder.build(),properties(),true); client.beginVideo(1L);
        try {
            var parsed=client.completeAsJson("JSON","raw",CandidateReviewEngine.Discovery.class).orElseThrow();
            assertThat(parsed.candidates()).hasSize(2);
            assertThat(parsed.candidates().get(0).sourceRelations()).isNull();
            assertThat(parsed.candidates().get(1).reason()).isNull();
            assertThat(parsed.candidates().get(1).sourceRelations()).isNotNull();
            assertThat(parsed.candidates()).allSatisfy(p -> assertThat(p.evidence().get(0).quote()).isNull());
        } finally { client.endVideo(); }
        server.verify();
    }
    @Test void springDefaultUsesVerifiedDiscoverySchemaWithExplicitOptOut() throws Exception {
        var constructor=Arrays.stream(OpenAiClient.class.getConstructors())
                .filter(c -> c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)).findFirst().orElseThrow();
        assertThat(constructor.getParameters()[2].getAnnotation(org.springframework.beans.factory.annotation.Value.class).value())
                .isEqualTo("${oops.openai.structured-discovery-output-enabled:true}");
        try(var in=getClass().getResourceAsStream("/application.yml")) {
            assertThat(new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))
                    .contains("structured-discovery-output-enabled: ${STRUCTURED_DISCOVERY_OUTPUT_ENABLED:true}");
        }
    }
    @Test void schemaRequiresBooleanAndModelsBothProposalVariantsWithoutForcedNullableFields() throws Exception {
        try(var in=getClass().getResourceAsStream("/candidate-discovery-output.schema.json")) {
            var schema=JSON.readTree(Objects.requireNonNull(in).readAllBytes()); assertRequiredObjects(schema);
            assertThat(schema.path("properties").path("truncated").path("type").asText()).isEqualTo("boolean");
            assertThat(schema.path("$defs").path("proposal").path("properties").has("sourceRelations")).isFalse();
            assertThat(schema.path("$defs").path("relationProposal").path("properties").has("reason")).isFalse();
            assertThat(schema.path("$defs").path("evidence").path("properties").has("quote")).isFalse();
        }
    }
}
