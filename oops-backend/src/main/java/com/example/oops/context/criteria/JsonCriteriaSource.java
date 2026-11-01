package com.example.oops.context.criteria;

import com.example.oops.context.criteria.CriteriaModel.AdditionalCriterion;
import com.example.oops.context.criteria.CriteriaModel.CommonCriterion;
import com.example.oops.context.criteria.CriteriaModel.CriteriaSnapshot;
import com.example.oops.context.criteria.CriteriaModel.CriterionBundle;
import com.example.oops.context.criteria.CriteriaModel.ReviewGate;
import com.example.oops.context.criteria.CriteriaModel.StopJudgment;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 저장소 안 JSON 파일에서 기준을 읽는다. (redesign-v3)
 *
 * - 기준: criteria/ruleset-oops-all-0.4-draft.json (oops_ai_criteria_full.html 의 source-data)
 * - 중단 판단 코드: criteria/stop-judgments-draft.json
 *
 * Neo4j 서버 DB 가 준비되기 전까지 쓰는 구현이다. 파일은 바뀌지 않으므로 처음 성공한 결과를 재사용한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oops.criteria", name = "source", havingValue = "json", matchIfMissing = true)
public class JsonCriteriaSource implements CriteriaSource {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern COMMON_ID = Pattern.compile("G\\d{2}-C\\d{2}");
    private static final Pattern ADDITIONAL_ID = Pattern.compile("G\\d{2}-A\\d{2}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private final Resource ruleset;
    private final Resource stopJudgments;
    private volatile CriteriaSnapshot cached;

    @Autowired
    public JsonCriteriaSource(ResourceLoader loader,
                              @Value("${oops.criteria.ruleset-location:classpath:criteria/ruleset-oops-all-0.4-draft.json}") String rulesetLocation,
                              @Value("${oops.criteria.stop-judgments-location:classpath:criteria/stop-judgments-draft.json}") String stopLocation) {
        this(loader.getResource(rulesetLocation), loader.getResource(stopLocation));
    }

    public JsonCriteriaSource(Resource ruleset, Resource stopJudgments) {
        this.ruleset = ruleset;
        this.stopJudgments = stopJudgments;
    }

    @Override
    public CriteriaSnapshot load() {
        CriteriaSnapshot snapshot = cached;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (cached == null) {
                cached = parse();
                log.info("[criteria] 기준 로드 완료 ruleset={} version={} gate={} stop={} criteria={}",
                        cached.rulesetId(), cached.rulesetVersion(), cached.reviewGateVersion(),
                        cached.stopJudgmentVersion(), cached.criteria().size());
            }
            return cached;
        }
    }

    private CriteriaSnapshot parse() {
        RawRuleset raw = read(ruleset, RawRuleset.class);
        RawStops stops = read(stopJudgments, RawStops.class);
        return build(raw, stops);
    }

    static <T> T read(Resource resource, Class<T> type) {
        if (resource == null || !resource.exists()) {
            throw new CriteriaLoadException("기준 파일이 없습니다: " + resource);
        }
        try (InputStream in = resource.getInputStream()) {
            return JSON.readValue(in, type);
        } catch (Exception e) {
            throw new CriteriaLoadException("기준 파일을 읽지 못했습니다: " + resource, e);
        }
    }

