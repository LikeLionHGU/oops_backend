"""Bounded paired regression: read full allowed dialogue before candidate shortlist.

Never changes labels, anchors, references, validators or deployed prompts.
"""
import argparse
import copy
from decimal import Decimal
from pathlib import Path

from fixed_review_suite import digest, read, require, write_private
from run_boundary_comparison import reserve

OLD = '''먼저 proposedEvidence의 평가·대상·앞뒤 연결을 실제 raw에서 확인한 뒤 허용 문맥을 대조한다.
  anchor가 질문·대명사라는 이유만으로 그 문장만 떼어 PASS 처리하지 않는다.
  전체 연결에도 구체적인 낮춤이 없으면 PASS다. 시간 인접·부정 표현 누적만으로 문제를 만들지 않는다.'''
NEW = '''먼저 segmentIds에 허용된 raw를 시간순으로 읽어 대상과 실제 평가 연결을 찾고, 그 다음 proposedEvidence를 확인한다.
  proposedEvidence는 일부 발언의 제안이지 판단 범위가 아니다. 가설에 없는 앞선 반문·응답도 같은 대상의 평가를 잇는 원문이면 대조한다.
  앞선 선택지의 일축과 뒤의 결핍·대체 설명이 동일 대상을 낮추는 흐름인지, 단순 문의·정보·개인 취향인지 구별한다.
  반복·부재·대체만으로 경고하지 않는다. 평가 연결을 인용하고 정상 설명이 그 연결을 설명하는지 비교한다.
  anchor의 원문·ID는 유지한다. 다른 쟁점·화자 동조·억양을 시간 인접만으로 연결하지 않는다. 전체에도 낮춤이 없으면 PASS다.'''


def prompt(system):
    require(system.count(OLD)==1,'recorded procedure required')
    return system.replace(OLD,NEW,1)


def candidate_body(base, variant):
    result=copy.deepcopy(base)
    require(variant in {'READING_ORDER','REASONING_LOW','TARGET_WIRE','PROPOSAL_SALIENCE'},'isolated variant required')
    if variant=='READING_ORDER': result['messages'][0]['content']=prompt(base['messages'][0]['content'])
    elif variant=='REASONING_LOW':
        require(base['reasoning_effort']=='none' and base['temperature']==.1,'recorded sampling required')
        result['reasoning_effort']='low'
        result.pop('temperature')
    elif variant=='TARGET_WIRE':
        from target_wire_contract import prompt as wire_prompt
        result['messages'][0]['content']=wire_prompt(base['messages'][0]['content'])
    return result


