package com.example.oops.context.criteria;

import com.example.oops.context.criteria.CriteriaModel.CriteriaSnapshot;
import com.example.oops.context.criteria.JsonCriteriaSource.RawAdditional;
import com.example.oops.context.criteria.JsonCriteriaSource.RawCommon;
import com.example.oops.context.criteria.JsonCriteriaSource.RawGate;
import com.example.oops.context.criteria.JsonCriteriaSource.RawGroup;
import com.example.oops.context.criteria.JsonCriteriaSource.RawRuleset;
import com.example.oops.context.criteria.JsonCriteriaSource.RawStops;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 서버 Neo4j 에서 기준을 읽는다. (redesign-v3, docs/redesign-v3.md 8절)
 *
 * `oops.criteria.source=neo4j` 일 때만 쓴다. 기본은 {@link JsonCriteriaSource}.
 *
 * 연결: Neo4j HTTP Query API (`POST {http-uri}/db/{database}/query/v2`).
 * 서버 배포 묶음(infra/neo4j, neo4j-data-setup 브랜치)은 7474·7687 을 127.0.0.1 에만 열고,
 * 백엔드 JAR 은 같은 서버에서 돈다. Bolt 드라이버를 추가하지 않고 HTTP 로 읽는다.
 *
 * 조회문은 infra/neo4j/queries 의 02(공통 본문)·03(추가 본문)·09(독립 점검)와 같은 경로를 따른다.
 * 중단 판단 코드는 아직 Neo4j 에 없으므로 저장소 JSON(stop-judgments)을 함께 읽는다.
 *
 * 매 실행마다 새로 읽고, 받은 스냅숏을 그 실행 동안 고정한다(캐시하지 않는다).
 * 연결 실패·누락·중복·버전 불일치는 {@link CriteriaLoadException} 으로 실패시킨다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oops.criteria", name = "source", havingValue = "neo4j")
public class Neo4jCriteriaSource implements CriteriaSource {

    static final String COMMON_QUERY = """
            MATCH (:RuleSet {id:$ruleset})-[h:HAS_COMMON]->(c:Criterion)-[:HAS_CONTENT]->(b:ContentBody)
            RETURN c.criterion_id AS id, c.title AS title, c.summary AS summary, c.version AS version,
                   b.definition AS definition, b.instructions AS instructions,
                   b.candidate_signals AS candidate_signals, b.interpretation_limit AS interpretation_limit
            ORDER BY h.order""";

    static final String GATE_QUERY = """
            MATCH (:RuleSet {id:$ruleset})-[h:HAS_COMMON]->(c:Criterion)
            MATCH (c)-[:HAS_REVIEW_GATE]->(g:ReviewGate {version:$gateVersion})-[:HAS_CONTENT]->(b:ContentBody)
            RETURN c.criterion_id AS criterion_id, g.id AS id, g.version AS version,
                   b.minimum_information AS minimum_information,
                   b.exclusion_conditions AS exclusion_conditions,
                   b.missing_information_scope AS missing_information_scope
            ORDER BY h.order""";

    static final String ADDITIONAL_QUERY = """
            MATCH (:RuleSet {id:$ruleset})-[h:HAS_COMMON]->(c:Criterion)-[r:HAS_ADDITIONAL]->(a:Criterion)-[:HAS_CONTENT]->(b:ContentBody)
            RETURN c.criterion_id AS common_id, a.criterion_id AS id, a.title AS title, a.summary AS summary,
                   a.version AS version, b.definition AS definition, b.instructions AS instructions,
                   b.review_questions AS review_questions, b.judgment_effect AS judgment_effect
            ORDER BY h.order, r.order""";

    private final RestClient client;
    private final String database;
    private final String rulesetId;
    private final String gateVersion;
    private final Resource stopJudgments;

    @Autowired
    public Neo4jCriteriaSource(ResourceLoader loader,
                               @Value("${oops.criteria.neo4j.http-uri:http://127.0.0.1:7474}") String httpUri,
                               @Value("${oops.criteria.neo4j.database:neo4j}") String database,
                               @Value("${oops.criteria.neo4j.username:neo4j}") String username,
                               @Value("${oops.criteria.neo4j.password:}") String password,
                               @Value("${oops.criteria.neo4j.ruleset-id:ruleset:oops:all:0.4-draft}") String rulesetId,
                               @Value("${oops.criteria.neo4j.review-gate-version:independent-review/1.0}") String gateVersion,
                               @Value("${oops.criteria.stop-judgments-location:classpath:criteria/stop-judgments-draft.json}") String stopLocation) {
        this(buildClient(httpUri, username, password), database, rulesetId, gateVersion, loader.getResource(stopLocation));
        if (password == null || password.isBlank()) {
            log.warn("[criteria] oops.criteria.neo4j.password(NEO4J_PASSWORD)가 비어 있습니다. 기준 조회가 실패합니다.");
        }
    }

