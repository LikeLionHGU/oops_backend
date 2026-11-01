// 내부 관리자용. 역할·범위·관찰은 별도 본문의 derivation_json에 보존합니다.
MATCH (:RuleSet {id:'ruleset:oops:all:0.4-draft'})-[:HAS_COMMON]->(c:Criterion {criterion_id:'G02-C01'})
MATCH (c)-[:HAS_CONTENT]->(b:ContentBody)
RETURN c.title AS title,b.derivation_json AS internal_derivation;
