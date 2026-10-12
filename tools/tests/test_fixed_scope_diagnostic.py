import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from bound_review_contract import prepare_attempt, receive
from fixed_review_suite import digest
from fixed_scope_diagnostic import prepare, validate, score, SPEC, SOURCE_HASHES, diagnostic_audit_export
from compare_review_prompts import digest as audit_digest
import urllib.error
from run_issue_scoped_comparison import execute, BOUNDARY_FUNDING
from test_bound_review_contract import setup, response
from test_bound_comparison_runner import mock, fake_audit
from test_run_issue_scoped_comparison import source


def seed_fixture():
    _,_,scope,base,out=setup()
    records=[]
    for i,(name,case,repeat) in enumerate(SPEC,1):
        ticket=prepare_attempt(base,scope,f'{SOURCE_HASHES["frozen"]}-{i:03d}',judgment_variant='RELATION_CHECKLIST')
        receipt=receive(ticket,ticket['requestBody'],response(out))
        records.append(dict(name=name,sourceCase=case,sourceRepeat=repeat,sourceIndex=i,
                            localAttempt=ticket,receipt=receipt))
    return dict(schemaVersion='fixed-scope-source-seed-1',hashes=SOURCE_HASHES,funding=BOUNDARY_FUNDING,
        config=source()['config'],previousReservationUsd='0.4039344',remainingBudgetUsd='0.0960656',records=records)


class FixedScopeTests(unittest.TestCase):
    def test_only_judgment_is_repeated_with_identical_observed_body(self):
        seed=seed_fixture(); before=copy.deepcopy(seed)
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)):
            f=prepare(seed);validate(f)
        self.assertEqual(seed,before);self.assertEqual(f['maxApiCalls'],12)
        for j in f['jobs']:
            record=next(r for r in seed['records'] if r['name']==j['case'])
            self.assertEqual(j['stages'][0]['body'],record['localAttempt']['requestBody'])
            self.assertEqual(j['fixedSelectedScope'],record['localAttempt']['selectedScope'])
            self.assertEqual([s['stage'] for s in j['stages']],['JUDGE'])
        self.assertNotIn('developmentExpectation',json.dumps([j['stages'][0]['body'] for j in f['jobs']]))

    def test_seed_or_funding_changes_and_frozen_edits_are_blocked(self):
        seed=seed_fixture()
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)):
            f=prepare(seed);bad=copy.deepcopy(f);bad['jobs'][0]['fixedSelectedScope']['evidence'][0]['quote']='changed'
            with self.assertRaises(ValueError):validate(bad)
            bad=copy.deepcopy(seed);bad['remainingBudgetUsd']='0.50'
            with self.assertRaises(ValueError):prepare(bad)
        for field,value in [('funding',dict(BOUNDARY_FUNDING,userAuthorized=False)),('remainingBudgetUsd','0.50')]:
            bad=copy.deepcopy(seed);bad[field]=value
            with patch('fixed_scope_diagnostic.SEED_HASH',digest(bad)),self.assertRaises(ValueError):prepare(bad)

    def test_mock_receipts_do_not_become_detection_success(self):
        seed=seed_fixture()
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)),tempfile.TemporaryDirectory() as tmp:
            f=prepare(seed);r=execute(f,Path(tmp)/'datasets'/'run',None,mock,simulate=True)
            s=score(f,r,fake_audit(r))
            self.assertEqual(r['simulatedCalls'],12);self.assertEqual(r['apiCalls'],0)
            self.assertIsNone(s['diagnosticExpectedDecisionsMet']);self.assertIsNone(s['developmentRegressionGateMet'])
            self.assertFalse(s['runtimeUseAuthorized'])
            for unit in r['calls']:self.assertEqual(len(unit['stages']),1)
            bad=copy.deepcopy(r);bad['calls'][0]['stages'][0]['localAttempt']=copy.deepcopy(r['calls'][1]['stages'][0]['localAttempt'])
            with self.assertRaises(ValueError):score(f,bad,fake_audit(bad))

    def test_invalid_judgment_is_saved_and_never_repaired(self):
        def invalid(key,body,timeout=90):
            r=mock(key,body,timeout);out=json.loads(r['choices'][0]['message']['content'])
            out['relevance']=[];r['choices'][0]['message']['content']=json.dumps(out);return r
        seed=seed_fixture()
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)),tempfile.TemporaryDirectory() as tmp:
            f=prepare(seed);r=execute(f,Path(tmp)/'datasets'/'run',None,invalid,simulate=True)
            s=score(f,r,fake_audit(r))
            self.assertTrue(all(u['status']=='JUDGMENT_REJECTED' and 'output' not in u for u in r['calls']))
            self.assertEqual(sum(x['accepted'] for x in s['summary']),0)

    def test_missing_usage_stops_without_retry_and_partial_score_is_blocked(self):
        def unknown(key,body,timeout=90):
            r=mock(key,body,timeout);r.pop('usage');return r
        seed=seed_fixture()
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)),tempfile.TemporaryDirectory() as tmp:
            f=prepare(seed);r=execute(f,Path(tmp)/'datasets'/'run',None,unknown,simulate=True)
            self.assertEqual(r['status'],'MOCK_PARTIAL');self.assertGreater(r['unknownCostAttempts'],0)
            with self.assertRaises(ValueError):score(f,r,fake_audit(r))
            audit=fake_audit(r);audit['sourceReportSha256']=audit_digest(diagnostic_audit_export(r,partial=True))
            scored=score(f,r,audit,allow_partial=True)
            self.assertFalse(scored['diagnosticComplete']);self.assertIsNone(scored['diagnosticExpectedDecisionsMet'])

    def test_http_status_is_safe_metadata_without_url_body_or_retry(self):
        def unavailable(*args,**kwargs):
            raise urllib.error.HTTPError('https://example.test/private-key-in-url',429,
                'private-provider-message',None,None)
        seed=seed_fixture()
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)),tempfile.TemporaryDirectory() as tmp:
            f=prepare(seed);r=execute(f,Path(tmp)/'datasets'/'run',None,unavailable,simulate=True)
            self.assertEqual(r['status'],'MOCK_PARTIAL')
            for u in r['calls']:self.assertEqual(u['stages'][0]['httpStatus'],429)
            self.assertNotIn('private-key',json.dumps(r));self.assertNotIn('private-provider-message',json.dumps(r))
