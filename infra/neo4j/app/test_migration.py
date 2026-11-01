"""Offline migration-contract tests. These do not execute Cypher on a DB."""
import copy
import unittest
from migrate_review_gates import load_rules,validate,apply_rules

class Result(list):
    def consume(self):return None

class FakeTransaction:
    def __init__(self,data):
        self.owners=[{'id':r['criterion_node_id'],'criterion_id':r['criterion_id'],'role':'common','criterion':{'id':r['criterion_node_id'],'custom_note':'사용자가 수정한 기존 본문 보존'},'body_id':'body:'+r['criterion_node_id'],'body':{'instructions':['사용자가 수정한 지시문']}} for r in data['rules']]
        self.nodes={};self.rels={}
    def run(self,q,**p):
        if 'properties(c) AS criterion' in q:return Result(copy.deepcopy(self.owners))
        if 'labels(n) AS labels' in q:return Result(copy.deepcopy(list(self.nodes.values())))
        if 'type(r) AS type' in q:return Result(copy.deepcopy(list(self.rels.values())))
        if 'MERGE (n:OoPsEntity' in q:
            for n in p['rows']:
                self.nodes.setdefault(n['id'],{'id':n['id'],'labels':['OoPsEntity',n['label']],'props':{'id':n['id'],**copy.deepcopy(n['properties'])}})
            return Result()
        if 'MERGE (a)-[r:' in q:
            for r in p['rows']:self.rels.setdefault(r['id'],{'source':r['source'],'target':r['target'],'type':r['type'],'props':{'id':r['id'],**copy.deepcopy(r['properties'])}})
            return Result()
        raise AssertionError('Unexpected query')

class MigrationTest(unittest.TestCase):
    def setUp(self):self.data=load_rules();self.tx=FakeTransaction(self.data)
    def test_complete_rules(self):self.assertEqual(validate(self.data)['rules'],21)
    def test_reject_empty_body(self):
        self.data['rules'][0]['minimum_information']=''
        with self.assertRaises(ValueError):validate(self.data)
    def test_preserve_edited_original_and_idempotent(self):
        before=copy.deepcopy(self.tx.owners)
        self.assertEqual(apply_rules(self.tx,self.data)['review_gate_status'],'applied')
        state=copy.deepcopy((self.tx.nodes,self.tx.rels))
        self.assertEqual(apply_rules(self.tx,self.data)['review_gate_status'],'already_applied')
        self.assertEqual(state,(self.tx.nodes,self.tx.rels));self.assertEqual(before,self.tx.owners)
    def test_missing_owner_no_writes(self):
        self.tx.owners.pop()
        with self.assertRaises(ValueError):apply_rules(self.tx,self.data)
        self.assertEqual(self.tx.nodes,{})
    def test_missing_original_body_no_writes(self):
        self.tx.owners[0]['body']=None
        with self.assertRaises(ValueError):apply_rules(self.tx,self.data)
        self.assertEqual(self.tx.nodes,{})
    def test_modified_gate_not_overwritten(self):
        apply_rules(self.tx,self.data)
        bodyid=self.data['rules'][0]['body_id'];self.tx.nodes[bodyid]['props']['minimum_information']='수동 수정'
        before=copy.deepcopy(self.tx.nodes)
        with self.assertRaises(ValueError):apply_rules(self.tx,self.data)
        self.assertEqual(self.tx.nodes,before)
    def test_partial_gate_no_repair(self):
        apply_rules(self.tx,self.data);self.tx.rels.pop(next(iter(self.tx.rels)))
        before=copy.deepcopy((self.tx.nodes,self.tx.rels))
        with self.assertRaises(ValueError):apply_rules(self.tx,self.data)
        self.assertEqual((self.tx.nodes,self.tx.rels),before)
    def test_verify_cannot_create(self):
        with self.assertRaises(ValueError):apply_rules(self.tx,self.data,False)
        self.assertEqual(self.tx.nodes,{})
    def test_wrong_scope_rejected(self):
        self.data['rules'][0]['criterion_node_id']='criterion:G01-C01@other'
        with self.assertRaises(ValueError):validate(self.data)

if __name__=='__main__':unittest.main()
