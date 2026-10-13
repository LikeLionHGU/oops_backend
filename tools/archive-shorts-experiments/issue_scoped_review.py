"""Offline evidence-first review prototype. No transport, deployment or semantic grading.

Selection and judgment are separate model outputs. Both remain untrusted.
Offsets use Unicode code points, not bytes or UTF-16 units. Hydration is exact,
never a transcription correction. Existing Java validation is still required.
"""
import argparse
import copy
import json
from pathlib import Path

from fixed_review_suite import digest, read, require, write_private
from target_wire_contract import adapt as target_adapt

VERSION = 'issue-scoped-review-draft-1'
SELECTION_CONTRACT = """
# 후보 쟁점의 근거 선택
현재 후보의 anchor와 쟁점에 연결되는 원문만 선택한다. 최종 판정·점수·유형은 반환하지 않는다.
후보 가설은 사실이 아니다. 허용 raw 전체를 읽고 정상 해석에 필요한 앞뒤 발언도 보존한다.
같은 시간 창에 있어도 다른 대상·다른 쟁점의 평가를 현재 후보의 근거로 빌리지 않는다.
issueSummary는 현재 후보가 질문하는 쟁점의 짧은 설명이지 논란 확정이나 새 후보가 아니다.
selections는 1~8개다. role은 PRIMARY/TARGET/CONTEXT, PRIMARY는 정확히 하나이며 anchorId다.
TARGET은 실제 지칭어가 있는 경우 최대 하나, CONTEXT는 그 쟁점에 연결되는 발언이다.
segmentId와 start/end만 고른다. start 포함·end 제외, 한 행 text의 Unicode code point 위치다.
quote·교정 전사·해석 대상명·decision을 반환하지 않는다. 서버가 실제 원문을 잘라 붙인다.
근거 선택을 못 하더라도 anchor를 다른 발언으로 바꾸거나 대상 지칭을 만들어내지 않는다.
JSON: {"candidateId":"현재 후보 ID","anchorId":"고정 anchorId",
 "issueSummary":"현재 쟁점 설명, 300자 이내",
 "selections":[{"segmentId":"허용 ID","start":0,"end":1,"role":"PRIMARY"}]}
"""
JUDGMENT_CONTRACT = """
# 선택 근거를 별도 검증하고 판단
selectedScope는 앞 단계 모델의 제안이지 승인된 근거가 아니다. issueSummary에도 동의할 의무가 없다.
raw 전체와 현재 후보의 실제 쟁점을 다시 대조한다. 앞 단계가 중요한 문맥을 누락했으면 명시한다.
각 선택에 대해 SAME_ISSUE/OTHER_ISSUE/UNSURE와 연결 이유를 짧게 반환한다.
SAME_ISSUE는 같은 쟁점을 설명한다는 뜻이지 비하·논란이라는 뜻이 아니다.
다른 쟁점의 근거로 현재 쟁점을 REVIEW_REQUIRED로 만들지 않는다. 필요한 근거가 부족하면 UNCERTAIN이다.
원문·ID·인용·역할·anchor는 재작성하지 않는다. usedSelectionIndexes로 사용할 근거만 지정한다.
PRIMARY를 반드시 포함한다. OTHER_ISSUE/UNSURE인 선택은 사용하지 않는다. 새 근거가 필요하면 누락 정보로 남긴다.
대상 평가를 검토할 때는 실제 TARGET 지칭과 target/targetType/targetReason이 필요하다.
표현 자체에 대상을 억지로 만들지 않는다. targetMention/targetRelation은 서버가 계산한다.
REVIEW_REQUIRED에는 정상 해석과 구별되는 구체적 이유·alternativeInterpretation이 필요하다.
연결된 대상 후보의 PASS도 전체 정상 해석과 비판 가설의 근거 부족을 alternativeInterpretation에 적는다.
이 PASS는 proposedEvidence의 anchor 이외 행에 연결된 CONTEXT를 사용해야 한다.
reason 400자, alternativeInterpretation 300자, target 200자, targetReason 300자 이내.
PASS/UNCERTAIN의 category/target/targetType/targetReason/score는 null이다.
missingInformation은 UNCERTAIN만 1~6개, 나머지는 []이다. score는 0~1 검토 우선순위이지 논란 확률이 아니다.
JSON: {"candidateId":"현재 후보 ID","scopeHash":"입력 selectedScope의 hash",
 "relevance":[{"selectionIndex":0,"relation":"SAME_ISSUE","reason":"쟁점 연결 설명"}],
 "usedSelectionIndexes":[0],"assessment":{"decision":"PASS/REVIEW_REQUIRED/UNCERTAIN",
 "reason":"현재 후보의 쟁점 판단","alternativeInterpretation":null,"category":null,
 "target":null,"targetType":null,"targetReason":null,"score":null,"missingInformation":[]}}
"""
ASSESSMENT_FIELDS = {'decision', 'reason', 'alternativeInterpretation', 'category', 'target',
                     'targetType', 'targetReason', 'score', 'missingInformation'}