    Neo4jCriteriaSource(RestClient client, String database, String rulesetId, String gateVersion, Resource stopJudgments) {
        this.client = client;
        this.database = database;
        this.rulesetId = rulesetId;
        this.gateVersion = gateVersion;
        this.stopJudgments = stopJudgments;
    }

    private static RestClient buildClient(String httpUri, String username, String password) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        String token = Base64.getEncoder().encodeToString(
                (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        return RestClient.builder().baseUrl(httpUri).requestFactory(factory)
                .defaultHeader("Authorization", "Basic " + token).build();
    }

    @Override
    public CriteriaSnapshot load() {
        Map<String, Object> params = Map.of("ruleset", rulesetId, "gateVersion", gateVersion);
        List<Map<String, JsonNode>> commons = query(COMMON_QUERY, params);
        List<Map<String, JsonNode>> gates = query(GATE_QUERY, params);
        List<Map<String, JsonNode>> additionals = query(ADDITIONAL_QUERY, params);
        if (commons.isEmpty()) throw new CriteriaLoadException("Neo4j 에서 공통 기준을 찾지 못했습니다: " + rulesetId);

        Map<String, RawGate> gateById = new HashMap<>();
        for (var row : gates) {
            String cid = str(row, "criterion_id");
            if (gateById.put(cid, new RawGate(str(row, "id"), str(row, "version"), str(row, "minimum_information"),
                    str(row, "exclusion_conditions"), str(row, "missing_information_scope"))) != null) {
                throw new CriteriaLoadException(cid + " 독립 점검 규칙이 둘 이상입니다.");
            }
        }
        Map<String, List<RawAdditional>> additionalById = new LinkedHashMap<>();
        for (var row : additionals) {
            additionalById.computeIfAbsent(str(row, "common_id"), k -> new ArrayList<>())
                    .add(new RawAdditional(str(row, "id"), str(row, "title"), str(row, "summary"), str(row, "version"),
                            str(row, "definition"), list(row, "instructions"), list(row, "review_questions"),
                            str(row, "judgment_effect")));
        }

        List<RawGroup> groups = new ArrayList<>();
        String version = str(commons.get(0), "version");
        for (var row : commons) {
            String id = str(row, "id");
            var common = new RawCommon(id, str(row, "title"), str(row, "summary"), str(row, "version"),
                    str(row, "definition"), list(row, "instructions"), list(row, "candidate_signals"),
                    str(row, "interpretation_limit"));
            groups.add(new RawGroup(common, additionalById.remove(id), gateById.remove(id)));
        }
        if (!gateById.isEmpty()) throw new CriteriaLoadException("공통 기준과 연결되지 않은 독립 점검 규칙: " + gateById.keySet());
        if (!additionalById.isEmpty()) throw new CriteriaLoadException("공통 기준과 연결되지 않은 추가 기준: " + additionalById.keySet());

        var raw = new RawRuleset(rulesetId, version, gateVersion, groups);
        var snapshot = JsonCriteriaSource.build(raw, JsonCriteriaSource.read(stopJudgments, RawStops.class));
        log.info("[criteria] Neo4j 기준 로드 ruleset={} version={} gate={} criteria={}",
                snapshot.rulesetId(), snapshot.rulesetVersion(), snapshot.reviewGateVersion(), snapshot.criteria().size());
        return snapshot;
    }

    /** Query API v2 응답 {"data":{"fields":[...],"values":[[...]]}} 을 행 목록으로 바꾼다. */
    List<Map<String, JsonNode>> query(String statement, Map<String, Object> parameters) {
        JsonNode response;
        try {
            response = client.post().uri("/db/{database}/query/v2", database)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("statement", statement, "parameters", parameters))
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new CriteriaLoadException("Neo4j 기준 조회에 실패했습니다: " + e.getMessage(), e);
        }
        if (response == null) throw new CriteriaLoadException("Neo4j 응답이 비어 있습니다.");
        JsonNode errors = response.get("errors");
        if (errors != null && errors.isArray() && !errors.isEmpty()) {
            throw new CriteriaLoadException("Neo4j 조회 오류: " + errors.get(0).path("message").asText());
        }
        JsonNode data = response.get("data");
        if (data == null || !data.path("fields").isArray() || !data.path("values").isArray()) {
            throw new CriteriaLoadException("Neo4j 응답 형식이 다릅니다.");
        }
        List<String> fields = new ArrayList<>();
        data.get("fields").forEach(f -> fields.add(f.asText()));
        List<Map<String, JsonNode>> rows = new ArrayList<>();
        for (JsonNode values : data.get("values")) {
            Map<String, JsonNode> row = new HashMap<>();
            for (int i = 0; i < fields.size(); i++) row.put(fields.get(i), values.get(i));
            rows.add(row);
        }
        return rows;
    }

    private static String str(Map<String, JsonNode> row, String field) {
        JsonNode v = row.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static List<String> list(Map<String, JsonNode> row, String field) {
        JsonNode v = row.get(field);
        if (v == null || !v.isArray()) return null;
        List<String> out = new ArrayList<>();
        v.forEach(x -> out.add(x.isNull() ? null : x.asText()));
        return out;
    }
}