    static CriteriaSnapshot build(RawRuleset raw, RawStops stops) {
        require(raw != null, "기준 파일이 비어 있습니다.");
        text(raw.rulesetId(), "ruleset_id");
        text(raw.version(), "version");
        text(raw.independentReviewVersion(), "independent_review_version");
        require(raw.groups() != null && !raw.groups().isEmpty(), "기준 묶음(groups)이 없습니다.");

        require(stops != null, "중단 판단 파일이 비어 있습니다.");
        text(stops.version(), "stop-judgments version");
        require(raw.rulesetId().equals(stops.rulesetId()),
                "중단 판단 파일의 ruleset_id 가 다릅니다: " + stops.rulesetId());
        require(raw.independentReviewVersion().equals(stops.basedOn()),
                "중단 판단 파일의 based_on 이 독립 점검 버전과 다릅니다: " + stops.basedOn());
        require(stops.criteria() != null, "중단 판단 항목(criteria)이 없습니다.");

        Map<String, RawStopCriterion> stopById = new LinkedHashMap<>();
        for (RawStopCriterion s : stops.criteria()) {
            require(s != null && s.criterionId() != null, "중단 판단 항목에 criterion_id 가 없습니다.");
            require(stopById.put(s.criterionId(), s) == null, "중단 판단 항목이 중복됩니다: " + s.criterionId());
        }

        Set<String> additionalIds = new HashSet<>();
        List<CriterionBundle> bundles = new ArrayList<>();
        Set<String> commonIds = new HashSet<>();
        for (RawGroup g : raw.groups()) {
            require(g != null && g.common() != null, "공통 기준이 없는 묶음이 있습니다.");
            RawCommon c = g.common();
            String id = text(c.id(), "common.id");
            require(COMMON_ID.matcher(id).matches(), "공통 기준 ID 형식이 아닙니다: " + id);
            require(commonIds.add(id), "공통 기준 ID 가 중복됩니다: " + id);
            require(raw.version().equals(c.version()), id + " 버전이 ruleset 버전과 다릅니다: " + c.version());
            var common = new CommonCriterion(id, text(c.title(), id + ".title"), text(c.summary(), id + ".summary"),
                    c.version(), text(c.definition(), id + ".definition"),
                    texts(c.instructions(), id + ".instructions"), texts(c.candidateSignals(), id + ".candidate_signals"),
                    text(c.interpretationLimit(), id + ".interpretation_limit"));

            RawGate rg = g.independentReview();
            require(rg != null, id + " 독립 점검 규칙이 없습니다.");
            String gateId = text(rg.id(), id + ".independent_review.id");
            require(gateId.startsWith("review-gate:" + id + "@"), id + " 독립 점검 ID 가 기준과 맞지 않습니다: " + gateId);
            require(raw.independentReviewVersion().equals(rg.version()),
                    id + " 독립 점검 버전이 다릅니다: " + rg.version());
            var gate = new ReviewGate(gateId, rg.version(),
                    text(rg.minimumInformation(), id + ".minimum_information"),
                    text(rg.exclusionConditions(), id + ".exclusion_conditions"),
                    text(rg.missingInformationScope(), id + ".missing_information_scope"));

            require(g.additional() != null && !g.additional().isEmpty(), id + " 추가 기준이 없습니다.");
            String prefix = id.substring(0, 3);
            List<AdditionalCriterion> additional = new ArrayList<>();
            for (RawAdditional a : g.additional()) {
                require(a != null, id + " 추가 기준에 빈 항목이 있습니다.");
                String aid = text(a.id(), id + ".additional.id");
                require(ADDITIONAL_ID.matcher(aid).matches() && aid.startsWith(prefix),
                        id + " 추가 기준 ID 가 맞지 않습니다: " + aid);
                require(additionalIds.add(aid), "추가 기준 ID 가 중복됩니다: " + aid);
                require(raw.version().equals(a.version()), aid + " 버전이 ruleset 버전과 다릅니다: " + a.version());
                additional.add(new AdditionalCriterion(aid, text(a.title(), aid + ".title"), text(a.summary(), aid + ".summary"),
                        a.version(), text(a.definition(), aid + ".definition"),
                        texts(a.instructions(), aid + ".instructions"), texts(a.reviewQuestions(), aid + ".review_questions"),
                        text(a.judgmentEffect(), aid + ".judgment_effect")));
            }

            RawStopCriterion s = stopById.remove(id);
            require(s != null, id + " 중단 판단 항목이 없습니다.");
            require(gateId.equals(s.reviewGateId()), id + " 중단 판단 항목의 review_gate_id 가 다릅니다: " + s.reviewGateId());
            List<StopJudgment> judgments = new ArrayList<>();
            Set<String> codes = new HashSet<>();
            for (RawJudgment j : s.judgments() == null ? List.<RawJudgment>of() : s.judgments()) {
                require(j != null && j.code() != null && CODE.matcher(j.code()).matches(),
                        id + " 중단 판단 코드 형식이 아닙니다: " + (j == null ? null : j.code()));
                require(codes.add(j.code()), id + " 중단 판단 코드가 중복됩니다: " + j.code());
                StopJudgment.Kind kind;
                try {
                    kind = StopJudgment.Kind.valueOf(String.valueOf(j.kind()));
                } catch (IllegalArgumentException e) {
                    throw new CriteriaLoadException(id + " 중단 판단 종류가 아닙니다: " + j.kind());
                }
                String requires = j.requires();
                if (kind == StopJudgment.Kind.STOP_IF_MISSING) text(requires, id + "." + j.code() + ".requires");
                judgments.add(new StopJudgment(j.code(), text(j.label(), id + "." + j.code() + ".label"), kind, requires));
            }
            String wholeStop = s.wholeStopIf() == null || s.wholeStopIf().isBlank() ? null : s.wholeStopIf().strip();
            bundles.add(new CriterionBundle(common, gate, additional, wholeStop, judgments));
        }
        require(stopById.isEmpty(), "기준에 없는 중단 판단 항목이 있습니다: " + stopById.keySet());

        return new CriteriaSnapshot(raw.rulesetId(), raw.version(), raw.independentReviewVersion(), stops.version(), bundles);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new CriteriaLoadException(message);
    }