def useful(value, limit):
    return isinstance(value, str) and bool(value.strip()) and len(value) <= limit


def candidate_input(payload):
    require(isinstance(payload, dict) and isinstance(payload.get('raw'), list)
            and 1 <= len(payload['raw']) <= 48 and isinstance(payload.get('candidates'), list)
            and len(payload['candidates']) == 1, 'one bounded candidate required')
    rows = payload['raw']
    require(all(isinstance(r, dict) and useful(r.get('id'), 200) and useful(r.get('text'), 6000) for r in rows),
            'nonempty original raw required')
    ids = [r['id'] for r in rows]
    require(len(ids) == len(set(ids)) and sum(len(r['text']) for r in rows) <= 6000, 'ambiguous or oversized raw')
    c = payload['candidates'][0]
    require(useful(c.get('candidateId'), 200) and c.get('axis') in {'TARGET_TREATMENT', 'EXPRESSION_CONTENT'}
            and isinstance(c.get('segmentIds'), list) and c['segmentIds']
            and len(c['segmentIds']) == len(set(c['segmentIds'])) and set(c['segmentIds']) <= set(ids)
            and c.get('anchorId') in c['segmentIds'], 'fixed allowed candidate required')
    return c, {r['id']: r for r in rows}


def hydrate_selection(selection, payload):
    c, rows = candidate_input(payload)
    require(isinstance(selection, dict) and set(selection) == {'candidateId', 'anchorId', 'issueSummary', 'selections'}
            and selection['candidateId'] == c['candidateId'] and selection['anchorId'] == c['anchorId']
            and useful(selection['issueSummary'], 300), 'selection identity or fields invalid')
    picks = selection['selections']
    require(isinstance(picks, list) and 1 <= len(picks) <= 8, 'selection budget invalid')
    evidence, seen = [], set()
    for p in picks:
        require(isinstance(p, dict) and set(p) == {'segmentId', 'start', 'end', 'role'}
                and p['segmentId'] in c['segmentIds'] and p['role'] in {'PRIMARY', 'TARGET', 'CONTEXT'},
                'selection outside candidate or generated fields')
        text = rows[p['segmentId']]['text']
        require(type(p['start']) is int and type(p['end']) is int and 0 <= p['start'] < p['end'] <= len(text),
                'invalid code point range')
        pair = p['segmentId'], p['role']
        require(pair not in seen, 'duplicate ID role selection')
        seen.add(pair)
        quote = text[p['start']:p['end']]
        require(quote.strip() and (p['role'] != 'TARGET' or len(quote) <= 200), 'empty or oversized target span')
        evidence.append(dict(segmentId=p['segmentId'], quote=quote, role=p['role']))
    primaries = [e for e in evidence if e['role'] == 'PRIMARY']
    require(len(primaries) == 1 and primaries[0]['segmentId'] == c['anchorId'], 'fixed PRIMARY required')
    require(sum(e['role'] == 'TARGET' for e in evidence) <= 1, 'ambiguous target selection')
    result = dict(schemaVersion=VERSION, inputHash=digest(payload), selectionOutput=copy.deepcopy(selection),
                  evidence=evidence, semanticReviewApproved=False)
    result['scopeHash'] = digest(result)
    return result


