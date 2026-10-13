"""Small near-boundary/quote-contract trial, isolated from runtime adoption.

Normal cases are synthetic, not original utterances or approved labels.
Original C compares output instructions only with its refined reference fixed.
Normal cases compare references only with their output instructions fixed.
"""
import argparse
import copy
from decimal import Decimal
import json
from pathlib import Path

from fixed_review_suite import read, digest, require, write_private
from run_boundary_comparison import reserve, positive

QUOTE_CONTRACT = """
# 원문-ID와 인용 수 최종 점검 — 판단 기준을 바꾸지 않는다
각 quote를 먼저 허용 raw의 한 행에서 연속 문자열로 고르고 그 행의 id를 segmentId로 복사한다.
anchorId는 PRIMARY에만 고정된다. TARGET/CONTEXT가 다른 행이면 그 행의 id를 사용한다.
PRIMARY·TARGET·CONTEXT 객체를 모두 합쳐 최대 8개다. 같은 행의 역할 두 개도 2개로 센다.
anchor PRIMARY, 실제 TARGET, 관계 설명에 필수인 별도 CONTEXT부터 확보하고 불필요한 중복은 반환하지 않는다.
인용을 맞추려고 원문·ID·대상 관계를 추측 수정하지 않는다. 근거 부족은 PASS/UNCERTAIN으로 판단한다.
"""

NORMALS = [
    ('N1', ['이 동네는 햄버거 가게가 멀어요.', '아이가 햄버거를 먹고 싶대서 집에서 만들어 줬어요.',
            '오늘은 밖에서 못 사니까 집에서 대신 먹었어요.']),
    ('N2', ['여기에는 그 햄버거 가게가 없어요.', '아들이 햄버거를 원해서 이걸로 대신 먹어요.',
            '집에서 만들면 재료를 골라 넣을 수 있어 이 쪽을 더 좋아해요.']),
    ('N3', ['주민이 원하는 햄버거 매장이 아직 없어요.', '못 사니까 다른 메뉴를 대신 주문하는 경우가 있어요.',
            '다음 달에 매장을 열 계획이라고 안내했습니다.']),
    ('N4', ['아이들이 햄버거를 먹고 싶어 했어요.', '근처에 가게가 없어 어머니가 만들어 주셨죠.',
            '못 사 먹어서 시작했지만 지금도 그 맛이 좋아요.'])
]


def prepare(previous_artifact, reports):
    require(previous_artifact.get('comparisonKind') == 'REFERENCE_PATTERN_ONLY_NOT_PROCEDURE_DELTA'
            and previous_artifact['artifactHash'] == digest({k:v for k,v in previous_artifact.items() if k != 'artifactHash'}),
            'frozen prior pattern comparison required')
    require(len(reports) == 5 and len({digest(r) for r in reports}) == 5, 'five distinct prior cost reports required')
    expected = ['COMPLETED_CONTRACT_CHECK_ONLY','PARTIAL','COMPLETED_PENDING_JAVA_AUDIT',
                'COMPLETED_PENDING_JAVA_AUDIT','COMPLETED_CONTRACT_CHECK_ONLY']
    require([r['status'] for r in reports] == expected, 'prior ledger order mismatch')
    ledger = [{'reportHash':digest(r),'reservedCostUsd':str(positive(r['reservedCostUsd']))} for r in reports]
    require(ledger[:3] == previous_artifact['priorReservationLedger']
            and reports[3]['artifactHash'] == previous_artifact['artifactHash'], 'prior report provenance mismatch')
    prior = sum(Decimal(r['reservedCostUsd']) for r in ledger)
    remaining = Decimal('.5') - prior
    require(remaining > 0, 'budget exhausted')
    requests, cases = [], []
    originals = {c['name']:c for c in previous_artifact['cases']}
    templates = {(r['case'],r['arm']):r['body'] for r in previous_artifact['requests']}
    def add(name, payload, bodies, repeats):
        cases.append({'name':name,'payload':payload, 'body':bodies['BASELINE'],
                      'inputHash':digest(payload), 'inputMode':'SAVED_STT' if name == 'C' else 'SYNTHETIC_TEXT',
                      'syntheticTimingOnly':name != 'C'})
        for repeat in range(1,repeats+1):
            for arm in (('BASELINE','CANDIDATE') if repeat % 2 else ('CANDIDATE','BASELINE')):
                body = bodies[arm]
                tokens,cost = reserve(body, previous_artifact['config'])
                require(tokens < 272000, 'unsupported long context')
                requests.append({'case':name,'arm':arm,'repeat':repeat,'body':body,'requestHash':digest(body),
                                 'reservedInputTokensHeuristic':tokens,'reservedCostUsd':str(cost)})
    c = copy.deepcopy(templates['C','CANDIDATE'])
    changed = copy.deepcopy(c)
    changed['messages'][0]['content'] += QUOTE_CONTRACT
    add('C', originals['C']['payload'], {'BASELINE':c,'CANDIDATE':changed}, 2)
    for name,texts in NORMALS:
        raw = [{'id':f'synthetic-{i}','startMs':i*1000,'endMs':(i+1)*1000,'text':text} for i,text in enumerate(texts)]
        proposal = {'candidateId':'synthetic-candidate','anchorId':raw[1]['id'],'axis':'TARGET_TREATMENT',
                    'segmentIds':[r['id'] for r in raw], 'contextExpanded':False,'contextLimited':False,
                    'hypothesisNotEvidence':'대체 설명이 생활·선택지를 낮추는지 전체 원문으로 검토한다.',
                    'proposedEvidence':[{'segmentId':r['id'],'quote':r['text']} for r in raw]}
        payload = copy.deepcopy(originals['B']['payload'])
        require(set(payload) == {'promptRevision','raw','candidates'}, 'unexpected captured input fields')
        payload.update(raw=raw,candidates=[proposal])
        bodies = {}
        for arm in ('BASELINE','CANDIDATE'):
            bodies[arm] = copy.deepcopy(templates['B',arm])
            bodies[arm]['max_completion_tokens'] = 2000
            bodies[arm]['messages'][1]['content'] = json.dumps(payload,ensure_ascii=False)
        add(name,payload,bodies,1)
    total = sum(Decimal(r['reservedCostUsd']) for r in requests)
    require(total <= remaining, 'new trial exceeds remaining cumulative budget')
    artifact = {'schemaVersion':'recorded-candidate-boundary-trial-1', 'plannedCalls':len(requests),
                'comparisonScope':'C_OUTPUT_ONLY_NORMAL_REFERENCE_ONLY', 'sourceArtifactHash':previous_artifact['artifactHash'],
                'priorReservationLedger':ledger,'cumulativeBudgetUsd':'.5','remainingBudgetUsd':str(remaining),
                'reservedTotalCostUsd':str(total),'config':previous_artifact['config'],'cases':cases,'requests':requests,
                'normalControlsIncluded':True,'fullPipelineReplayed':False,'runtimeChanged':False,'qualityGateMet':None}
    artifact['artifactHash'] = digest(artifact)
    return artifact


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--previous-artifact',required=True)
    p.add_argument('--prior-cost-report',action='append',required=True)
    p.add_argument('--output',required=True)
    a = p.parse_args()
    value = prepare(read(a.previous_artifact),[read(f) for f in a.prior_cost_report])
    path = Path(a.output).resolve()
    require('datasets' in path.parts and not path.exists(), 'new private output required')
    write_private(path,value)
    print(json.dumps({k:value[k] for k in ('plannedCalls','reservedTotalCostUsd','remainingBudgetUsd','artifactHash')}))
