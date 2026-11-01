// 내부 관리자용. 근거 연결의 부분·경계 대조 역할을 확인합니다.
MATCH (:RuleSet {id:'ruleset:oops:all:0.4-draft'})-[:HAS_COMMON]->(c:Criterion {criterion_id:'G02-C01'})
MATCH p=(c)-[:DERIVED_FROM]->(:Case)
RETURN p;
