// 본문 없이 제목·요약·관계만 조회합니다. 본문 속성을 함께 반환하지 않습니다.
MATCH (:RuleSet {id:'ruleset:oops:all:0.4-draft'})-[h:HAS_COMMON]->(c:Criterion)
OPTIONAL MATCH (c)-[r:HAS_ADDITIONAL]->(a:Criterion)
RETURN c{.criterion_id,.title,.summary,.role,.version,.status} AS common,
       a{.criterion_id,.title,.summary,.role,.version,.status} AS additional,
       h.order AS common_order,r.order AS additional_order
ORDER BY common_order,additional_order;