def validate_scope(scope, payload):
    require(isinstance(scope, dict) and scope == hydrate_selection(scope['selectionOutput'], payload),
            'scope changed or from another input')


def adapt_judgment(judgment, scope, payload):
    """Mechanical linkage only. SAME_ISSUE is a model claim, not semantic proof."""
    validate_scope(scope, payload)
    c, _ = candidate_input(payload)
    require(isinstance(judgment, dict) and set(judgment) == {'candidateId', 'scopeHash', 'relevance',
                                                          'usedSelectionIndexes', 'assessment'}
            and judgment['candidateId'] == c['candidateId'] and judgment['scopeHash'] == scope['scopeHash'],
            'judgment identity or stage linkage invalid')
    relations = judgment['relevance']
    require(isinstance(relations, list) and len(relations) == len(scope['evidence']), 'all selections need relevance')
    indexed = {}
    for r in relations:
        require(isinstance(r, dict) and set(r) == {'selectionIndex', 'relation', 'reason'}
                and type(r['selectionIndex']) is int and 0 <= r['selectionIndex'] < len(scope['evidence'])
                and r['selectionIndex'] not in indexed and r['relation'] in {'SAME_ISSUE', 'OTHER_ISSUE', 'UNSURE'}
                and useful(r['reason'], 300), 'relevance contract invalid')
        indexed[r['selectionIndex']] = r['relation']
    used = judgment['usedSelectionIndexes']
    require(isinstance(used, list) and 1 <= len(used) <= 8
            and all(type(i) is int and i in indexed for i in used) and len(used) == len(set(used)),
            'invalid selected indexes')
    require(all(indexed[i] == 'SAME_ISSUE' for i in used), 'other or uncertain issue cannot support assessment')
    evidence = [copy.deepcopy(scope['evidence'][i]) for i in used]
    primary = [e for e in evidence if e['role'] == 'PRIMARY']
    require(len(primary) == 1, 'judgment must retain PRIMARY')
    a = copy.deepcopy(judgment['assessment'])
    require(isinstance(a, dict) and set(a) == ASSESSMENT_FIELDS
            and a['decision'] in {'PASS', 'REVIEW_REQUIRED', 'UNCERTAIN'} and useful(a['reason'], 400),
            'judgment assessment fields invalid')
    require(a['alternativeInterpretation'] is None or useful(a['alternativeInterpretation'], 300), 'invalid contrast')
    require(isinstance(a['missingInformation'], list)
            and (1 <= len(a['missingInformation']) <= 6 if a['decision'] == 'UNCERTAIN' else not a['missingInformation'])
            and all(useful(s, 300) for s in a['missingInformation']), 'missing information contract invalid')
    if a['decision'] == 'REVIEW_REQUIRED':
        require(useful(a['category'], 100) and useful(a['alternativeInterpretation'], 300), 'review needs category and contrast')
        if c['axis'] == 'TARGET_TREATMENT': require(useful(a['target'], 200), 'target issue needs grounded target')
    else:
        require(all(a[k] is None for k in ('category', 'target', 'targetType', 'targetReason', 'score')), 'non-review fields')
    if a['target'] is not None:
        require(useful(a['target'], 200) and useful(a['targetType'], 100) and useful(a['targetReason'], 300), 'target explanation required')
    else:
        require(a['targetType'] is None and a['targetReason'] is None, 'orphan target fields')
    if a['decision'] == 'PASS' and c['axis'] == 'TARGET_TREATMENT' and len({q['segmentId'] for q in c['proposedEvidence']}) > 1:
        require(useful(a['alternativeInterpretation'], 300), 'linked PASS needs contrast')
        require(any(e['role'] == 'CONTEXT' and e['segmentId'] != c['anchorId']
                    and e['segmentId'] in {q['segmentId'] for q in c['proposedEvidence']} for e in evidence),
                'linked PASS needs proposed context')
    a.update(segmentId=c['anchorId'], evidenceText=primary[0]['quote'], evidence=evidence, context=None, reading=None)
    return target_adapt({'verifications': [dict(candidateId=c['candidateId'], assessment=a)]}, payload)


