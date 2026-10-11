"""Versioned follow-up: select catalog IDs, never ask a model to count characters.

Original offset experiment remains reproducible. Catalog hydration changes only
the output contract, not text, anchors, validators or semantic approval.
"""
import copy
import json
import re

from fixed_review_suite import require
from issue_scoped_review import (candidate_input, hydrate_selection, judgment_request as offset_judgment,
                                 prompts)

SELECTION_CONTRACT = """
# 쟁점 근거 선택 — 원문 ID와 지칭어 ID
original.raw 전체를 읽고 현재 후보 쟁점과 연결되는 원문만 고른다. 최종 판정·유형·점수는 만들지 않는다.
가설과 issueSummary는 사실이 아니다. 정상 해석의 문맥을 빼거나 다른 쟁점으로 anchor를 바꾸지 않는다.
selections는 1~8개, PRIMARY는 정확히 하나이며 anchorId다. PRIMARY/CONTEXT의 spanId는 FULL이다.
TARGET은 실제 지칭어가 필요한 경우 최대 하나다. targetSpans의 해당 segmentId에 있는 spanId만 고른다.
TARGET에 평가 문장 전체를 지칭어처럼 쓰지 않는다. 원문과 문자열이 맞아도 의미상 대상인지 별도로 확인한다.
서버가 원문을 붙이므로 quote·start·end·교정 전사·해석 대상명·decision을 반환하지 않는다.
같은 시간 창의 별도 메뉴 평가 등을 현재 쟁점 근거로 빌리지 않는다. 정상 대조에 필요한 연결은 보존한다.
JSON: {"candidateId":"현재 후보 ID","anchorId":"고정 anchorId","issueSummary":"쟁점 설명 300자 이내",
 "selections":[{"segmentId":"anchorId","role":"PRIMARY","spanId":"FULL"}]}
"""
JUDGMENT_FIX = """
# 대상 및 단계 연결 계약 보완
targetType은 PERSON/GROUP/REGION/RESIDENT_GROUP/BUSINESS/PRODUCT/WORK/OTHER 중 하나다.
PLACE나 한국어 유형명은 허용 값이 아니다. 대상 지칭이 불충분하면 새 대상을 만들지 않는다.
PASS/UNCERTAIN 또는 target=null인 표현 자체 검토에서는 TARGET 선택 index를 사용하지 않는다.
그 선택의 relevance는 별도로 대조할 수 있으나 usedSelectionIndexes에는 PRIMARY와 필요한 CONTEXT만 넣는다.
scopeHash는 selectedScope.scopeHash 값을 정확히 복사한다. 축약하거나 새 hash를 생성하지 않는다.
"""


def catalog(payload):
    c, rows = candidate_input(payload)
    result = {}
    for sid in c['segmentIds']:
        text = rows[sid]['text']
        words = list(re.finditer(r'\S+', text))
        spans = {}
        for i,w in enumerate(words):
            for end in range(i, min(i+2,len(words))):
                start, stop = w.start(), words[end].end()
                if stop-start <= 200:
                    spans[f'w{i}-{end}'] = dict(start=start,end=stop,text=text[start:stop])
        result[sid] = spans
    return result


def hydrate(output, payload):
    c, rows = candidate_input(payload)
    require(isinstance(output,dict) and set(output)=={'candidateId','anchorId','issueSummary','selections'}
            and isinstance(output['selections'],list), 'catalog output fields invalid')
    converted = copy.deepcopy(output)
    spans = catalog(payload)
    for q in converted['selections']:
        require(isinstance(q,dict) and set(q)=={'segmentId','role','spanId'}
                and q['segmentId'] in c['segmentIds'] and q['role'] in {'PRIMARY','TARGET','CONTEXT'},
                'catalog selection fields or allowed row invalid')
        if q['role'] == 'TARGET':
            require(q['spanId'] in spans[q['segmentId']], 'unknown target span ID')
            chosen = spans[q['segmentId']][q['spanId']]
            start,end = chosen['start'],chosen['end']
        else:
            require(q['spanId']=='FULL','PRIMARY and CONTEXT require whole original row')
            start,end = 0,len(rows[q['segmentId']]['text'])
        q.pop('spanId');q.update(start=start,end=end)
    return hydrate_selection(converted,payload)


def selection_request(base):
    body=copy.deepcopy(base)
    payload=json.loads(body['messages'][1]['content'])
    policy=base['messages'][0]['content'].split('# 후보 근거 검증',1)[0]
    # Reuse strict original boundary checking before constructing the versioned prompt.
    prompts(base['messages'][0]['content'])
    sidecar={sid:[dict(spanId=k,text=v['text']) for k,v in spans.items()] for sid,spans in catalog(payload).items()}
    body['messages'][0]['content']=policy+SELECTION_CONTRACT
    body['messages'][1]['content']=json.dumps(dict(original=payload,targetSpans=sidecar),ensure_ascii=False)
    return body


def judgment_request(base,scope):
    body=offset_judgment(base,scope)
    marker='# 범용 맥락 참고 기준'
    before,reference=body['messages'][0]['content'].split(marker,1)
    body['messages'][0]['content']=before+JUDGMENT_FIX+'\n'+marker+reference
    return body
