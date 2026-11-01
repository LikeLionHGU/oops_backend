// 내부 관리용: 통합 버전의 지침 묶음·공통 기준 21개·추가 기준 68개.
MATCH p=(:RuleSet {id:'ruleset:oops:all:0.4-draft'})-[:HAS_COMMON]->(c:Criterion)
OPTIONAL MATCH a=(c)-[:HAS_ADDITIONAL]->(:Criterion)
RETURN p,a;