def review_packet(judgment, scope, payload):
    """Preserve both stages and leave semantic approval empty, including SAME_ISSUE claims."""
    output = adapt_judgment(judgment, scope, payload)
    return dict(schemaVersion=VERSION, inputHash=digest(payload), originalInput=copy.deepcopy(payload),
                selectedScope=copy.deepcopy(scope), judgmentOutput=copy.deepcopy(judgment),
                compatibleAssessment=output, mechanicalContractOnly=True,
                currentJavaValidationRequired=True, semanticQualityGateMet=None,
                runtimeUseAuthorized=False, responsesModified=False,
                semanticReview={'candidateIssueMatched': None, 'evidenceRelationsVerified': None,
                                'normalContrastSufficient': None, 'reviewer': None})


def prompts(system):
    marker = '# 범용 맥락 참고 기준'
    require(system.count(marker) == 1 and system.count('# 후보 근거 검증') == 1, 'recorded prompt boundaries required')
    policy = system.split('# 후보 근거 검증', 1)[0]
    before_reference, reference = system.split(marker, 1)
    judgment = before_reference.split('각 candidateId에 정확히 하나의 assessment', 1)[0]
    require(before_reference.count('각 candidateId에 정확히 하나의 assessment') == 1
            and 'category는 검토 이유' in before_reference and 'score는' in before_reference,
            'recorded output and category boundaries required')
    categories = before_reference.split('category는 검토 이유', 1)[1].split('score는', 1)[0]
    return (policy + SELECTION_CONTRACT,
            judgment + JUDGMENT_CONTRACT + '\ncategory는 검토 이유' + categories + '\n' + marker + reference)


def selection_request(base):
    body = copy.deepcopy(base)
    payload = json.loads(body['messages'][1]['content'])
    candidate_input(payload)
    body['messages'][0]['content'] = prompts(body['messages'][0]['content'])[0]
    # Keep all permitted raw, including normal interpretations; never trim other topics automatically.
    return body


def judgment_request(base, scope):
    body = copy.deepcopy(base)
    payload = json.loads(body['messages'][1]['content'])
    validate_scope(scope, payload)
    body['messages'][0]['content'] = prompts(body['messages'][0]['content'])[1]
    body['messages'][1]['content'] = json.dumps(dict(original=payload, selectedScope=scope), ensure_ascii=False)
    return body


def prepare_draft(source):
    from prepare_flow_regression import validate
    validate(source)
    require(source['artifactHash'] == digest({k:v for k,v in source.items() if k != 'artifactHash'}), 'source changed')
    rows = [r for r in source['requests'] if r['arm'] == 'CANDIDATE' and r['repeat'] == 1]
    require(len(rows) == len(source['cases']), 'complete source cases required')
    drafts = []
    for row in rows:
        require(row['requestHash'] == digest(row['body']), 'source request changed')
        selection = selection_request(row['body'])
        drafts.append(dict(case=row['case'], sourceRequestHash=row['requestHash'],
                           selectionRequest=selection, selectionRequestHash=digest(selection),
                           judgmentSystem=prompts(row['body']['messages'][0]['content'])[1],
                           judgmentRequiresValidatedScope=True,
                           semanticReview={'candidateIssueMatched': None, 'evidenceRelationsVerified': None,
                                           'normalContrastSufficient': None, 'reviewer': None}))
    result = dict(schemaVersion=VERSION, sourceArtifactHash=source['artifactHash'], drafts=drafts,
                  executionEnabled=False, runtimeChanged=False, reservationUsd=None,
                  fullPipelineReplayed=False, qualityGateMet=None, independentGoldApprovals=0)
    result['artifactHash'] = digest(result)
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', required=True)
    p.add_argument('--output', required=True)
    args = p.parse_args()
    out = Path(args.output).resolve()
    require('datasets' in out.parts and not out.exists(), 'new private draft required')
    draft = prepare_draft(read(args.source))
    write_private(out, draft)
    print({'drafts': len(draft['drafts']), 'executionEnabled': False, 'reserved': False})
