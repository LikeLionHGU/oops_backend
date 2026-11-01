// G02-C01을 원하는 공통 기준 ID(G01-C01~G21-C01)로 바꿔서 실행합니다.
MATCH (:RuleSet {id:'ruleset:oops:all:0.4-draft'})-[:HAS_COMMON]->(c:Criterion {criterion_id:'G02-C01'})
MATCH (c)-[r:HAS_ADDITIONAL]->(a:Criterion)-[:HAS_CONTENT]->(b:ContentBody)
RETURN a.criterion_id AS id,a.title AS title,a.summary AS summary,
       b.definition AS definition,b.instructions AS instructions,
       b.review_questions AS review_questions,b.judgment_effect AS judgment_effect
ORDER BY r.order;
