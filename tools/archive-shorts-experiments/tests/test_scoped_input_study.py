import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fixed_review_suite import digest
from fixed_scope_diagnostic import prepare as prepare_source
from scoped_input_study import prepare,validate,score
from run_issue_scoped_comparison import execute
from test_fixed_scope_diagnostic import seed_fixture
from test_bound_comparison_runner import fake_audit


def mock_scoped(key,body,timeout=90):
    p=json.loads(body['messages'][1]['content'])
    candidate=p.get('candidate') or p['original']['candidates'][0]
    out=dict(candidateId=candidate['candidateId'],relevance=[dict(selectionIndex=i,relation='SAME_ISSUE',reason='mock')
        for i in p['expectedSelectionIndexes']],usedSelectionIndexes=[i for i,e in enumerate(p['selectedScope']['evidence'])
            if e['role']!='TARGET'],assessment=dict(decision='UNCERTAIN',reason='mock missing context',
        alternativeInterpretation=None,category=None,target=None,targetType=None,targetReason=None,
        score=None,missingInformation=['synthetic fixture only']))
    return dict(model='gpt-6-luna',usage=dict(prompt_tokens=100,completion_tokens=100),
        choices=[dict(finish_reason='stop',message=dict(content=json.dumps(out)))])


class ScopedStudyTests(unittest.TestCase):
    def test_live_is_blocked_before_any_callback_or_directory_creation(self):
        seed=seed_fixture()
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)),tempfile.TemporaryDirectory() as tmp:
            f=prepare(prepare_source(seed));validate(f)
            self.assertFalse(f['executionEnabled']);self.assertEqual(f['reservedCostUsd'],'0')
            output=Path(tmp)/'datasets'/'blocked'
            with self.assertRaises(ValueError):execute(f,output,'unused',lambda *a,**kw:self.fail('paid callback'))
            self.assertFalse(output.exists())
            bad=copy.deepcopy(f);bad['executionEnabled']=True
            with self.assertRaises(ValueError):validate(bad)

    def test_mock_full_receipts_audit_and_scoring_leave_semantics_unapproved(self):
        seed=seed_fixture()
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)),tempfile.TemporaryDirectory() as tmp:
            original=prepare_source(seed);f=prepare(original)
            self.assertEqual(f['sourceFrozen'],original);self.assertEqual(f['plannedUnits'],24)
            r=execute(f,Path(tmp)/'datasets'/'run',None,mock_scoped,simulate=True)
            s=score(f,r,fake_audit(r));self.assertTrue(s['mockContractGateMet'])
            self.assertEqual(s['apiCalls'],0);self.assertIsNone(s['developmentRegressionGateMet'])
            self.assertIsNone(s['semanticQualityGateMet']);self.assertFalse(s['runtimeUseAuthorized'])
            for unit in r['calls']:
                ticket=unit['stages'][0]['localAttempt']
                self.assertEqual(ticket['judgmentVariant'],unit['arm'])
                self.assertEqual(ticket['schemaVersion'],'server-bound-review-draft-4')
            changed=copy.deepcopy(r);changed['calls'][0]['stages'][0]['localAttempt']=copy.deepcopy(r['calls'][1]['stages'][0]['localAttempt'])
            with self.assertRaises(ValueError):score(f,changed,fake_audit(changed))

    def test_output_errors_are_not_filled_from_hidden_original(self):
        def bad(key,body,timeout=90):
            r=mock_scoped(key,body,timeout);out=json.loads(r['choices'][0]['message']['content'])
            out['relevance']=[];r['choices'][0]['message']['content']=json.dumps(out);return r
        seed=seed_fixture()
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)),tempfile.TemporaryDirectory() as tmp:
            f=prepare(prepare_source(seed));r=execute(f,Path(tmp)/'datasets'/'run',None,bad,simulate=True)
            self.assertTrue(all(u['status']=='JUDGMENT_REJECTED' and 'output' not in u for u in r['calls']))
            self.assertFalse(score(f,r,fake_audit(r))['mockContractGateMet'])
