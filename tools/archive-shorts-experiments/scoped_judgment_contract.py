"""Experimental scope-local input views; no transport or runtime adoption.

RAW_REMOVED isolates the full raw channel, but keeps potentially leaking model
summaries/proposals for comparison. CLOSED_SCOPE removes those channels too.
Neither view proves semantic correctness or eliminates model hallucination.
Full raw, offsets and immutable selection provenance stay server-side.
"""
import copy
import json

from bound_review_contract import grounding
from fixed_review_suite import require
from issue_scoped_review import candidate_input, validate_scope
from review_boundary_variants import judgment_request as relation_request

MODES = {'RAW_REMOVED', 'CLOSED_SCOPE'}
RAW_READ = 'raw 전체와 현재 후보의 실제 쟁점을 다시 대조한다. 앞 단계가 중요한 문맥을 누락했으면 명시한다.'
SUMMARY_READ = 'selectedScope는 앞 단계 모델의 제안이지 승인된 근거가 아니다. issueSummary에도 동의할 의무가 없다.'
PASS_READ = '이 PASS는 proposedEvidence의 anchor 이외 행에 연결된 CONTEXT를 사용해야 한다.'
BOUNDARY = """
# 판단 입력의 원문 경계
현재 영상의 직접 원문 근거는 selectedScope.evidence의 실제 선택 조각뿐이다.
범용 맥락 참고 기준은 사전 지식이며 현재 영상의 추가 발언·장면 근거가 아니다.
선택하지 않은 발언을 기억·가설·사전 예시로 보충하거나 reason/targetReason의 검토 근거로 빌리지 않는다.
scopeCoverage는 서버의 노출 범위 표시이지 정상/논란 판정이나 문맥 충분성 승인이 아니다.
선택은 일부일 수 있다. 해석에 필수인 정상/대안 문맥이 부족하면 UNCERTAIN과 필요한 정보를 명시한다.
추가 문맥을 자동 생성하거나 인용/index/역할을 늘리지 않는다. 재선정은 별도 단계이며 이 요청에서는 하지 않는다.
문맥 제한 자체로 PASS나 REVIEW_REQUIRED를 강제하지 않는다. 선택에 정상 설명이 충분하면 PASS도 가능하다.
"""


def judgment_request(base, scope, mode):
    require(mode in MODES, 'explicit scope input mode required')
    original = json.loads(base['messages'][1]['content'])
    validate_scope(scope, original)
    candidate, rows = candidate_input(original)
    body = relation_request(base, scope, 'RELATION_CHECKLIST')
    model_input = json.loads(body['messages'][1]['content'])
    system = body['messages'][0]['content']
    require(system.count(RAW_READ) == 1, 'versioned raw instruction boundary missing')
    system = system.replace(RAW_READ,
        'selectedScope.evidence와 현재 후보의 실제 쟁점을 대조한다. 필수 문맥이 부족하면 명시한다.')
    if mode == 'RAW_REMOVED':
        # Deliberately retain the other channels for a scoped experiment. This
        # mode is not advertised as a closed source boundary.
        model_input['original'].pop('raw')
    else:
        require(system.count(SUMMARY_READ) == 1 and system.count(PASS_READ) == 1,
                'versioned summary/pass instruction boundary missing')
        system = system.replace(SUMMARY_READ,
            'selectedScope는 앞 단계 모델의 제안이지 승인된 근거가 아니다. 현재 근거 연결을 다시 판단한다.')
        system = system.replace(PASS_READ,
            '연결된 대상 후보의 PASS에는 grounding.passContextSelectionIndexes 중 필요한 CONTEXT를 사용한다.')
        proposed_ids = {e['segmentId'] for e in candidate['proposedEvidence']}
        g = grounding(scope, original)
        g['passContextSelectionIndexes'] = [i for i,e in enumerate(scope['evidence'])
            if e['role']=='CONTEXT' and e['segmentId']!=candidate['anchorId'] and e['segmentId'] in proposed_ids]
        model_input = dict(candidate={k:candidate[k] for k in ('candidateId','anchorId','axis')},
            selectedScope=dict(evidence=copy.deepcopy(scope['evidence'])),grounding=g,
            expectedSelectionIndexes=list(range(len(scope['evidence']))))
    selected_ids = {e['segmentId'] for e in scope['evidence']}
    model_input['scopeCoverage'] = dict(sourceRows=len(rows),selectedSourceRows=len(selected_ids),
        sourceContextLimited=candidate.get('contextLimited') if type(candidate.get('contextLimited')) is bool else None,
        additionalRawExposed=False,selectionCompletenessApproved=False)
    marker='# 범용 맥락 참고 기준'
    before, reference = system.split(marker, 1)
    body['messages'][0]['content']=before+BOUNDARY+'\n'+marker+reference
    body['messages'][1]['content']=json.dumps(model_input,ensure_ascii=False)
    return body


def exposure_manifest(scope, original, body, mode):
    """Server-side inventory, not an assessment of relevance or intent."""
    validate_scope(scope, original)
    _, rows = candidate_input(original)
    selected = {e['segmentId'] for e in scope['evidence']}
    from fixed_review_suite import digest
    return dict(mode=mode,fullOriginalHash=digest(original),modelInputHash=digest(json.loads(body['messages'][1]['content'])),
        selectedIndexes=list(range(len(scope['evidence']))),selectedSegmentIds=sorted(selected),
        visibleSlices=[dict(selectionIndex=i,**copy.deepcopy(p)) for i,p in enumerate(scope['selectionOutput']['selections'])],
        withheldSegmentIds=sorted(set(rows)-selected),fullOriginalRetainedServerSide=True,
        rawChannelExcluded=True,generatedTextChannelsExcluded=mode=='CLOSED_SCOPE',
        semanticSourceBoundaryApproved=False)
