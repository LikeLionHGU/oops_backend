// 독립 점검은 추가 기준을 읽기 전에 실행한다. 같은 요청에서 버전을 고정한다.
MATCH (:RuleSet {id:'ruleset:oops:all:0.4-draft'})-[h:HAS_COMMON]->(c:Criterion)
MATCH (c)-[:HAS_REVIEW_GATE]->(g:ReviewGate {version:'independent-review/1.0'})
MATCH (g)-[:HAS_CONTENT]->(b:ContentBody)
RETURN c.criterion_id AS criterion_id,c.title AS title,g.id AS gate_id,g.version AS rule_version,
       b.minimum_information AS minimum_information,
       b.exclusion_conditions AS exclusion_conditions,
       b.missing_information_scope AS missing_information_scope
ORDER BY h.order;
