// 기준에 강제 편입하지 않은 7개 별도 사례. 대상에서 제외한 것이 아닙니다.
MATCH (c:Case {version:'0.4-draft',classification:'standalone'})-[:HAS_CONTENT]->(b:ContentBody)
RETURN c.case_id AS id,c.title AS title,b.source_record_json AS internal_record
ORDER BY id;