def prepare(source, authorization, prior_reports=(), repetitions=3, variant='READING_ORDER', source_arm='BASELINE',
            owner_controls=None,candidate_only=False,max_completion_tokens=4000):
    require(source.get('schemaVersion') in {'recorded-semantic-guidance-trial-1','recorded-flow-regression-1'}
            and source['artifactHash']==digest({k:v for k,v in source.items() if k!='artifactHash'}),
            'unchanged regression source required')
    require(source_arm in {'BASELINE','CANDIDATE'}
            and (source_arm=='BASELINE' or source.get('schemaVersion')=='recorded-flow-regression-1'),
            'explicit recorded baseline arm required')
    if source.get('schemaVersion')=='recorded-flow-regression-1': validate(source)
    require(authorization=={'schemaVersion':'flow-regression-budget-1','additionalBudgetUsd':'1.00',
                           'userAuthorized':True,'previousAuthorizationsReservedUsd':'0.9924228'},
            'separate authorization required')
    require(type(repetitions) is int and 3<=repetitions<=5,'three to five paired repetitions required')
    require(type(candidate_only) is bool and max_completion_tokens in (4000,8000),'bounded regression design required')
    hashes=[digest(r) for r in prior_reports]
    require(len(hashes)==len(set(hashes)), 'duplicate prior reservation')
    for r in prior_reports:
        require(r.get('trialSchemaVersion')=='recorded-flow-regression-1'
                and r.get('authorizationHash')==digest(authorization)
                and r.get('status') in {'PARTIAL','COMPLETED_PENDING_JAVA_AUDIT'},'prior budget source mismatch')
    ledger=[dict(reportHash=digest(r),reservedCostUsd=r['reservedCostUsd']) for r in prior_reports]
    require(all(Decimal(r['reservedCostUsd']).is_finite() and Decimal(r['reservedCostUsd'])>0 for r in ledger),
            'positive reservations required')
    remaining=Decimal('1.00')-sum(Decimal(r['reservedCostUsd']) for r in ledger)
    cases=copy.deepcopy(source['cases'])
    templates={r['case']:r['body'] for r in source['requests'] if r['arm']==source_arm}
    require(all(r['body']==templates[r['case']] for r in source['requests'] if r['arm']==source_arm),
            'baseline requests must be identical across repetitions')
    require(all(r.get('outputAdapter') is None for r in source['requests'] if r['arm']==source_arm),
            'flat baseline output contract required')
    require({c['name'] for c in cases}=={'B','C','D','N1','N2','N3','N4'},'fixed seven cases required')
    if owner_controls is not None:
        require(owner_controls.get('schemaVersion')=='owner-closer-normal-boundaries-1'
                and owner_controls.get('reviewStatus')=='OWNER_DEVELOPMENT_ONLY'
                and owner_controls.get('ownerApproved') is True and owner_controls.get('independentGoldApprovals')==0
                and {r['name'] for r in owner_controls['controls']}=={'N5','N6'}
                and len(owner_controls['controls'])==2,'approved closer development controls required')
        import json
        prototype=next(c for c in cases if c['name']=='N3')
        for row in owner_controls['controls']:
            require(row['decision']=='PASS' and len(row['segments'])==3
                    and all(isinstance(s,str) and s.strip() for s in row['segments']), 'three normal raw rows required')
            payload=copy.deepcopy(prototype['payload'])
            raw=[dict(id=f'owner-normal-{i}',startMs=i*1000,endMs=(i+1)*1000,text=s) for i,s in enumerate(row['segments'])]
            anchor=raw[1]
            payload['raw']=raw
            payload['candidates']=[dict(candidateId='candidate-normal',anchorId=anchor['id'],axis='TARGET_TREATMENT',
                hypothesisNotEvidence='이 대화에서 대상의 생활·선택을 낮추는 평가 연결이 있는지 원문으로 확인한다.',
                segmentIds=[r['id'] for r in raw],proposedEvidence=[dict(segmentId=r['id'],quote=r['text']) for r in raw],
                contextLimited=False,contextExpanded=False)]
            body=copy.deepcopy(templates['N3'])
            body['messages'][1]['content']=json.dumps(payload,ensure_ascii=False,separators=(',',':'))
            templates[row['name']]=body
            cases.append(dict(name=row['name'],payload=payload,inputMode='SYNTHETIC_TEXT',developmentExpectation='PASS'))
    for name in templates:
        templates[name]=copy.deepcopy(templates[name])
        templates[name]['max_completion_tokens']=max_completion_tokens
    config=copy.deepcopy(source['config'])
    config['maxCompletionTokens']=max_completion_tokens
    requests=[]
    for repeat in range(1,repetitions+1):
        for i,c in enumerate(cases):
            arms=('CANDIDATE',) if candidate_only else (('BASELINE','CANDIDATE') if (repeat+i)%2 else ('CANDIDATE','BASELINE'))
            for arm in arms:
                body=copy.deepcopy(templates[c['name']])
                if arm=='CANDIDATE': body=candidate_body(body,variant)
                tokens,cost=reserve(body,config)
                require(tokens<272000,'long context unsupported')
                row=dict(case=c['name'],arm=arm,repeat=repeat,body=body,requestHash=digest(body),
                         reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))
                if arm=='CANDIDATE' and variant=='TARGET_WIRE': row['outputAdapter']='single-target-evidence-wire-1'
                requests.append(row)
    total=sum(Decimal(r['reservedCostUsd']) for r in requests)
    require(total<=remaining,'new reservation exceeds remaining separate budget')
    result=dict(schemaVersion='recorded-flow-regression-1',variant=variant,sourceArm=source_arm,sourceArtifactHash=source['artifactHash'],
                authorization=copy.deepcopy(authorization),authorizationHash=digest(authorization),
                additionalBudgetUsd='1.00',priorReservationLedger=ledger,remainingBudgetUsd=str(remaining),
                repetitionsPerCondition=repetitions,plannedCalls=len(requests),reservedTotalCostUsd=str(total),
                config=config,cases=cases,requests=requests,
                normalControlsIncluded=True,fullPipelineReplayed=False,runtimeChanged=False,qualityGateMet=None,
                developmentGate={'reviewCases':['B','C','D'],'normalCases':['N1','N2','N3','N4'],
                                 'allRepetitionsMustMatchAndPassContract':True,'independentGoldApprovals':0})
    result.update(trialDesign='CANDIDATE_ONLY_REGRESSION' if candidate_only else 'PAIRED_COMPARISON',
                  comparisonBaselines=copy.deepcopy(templates),maxCompletionTokens=max_completion_tokens,
                  requestTimeoutSeconds=90 if max_completion_tokens==8000 else 55)
    result['config']['maxCompletionTokens']=max_completion_tokens
    if owner_controls is not None:
        result.update(ownerControls=copy.deepcopy(owner_controls),ownerControlsHash=digest(owner_controls))
        result['developmentGate']['normalCases']+=['N5','N6']
    result['artifactHash']=digest(result)
    return result


