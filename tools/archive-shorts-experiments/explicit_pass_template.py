"""Remove a nullable JSON example only where the existing PASS contract forbids it."""
import copy
import json
from fixed_review_suite import require


def explicit_request(body):
    out=copy.deepcopy(body);payload=json.loads(out['messages'][1]['content'])
    active=(payload['candidate']['axis']=='TARGET_TREATMENT'
        and bool(payload['grounding']['passContextSelectionIndexes']))
    if active:
        system=out['messages'][0]['content'];old='"alternativeInterpretation":null'
        require(system.count(old)==1,'single versioned JSON example required')
        out['messages'][0]['content']=system.replace(old,
            '"alternativeInterpretation":"PASS/REVIEW_REQUIRED: 정상 해석과 구체적인 문제 연결의 유무를 설명"')
    return out
