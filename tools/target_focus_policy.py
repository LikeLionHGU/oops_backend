"""Experimental single-block delta. No transport, authority or runtime adoption."""
import copy
from fixed_review_suite import require

DELTA = """# 평가 대상 분리 — 실험 지침
선택된 TARGET이 실제로 무엇을 지칭하는지 확인하되, 역할 지정이나 후보 가설을 정답으로 취급하지 않는다.
사람·지역·생활에 대한 취급과 음식·상품 자체의 평가는 구분한다.
음식이 열등하다는 평가가 없다는 이유만으로 다른 대상에 대한 낮춤도 없다고 결론내리지 않는다.
반대로 음식 불만·매장 부재·대체 행동을 사람이나 지역의 낮춤으로 확대하지 않는다.
정상 해석과 검토 해석은 각각 어떤 대상의 어떤 원문을 설명하는지 비교한다.
긍정 평가도 그 평가가 가리키는 대상에 적용한다. 다른 대상의 문제를 자동 면책하거나 부정하지 않는다.
reason에는 판단한 대상과 정상 설명과 구별되는 연결의 유무를 짧게 적는다.
지칭·평가 연결이 불명확하면 필요한 정보를 명시한다. 화자·억양·장면·악의를 만들지 않는다.
"""

def focused_request(body):
    candidate = copy.deepcopy(body)
    system = candidate['messages'][0]['content']
    marker = '# 범용 맥락 참고 기준'
    require(system.count(marker) == 1 and DELTA not in system, 'single unchanged reference boundary required')
    before, reference = system.split(marker, 1)
    candidate['messages'][0]['content'] = before + DELTA + marker + reference
    return candidate
