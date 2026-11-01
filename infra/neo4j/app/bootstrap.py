"""Add the complete ruleset as a new snapshot; never overwrite an existing snapshot."""
from pathlib import Path
import json,os
from neo4j import GraphDatabase
from import_neo4j import validate,write_graph
from verify_graph import verify
from migrate_review_gates import load_rules,migrate

def bootstrap(driver,database,data):
    validate(data)
    patch=load_rules()
    added_nodes={n['id'] for n in patch['nodes']}
    added_rels={r['id'] for r in patch['relationships']}
    seed={**data,'nodes':[n for n in data['nodes'] if n['id'] not in added_nodes],
          'relationships':[r for r in data['relationships'] if r['id'] not in added_rels]}
    seed.pop('independent_review_version',None)
    # Creating the identifying schema first also avoids empty-database label/property warnings.
    driver.execute_query('CREATE CONSTRAINT oops_entity_id IF NOT EXISTS FOR (n:OoPsEntity) REQUIRE n.id IS UNIQUE',database_=database)
    rows,_,_=driver.execute_query('MATCH (n:OoPsEntity {id:$id}) RETURN count(n) AS count',id=data['ruleset_id'],database_=database)
    if rows[0]['count']:
        print('전체 기준 버전이 이미 있습니다. 기존 편집 내용을 덮어쓰지 않습니다.')
    else:
        existing,_,_=driver.execute_query('MATCH (n:OoPsEntity) WHERE n.id IN $ids RETURN count(n) AS count',ids=[n['id'] for n in data['nodes']],database_=database)
        if existing[0]['count']:
            raise RuntimeError('통합 버전의 일부 노드가 이미 있습니다. 자동 덮어쓰기 없이 중단합니다. 데이터 상태를 확인하세요.')
        with driver.session(database=database) as session:session.execute_write(write_graph,seed)
        print('전체 기준 버전 첫 적재를 완료했습니다. 기존 G02 버전은 유지됩니다.')
    verify(driver,database,seed)
    gate_report=migrate(driver,database,patch,write=True)
    return {**verify(driver,database,data),**gate_report}

def main():
    data=json.loads(Path(__file__).with_name('neo4j_graph.json').read_text())
    with GraphDatabase.driver(os.environ['NEO4J_URI'],auth=(os.environ['NEO4J_USER'],os.environ['NEO4J_PASSWORD'])) as driver:
        driver.verify_connectivity();report=bootstrap(driver,os.environ.get('NEO4J_DATABASE','neo4j'),data)
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
