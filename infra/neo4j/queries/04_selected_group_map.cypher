// G02-C01을 원하는 공통 기준 ID로 바꾸면 해당 묶음만 표시합니다.
MATCH (:RuleSet {id:'ruleset:oops:all:0.4-draft'})-[:HAS_COMMON]->(c:Criterion {criterion_id:'G02-C01'})
MATCH p=(c)-[:HAS_ADDITIONAL]->(:Criterion)
RETURN p;
