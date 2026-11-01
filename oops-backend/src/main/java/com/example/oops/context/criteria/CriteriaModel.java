package com.example.oops.context.criteria;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 맥락 확인 기준 데이터. (redesign-v3, docs/redesign-v3.md 8절)
 *
 * 저장 위치(JSON 파일 / Neo4j)와 상관없이 분석 코드는 이 모델만 쓴다.
 * 필드 이름은 Neo4j 본문 필드와 1:1 이다.
 */
public final class CriteriaModel {

    private CriteriaModel() {
    }

    /** 공통 기준. Neo4j: Criterion -HAS_CONTENT-> ContentBody */
    public record CommonCriterion(String id, String title, String summary, String version, String definition,
                                  List<String> instructions, List<String> candidateSignals,
                                  String interpretationLimit) {
        public CommonCriterion {
            instructions = List.copyOf(instructions);
            candidateSignals = List.copyOf(candidateSignals);
        }
    }

    /** 추가 기준. Neo4j: Criterion -HAS_ADDITIONAL-> ... */
    public record AdditionalCriterion(String id, String title, String summary, String version, String definition,
                                      List<String> instructions, List<String> reviewQuestions,
                                      String judgmentEffect) {
        public AdditionalCriterion {
            instructions = List.copyOf(instructions);
            reviewQuestions = List.copyOf(reviewQuestions);
        }
    }

    /** 독립 점검 규칙. Neo4j: Criterion -HAS_REVIEW_GATE-> ReviewGate -HAS_CONTENT-> ContentBody */
    public record ReviewGate(String id, String version, String minimumInformation, String exclusionConditions,
                             String missingInformationScope) {}

    /** 기준별 중단 판단 항목. criteria/stop-judgments-draft.json */
    public record StopJudgment(String code, String label, Kind kind, String requires) {
        public enum Kind {
            /** requires 근거가 대본에 없으면 이 판단만 중단한다. */
            STOP_IF_MISSING,
            /** 근거와 상관없이 결과에 단정해서 쓰지 않는다. */
            NEVER_ASSERT
        }
    }

    /** 공통 기준 하나와 거기 딸린 점검 규칙·추가 기준·중단 판단. */
    public record CriterionBundle(CommonCriterion common, ReviewGate gate, List<AdditionalCriterion> additional,
                                  String wholeStopIf, List<StopJudgment> judgments) {
        public CriterionBundle {
            additional = List.copyOf(additional);
            judgments = List.copyOf(judgments);
        }

        public String id() {
            return common.id();
        }

        public Set<String> stopIfMissingCodes() {
            return codes(StopJudgment.Kind.STOP_IF_MISSING);
        }

        public Set<String> neverAssertCodes() {
            return codes(StopJudgment.Kind.NEVER_ASSERT);
        }

        public Optional<StopJudgment> judgment(String code) {
            return judgments.stream().filter(j -> j.code().equals(code)).findFirst();
        }

        private Set<String> codes(StopJudgment.Kind kind) {
            return judgments.stream().filter(j -> j.kind() == kind).map(StopJudgment::code)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * 한 번의 분석 실행에 고정해서 쓰는 기준 묶음.
     * 실행 도중 원본(JSON·Neo4j)이 바뀌어도 이 객체는 바뀌지 않는다.
     */
    public record CriteriaSnapshot(String rulesetId, String rulesetVersion, String reviewGateVersion,
                                   String stopJudgmentVersion, List<CriterionBundle> criteria) {
        public CriteriaSnapshot {
            criteria = List.copyOf(criteria);
        }

        public Optional<CriterionBundle> find(String criterionId) {
            return criteria.stream().filter(c -> c.id().equals(criterionId)).findFirst();
        }

        public Map<String, CriterionBundle> byId() {
            Map<String, CriterionBundle> map = new LinkedHashMap<>();
            criteria.forEach(c -> map.put(c.id(), c));
            return map;
        }

        /** 결과에 함께 저장하는 버전 문자열 */
        public String versionLabel() {
            return rulesetId + "|" + rulesetVersion + "|" + reviewGateVersion + "|" + stopJudgmentVersion;
        }
    }
}
