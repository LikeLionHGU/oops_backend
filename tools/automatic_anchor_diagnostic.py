"""Mask the human PRIMARY within fixed selected quotes; not full discovery."""
import copy
import json
from fixed_review_suite import require
from issue_scoped_review import validate_scope,useful
from reanchor_incident_scope import reanchor

POLICY="""선택된 원문에서 게시 전 재검토 쟁점을 가장 직접적으로 표현하는 대표 발언을 고른다.
단순 배경·부재·욕구·대체·불만 대신 구체적인 일축·가치판단·모욕·신체 훼손 비유가 있는지 본다.
정상 리뷰·상황 설명·선호만 있으면 null이다. 모든 입력에 문제 발언이 있다고 가정하지 않는다.
원문 저장 순서로 읽는다. 참고 사례는 현재 영상의 새 발언/판정 근거가 아니다.
문맥을 지우거나 화자·관계·억양·의도·장면을 만들지 않는다. 인용을 교정하거나 생성하지 않는다.
JSON: {"selectedSegmentId":"제공한 quotes의 ID 또는 null","reason":"현재 원문에 근거한 선정/보류 이유, 300자 이내"}
"""


def request(base,scope):
    payload=json.loads(base['messages'][1]['content']);validate_scope(scope,payload)
    positions={r['id']:i for i,r in enumerate(payload['raw'])};quotes={}
    for e in scope['evidence']:
        if e['role']=='TARGET':continue
        sid=e['segmentId']
        require(sid not in quotes or quotes[sid]==e['quote'],'ambiguous selected quote')
        quotes[sid]=e['quote']
    rows=[dict(segmentId=sid,text=text) for sid,text in sorted(quotes.items(),key=lambda kv:positions[kv[0]])]
    marker='# 범용 맥락 참고 기준';system=base['messages'][0]['content']
    require(system.count(marker)==1,'unchanged reference boundary required')
    out=copy.deepcopy(base);out['messages'][0]['content']=POLICY+'\n'+marker+system.split(marker,1)[1]
    out['messages'][1]['content']=json.dumps(dict(quotes=rows),ensure_ascii=False)
    return out


def accept(output,base,scope):
    require(isinstance(output,dict) and set(output)=={'selectedSegmentId','reason'}
        and useful(output['reason'],300),'anchor selection contract invalid')
    sid=output['selectedSegmentId']
    if sid is None:return dict(status='ABSTAIN',baseBody=copy.deepcopy(base),scope=copy.deepcopy(scope))
    require(isinstance(sid,str) and sid in {e['segmentId'] for e in scope['evidence'] if e['role']!='TARGET'},
        'selected ID outside exposed quotes')
    payload=json.loads(base['messages'][1]['content'])
    if sid==payload['candidates'][0]['anchorId']:
        return dict(status='UNCHANGED',baseBody=copy.deepcopy(base),scope=copy.deepcopy(scope))
    body,new_scope=reanchor(base,scope,sid)
    return dict(status='REANCHORED',baseBody=body,scope=new_scope)
