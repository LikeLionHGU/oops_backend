import copy
import json
from pathlib import Path
import sys
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from test_bound_review_contract import setup
from bound_review_contract import prepare_attempt,validate_attempt
from scope_source_order import INSTRUCTION
from fixed_review_suite import digest


def test_only_source_order_and_instruction_are_added_without_renumbering():
    p,_,s,b,_=setup();before=copy.deepcopy((b,s))
    original=prepare_attempt(b,s,'test',judgment_variant='COMPACT_REVIEW_CLOSED_SCOPE')
    ordered=prepare_attempt(b,s,'test',judgment_variant='CHRONOLOGY_COMPACT_REVIEW_CLOSED_SCOPE')
    assert (b,s)==before;validate_attempt(ordered)
    body=copy.deepcopy(ordered['requestBody']);incoming=json.loads(body['messages'][1]['content'])
    indices=incoming['selectedScope'].pop('sourceOrderSelectionIndexes')
    positions={r['id']:i for i,r in enumerate(p['raw'])}
    assert indices==sorted(range(len(s['evidence'])),key=lambda i:positions[s['evidence'][i]['segmentId']])
    assert incoming==json.loads(original['requestBody']['messages'][1]['content'])
    assert body['messages'][0]['content'].replace(INSTRUCTION,'')==original['requestBody']['messages'][0]['content']
    ordered['sourceOrderPolicyHash']='changed';ordered['attemptHash']=digest({k:v for k,v in ordered.items() if k!='attemptHash'})
    with pytest.raises(ValueError):validate_attempt(ordered)
