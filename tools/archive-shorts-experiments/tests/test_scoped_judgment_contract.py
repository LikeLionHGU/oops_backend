import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from bound_review_contract import prepare_attempt, receive, validate_attempt, validate_receipt
from fixed_review_suite import digest
from issue_scoped_review import hydrate_selection
from scoped_judgment_contract import judgment_request
from review_boundary_variants import judgment_request as old_request
from test_bound_review_contract import setup,response


def scoped_fixture():
    p,selection,_,base,out=setup()
    p['raw'].append(dict(id='outside',text='UNSELECTED_SOURCE_SENTINEL'))
    p['candidates'][0]['segmentIds'].append('outside')
    p['candidates'][0]['hypothesisNotEvidence']='HYPOTHESIS_SENTINEL'
    p['candidates'][0]['proposedEvidence'].append(dict(segmentId='outside',quote='PROPOSAL_SENTINEL'))
    selection['issueSummary']='SUMMARY_SENTINEL'
    scope=hydrate_selection(selection,p)
    base['messages'][1]['content']=json.dumps(p,ensure_ascii=False)
    return p,selection,scope,base,out


class ScopedInputTests(unittest.TestCase):
    def test_raw_only_variant_does_not_claim_other_channels_are_closed(self):
        _,_,scope,base,_=scoped_fixture()
        body=judgment_request(base,scope,'RAW_REMOVED');text=json.dumps(body,ensure_ascii=False)
        self.assertNotIn('UNSELECTED_SOURCE_SENTINEL',text)
        for s in ('HYPOTHESIS_SENTINEL','PROPOSAL_SENTINEL','SUMMARY_SENTINEL'):self.assertIn(s,text)
        ticket=prepare_attempt(base,scope,'raw-1',judgment_variant='RAW_REMOVED')
        self.assertFalse(ticket['exposure']['generatedTextChannelsExcluded'])

    def test_closed_view_uses_exact_fragments_and_preserves_server_source_reference_and_settings(self):
        p,_,scope,base,_=scoped_fixture();before=copy.deepcopy((p,scope,base))
        body=judgment_request(base,scope,'CLOSED_SCOPE');incoming=json.loads(body['messages'][1]['content'])
        for s in ('UNSELECTED_SOURCE_SENTINEL','HYPOTHESIS_SENTINEL','PROPOSAL_SENTINEL','SUMMARY_SENTINEL'):
            self.assertNotIn(s,json.dumps(body,ensure_ascii=False))
        self.assertEqual(incoming['selectedScope']['evidence'],scope['evidence'])
        self.assertEqual(incoming['expectedSelectionIndexes'],list(range(len(scope['evidence']))))
        self.assertNotIn('original',incoming)
        self.assertEqual(set(incoming['candidate']),{'candidateId','anchorId','axis'})
        old=old_request(base,scope,'RELATION_CHECKLIST');marker='# 범용 맥락 참고 기준'
        self.assertEqual(body['messages'][0]['content'].split(marker)[1],old['messages'][0]['content'].split(marker)[1])
        for k in ('model','reasoning_effort','max_completion_tokens','store','response_format'):self.assertEqual(body[k],old[k])
        self.assertEqual((p,scope,base),before)
        t=prepare_attempt(base,scope,'closed-1',judgment_variant='CLOSED_SCOPE')
        self.assertEqual(t['schemaVersion'],'server-bound-review-draft-4')
        self.assertEqual(t['originalInput'],p)
        self.assertEqual(t['exposure']['withheldSegmentIds'],
            sorted({r['id'] for r in p['raw']}-{e['segmentId'] for e in scope['evidence']}))
        self.assertIn('outside',t['exposure']['withheldSegmentIds'])
        self.assertTrue(t['exposure']['generatedTextChannelsExcluded'])

    def test_target_fragment_does_not_expose_unselected_tail_on_same_source_row(self):
        p,selection,_,base,_=setup()
        target=next(s for s in selection['selections'] if s['role']=='TARGET')
        row=next(r for r in p['raw'] if r['id']==target['segmentId'])
        row['text']+=' SAME_ROW_HIDDEN_TAIL'
        base['messages'][1]['content']=json.dumps(p,ensure_ascii=False)
        scope=hydrate_selection(selection,p)
        self.assertNotIn('SAME_ROW_HIDDEN_TAIL',json.dumps(judgment_request(base,scope,'CLOSED_SCOPE'),ensure_ascii=False))

    def test_receipts_bind_visible_view_and_full_provenance_without_output_repair(self):
        _,_,scope,base,out=scoped_fixture()
        ticket=prepare_attempt(base,scope,'new-1',judgment_variant='CLOSED_SCOPE')
        r=receive(ticket,ticket['requestBody'],response(out));validate_receipt(r,ticket)
        self.assertEqual(r['exposure'],ticket['exposure']);self.assertFalse(r['responsesModified'])
        self.assertIsNone(r['qualityGateMet']);self.assertFalse(r['runtimeUseAuthorized'])
        with self.assertRaises(ValueError):receive(ticket,old_request(base,scope,'RELATION_CHECKLIST'),response(out))
        bad=copy.deepcopy(ticket);bad['exposure']['withheldSegmentIds']=[]
        bad['attemptHash']=digest({k:v for k,v in bad.items() if k!='attemptHash'})
        with self.assertRaises(ValueError):validate_attempt(bad)

    def test_incomplete_context_can_remain_uncertain_and_never_adds_evidence(self):
        _,_,scope,base,out=scoped_fixture()
        out['assessment'].update(decision='UNCERTAIN',category=None,target=None,targetType=None,targetReason=None,
            score=None,missingInformation=['선택 밖의 정상 설명을 확인할 필요가 있음'])
        out['usedSelectionIndexes']=[i for i,e in enumerate(scope['evidence']) if e['role']!='TARGET']
        ticket=prepare_attempt(base,scope,'uncertain-1',judgment_variant='CLOSED_SCOPE')
        r=receive(ticket,ticket['requestBody'],response(out))
        self.assertEqual(r['modelOutput']['assessment']['decision'],'UNCERTAIN')
        self.assertEqual(r['compatiblePacket']['selectedScope'],scope)
        self.assertFalse(json.loads(ticket['requestBody']['messages'][1]['content'])['scopeCoverage']['selectionCompletenessApproved'])

    def test_invalid_mode_or_source_offset_is_rejected_before_request_creation(self):
        _,_,scope,base,_=scoped_fixture()
        with self.assertRaises(ValueError):judgment_request(base,scope,'AUTO')
        changed=copy.deepcopy(scope);changed['evidence'][0]['quote']='invented'
        with self.assertRaises(ValueError):judgment_request(base,changed,'CLOSED_SCOPE')
