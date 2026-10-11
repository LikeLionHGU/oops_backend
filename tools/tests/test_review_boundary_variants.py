import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from bound_review_contract import judgment_request as original_judge
from review_boundary_variants import selection_request, selection_request_v2, judgment_request
from span_catalog_review import selection_request as original_select
from run_issue_scoped_comparison import BOUND_FUNDING, fund_bound, prepare_target_selection, execute, validate
from test_bound_review_contract import setup
from test_bound_comparison_runner import frozen, mock, fake_audit
from score_issue_scoped_comparison import score


def funded_fixture():
    old=frozen(); funding=dict(BOUND_FUNDING,previousRemainingUsd=old['remainingBudgetUsd'])
    with patch('run_issue_scoped_comparison.BOUND_FUNDING',funding):
        a=fund_bound(old,funding)
    return a,funding


class BoundaryVariantTests(unittest.TestCase):
    def test_output_field_disambiguation_is_separate_from_executed_v1(self):
        _,_,_,base,_=setup()
        v1=selection_request(base);v2=selection_request_v2(base)
        self.assertEqual(v2['messages'][1],v1['messages'][1])
        self.assertNotIn('입력 목록과 출력 필드 구분',v1['messages'][0]['content'])
        self.assertIn('네 개만 허용',v2['messages'][0]['content'])
        self.assertIn('같은 selections 배열',v2['messages'][0]['content'])
    def test_selection_changes_only_instruction_not_payload_or_catalog(self):
        _,_,_,base,_=setup(); before=copy.deepcopy(base)
        original=original_select(base); updated=selection_request(base)
        self.assertEqual(base,before)
        self.assertEqual(updated['messages'][1],original['messages'][1])
        self.assertTrue(updated['messages'][0]['content'].startswith(original['messages'][0]['content']))
        self.assertNotIn('롯데리아',updated['messages'][0]['content'])
        self.assertNotIn('REVIEW_REQUIRED',updated['messages'][0]['content'])

    def test_judgment_variants_are_isolated_and_preserve_scope_raw_reference(self):
        _,_,scope,base,_=setup(); original=original_judge(base,scope)
        for variant in ('TARGET_SCOPE','RELATION_CHECKLIST'):
            updated=judgment_request(base,scope,variant)
            old=json.loads(original['messages'][1]['content']); new=json.loads(updated['messages'][1]['content'])
            self.assertEqual(new['original'],old['original']);self.assertEqual(new['selectedScope'],old['selectedScope'])
            marker='# 범용 맥락 참고 기준'
            self.assertEqual(updated['messages'][0]['content'].split(marker)[1],original['messages'][0]['content'].split(marker)[1])
            if variant=='TARGET_SCOPE':self.assertEqual(updated['messages'][1],original['messages'][1])
            else:self.assertEqual(new['expectedSelectionIndexes'],list(range(len(scope['evidence']))))

    def test_target_followup_debits_prior_reservation_and_leaves_judge_fixed(self):
        a,funding=funded_fixture()
        meta=dict(schemaVersion=a['schemaVersion'],artifactHash=a['artifactHash'],additionalFundingHash=a['additionalFundingHash'],
                  status='COMPLETED_PENDING_JAVA_AUDIT',reservedCostUsd=a['reservedCostUsd'],runKind='LIVE')
        with patch('run_issue_scoped_comparison.BOUND_FUNDING',funding):
            new=prepare_target_selection(a,meta);validate(new)
            self.assertEqual(new['plannedUnits'],6);self.assertEqual(new['maxApiCalls'],12)
            self.assertEqual({c['name'] for c in new['evaluatedCases']},{'B','C','N3','N4'})
            for j in new['jobs']:
                old=next(x for x in a['jobs'] if x['case']==j['case'] and x['repeat']==j['repeat'])
                self.assertEqual(j['baseBody'],old['baseBody']);self.assertEqual(j['stages'][1],old['stages'][1])
            with tempfile.TemporaryDirectory() as tmp:
                r=execute(new,Path(tmp)/'datasets'/'mock',None,mock,simulate=True)
                s=score(new,r,fake_audit(r))
                self.assertEqual(r['simulatedCalls'],12);self.assertIsNone(s['developmentRegressionGateMet'])
            bad=dict(meta,status='PARTIAL')
            with self.assertRaises(ValueError):prepare_target_selection(a,bad)
