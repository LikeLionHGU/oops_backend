"""Experimental relation reading; no known-case keywords, authority or transport."""
import copy
from fixed_review_suite import require

DELTA="""# 평가 연결을 설명하는 순서 — 실험 지침
1. 부재·불편·욕구·대체·맛 평가라는 상황 설명과, 대상의 기대·선택·가치를 취급하는 표현을 구분한다.
2. 각각 실제로 무엇을 가리키는지 원문으로 연결한다. 시간 인접이나 역할 지정만으로 같은 대상을 만들지 않는다.
3. 정상 해석은 선택된 평가 표현 전체를 설명해야 한다. 사연 소개·음식 칭찬만 말하고 다른 대상에 대한 일축·무가치화가 있는지 생략하지 않는다.
4. 검토 해석도 현재 원문으로 설명한다. 명시적인 열등하다는 형용사·욕설만이 유일한 근거는 아니다.
   실제 반문·일축·가치판단이 대상의 기대나 선택을 우스운 것/의미 없는 것으로 취급하는 연결인지 확인한다.
5. 부재·대체·반문·부정 평가만으로 그런 취급을 확정하지 않는다. 단순 정보·취향·단일 선택 안내·별도 농담과 구별되는 연결이 없으면 PASS다.
검토에는 정상 해석으로 설명되지 않는 구체적인 대상 취급과 원문 연결을 적는다. 농담·칭찬은 자동 면책도 자동 경고도 아니다.
과거 사례의 해석을 현재 원문의 결론으로 복사하지 않는다. 원문 밖 의도·태도·장면을 만들지 않는다. 필수 연결이 부족하면 UNCERTAIN이다.
"""


def focused_request(body):
    result=copy.deepcopy(body);system=result['messages'][0]['content'];marker='# 범용 맥락 참고 기준'
    require(system.count(marker)==1 and DELTA not in system,'single unchanged reference required')
    a,b=system.split(marker,1);result['messages'][0]['content']=a+DELTA+marker+b
    return result
