"""Experimental ID selection only. Hydrate raw text; never repair selections."""
import copy
from fixed_review_suite import require
from target_wire_contract import adapt as target_adapt, prompt as target_prompt

VERSION = 'evidence-id-selection-wire-1'
CONTRACT = """evidence는 PRIMARY·TARGET·CONTEXT를 합쳐 1~8개다.
각 객체에는 허용된 raw의 segmentId와 role만 반환한다. quote와 evidenceText를 쓰지 않는다.
PRIMARY는 정확히 한 개이며 anchorId를 선택한다. 서버가 선택한 행의 원문 전체를 붙인다.
같은 행을 PRIMARY와 TARGET으로 선택할 수 있지만 동일한 ID·role 쌍은 중복하지 않는다.
대상 평가이면 target은 해석한 대상, targetType은 PERSON/GROUP/REGION/RESIDENT_GROUP/BUSINESS/PRODUCT/WORK/OTHER,
targetReason은 현재 원문 연결 설명(300자 이내)이다. TARGET은 정확히 한 개다.
TARGET에는 실제 대상을 지칭하는 행을 선택하고 다른 행의 CONTEXT로 대상 연결을 뒷받침한다.
시간 인접만으로 연결하지 않는다. 무관한 대명사 행을 선택하지 않는다.
targetMention·targetRelation도 쓰지 않는다. 서버가 선택된 TARGET 원문과 target으로 계산한다.
원문·대상 연결이 부족하면 UNCERTAIN이다. 표현 자체 검토에는 대상을 억지로 만들지 않는다.
"""


def prompt(system):
    value = target_prompt(system)
    value, reference = value.split('# 범용 맥락 참고 기준', 1)
    start = value.index('evidence는 PRIMARY·TARGET·CONTEXT를 합쳐 최대 8개다.')
    end = value.index('category는 검토 이유', start)
    value = value[:start] + CONTRACT + value[end:]
    value = value.replace('"evidenceText":"PRIMARY 인용",', '')
    value = value.replace('"quote":"PRIMARY 인용",', '')
    value = value.replace('모든 결정에 원문 인용과 구체적 reason이 필요하다.',
                          '모든 결정에 원문 구간 선택과 구체적 reason이 필요하다.')
    return value + '# 범용 맥락 참고 기준' + reference


def adapt(output, payload):
    result = copy.deepcopy(output)
    rows = {r['id']: r for r in payload['raw']}
    candidates = {c['candidateId']: c for c in payload['candidates']}
    require(len(rows) == len(payload['raw']) and len(candidates) == len(payload['candidates']),
            'ambiguous input IDs')
    values = result.get('verifications')
    require(isinstance(values, list) and len(values) == len(candidates)
            and {v['candidateId'] for v in values} == set(candidates), 'selection coverage mismatch')
    for v in values:
        a, candidate = v['assessment'], candidates[v['candidateId']]
        require('evidenceText' not in a, 'generated evidence text forbidden')
        require(a['segmentId'] == candidate['anchorId'], 'anchor substitution forbidden')
        evidence = a['evidence']
        require(isinstance(evidence, list) and 1 <= len(evidence) <= 8, 'selection count invalid')
        seen = set()
        for e in evidence:
            require(set(e) == {'segmentId', 'role'} and e['role'] in {'PRIMARY', 'TARGET', 'CONTEXT'},
                    'selection fields invalid')
            require(e['segmentId'] in candidate['segmentIds'] and e['segmentId'] in rows,
                    'selection outside candidate')
            pair = (e['segmentId'], e['role'])
            require(pair not in seen, 'duplicate selection')
            seen.add(pair)
            text = rows[e['segmentId']]['text']
            require(isinstance(text, str) and text.strip(), 'empty raw row')
            e['quote'] = text
        primary = [e for e in evidence if e['role'] == 'PRIMARY']
        require(len(primary) == 1 and primary[0]['segmentId'] == candidate['anchorId'], 'one anchor PRIMARY required')
        a['evidenceText'] = primary[0]['quote']
    # Existing target constraints and current Java validator still apply.
    return target_adapt(result, payload)
