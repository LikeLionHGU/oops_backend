"""Add independent review rules without updating any existing criterion/case text."""
from pathlib import Path
import argparse
import json
import os

FIELDS=('minimum_information','exclusion_conditions','missing_information_scope')
VERSION='independent-review/1.0'

def load_rules(path=None):
    data=json.loads(Path(path or Path(__file__).with_name('review_gate_rules.json')).read_text())
    validate(data)
    return data

def validate(data):
    expected={f'G{i:02}-C01' for i in range(1,22)}
    if data['version']!=VERSION or data['ruleset_id']!='ruleset:oops:all:0.4-draft':
        raise ValueError('Unexpected rule version or ruleset')
    rules=data['rules'];nodes=data['nodes'];rels=data['relationships']
    if len(rules)!=21 or {r['criterion_id'] for r in rules}!=expected:
        raise ValueError('Exactly G01–G21 are required')
    if len(nodes)!=42 or len({n['id'] for n in nodes})!=42 or len(rels)!=42 or len({r['id'] for r in rels})!=42:
        raise ValueError('Expected 42 unique nodes and 42 unique relationships')
    ns={n['id']:n for n in nodes}
    for r in rules:
        if any(not isinstance(r[k],str) or not r[k].strip() for k in FIELDS):raise ValueError('Empty rule field')
        if r['criterion_node_id']!=f"criterion:{r['criterion_id']}@ALL-v0.4-draft":raise ValueError('Unexpected owner')
        g=ns[r['gate_id']];b=ns[r['body_id']]
        if g['label']!='ReviewGate' or b['label']!='ContentBody':raise ValueError('Unexpected node label')
        if g['properties']['criterion_id']!=r['criterion_id'] or g['properties']['version']!=VERSION:raise ValueError('Gate metadata mismatch')
        if b['properties']['version']!=VERSION or any(b['properties'][k]!=r[k] for k in FIELDS):raise ValueError('Body text mismatch')
        endpoints={(x['source'],x['target'],x['type']) for x in rels}
        if (r['criterion_node_id'],r['gate_id'],'HAS_REVIEW_GATE') not in endpoints or (r['gate_id'],r['body_id'],'HAS_CONTENT') not in endpoints:raise ValueError('Missing gate connection')
    if any('id' in n['properties'] for n in nodes):raise ValueError('Property ID overwrite')
    return {'rules':21,'new_nodes':42,'new_relationships':42}

def read_owners(tx,data):
    records=list(tx.run('''MATCH (:RuleSet:OoPsEntity {id:$ruleset})-[:HAS_COMMON]->(c:Criterion:OoPsEntity)
WHERE c.id IN $ids
OPTIONAL MATCH (c)-[:HAS_CONTENT]->(b:ContentBody:OoPsEntity)
RETURN c.id AS id, c.criterion_id AS criterion_id, c.role AS role,
properties(c) AS criterion, b.id AS body_id, properties(b) AS body''',ruleset=data['ruleset_id'],ids=[r['criterion_node_id'] for r in data['rules']]))
    byid={r['id']:r for r in records}
    if len(records)!=21 or len(byid)!=21:raise ValueError('공통 기준 21개와 각각의 단일 본문을 확인할 수 없습니다. 기존 자료를 먼저 확인하세요.')
    for r in data['rules']:
        v=byid[r['criterion_node_id']]
        if v['criterion_id']!=r['criterion_id'] or v['role']!='common' or not v['body_id'] or not v['body']:
            raise ValueError('공통 기준의 연결·역할·본문이 올바르지 않습니다.')
    return sorted([dict(r) for r in records],key=lambda r:r['id'])

def read_managed(tx,data):
    ns=[dict(r) for r in tx.run('MATCH (n:OoPsEntity) WHERE n.id IN $ids RETURN n.id AS id, labels(n) AS labels, properties(n) AS props',ids=[n['id'] for n in data['nodes']])]
    # Inspect all connections touching managed nodes, including unexpected/manual ones.
    es=[dict(r) for r in tx.run('''MATCH (a)-[r]->(b)
WHERE a.id IN $ids OR b.id IN $ids OR r.id IN $rel_ids
RETURN a.id AS source, b.id AS target, type(r) AS type, properties(r) AS props''',ids=[n['id'] for n in data['nodes']],rel_ids=[r['id'] for r in data['relationships']])]
    return ns,es

