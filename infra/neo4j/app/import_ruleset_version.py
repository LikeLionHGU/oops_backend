"""Create an immutable criteria version; never update an existing node or relation.

Uses the existing importer image/credentials. No backend configuration is changed.
All writes and graph comparisons occur within a single transaction.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path

VERSION = '0.4.1-draft'
RULESET = 'ruleset:oops:all:' + VERSION
GATE_VERSION = 'independent-review/1.0'
OLD_RULESET = 'ruleset:oops:all:0.4-draft'


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def build_graph(source, stops):
    if source['ruleset_id'] != RULESET or source['version'] != VERSION:
        raise ValueError('Unexpected ruleset/version')
    if source['independent_review_version'] != GATE_VERSION or stops['ruleset_id'] != RULESET:
        raise ValueError('Gate/stop-judgment version mismatch')
    groups = source['groups']
    if [g['common']['id'] for g in groups] != [f'G{i:02}-C01' for i in range(1, 22)]:
        raise ValueError('Expected ordered common criteria G01–G21')
    if sum(len(g['additional']) for g in groups) != 68:
        raise ValueError('Expected 68 additional criteria')
    nodes, rels = [], []

    def node(id_, label, **props):
        nodes.append({'id': id_, 'label': label, 'props': {'id': id_, **props}})

    def rel(start, end, kind, **props):
        id_ = 'rel:' + hashlib.sha256(canonical([RULESET, start, end, kind]).encode()).hexdigest()[:24]
        rels.append({'source': start, 'target': end, 'type': kind, 'props': {'id': id_, **props}})

    def criterion(c, role):
        if c['version'] != VERSION:
            raise ValueError('Mixed criterion version')
        id_ = f"criterion:{c['id']}@ALL-v{VERSION}"
        node(id_, 'Criterion', criterion_id=c['id'], group_id=c['id'].split('-')[0],
             title=c['title'], summary=c['summary'], version=VERSION, role=role,
             access='internal', status='draft')
        body_keys = ('definition', 'instructions', 'candidate_signals', 'interpretation_limit') if role == 'common' else (
            'definition', 'instructions', 'review_questions', 'judgment_effect')
        body = {k: c[k] for k in body_keys}
        if any(not v for v in body.values()):
            raise ValueError('Empty criterion body')
        if set(c) != {'id', 'title', 'summary', 'version', *body_keys}:
            raise ValueError('Unmapped criterion JSON fields')
        node('body:' + id_, 'ContentBody', kind='criterion', version=VERSION, access='internal', **body)
        rel(id_, 'body:' + id_, 'HAS_CONTENT', version=VERSION)
        return id_

    node(RULESET, 'RuleSet', version=VERSION, access='internal', status='draft',
         common_count=21, additional_count=68, independent_review_version=GATE_VERSION)
    stop_map = {c['criterion_id']: c for c in stops['criteria']}
    if len(stops['criteria']) != 21 or set(stop_map) != {g['common']['id'] for g in groups}:
        raise ValueError('Invalid stop-judgment owners')
    for order, g in enumerate(groups, 1):
        cid = criterion(g['common'], 'common')
        rel(RULESET, cid, 'HAS_COMMON', order=order)
        for aorder, a in enumerate(g['additional'], 1):
            if not a['id'].startswith(g['common']['id'].split('-')[0] + '-A'):
                raise ValueError('Additional criterion belongs to another group')
            rel(cid, criterion(a, 'additional'), 'HAS_ADDITIONAL', order=aorder)
        gate = g['independent_review']
        gid = f"review-gate:{g['common']['id']}@ALL-v{VERSION}:1.0"
        if gate['id'] != gid or gate['version'] != GATE_VERSION or stop_map[g['common']['id']]['review_gate_id'] != gid:
            raise ValueError('Unexpected gate ID/version')
        fields = ('minimum_information', 'exclusion_conditions', 'missing_information_scope')
        if set(gate) != {'id', 'version', *fields} or any(not gate[k] for k in fields):
            raise ValueError('Invalid review gate fields')
        if stop_map[g['common']['id']]['source_missing_information_scope'] != gate['missing_information_scope']:
            raise ValueError('Stop-judgment source differs from review gate')
        node(gid, 'ReviewGate', criterion_id=g['common']['id'], version=GATE_VERSION,
             ruleset_id=RULESET, access='internal')
        node('body:' + gid, 'ContentBody', kind='independent_review', version=GATE_VERSION,
             access='internal', **{k: gate[k] for k in fields})
        rel(cid, gid, 'HAS_REVIEW_GATE', version=GATE_VERSION)
        rel(gid, 'body:' + gid, 'HAS_CONTENT', version=GATE_VERSION)
    if len({n['id'] for n in nodes}) != len(nodes) or len({r['props']['id'] for r in rels}) != len(rels):
        raise ValueError('Duplicate generated ID')
    return nodes, rels


def snapshot(tx):
    nodes = [dict(r) for r in tx.run('MATCH (n) RETURN elementId(n) AS key, labels(n) AS labels, properties(n) AS props')]
    for n in nodes:
        n['labels'] = sorted(n['labels'])
    rels = [dict(r) for r in tx.run('MATCH (a)-[r]->(b) RETURN elementId(r) AS key, elementId(a) AS source_key, '
                                  'elementId(b) AS target_key, a.id AS source, b.id AS target, type(r) AS type, properties(r) AS props')]
    return {'nodes': sorted(nodes, key=lambda n: n['key']), 'relationships': sorted(rels, key=lambda r: r['key'])}


def assert_exact(actual, nodes, rels):
    ids = {n['id'] for n in nodes}
    expected_nodes = [{'labels': sorted(['OoPsEntity', n['label']]), 'props': n['props']} for n in nodes]
    actual_nodes = [{'labels': n['labels'], 'props': n['props']} for n in actual['nodes'] if n['props'].get('id') in ids]
    expected_rels = rels
    actual_rels = [{'source': r['source'], 'target': r['target'], 'type': r['type'], 'props': r['props']}
                   for r in actual['relationships'] if r['source'] in ids or r['target'] in ids or r['props'].get('id') in {x['props']['id'] for x in rels}]
    if sorted(map(canonical, actual_nodes)) != sorted(map(canonical, expected_nodes)):
        raise ValueError('New-version nodes differ from JSON; refusing overwrite')
    if sorted(map(canonical, actual_rels)) != sorted(map(canonical, expected_rels)):
        raise ValueError('New-version relationships differ; refusing overwrite')


def apply(tx, nodes, rels, write, backup):
    before = snapshot(tx)
    if backup != before:
        raise ValueError('Database changed after backup; retry with a fresh backup')
    if not any(n['props'].get('id') == OLD_RULESET for n in before['nodes']):
        raise ValueError('Existing 0.4-draft RuleSet is missing')
    ids = {n['id'] for n in nodes}
    relids = {r['props']['id'] for r in rels}
    exists = any(n['props'].get('id') in ids for n in before['nodes']) or any(r['props'].get('id') in relids for r in before['relationships'])
    if exists:
        assert_exact(before, nodes, rels)
        status = 'already_applied'
    elif not write:
        raise ValueError('New ruleset has not been applied')
    else:
        for label in ('RuleSet', 'Criterion', 'ContentBody', 'ReviewGate'):
            tx.run(f'UNWIND $rows AS row CREATE (n:OoPsEntity:{label}) SET n = row.props',
                   rows=[n for n in nodes if n['label'] == label]).consume()
        for kind in ('HAS_COMMON', 'HAS_ADDITIONAL', 'HAS_CONTENT', 'HAS_REVIEW_GATE'):
            tx.run(f'UNWIND $rows AS row MATCH (a:OoPsEntity {{id:row.source}}), (b:OoPsEntity {{id:row.target}}) '
                   f'CREATE (a)-[r:{kind}]->(b) SET r = row.props', rows=[r for r in rels if r['type'] == kind]).consume()
        status = 'applied'
    after = snapshot(tx)
    assert_exact(after, nodes, rels)
    if status == 'applied':
        preserved = {'nodes': [n for n in after['nodes'] if n['props'].get('id') not in ids],
                     'relationships': [r for r in after['relationships'] if r['props'].get('id') not in relids]}
    else:
        preserved = after
    if preserved != before:
        raise ValueError('Existing graph changed; transaction will roll back')
    return {'status': status, 'ruleset_id': RULESET, 'version': VERSION, 'review_gate_version': GATE_VERSION,
            'common_criteria': 21, 'additional_criteria': 68, 'review_gates': 21,
            'version_nodes': len(nodes), 'version_relationships': len(rels), 'json_graph_differences': 0,
            'existing_graph_preserved': True, 'existing_graph_sha256': digest(before),
            'backend_configuration_changed': False}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--directory', type=Path, required=True)
    mode = p.add_mutually_exclusive_group()
    mode.add_argument('--apply', action='store_true')
    mode.add_argument('--verify', action='store_true')
    mode.add_argument('--dry-run', action='store_true', help='Exercise the full write and verification, then roll back')
    args = p.parse_args()
    source = json.loads((args.directory / 'ruleset-oops-all-0.4.1-draft.json').read_text())
    stops = json.loads((args.directory / 'stop-judgments-0.4.1-draft.json').read_text())
    nodes, rels = build_graph(source, stops)
    if not (args.apply or args.verify or args.dry_run):
        print(json.dumps({'mode': 'offline_validation', 'nodes': len(nodes), 'relationships': len(rels)}))
        return
    from neo4j import GraphDatabase
    with GraphDatabase.driver(os.environ['NEO4J_URI'], auth=(os.environ['NEO4J_USER'], os.environ['NEO4J_PASSWORD'])) as driver:
        driver.verify_connectivity()
        with driver.session(database=os.environ.get('NEO4J_DATABASE', 'neo4j')) as session:
            backup = session.execute_read(snapshot)
            # A logical full-graph snapshot, readable without APOC; retained before any write.
            backup_name = 'graph-before-' + digest(backup) + '.json'
            out = args.directory / backup_name
            if not out.exists():
                with out.open('x', encoding='utf-8') as f:
                    os.chmod(out, 0o600)
                    f.write(canonical(backup) + '\n')
            if args.dry_run:
                with session.begin_transaction() as tx:
                    report = apply(tx, nodes, rels, True, backup)
                    tx.rollback()
                if session.execute_read(snapshot) != backup:
                    raise ValueError('Dry-run rollback did not preserve graph')
                report.update(status='dry_run_rolled_back', rollback_verified=True)
                print(json.dumps(report, ensure_ascii=False, indent=2))
                return
            report = (session.execute_write if args.apply else session.execute_read)(apply, nodes, rels, args.apply, backup)
            # Fresh read after commit, not just a comparison of the input objects.
            actual = session.execute_read(snapshot)
            assert_exact(actual, nodes, rels)
            report['post_commit_verified'] = True
            report['backup_file'] = backup_name
            report['criteria_json_sha256'] = hashlib.sha256((args.directory / 'ruleset-oops-all-0.4.1-draft.json').read_bytes()).hexdigest()
            report['stop_json_sha256'] = hashlib.sha256((args.directory / 'stop-judgments-0.4.1-draft.json').read_bytes()).hexdigest()
            print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
