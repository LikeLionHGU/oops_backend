"""Isolated prompt variants. No transport, labels or response repair."""
import json

from fixed_review_suite import require
from span_catalog_review import selection_request as catalog_select
from bound_review_contract import judgment_request as bound_judge

TARGET_SELECTION = """
# 대상 근거 선택 보완 v1
TARGET_TREATMENT 후보에서는 현재 평가받는 대상의 실제 지칭어를 raw에서 확인한다.
사람·지역만 대상이 아니다. 음식·메뉴·상품·서비스·업체·제공물도 평가 대상일 수 있다.
실제 지칭어가 있다면 targetSpans에서 그 조각 하나를 TARGET으로 선택한다.
PRIMARY나 CONTEXT로 전체 행을 골랐더라도 그 안의 대상 지칭어 선택을 대신한 것은 아니다.
지칭어를 못 찾으면 TARGET 없이 반환한다. 후보 가설에서 대상을 만들거나 다른 쟁점의 대상을 빌리지 않는다.
TARGET 선택은 지칭 확인이며 논란 판정이 아니다. 정상 평가에도 지칭어가 있을 수 있다.
EXPRESSION_CONTENT 후보에는 억지로 TARGET을 만들지 않는다. 기존 출력 계약은 그대로다.
"""
TARGET_SCOPE = """
# 평가 대상 범위 대조 v1
원문에서 실제로 평가하는 대상을 먼저 구분한다. 메뉴·상품·서비스·업체 평가를 지역 주민 전체 공격으로 확대하지 않는다.
반대로 지역 주민 전체 공격이 없다는 이유만으로 제공물이나 업체를 낮추는 구체적 표현까지 PASS로 만들지 않는다.
강한 취향·맛·가격 불만 또는 메뉴 선택 안내만으로 경고하지 않는다. 무가치·우스운 것으로 취급하는 연결이 실제로 있는지 대조한다.
같은 창의 다른 쟁점이 현재 평가를 대신 증명할 수 없다. 정상 리뷰와 구별할 근거가 없으면 PASS도 가능하다.
"""
RELATION_CHECKLIST = """
# 선택별 관계 출력 점검 v1
expectedSelectionIndexes의 모든 index를 relevance에 정확히 한 번 반환한다. PRIMARY만 출력하고 나머지를 생략하지 않는다.
사용하지 않는 선택도 OTHER_ISSUE 또는 UNSURE 등 실제 판단한 관계와 짧은 이유를 적는다.
사용 여부와 쟁점 관계는 별개다. 무조건 SAME_ISSUE로 채우지 않는다. usedSelectionIndexes에는 실제 사용한 SAME_ISSUE만 넣는다.
새 인용·역할·대상·판정을 자동 보충하지 않는다. 기존 출력 계약은 그대로다.
"""


def selection_request(base):
    body = catalog_select(base)
    body['messages'][0]['content'] += TARGET_SELECTION
    return body


def selection_request_v2(base):
    """Unexecuted follow-up: v1's unexpected output field remains a failure."""
    body = selection_request(base)
    body['messages'][0]['content'] += """
# 입력 목록과 출력 필드 구분 v2
targetSpans는 서버가 준 입력 후보 목록의 이름이다. 출력의 최상위 필드가 아니다.
선택한 TARGET은 PRIMARY/CONTEXT와 같은 selections 배열 안에 넣는다.
출력 최상위 키는 candidateId, anchorId, issueSummary, selections 네 개만 허용한다.
TARGET의 객체 키도 segmentId, role, spanId 세 개만 허용한다. 별도 targetSpans/targets/target 필드를 만들지 않는다.
TARGET을 못 찾으면 selections에 TARGET 객체를 넣지 않는다. 원문 밖 대상이나 교정 인용을 생성하지 않는다.
"""
    return body


def judgment_request(base, scope, variant):
    require(variant in {'TARGET_SCOPE', 'RELATION_CHECKLIST'}, 'one isolated judgment variant required')
    body = bound_judge(base, scope)
    marker = '# 범용 맥락 참고 기준'
    before, reference = body['messages'][0]['content'].split(marker, 1)
    extra = TARGET_SCOPE if variant == 'TARGET_SCOPE' else RELATION_CHECKLIST
    body['messages'][0]['content'] = before + extra + '\n' + marker + reference
    if variant == 'RELATION_CHECKLIST':
        payload = json.loads(body['messages'][1]['content'])
        payload['expectedSelectionIndexes'] = list(range(len(scope['evidence'])))
        body['messages'][1]['content'] = json.dumps(payload, ensure_ascii=False)
    return body
