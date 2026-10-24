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
    private String request(String... primary) {
        return JSON.writeValueAsString(Map.of("primaryIds",List.of(primary),"raw",
                java.util.stream.Stream.concat(Arrays.stream(primary),java.util.stream.Stream.of("context-only"))
                        .map(id -> Map.of("id",id,"text","원문")).toList()));
    }
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
                .andExpect(jsonPath("$.response_format.json_schema.schema.$defs.primaryId.enum[0]").value("stt-index-0"))
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
            assertThat(client.completeAsJson("JSON",request("s"),CandidateReviewEngine.Discovery.class)).isEmpty();
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
                assertThat(client.completeAsJson("JSON",request("s"),CandidateReviewEngine.Discovery.class)).isEmpty();
                assertThat(client.failureCode()).contains(refusal?"MODEL_REFUSAL":"INCOMPLETE_RESPONSE");
                assertThat(client.videoUsage().calls()).isOne();
            } finally { client.endVideo(); }
            server.verify();
        }
    }
    @Test void optInIsDtoScopedAndHistoricalConstructorRetainsOldFormat() {
        var on=new OpenAiClient(RestClient.create(),properties(),true);
        var off=new OpenAiClient(RestClient.create(),properties());
        assertThat(JSON.readTree(JSON.writeValueAsString(on.replayRequestBody("JSON",request("s"),CandidateReviewEngine.Discovery.class)))
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
    private tools.jackson.databind.JsonNode schema(OpenAiClient client, String input) {
        return JSON.valueToTree(client.replayRequestBody("JSON",input,CandidateReviewEngine.Discovery.class))
                .path("response_format").path("json_schema").path("schema");
    }
    @Test void scopeSeparatesPrimaryFromContextAcrossAllIdSlotsWithoutLeakingAcrossBatches() {
        var client=new OpenAiClient(RestClient.create(),properties(),true);
        var first=schema(client,request("a","b"));
        var second=schema(client,request("c"));
        assertThat(first.at("/$defs/primaryId/enum")).isEqualTo(JSON.valueToTree(List.of("a","b")));
        assertThat(first.at("/$defs/rawId/enum")).isEqualTo(JSON.valueToTree(List.of("a","b","context-only")));
        assertThat(second.at("/$defs/primaryId/enum")).isEqualTo(JSON.valueToTree(List.of("c")));
        assertThat(second.at("/$defs/rawId/enum")).isEqualTo(JSON.valueToTree(List.of("c","context-only")));
        for(String path:List.of("/properties/reviewedSegmentIds/items","/$defs/proposal/properties/anchorId",
                "/$defs/relationProposal/properties/anchorId","/$defs/uncertain/properties/segmentId"))
            assertThat(first.at(path).path("$ref").asText()).isEqualTo("#/$defs/primaryId");
        assertThat(first.at("/$defs/evidence/properties/segmentId/$ref").asText()).isEqualTo("#/$defs/rawId");
        first.at("/$defs/relations/properties").properties().forEach(e ->
                assertThat(e.getValue().at("/items/$ref").asText()).isEqualTo("#/$defs/rawId"));
        assertThat(first.at("/properties/visualCandidates/items/$ref").asText()).isEqualTo("#/$defs/proposal");
    }
    @Test void testOnlyControlChangesJustEnumBindingAndPreservesActualHttpAndReplay() {
        var builder=RestClient.builder().baseUrl("https://example.invalid");var server=MockRestServiceServer.bindTo(builder).build();
        var control=new MechanismRoutingTrial.Client(builder.build(),properties(),true,false);
        var scoped=new MechanismRoutingTrial.Client(RestClient.create(),properties(),true);
        String input=request("s");
        var scopedBody=JSON.valueToTree(scoped.replayRequestBody("JSON",input,CandidateReviewEngine.Discovery.class));
        var controlBody=JSON.valueToTree(control.replayRequestBody("JSON",input,CandidateReviewEngine.Discovery.class));
        for(String definition:List.of("primaryId","rawId"))
            ((tools.jackson.databind.node.ObjectNode)scopedBody.at("/response_format/json_schema/schema/$defs/"+definition)).remove("enum");
        assertThat(scopedBody).isEqualTo(controlBody);
        server.expect(requestTo("https://example.invalid/chat/completions"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.$defs.primaryId.enum").doesNotExist())
                .andExpect(jsonPath("$.response_format.json_schema.schema.$defs.rawId.enum").doesNotExist())
                .andRespond(withSuccess(response(emptyDiscovery("[\"s\"]","false"),"stop",null),MediaType.APPLICATION_JSON));
        var parsed=control.completeAsJson("JSON",input,CandidateReviewEngine.Discovery.class).orElseThrow();
        assertThat(parsed.reviewedSegmentIds()).containsExactly("s");assertThat(parsed.truncated()).isFalse();
        assertThat(control.calls).singleElement().satisfies(c -> {
            assertThat(c.get("kind")).isEqualTo("Discovery");
            assertThat(c.get("discoveryIdScopeEnabled")).isEqualTo(false);
            assertThat(c.get("responseFormatSha256")).isEqualTo(ReviewGuidelineLibrary.retrievalHash(controlBody.path("response_format").toString()));
        });
        server.verify();
    }
    @Test void malformedScopeFailsBeforeHttpWithoutJsonFallback() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new OpenAiClient(builder.build(),properties(),true);
        for(String input:List.of("null","{}","[]",
                "{\"primaryIds\":[],\"raw\":[]}",
                "{\"primaryIds\":[\"a\",\"a\"],\"raw\":[{\"id\":\"a\"}]}",
                "{\"primaryIds\":[\"a\"],\"raw\":[{\"id\":\"b\"}]}",
                "{\"primaryIds\":[1],\"raw\":[{\"id\":1}]}",
                "{\"primaryIds\":[\"a\"],\"raw\":[{\"id\":\"a\"},{\"id\":\"a\"}]}"))
            assertThatThrownBy(() -> client.completeAsJson("JSON",input,CandidateReviewEngine.Discovery.class))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schema(client,request("a".repeat(257)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schema(client,request(java.util.stream.IntStream.range(0,129)
                .mapToObj(i -> "s"+i).toArray(String[]::new)))).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }
    @Test void primaryButNotReviewedUncertaintyRemainsInvalidAtRuntime() {
        var builder=RestClient.builder().baseUrl("https://example.invalid");var server=MockRestServiceServer.bindTo(builder).build();
        String content="""
                {"reviewedSegmentIds":["stt-index-0"],"candidates":[],"visualCandidates":[],"truncated":false,
                 "uncertainSegments":[{"segmentId":"stt-index-1","missingInformation":["문맥 부족"]}]}
                """;
        server.expect(requestTo("https://example.invalid/chat/completions"))
                .andRespond(withSuccess(response(content,"stop",null),MediaType.APPLICATION_JSON));
        var client=new OpenAiClient(builder.build(),properties(),true);
        var r=CandidateReviewEngine.run(client,new CandidateReviewEngineTest().context("첫 발언","두 번째 발언"),24);
        assertThat(r.status().name()).isEqualTo("FAILED");
        assertThat(r.unassessedSegmentIds()).contains("stt-index-0","stt-index-1");
        assertThat(r.diagnostics().candidatePipeline().candidates()).anySatisfy(c ->
                assertThat(c.failureCode()).isEqualTo("DISCOVERY_ENVELOPE_INVALID"));
        server.verify();
    }
    @Test void overlappingBatchContextIsAvailableAsEvidenceButCannotBecomeUncertaintyReview() {
        var context=new CandidateReviewEngineTest().context(java.util.stream.IntStream.range(0,80)
                .mapToObj(i -> "발언 "+i).toArray(String[]::new));
        var batches=DialogueReadingPage.plan(context.reviewInput());
        assertThat(batches).hasSize(2);
        String contextId=batches.get(1).context().get(0).id();
        assertThat(batches.get(1).primary()).extracting(ReviewInput.Segment::id).doesNotContain(contextId);
        var builder=RestClient.builder().baseUrl("https://example.invalid");var server=MockRestServiceServer.bindTo(builder).build();
        var primary=batches.get(1).primary().stream().map(ReviewInput.Segment::id).toList();
        String invalid=JSON.writeValueAsString(Map.of("reviewedSegmentIds",primary,"candidates",List.of(),
                "visualCandidates",List.of(),"truncated",false,"uncertainSegments",List.of(
                        Map.of("segmentId",contextId,"missingInformation",List.of("문맥 부족")))));
        server.expect(requestTo("https://example.invalid/chat/completions"))
                .andRespond(withSuccess(response(emptyDiscovery(JSON.writeValueAsString(
                        batches.get(0).primary().stream().map(ReviewInput.Segment::id).toList()),"false"),"stop",null),MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://example.invalid/chat/completions"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.$defs.primaryId.enum.length()").value(primary.size()))
                .andExpect(jsonPath("$.response_format.json_schema.schema.$defs.rawId.enum.length()").value(DialogueReadingPage.raw(batches.get(1)).size()))
                .andRespond(withSuccess(response(invalid,"stop",null),MediaType.APPLICATION_JSON));
        var r=CandidateReviewEngine.run(new OpenAiClient(builder.build(),properties(),true),context,24);
        assertThat(r.status().name()).isEqualTo("PARTIAL");
        assertThat(r.unassessedSegmentIds()).contains(primary.get(0));
        assertThat(r.diagnostics().candidatePipeline().candidates()).anySatisfy(c ->
                assertThat(c.failureCode()).isEqualTo("DISCOVERY_ENVELOPE_INVALID"));
        server.verify();
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
            var parsed=client.completeAsJson("JSON",request("s"),CandidateReviewEngine.Discovery.class).orElseThrow();
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
