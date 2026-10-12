import json
import copy
from pathlib import Path
import sys
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from reanchor_incident_scope import reanchor
from test_bound_review_contract import setup


def test_only_anchor_and_selected_roles_change_no_new_quotation():
    p,_,scope,base,_=setup();before=copy.deepcopy((base,scope))
    new_id=next(e['segmentId'] for e in scope['evidence'] if e['role']=='CONTEXT')
    b,s=reanchor(base,scope,new_id)
    assert (base,scope)==before
    q=json.loads(b['messages'][1]['content'])
    assert q['raw']==p['raw']
    q['candidates'][0]['anchorId']=p['candidates'][0]['anchorId'];assert q==p
    assert [(e['segmentId'],e['quote']) for e in s['evidence']]==[(e['segmentId'],e['quote']) for e in scope['evidence']]
    assert b['messages'][0]==base['messages'][0]


def test_unselected_or_current_anchor_refused():
    p,_,scope,base,_=setup()
    for sid in ('missing',p['candidates'][0]['anchorId']):
        with pytest.raises(ValueError):reanchor(base,scope,sid)


def test_new_anchor_missing_from_proposal_gets_existing_selected_quote_only():
    p,_,scope,base,_=setup()
    sid=next(e['segmentId'] for e in scope['evidence'] if e['role']=='CONTEXT')
    p['candidates'][0]['proposedEvidence']=[e for e in p['candidates'][0]['proposedEvidence'] if e['segmentId']!=sid]
    from issue_scoped_review import hydrate_selection
    scope=hydrate_selection(scope['selectionOutput'],p)
    base['messages'][1]['content']=json.dumps(p,ensure_ascii=False)
    b,s=reanchor(base,scope,sid);q=json.loads(b['messages'][1]['content'])
    assert q['raw']==p['raw']
    expected=next(e for e in s['evidence'] if e['role']=='PRIMARY')
    assert q['candidates'][0]['proposedEvidence'][-1]==dict(segmentId=sid,quote=expected['quote'])
