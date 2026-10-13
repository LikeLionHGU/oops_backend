"""Unadopted prompt candidates: compare judgment and target selection separately."""

JUDGMENT = """# 정상 설명과 평가 연결의 경계
설명·추측·추억·농담이라는 형식과 그 내용이 대상을 어떻게 취급하는지는 별개다.
정상 해석에는 원문이 설명하는 상황을, 비판 해석에는 그 상황을 빌미로 무엇을 어떻게 낮추는지를 적는다.
각 해석에 원문 밖 전제가 필요한지 대조한다. 추측이라는 형식만으로 PASS하지 않는다.
부재·욕구·대체·접근 제약만으로 REVIEW_REQUIRED하지도 않는다.
비판 해석은 대상의 생활·선택·제공을 낮춰 읽히는 실제 연결로 설명한다. 열등하다는 단어 자체는 필수가 아니다.
정상 설명과 구별되는 연결이 없으면 낮춤을 만들어내지 않는다.
판단에 필요한 화자·억양·장면이 없으면 그 정보를 명시하며, 가상의 악의·분위기로 대신하지 않는다.
reason과 alternativeInterpretation은 서로 다른 해석의 근거와 한계를 짧게 설명한다. 같은 말을 반복하지 않는다.
"""

TARGET = """# 대상 지칭 선택 — 판단 기준 변경 아님
TARGET은 무엇을 평가하는지 지칭하는 원문이다. 그 대상에 대한 평가·제약 설명과 구별한다.
허용 문맥에 대상 명사·명시 지칭이 있으면 그 행을 우선 검토한다.
PRIMARY 행에 대상 지칭이 있으면 동일 행을 TARGET으로도 사용할 수 있다.
대명사로 연결할 때는 현재 원문의 명사·상황 연결을 설명한다. 시간 인접만으로 연결하지 않는다.
명시 지칭이 없다는 이유로 표현 자체 검토에 대상을 억지로 만들지 않는다.
기존 허용 ID·원문·역할 수·출력 계약을 유지한다. 원응답의 역할을 서버가 자동 교정하는 규칙이 아니다.
"""


def prompt(system, mode):
    marker = '# 범용 맥락 참고 기준'
    if system.count(marker) != 1 or mode not in {'JUDGMENT','TARGET'}:
        raise ValueError('single recorded reference boundary and isolated mode required')
    policy,reference = system.split(marker,1)
    return policy + '\n' + (JUDGMENT if mode == 'JUDGMENT' else TARGET) + '\n' + marker + reference


def freeze(source, draft, reports, authorization):
    import copy
    from decimal import Decimal
    from fixed_review_suite import digest,require
    from run_boundary_comparison import reserve
    require(draft == prepare(source),'draft differs from reviewed guidance')
    require(authorization == {'schemaVersion':'additional-evaluation-budget-1','additionalBudgetUsd':'.5',
                             'priorReservedCostUsd':'0.4994146','userAuthorized':True}, 'authorization required')
    require(len(reports)==2 and [r['reservedCostUsd'] for r in reports]==['0.1829466','0.1829613']
            and all(r['status']=='COMPLETED_PENDING_JAVA_AUDIT' and len(r['calls'])==42 for r in reports)
            and reports[1]['artifactHash']==source['artifactHash']
            and digest(reports[0])==source['previousReportHash'], 'complete prior ledger required')
    ledger=[{'reportHash':digest(r),'reservedCostUsd':r['reservedCostUsd']} for r in reports]
    remaining=Decimal('.5')-sum(Decimal(r['reservedCostUsd']) for r in ledger)
    requests=[]
    for c in draft['comparisons']:
        slot=1 if c['mode']=='JUDGMENT' else 2
        # slot identifies the comparison, not repeat evidence of stability.
        for arm in (('BASELINE','CANDIDATE') if slot==1 else ('CANDIDATE','BASELINE')):
            body=copy.deepcopy(c['baselineBody'] if arm=='BASELINE' else c['candidateBody'])
            tokens,cost=reserve(body,source['config'])
            require(tokens<272000,'long context unsupported')
            requests.append(dict(case=c['case'],arm=arm,repeat=slot,comparisonMode=c['mode'],body=body,
                                 requestHash=digest(body),reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost)))
    total=sum(Decimal(r['reservedCostUsd']) for r in requests)
    require(total<=remaining,'remaining budget exceeded')
    artifact=dict(schemaVersion='recorded-semantic-guidance-trial-1',plannedCalls=28,
                  comparisonSlots={'1':'JUDGMENT','2':'TARGET'},repetitionsPerCondition=1,
                  additionalBudgetUsd='.5',priorReservedCostUsd='0.4994146',
                  priorAdditionalReservationLedger=ledger,remainingBudgetUsd=str(remaining),
                  authorizationHash=digest(authorization),sourceArtifactHash=source['artifactHash'],
                  sourceDraftHash=digest(draft),reservedTotalCostUsd=str(total),config=source['config'],
                  cases=copy.deepcopy(source['cases']),requests=requests,
                  normalControlsIncluded=True,fullPipelineReplayed=False,runtimeChanged=False,qualityGateMet=None)
    artifact['artifactHash']=digest(artifact)
    return artifact