def validate(artifact):
    """Preflight verifies complete pairs and system-only delta before any transport."""
    import json
    n=artifact['repetitionsPerCondition']
    design=artifact.get('trialDesign','PAIRED_COMPARISON')
    require(design in {'PAIRED_COMPARISON','CANDIDATE_ONLY_REGRESSION'},'explicit trial design required')
    arms=('CANDIDATE',) if design=='CANDIDATE_ONLY_REGRESSION' else ('BASELINE','CANDIDATE')
    require(type(n) is int and 3<=n<=5 and len(artifact['requests'])==artifact['plannedCalls']==len(artifact['cases'])*len(arms)*n,
            'complete bounded regression required')
    auth=artifact['authorization']
    require(auth=={'schemaVersion':'flow-regression-budget-1','additionalBudgetUsd':'1.00',
                  'userAuthorized':True,'previousAuthorizationsReservedUsd':'0.9924228'}
            and artifact['authorizationHash']==digest(auth) and artifact['additionalBudgetUsd']=='1.00',
            'new separate budget required')
    ledger=artifact['priorReservationLedger']
    require(len({r['reportHash'] for r in ledger})==len(ledger)
            and all(isinstance(r['reportHash'],str) and r['reportHash']
                    and Decimal(r['reservedCostUsd']).is_finite() and Decimal(r['reservedCostUsd'])>0 for r in ledger)
            and sum(Decimal(r['reservedCostUsd']) for r in ledger)+Decimal(artifact['remainingBudgetUsd'])==Decimal('1.00'),
            'debited budget ledger required')
    cases={c['name']:c['payload'] for c in artifact['cases']}
    expected_names={'B','C','D','N1','N2','N3','N4'}
    if 'ownerControls' in artifact:
        controls=artifact['ownerControls']
        require(artifact['ownerControlsHash']==digest(controls) and controls['ownerApproved'] is True
                and controls['reviewStatus']=='OWNER_DEVELOPMENT_ONLY' and controls['independentGoldApprovals']==0
                and len(controls['controls'])==2 and {r['name'] for r in controls['controls']}=={'N5','N6'},
                'owner boundary provenance required')
        expected_names|={'N5','N6'}
        for r in controls['controls']:
            require(r['decision']=='PASS' and [s['text'] for s in cases[r['name']]['raw']]==r['segments'],
                    'approved normal text changed')
    if artifact.get('variant')=='PROPOSAL_SALIENCE':
        expected_names={'B','N5','N6'}
        originals=artifact['probeOriginalBodies']
        require(design=='CANDIDATE_ONLY_REGRESSION' and n==3 and set(originals)==expected_names
                and artifact.get('diagnosticOnly') is True,'limited salience diagnosis required')
        for name in expected_names:
            original=json.loads(originals[name]['messages'][1]['content'])
            now=copy.deepcopy(cases[name])
            if name=='B':
                original_evidence=original['candidates'][0]['proposedEvidence']
                prefix=now['candidates'][0]['proposedEvidence'][:2]
                allowed={r['id']:r['text'] for r in original['raw']}
                require(len(prefix)==2 and len({q['segmentId'] for q in prefix})==2
                        and all(q['segmentId'] in original['candidates'][0]['segmentIds']
                                and q['quote']==allowed[q['segmentId']] for q in prefix)
                        and now['candidates'][0]['proposedEvidence'][2:]==original_evidence,
                        'two original allowed quotes only')
                now['candidates'][0]['proposedEvidence']=original_evidence
            require(now==original,'salience probe cannot change raw, labels, anchor or hypothesis')
            original_body=copy.deepcopy(originals[name])
            original_body['messages'][1]=copy.deepcopy(artifact['comparisonBaselines'][name]['messages'][1])
            require(original_body==artifact['comparisonBaselines'][name],'system/settings/reference changed')
    require(set(cases)==expected_names,'fixed regression set required')
    cap=artifact.get('maxCompletionTokens',4000)
    require(cap in (4000,8000) and artifact.get('requestTimeoutSeconds',55) in (55,90),'bounded completion and timeout required')
    indexed={(r['case'],r['arm'],r['repeat']):r for r in artifact['requests']}
    require(set(indexed)=={(name,arm,i) for name in cases for arm in arms for i in range(1,n+1)}
            and len(indexed)==len(artifact['requests']), 'unique complete paired requests required')
    for name,payload in cases.items():
        base=artifact['comparisonBaselines'][name] if design=='CANDIDATE_ONLY_REGRESSION' else indexed[name,'BASELINE',1]['body']
        require(json.loads(base['messages'][1]['content'])==payload,'frozen raw mismatch')
        require(base['max_completion_tokens']==cap,'frozen completion limit required')
        require((base['reasoning_effort']=='none' and base.get('temperature')==.1)
                or (base['reasoning_effort']=='low' and 'temperature' not in base),'baseline setting required')
        candidate=candidate_body(base,artifact.get('variant','READING_ORDER'))
        for i in range(1,n+1):
            adapter='single-target-evidence-wire-1' if artifact.get('variant') in {'TARGET_WIRE','PROPOSAL_SALIENCE'} else None
            require((design=='CANDIDATE_ONLY_REGRESSION' or (indexed[name,'BASELINE',i]['body']==base
                    and indexed[name,'BASELINE',i].get('outputAdapter') is None))
                    and indexed[name,'CANDIDATE',i]['body']==candidate
                    and indexed[name,'CANDIDATE',i].get('outputAdapter')==adapter,
                    'only isolated variant may differ')


