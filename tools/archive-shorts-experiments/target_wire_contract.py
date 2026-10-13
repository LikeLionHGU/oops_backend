"""Experimental wire contract; derive mechanical fields, never repair evidence."""
import copy
from fixed_review_suite import require

VERSION = 'single-target-evidence-wire-1'
CONTRACT = """evidence는 PRIMARY·TARGET·CONTEXT를 합쳐 최대 8개다.
PRIMARY는 anchorId의 실제 연속 인용이며 evidenceText와 같다.
각 quote를 허용 raw의 한 행에서 복사하고 반드시 그 행의 id를 segmentId로 사용한다.
대상 평가이면 target은 해석한 대상, targetType은 PERSON/GROUP/REGION/RESIDENT_GROUP/BUSINESS/PRODUCT/WORK/OTHER,
targetReason은 현재 원문의 연결 설명(300자 이내)이다.
TARGET은 정확히 한 객체로, 대상을 가리키는 원문 지칭어만 연속 인용한다. 평가 문장 전체 대신 실제 지칭어를 고른다.
해석 대상과 원문 지칭이 다르면 다른 행의 CONTEXT 인용으로 연결을 설명한다. 시간 인접만으로 관계를 만들지 않는다.
모델은 targetMention·targetRelation을 반환하지 않는다. 이 두 필드는 서버가 TARGET.quote와 target으로 계산한다.
원문·대상 연결이 부족하면 UNCERTAIN이다. 표현 자체 검토에는 대상을 억지로 만들지 않는다.
"""


def prompt(system):
    marker = '# 범용 맥락 참고 기준'
    require(system.count(marker) == 1, 'recorded reference boundary required')
    policy, reference = system.split(marker,1)
    start = policy.index('evidence는 최대 8개로')
    end = policy.index('category는 검토 이유')
    tail = policy[end:policy.index('# 대상 필드 형식 예시')]
    tail = tail.replace('·targetMention','').replace('·targetRelation','')
    tail = tail.replace('"targetRelation":null,','').replace('"targetMention":null,','')
    policy = policy[:start] + CONTRACT + tail
    return policy + '\n' + marker + reference


def adapt(output, payload):
    """Only the new contract. Historical flat responses cannot be repaired here."""
    result = copy.deepcopy(output)
    expected = {c['candidateId']:c for c in payload['candidates']}
    rows = {r['id']:r for r in payload['raw']}
    require(isinstance(result.get('verifications'),list)
            and len(result['verifications']) == len(expected)
            and {v['candidateId'] for v in result['verifications']} == set(expected), 'wire coverage mismatch')
    for v in result['verifications']:
        a = v['assessment']
        require('targetMention' not in a and 'targetRelation' not in a, 'legacy fields forbidden in new wire')
        evidence = a['evidence']
        require(isinstance(evidence,list) and 1 <= len(evidence) <= 8, 'wire evidence count invalid')
        allowed = set(expected[v['candidateId']]['segmentIds'])
        for q in evidence:
            require(q['segmentId'] in rows and q['segmentId'] in allowed
                    and isinstance(q['quote'],str) and q['quote'].strip()
                    and q['quote'] in rows[q['segmentId']]['text']
                    and q['role'] in {'PRIMARY','TARGET','CONTEXT'}, 'wire quote not in own allowed row')
        targets = [q for q in evidence if q['role'] == 'TARGET']
        if a['decision'] == 'REVIEW_REQUIRED' and a.get('target') is not None:
            require(isinstance(a['target'],str) and 0 < len(a['target'].strip()) <= 200
                    and len(targets) == 1 and len(targets[0]['quote']) <= 200, 'one grounded target required')
            mention = targets[0]['quote']
            relation = 'EXPLICIT' if mention == a['target'] else 'CONTEXTUAL'
            if relation == 'CONTEXTUAL':
                require(any(q['role'] == 'CONTEXT' and q['segmentId'] != targets[0]['segmentId'] for q in evidence),
                        'separate contextual evidence required')
            a.update(targetMention=mention,targetRelation=relation)
        else:
            require(a.get('target') is None and not targets, 'non-target wire must not contain target evidence')
            a.update(targetMention=None,targetRelation=None)
    return result
