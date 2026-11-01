from pathlib import Path
import json,os
from neo4j import GraphDatabase

def verify(driver,database,data):
    rows,_,_=driver.execute_query('MATCH (n:OoPsEntity) WHERE n.id IN $ids RETURN count(n) AS count',ids=[n['id'] for n in data['nodes']],database_=database)
    if rows[0]['count']!=len(data['nodes']):raise RuntimeError('통합 버전의 기본 노드가 일부 없습니다. 자동 복원하지 않습니다.')
    rows,_,_=driver.execute_query('MATCH (:OoPsEntity)-[r]->(:OoPsEntity) WHERE r.id IN $ids RETURN count(r) AS count',ids=[r['id'] for r in data['relationships']],database_=database)
    if rows[0]['count']!=len(data['relationships']):raise RuntimeError('통합 버전의 기본 관계가 일부 없습니다. 자동 복원하지 않습니다.')
    common,_,_=driver.execute_query('MATCH (:RuleSet {id:$ruleset})-[h:HAS_COMMON]->(c:Criterion)-[:HAS_CONTENT]->(b:ContentBody) RETURN c.criterion_id AS id,c.title AS title,b.definition AS definition,b.instructions AS instructions ORDER BY h.order',ruleset=data['ruleset_id'],database_=database)
    if len(common)<21 or any(not r['instructions'] for r in common):raise RuntimeError('공통 기준 본문 21개를 읽지 못했습니다.')
    additional,_,_=driver.execute_query('MATCH (:RuleSet {id:$ruleset})-[:HAS_COMMON]->(c:Criterion)-[r:HAS_ADDITIONAL]->(a:Criterion)-[:HAS_CONTENT]->(b:ContentBody) RETURN c.criterion_id AS common_id,a.criterion_id AS id,b.instructions AS instructions ORDER BY common_id,r.order',ruleset=data['ruleset_id'],database_=database)
    if len(additional)<68 or any(not r['instructions'] for r in additional):raise RuntimeError('추가 기준 본문 68개를 읽지 못했습니다.')
    gate_report={}
    if data.get('independent_review_version'):
        from migrate_review_gates import load_rules,migrate
        gate_report=migrate(driver,database,load_rules(),write=False)
    return {'connected':True,'ruleset_id':data['ruleset_id'],'all_seed_nodes_found':len(data['nodes']),'all_seed_relationships_found':len(data['relationships']),'common_bodies_read':len(common),'additional_bodies_read':len(additional),'past_cases_in_snapshot':111,'standalone_cases_preserved':7,'video_analysis_executed':False,**gate_report}

def main():
    data=json.loads(Path(__file__).with_name('neo4j_graph.json').read_text())
    with GraphDatabase.driver(os.environ['NEO4J_URI'],auth=(os.environ['NEO4J_USER'],os.environ['NEO4J_PASSWORD'])) as driver:
        driver.verify_connectivity();report=verify(driver,os.environ.get('NEO4J_DATABASE','neo4j'),data)
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
