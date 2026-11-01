// 서비스가 분석 전에 읽을 21개 공통 기준 본문. 과거 사례·도출 근거는 반환하지 않음.
MATCH (:RuleSet {id:'ruleset:oops:all:0.4-draft'})-[h:HAS_COMMON]->(c:Criterion)-[:HAS_CONTENT]->(b:ContentBody)
RETURN c.criterion_id AS id,c.title AS title,c.summary AS summary,c.version AS version,
       b.definition AS definition,b.instructions AS instructions,
       b.candidate_signals AS candidate_signals,b.interpretation_limit AS interpretation_limit
ORDER BY h.order;