def prepare(source):
    import copy
    from fixed_review_suite import digest,require
    require(source['artifactHash'] == digest({k:v for k,v in source.items() if k != 'artifactHash'}), 'source changed')
    cases = {c['name']:c['payload'] for c in source['cases']}
    require(set(cases) == {'B','C','D','N1','N2','N3','N4'}, 'fixed regression cases required')
    templates = {r['case']:r['body'] for r in source['requests'] if r['arm']=='BASELINE'}
    comparisons=[]
    for mode in ('JUDGMENT','TARGET'):
        for name,payload in cases.items():
            import json
            baseline=copy.deepcopy(templates[name])
            require(baseline['model']=='gpt-6-luna' and baseline['response_format']=={'type':'json_object'}
                    and json.loads(baseline['messages'][1]['content'])==payload,'original input contract required')
            candidate=copy.deepcopy(baseline)
            candidate['messages'][0]['content']=prompt(candidate['messages'][0]['content'],mode)
            comparisons.append(dict(case=name,mode=mode,baselineBody=baseline,candidateBody=candidate,
                                    baselineHash=digest(baseline),candidateHash=digest(candidate)))
    return dict(schemaVersion='semantic-guidance-draft-1',status='NOT_EXECUTABLE',
                sourceArtifactHash=source['artifactHash'],comparisons=comparisons,
                apiExecutionEnabled=False,runtimeChanged=False,qualityGateMet=None,
                plannedApiCalls=None,reservedCostUsd=None,
                blockingReasons=['SEMANTIC_BOUNDARY_NOT_INDEPENDENTLY_APPROVED','EXECUTION_BUDGET_AND_REPETITIONS_NOT_FROZEN'])


if __name__=='__main__':
    import argparse
    from pathlib import Path
    from fixed_review_suite import read,require,write_private
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',required=True); p.add_argument('--output',required=True)
    p.add_argument('--draft'); p.add_argument('--prior-report',action='append'); p.add_argument('--authorization')
    a=p.parse_args()
    path=Path(a.output).resolve()
    require('datasets' in path.parts and not path.exists(),'new private output required')
    require(bool(a.draft)==bool(a.prior_report)==bool(a.authorization),'freeze inputs required together')
    value=freeze(read(a.source),read(a.draft),[read(r) for r in a.prior_report],read(a.authorization)) if a.draft else prepare(read(a.source))
    write_private(path,value)
    print({'schema':value['schemaVersion'],'executed':False,'reservedCostUsd':value.get('reservedTotalCostUsd')})