def assert_exact(data,nodes,rels):
    if len(nodes)!=42 or len(rels)!=42:raise ValueError('독립 점검 자료가 일부만 있거나 중복 연결이 있습니다. 자동 덮어쓰기 없이 중단합니다.')
    actual={n['id']:n for n in nodes}
    if len(actual)!=42:raise ValueError('Duplicate rule node ID')
    for n in data['nodes']:
        a=actual.get(n['id'])
        if not a or set(a['labels'])!={'OoPsEntity',n['label']} or a['props']!={'id':n['id'],**n['properties']}:
            raise ValueError('기존 독립 점검 내용이 배포 파일과 다릅니다. 수정 내용을 덮어쓰지 않습니다.')
    def normalized(r):return json.dumps(r,sort_keys=True,ensure_ascii=False)
    expected=[{'source':r['source'],'target':r['target'],'type':r['type'],'props':{'id':r['id'],**r['properties']}} for r in data['relationships']]
    if sorted(map(normalized,rels))!=sorted(map(normalized,expected)):raise ValueError('독립 점검 관계가 배포 파일과 다릅니다.')

def apply_rules(tx,data,write=True):
    validate(data)
    before=read_owners(tx,data)
    nodes,rels=read_managed(tx,data)
    if nodes or rels:
        assert_exact(data,nodes,rels)
        status='already_applied'
    elif not write:
        raise ValueError('독립 점검 규칙이 아직 적용되지 않았습니다. start.sh 또는 --apply로 적용하세요.')
    else:
        for label in ('ReviewGate','ContentBody'):
            tx.run(f'''UNWIND $rows AS row
MERGE (n:OoPsEntity {{id:row.id}})
ON CREATE SET n:{label}, n += row.properties''',rows=[n for n in data['nodes'] if n['label']==label]).consume()
        for kind in ('HAS_REVIEW_GATE','HAS_CONTENT'):
            tx.run(f'''UNWIND $rows AS row
MATCH (a:OoPsEntity {{id:row.source}}), (b:OoPsEntity {{id:row.target}})
MERGE (a)-[r:{kind} {{id:row.id}}]->(b)
ON CREATE SET r += row.properties''',rows=[r for r in data['relationships'] if r['type']==kind]).consume()
        assert_exact(data,*read_managed(tx,data))
        status='applied'
    if before!=read_owners(tx,data):raise ValueError('기존 공통 기준·본문 변경 감지: 트랜잭션을 취소합니다.')
    return {'review_gate_status':status,'review_gate_version':VERSION,'review_gate_bodies_read':21,'review_gate_fields_verified':63,'original_common_text_preserved':True,'analysis_engine_updated':False}

def migrate(driver,database,data,write=True):
    with driver.session(database=database) as session:
        return (session.execute_write if write else session.execute_read)(apply_rules,data,write)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    mode=p.add_mutually_exclusive_group();mode.add_argument('--apply',action='store_true');mode.add_argument('--verify',action='store_true')
    p.add_argument('--data',type=Path)
    args=p.parse_args();data=load_rules(args.data)
    if not(args.apply or args.verify):
        print(json.dumps({'mode':'offline_validation','database_written':False,**validate(data)},ensure_ascii=False,indent=2));return
    from neo4j import GraphDatabase
    required=('NEO4J_URI','NEO4J_USER','NEO4J_PASSWORD')
    if any(not os.environ.get(k) for k in required):raise SystemExit('Neo4j 연결 환경변수가 없습니다. Docker 실행 스크립트를 사용하세요.')
    with GraphDatabase.driver(os.environ['NEO4J_URI'],auth=(os.environ['NEO4J_USER'],os.environ['NEO4J_PASSWORD'])) as driver:
        driver.verify_connectivity()
        report=migrate(driver,os.environ.get('NEO4J_DATABASE','neo4j'),data,write=args.apply)
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
