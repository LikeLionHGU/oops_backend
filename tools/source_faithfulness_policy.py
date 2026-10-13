"""Experimental explanation-only boundary; no post-hoc response repair."""
import copy
from fixed_review_suite import require

DELTA="""# 설명의 자료 경계
reason/alternativeInterpretation/targetReason 모두 현재 선택 원문에 근거한다.
정상 해석은 표현의 가능한 의미를 대조하는 것이지, 새 인물 관계나 장면을 가정하는 것이 아니다.
원문에 명시되지 않은 화자 간 관계·누가 누구에게 말했는지·상대의 반응·표정·억양·의도를 붙이지 않는다.
친근한 농담이라는 의미 해석과 실제로 친한 사람끼리 말한 상황은 다르다. 의미만 설명하고 상황을 지어내지 않는다.
참고 사례의 인물/관계를 현재 영상에 옮기지 않는다. 부족한 관계 정보가 판정에 필수일 때만 UNCERTAIN으로 명시한다.
원문에 실제로 등장한 관계/대상 지칭을 지우거나, 관계를 모른다는 이유로 근거 있는 표현 자체를 누락하지 않는다.
"""


def faithful_request(body):
    out=copy.deepcopy(body);system=out['messages'][0]['content'];marker='# 범용 맥락 참고 기준'
    require(system.count(marker)==1 and DELTA not in system,'single unchanged reference boundary required')
    before,reference=system.split(marker,1);out['messages'][0]['content']=before+DELTA+marker+reference
    return out
