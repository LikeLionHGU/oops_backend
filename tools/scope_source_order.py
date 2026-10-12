"""Expose saved source-row order for selected quotes, not unselected speech."""
import copy
import json
from fixed_review_suite import require
from issue_scoped_review import validate_scope

INSTRUCTION="""# 선택 원문의 저장 순서
selectedScope.sourceOrderSelectionIndexes는 선택된 조각을 저장 raw 행의 순서대로 읽는 index 목록이다.
배열의 표시 순서나 PRIMARY/TARGET 역할 순서를 대화 순서로 해석하지 않는다. 같은 행의 조각은 같은 순서 위치다.
앞뒤 관계를 설명할 때 이 목록을 따른다. 저장 전사 순서이지 화자·억양·장면·같은 쟁점의 승인이 아니다.
출력 selectionIndex는 원래 index를 사용한다. 원문·index·역할을 재작성하거나 새 발언을 보충하지 않는다.
PRIMARY는 이 요청이 평가하는 원문이다. 정상 표현이라고 OTHER_ISSUE로 바꾸거나 usedSelectionIndexes에서 비우지 않는다.
SAME_ISSUE는 현재 질문에 대한 관련성이지 위험하다는 판정이 아니다. 정상 해석의 PRIMARY도 SAME_ISSUE로 판단 근거에 사용한다.
"""


def ordered_request(body,scope,payload):
    validate_scope(scope,payload);out=copy.deepcopy(body)
    incoming=json.loads(out['messages'][1]['content'])
    require(incoming['selectedScope']['evidence']==scope['evidence'], 'identical selected quotes required')
    require('sourceOrderSelectionIndexes' not in incoming['selectedScope'], 'order already exposed')
    positions={r['id']:i for i,r in enumerate(payload['raw'])}
    incoming['selectedScope']['sourceOrderSelectionIndexes']=sorted(range(len(scope['evidence'])),
        key=lambda i:positions[scope['evidence'][i]['segmentId']])
    out['messages'][1]['content']=json.dumps(incoming,ensure_ascii=False)
    marker='# 범용 맥락 참고 기준';system=out['messages'][0]['content']
    require(system.count(marker)==1 and INSTRUCTION not in system,'single unchanged reference boundary required')
    before,reference=system.split(marker,1)
    out['messages'][0]['content']=before+INSTRUCTION+marker+reference
    return out