    private static String text(String value, String field) {
        require(value != null && !value.isBlank(), "필수 값이 비어 있습니다: " + field);
        return value.strip();
    }

    private static List<String> texts(List<String> values, String field) {
        require(values != null && !values.isEmpty(), "필수 목록이 비어 있습니다: " + field);
        List<String> out = new ArrayList<>();
        for (String v : values) out.add(text(v, field));
        return out;
    }

    // ── JSON 원본 형태 (snake_case) ─────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawRuleset(@JsonProperty("ruleset_id") String rulesetId,
                      @JsonProperty("version") String version,
                      @JsonProperty("independent_review_version") String independentReviewVersion,
                      @JsonProperty("groups") List<RawGroup> groups) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawGroup(@JsonProperty("common") RawCommon common,
                    @JsonProperty("additional") List<RawAdditional> additional,
                    @JsonProperty("independent_review") RawGate independentReview) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawCommon(@JsonProperty("id") String id, @JsonProperty("title") String title,
                     @JsonProperty("summary") String summary, @JsonProperty("version") String version,
                     @JsonProperty("definition") String definition,
                     @JsonProperty("instructions") List<String> instructions,
                     @JsonProperty("candidate_signals") List<String> candidateSignals,
                     @JsonProperty("interpretation_limit") String interpretationLimit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawAdditional(@JsonProperty("id") String id, @JsonProperty("title") String title,
                         @JsonProperty("summary") String summary, @JsonProperty("version") String version,
                         @JsonProperty("definition") String definition,
                         @JsonProperty("instructions") List<String> instructions,
                         @JsonProperty("review_questions") List<String> reviewQuestions,
                         @JsonProperty("judgment_effect") String judgmentEffect) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawGate(@JsonProperty("id") String id, @JsonProperty("version") String version,
                   @JsonProperty("minimum_information") String minimumInformation,
                   @JsonProperty("exclusion_conditions") String exclusionConditions,
                   @JsonProperty("missing_information_scope") String missingInformationScope) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawStops(@JsonProperty("version") String version, @JsonProperty("based_on") String basedOn,
                    @JsonProperty("ruleset_id") String rulesetId,
                    @JsonProperty("criteria") List<RawStopCriterion> criteria) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawStopCriterion(@JsonProperty("criterion_id") String criterionId,
                            @JsonProperty("review_gate_id") String reviewGateId,
                            @JsonProperty("whole_stop_if") String wholeStopIf,
                            @JsonProperty("judgments") List<RawJudgment> judgments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawJudgment(@JsonProperty("code") String code, @JsonProperty("label") String label,
                       @JsonProperty("kind") String kind, @JsonProperty("requires") String requires) {}
}
