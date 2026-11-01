// 서버에서는 $criterion_id와 $rule_version 파라미터로 전달한다.
// Browser 확인용: G02-C01을 원하는 공통 기준 ID로 변경한다.
MATCH (:RuleSet {id:'ruleset:oops:all:0.4-draft'})-[:HAS_COMMON]->(c:Criterion {criterion_id:'G02-C01'})
MATCH (c)-[:HAS_REVIEW_GATE]->(g:ReviewGate {version:'independent-review/1.0'})
MATCH (g)-[:HAS_CONTENT]->(b:ContentBody)
RETURN c.criterion_id AS criterion_id,g.id AS gate_id,g.version AS rule_version,
       b.minimum_information AS minimum_information,
       b.exclusion_conditions AS exclusion_conditions,
       b.missing_information_scope AS missing_information_scope;