def prepare_salience(source, authorization, prior_reports, support_plan):
    import json
    validate(source)
    require(source['artifactHash']==digest({k:v for k,v in source.items() if k!='artifactHash'})
            and source['variant']=='TARGET_WIRE' and source.get('ownerControls')
            and source['authorization']==authorization and len(prior_reports)==5,'expanded source and complete ledger required')
    require(all(r.get('authorizationHash')==digest(authorization)
                and r['status'] in {'PARTIAL','COMPLETED_PENDING_JAVA_AUDIT'} for r in prior_reports), 'same budget required')
    ledger=[dict(reportHash=digest(r),reservedCostUsd=r['reservedCostUsd']) for r in prior_reports]
    remaining=Decimal('1.00')-sum(Decimal(r['reservedCostUsd']) for r in ledger)
    result=copy.deepcopy(source)
    result['cases']=[c for c in result['cases'] if c['name'] in {'B','N5','N6'}]
    b=next(c for c in result['cases'] if c['name']=='B')['payload']
    # This manual selection is an explicitly benchmark-scoped diagnostic, not a deployed selector.
    raw={r['id']:r['text'] for r in b['raw']}
    require(support_plan.get('schemaVersion')=='candidate-support-salience-probe-1'
            and support_plan.get('sourceArtifactHash')==source['artifactHash']
            and support_plan.get('diagnosticOnly') is True and support_plan.get('runtimeUseAuthorized') is False,
            'private diagnostic support plan required')
    selected=copy.deepcopy(support_plan['quotes'])
    require(len(selected)==2 and len({q['segmentId'] for q in selected})==2
            and all(set(q)=={'segmentId','quote'} and q['segmentId'] in b['candidates'][0]['segmentIds']
                    and q['quote']==raw[q['segmentId']] for q in selected),'two allowed raw quotes required')
    b['candidates'][0]['proposedEvidence']=selected+b['candidates'][0]['proposedEvidence']
    result['probeOriginalBodies']={}
    result['comparisonBaselines']={}
    requests=[]
    for row in source['requests']:
        if row['case'] not in {'B','N5','N6'}: continue
        result['probeOriginalBodies'][row['case']]=copy.deepcopy(row['body'])
        new=copy.deepcopy(row)
        payload=next(c['payload'] for c in result['cases'] if c['name']==row['case'])
        if row['case']=='B': new['body']['messages'][1]['content']=json.dumps(payload,ensure_ascii=False,separators=(',',':'))
        new['requestHash']=digest(new['body'])
        tokens,cost=reserve(new['body'],result['config'])
        new.update(reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))
        requests.append(new)
        result['comparisonBaselines'][row['case']]=copy.deepcopy(new['body'])
    total=sum(Decimal(r['reservedCostUsd']) for r in requests)
    require(total<=remaining,'remaining reservation insufficient for probe')
    result.update(variant='PROPOSAL_SALIENCE',diagnosticOnly=True,sourceArtifactHash=source['artifactHash'],
                  sourceArm='CANDIDATE',priorReservationLedger=ledger,remainingBudgetUsd=str(remaining),
                  requests=requests,plannedCalls=len(requests),reservedTotalCostUsd=str(total),
                  developmentGate=dict(reviewCases=['B'],normalCases=['N5','N6'],
                                       allRepetitionsMustMatchAndPassContract=True,independentGoldApprovals=0))
    result['artifactHash']=digest({k:v for k,v in result.items() if k!='artifactHash'})
    validate(result)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',required=True); p.add_argument('--authorization',required=True)
    p.add_argument('--prior-report',action='append',default=[]); p.add_argument('--output',required=True)
    p.add_argument('--repetitions',type=int,default=3)
    p.add_argument('--variant',choices=['READING_ORDER','REASONING_LOW','TARGET_WIRE','PROPOSAL_SALIENCE'],default='READING_ORDER')
    p.add_argument('--source-arm',choices=['BASELINE','CANDIDATE'],default='BASELINE')
    p.add_argument('--owner-controls'); p.add_argument('--candidate-only',action='store_true')
    p.add_argument('--salience-support-plan')
    p.add_argument('--max-completion-tokens',type=int,choices=[4000,8000],default=4000)
    a=p.parse_args(); out=Path(a.output).resolve()
    require('datasets' in out.parts and not out.exists(),'new private output required')
    require(a.variant!='PROPOSAL_SALIENCE' or a.salience_support_plan,'diagnostic support plan required')
    value=prepare_salience(read(a.source),read(a.authorization),[read(r) for r in a.prior_report],read(a.salience_support_plan)) if a.variant=='PROPOSAL_SALIENCE' else prepare(
        read(a.source),read(a.authorization),[read(r) for r in a.prior_report],a.repetitions,a.variant,a.source_arm,
        read(a.owner_controls) if a.owner_controls else None,a.candidate_only,a.max_completion_tokens)
    validate(value); write_private(out,value)
    print({'plannedCalls':value['plannedCalls'],'reservedCostUsd':value['reservedTotalCostUsd'],'executed':False})
