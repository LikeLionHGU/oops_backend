"""OoPs Neo4j import. Defaults to offline validation; --apply performs writes."""
from pathlib import Path
import argparse,json,os

LABELS={'ReviewGate','ContentBody','RuleSet','Criterion','Case','Source','Utterance','DialogueContext','Target','ContextInformation','Interpretation','JudgmentBasis','Objection'}
TYPES={'HAS_REVIEW_GATE','HAS_CONTENT','HAS_COMMON','HAS_ADDITIONAL','DERIVED_FROM','HAS_ELEMENT','SOURCED_FROM','EVALUATES','REFERS_TO','INTERPRETS','CHALLENGES','USES_BASIS','SUPPORTS','LIMITS_INTERPRETATION','REFUTES'}

def property_ok(value):
    if isinstance(value,(str,bool,int,float)):return True
    return isinstance(value,list) and (not value or (all(type(v) is type(value[0]) for v in value) and all(isinstance(v,(str,bool,int,float)) for v in value)))

def validate(data):
    nodes=data['nodes'];rels=data['relationships'];ids={n['id'] for n in nodes}
    if len(ids)!=len(nodes):raise ValueError('Duplicate node IDs')
    if len({e['id'] for e in rels})!=len(rels):raise ValueError('Duplicate relationship IDs')
    for n in nodes:
        if not isinstance(n['id'],str) or not n['id']:raise ValueError('Missing node ID')
        if n['label'] not in LABELS:raise ValueError('Unknown label')
        if 'id' in n['properties']:raise ValueError('ID must not be overwritten by properties')
        if not all(property_ok(v) for v in n['properties'].values()):raise ValueError('Unsupported property type')
    for e in rels:
        if e['source'] not in ids or e['target'] not in ids:raise ValueError('Missing relationship endpoint')
        if e['type'] not in TYPES:raise ValueError('Unknown relationship type')
        if 'id' in e['properties']:raise ValueError('Relationship ID overwrite')
        if not all(property_ok(v) for v in e['properties'].values()):raise ValueError('Unsupported relationship property type')
    return {'nodes':len(nodes),'relationships':len(rels),'ruleset_id':data['ruleset_id']}

def grouped(items,key):
    return {k:[x for x in items if x[key]==k] for k in sorted({x[key] for x in items})}

def write_graph(tx,data):
    for label,rows in grouped(data['nodes'],'label').items():
        # Labels and relationship types are validated against fixed allowlists.
        query=f'UNWIND $rows AS row MERGE (n:OoPsEntity {{id: row.id}}) SET n:{label} SET n += row.properties'
        tx.run(query,rows=rows).consume()
    for kind,rows in grouped(data['relationships'],'type').items():
        query=f'UNWIND $rows AS row MATCH (a:OoPsEntity {{id: row.source}}) MATCH (b:OoPsEntity {{id: row.target}}) MERGE (a)-[r:{kind} {{id: row.id}}]->(b) SET r += row.properties'
        tx.run(query,rows=rows).consume()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true',help='Write to the configured Neo4j server')
    parser.add_argument('--data',type=Path,default=Path(__file__).with_name('neo4j_graph.json'))
    args=parser.parse_args();data=json.loads(args.data.read_text());summary=validate(data)
    if not args.apply:
        print(json.dumps({'mode':'offline_validation','database_written':False,**summary},ensure_ascii=False,indent=2));return
    required=['NEO4J_URI','NEO4J_USER','NEO4J_PASSWORD']
    if any(not os.environ.get(k) for k in required):raise SystemExit('Set NEO4J_URI, NEO4J_USER and NEO4J_PASSWORD in the server environment.')
    try:from neo4j import GraphDatabase
    except ImportError:raise SystemExit('Install requirements.txt before --apply.')
    database=os.environ.get('NEO4J_DATABASE','neo4j')
    with GraphDatabase.driver(os.environ['NEO4J_URI'],auth=(os.environ['NEO4J_USER'],os.environ['NEO4J_PASSWORD'])) as driver:
        driver.verify_connectivity()
        driver.execute_query('CREATE CONSTRAINT oops_entity_id IF NOT EXISTS FOR (n:OoPsEntity) REQUIRE n.id IS UNIQUE',database_=database)
        with driver.session(database=database) as session:session.execute_write(write_graph,data)
        counts,_,_=driver.execute_query('MATCH (n:OoPsEntity) WHERE n.id IN $ids RETURN count(n) AS count',ids=[n['id'] for n in data['nodes']],database_=database)
        edge_counts,_,_=driver.execute_query('MATCH (:OoPsEntity)-[r]->(:OoPsEntity) WHERE r.id IN $ids RETURN count(r) AS count',ids=[e['id'] for e in data['relationships']],database_=database)
        if counts[0]['count']!=summary['nodes'] or edge_counts[0]['count']!=summary['relationships']:raise RuntimeError('Post-import counts differ from the input. Inspect the database before using the imported draft.')
    print(json.dumps({'mode':'applied','database_written':True,**summary},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
