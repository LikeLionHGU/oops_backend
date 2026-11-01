package com.example.oops.context.criteria;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Neo4j Query API 응답을 흉내 내 JSON 원본과 같은 스냅숏이 나오는지 본다. (seed 데이터는 JSON 과 동일하다) */
class Neo4jCriteriaSourceTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    record Fixture(Neo4jCriteriaSource source, MockRestServiceServer server) {}

    static Fixture fixture(Consumer<ObjectNode[]> tweak) {
        var builder = RestClient.builder().baseUrl("http://neo4j.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var source = new Neo4jCriteriaSource(builder.build(), "neo4j", "ruleset:oops:all:0.4-draft",
                "independent-review/1.0", new ClassPathResource(JsonCriteriaSourceTest.STOPS));
        ObjectNode[] responses = responsesFromBundledJson();
        tweak.accept(responses);
        for (ObjectNode r : responses) {
            server.expect(requestTo("http://neo4j.test/db/neo4j/query/v2")).andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andRespond(withSuccess(JSON.writeValueAsString(r), MediaType.APPLICATION_JSON));
        }
        return new Fixture(source, server);
    }

    @Test void neo4jRowsBuildTheSameSnapshotAsTheBundledJson() {
        var f = fixture(r -> {});
        var fromNeo4j = f.source().load();
        f.server().verify();
        var fromJson = JsonCriteriaSourceTest.real().load();
        assertThat(fromNeo4j.criteria()).isEqualTo(fromJson.criteria());
        assertThat(fromNeo4j.versionLabel()).isEqualTo(fromJson.versionLabel());
    }

    @Test void queryErrorFailsInsteadOfReturningEmptyCriteria() {
        var f = fixture(r -> {
            r[0].removeAll();
            r[0].putArray("errors").addObject().put("message", "Neo.ClientError.Security.Unauthorized");
        });
        assertThatThrownBy(() -> f.source().load()).isInstanceOf(CriteriaLoadException.class)
                .hasMessageContaining("Unauthorized");
    }

    @Test void duplicateGateFails() {
        var f = fixture(r -> {
            var values = (ArrayNode) r[1].get("data").get("values");
            values.add(values.get(0).deepCopy());
        });
        assertThatThrownBy(() -> f.source().load()).isInstanceOf(CriteriaLoadException.class)
                .hasMessageContaining("둘 이상");
    }

    @Test void missingGateFails() {
        var f = fixture(r -> ((ArrayNode) r[1].get("data").get("values")).remove(3));
        assertThatThrownBy(() -> f.source().load()).isInstanceOf(CriteriaLoadException.class)
                .hasMessageContaining("G04-C01");
    }

    /** 번들 JSON 을 조회문 02·09·03 의 결과 형태로 바꾼다. */
    static ObjectNode[] responsesFromBundledJson() {
        JsonNode root;
        try (var in = new ClassPathResource(JsonCriteriaSourceTest.RULESET).getInputStream()) {
            root = JSON.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var common = response(List.of("id", "title", "summary", "version", "definition", "instructions",
                "candidate_signals", "interpretation_limit"));
        var gates = response(List.of("criterion_id", "id", "version", "minimum_information", "exclusion_conditions",
                "missing_information_scope"));
        var additional = response(List.of("common_id", "id", "title", "summary", "version", "definition",
                "instructions", "review_questions", "judgment_effect"));
        for (JsonNode g : root.get("groups")) {
            JsonNode c = g.get("common");
            row(common, c, "id", "title", "summary", "version", "definition", "instructions", "candidate_signals",
                    "interpretation_limit");
            JsonNode ir = g.get("independent_review");
            var gr = ((ArrayNode) gates.get("data").get("values")).addArray();
            gr.add(c.get("id").asText());
            for (String k : List.of("id", "version", "minimum_information", "exclusion_conditions",
                    "missing_information_scope")) gr.add(ir.get(k));
            for (JsonNode a : g.get("additional")) {
                var ar = ((ArrayNode) additional.get("data").get("values")).addArray();
                ar.add(c.get("id").asText());
                for (String k : List.of("id", "title", "summary", "version", "definition", "instructions",
                        "review_questions", "judgment_effect")) ar.add(a.get(k));
            }
        }
        return new ObjectNode[]{common, gates, additional};
    }

    static ObjectNode response(List<String> fields) {
        var r = JSON.createObjectNode();
        var data = r.putObject("data");
        var f = data.putArray("fields");
        fields.forEach(f::add);
        data.putArray("values");
        return r;
    }

    static void row(ObjectNode response, JsonNode source, String... keys) {
        var row = ((ArrayNode) response.get("data").get("values")).addArray();
        for (String k : keys) row.add(source.get(k));
    }
}
