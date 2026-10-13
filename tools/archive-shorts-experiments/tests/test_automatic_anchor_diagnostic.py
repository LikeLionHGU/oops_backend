import copy
import json
from pathlib import Path
import sys
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from test_bound_review_contract import setup
from automatic_anchor_diagnostic import request,accept


def test_role_and_old_anchor_are_masked_no_new_quote_is_exposed():
    p,_,s,b,_=setup();before=copy.deepcopy((b,s));body=request(b,s)
    q=json.loads(body['messages'][1]['content'])
    assert set(q)=={'quotes'} and all(set(r)=={'segmentId','text'} for r in q['quotes'])
    assert {r['text'] for r in q['quotes']}=={e['quote'] for e in s['evidence'] if e['role']!='TARGET'}
    assert (b,s)==before and body['messages'][0]['content'].split('# 범용 맥락 참고 기준')[1]==b['messages'][0]['content'].split('# 범용 맥락 참고 기준')[1]


def test_abstention_never_warns_and_unknown_id_is_rejected():
    _,_,s,b,_=setup()
    result=accept(dict(selectedSegmentId=None,reason='normal'),b,s)
    assert result['status']=='ABSTAIN' and result['scope']==s
    with pytest.raises(ValueError):accept(dict(selectedSegmentId='invented',reason='invented'),b,s)


def test_existing_context_can_become_primary_without_new_utterance():
    _,_,s,b,_=setup();sid=next(e['segmentId'] for e in s['evidence'] if e['role']=='CONTEXT')
    result=accept(dict(selectedSegmentId=sid,reason='selected quote'),b,s)
    assert result['status']=='REANCHORED'
    assert [(e['segmentId'],e['quote']) for e in result['scope']['evidence']]==[(e['segmentId'],e['quote']) for e in s['evidence']]
