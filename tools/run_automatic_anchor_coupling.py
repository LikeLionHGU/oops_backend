"""Automatic PRIMARY within fixed selected quotes followed by frozen judgment."""
import copy
import json
from decimal import Decimal
from fixed_review_suite import read,digest,require
from private_report_bundle import load
from experiment_costs import reserve,positive
from automatic_anchor_diagnostic import request,accept
from bound_review_contract import prepare_attempt,receive,parse_response
from reanchor_incident_scope import reanchor
from stabilize_incident_reference import ROOT


def source_inputs():
    from run_incident_reference_comparison import score
    from confirm_incident_stability import gate
    p=ROOT/'source-faithfulness-confirmation-20261013';f=read(p/'frozen.json')
    s=score(f,load(p/'live-run/report-index.json'),read(p/'java-audit.json'))
    require(s==read(p/'score.json') and gate(s),'audited faithful confirmation required')
    return f


def options(base,scope):
    result=[]
    for sid in [None,*dict.fromkeys(e['segmentId'] for e in scope['evidence'] if e['role']!='TARGET')]:
        try:result.append((sid,accept(dict(selectedSegmentId=sid,reason='reservation preview'),base,scope)))
        except ValueError:continue
    return result


def freeze(source,available):
    available=positive(available);require(available<=Decimal(source['remainingBudgetUsd']),'no budget expansion')
    jobs=[];cases=copy.deepcopy(source['source']['cases'])
    for old in source['jobs']:
        base=copy.deepcopy(old['baseBody']);scope=copy.deepcopy(old['fixedSelectedScope'])
        # Recorded old B anchor: diagnostic starting point, never an asserted live-server state.
        if old['case']=='F2':base,scope=reanchor(base,scope,'stt-2240')
        locate=request(base,scope);t,cost=reserve(locate,source['config'])
        stages=[dict(stage='LOCATE',body=locate,requestHash=digest(locate),reservedInputTokensHeuristic=t,reservedCostUsd=str(cost))]
        alternatives=[];hashes=[]
        for _,chosen in options(base,scope):
            body=prepare_attempt(chosen['baseBody'],chosen['scope'],'preview',judgment_variant='SOURCE_FAITHFUL_CLOSED_SCOPE')['requestBody']
            tokens,cost=reserve(body,source['config']);alternatives.append((tokens,cost,body));hashes.append(digest(body))
        tokens,cost,largest=max(alternatives,key=lambda x:x[0])
        stages.append(dict(stage='JUDGE',body=largest,requestHash=digest(largest),reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost)))
        jobs.append(dict(case=old['case'],arm='CANDIDATE',repeat=old['repeat'],baseBody=base,baseRequestHash=digest(base),
            fixedSelectedScope=scope,judgmentVariant='SOURCE_FAITHFUL_CLOSED_SCOPE',allowedJudgeRequestHashes=sorted(set(hashes)),stages=stages))
        next(c for c in cases if c['name']==old['case'])['payload']=json.loads(base['messages'][1]['content'])
    total=sum(Decimal(s['reservedCostUsd']) for j in jobs for s in j['stages'])
    require(len(jobs)==27 and total<=available,'full coupling trial exceeds remaining approval')
    result=copy.deepcopy(source)
    for key in ('artifactHash','incidentReferenceStudy','stabilizationStep','confirmationParentStep','sourceScoreHash'):result.pop(key,None)
    result.update(anchorCouplingStudy='MASKED_PRIMARY_COUPLING_1',sourceTrialHash=digest(source),source=dict(cases=cases),
        jobs=jobs,previewHash=digest(jobs),plannedUnits=27,maxApiCalls=54,availableBudgetUsd=str(available),
        reservedCostUsd=str(total),remainingBudgetUsd=str(available-total),runtimeChanged=False,qualityGateMet=None,
        semanticQualityGateMet=None,automaticDiscoveryEvaluated=False,
        evaluationScope='MASKED_PRIMARY_IN_FIXED_SELECTED_QUOTES_NOT_FULL_DISCOVERY')
    result['artifactHash']=digest(result);return result


def prepare(available):return freeze(source_inputs(),available)
def validate(frozen):require(frozen==prepare(frozen['availableBudgetUsd']),'coupling recipe changed')


def score(frozen,report,audit):
    from compare_recorded_candidates import audit_export
    from compare_review_prompts import digest as audit_digest
    validate(frozen)
    exported=audit_export(report);exported.update(trialSchemaVersion='recorded-flow-regression-1',repetitionsPerCondition=3)
    require(report['runKind'] in {'LIVE','MOCK_OFFLINE'} and report['artifactHash']==frozen['artifactHash']
        and report['authorizationHash']==frozen['authorizationHash']
        and report['previewHash']==frozen['previewHash']
        and report['reservedCostUsd']==frozen['reservedCostUsd']
        and report['status'] in {'COMPLETED_PENDING_JAVA_AUDIT','MOCK_COMPLETE_PENDING_JAVA_AUDIT'}
        and report['cases']==frozen['source']['cases'] and report['anchorCouplingStudy']==frozen['anchorCouplingStudy']
        and len(report['calls'])==27 and audit['sourceReportSha256']==audit_digest(exported),'complete associated audit required')
    indexed={(a['case'],a['arm'],a['repeat']):a for a in audit['results']}
    require(len(indexed)==len(audit['results'])==27 and set(indexed)=={
        (j['case'],j['arm'],j['repeat']) for j in frozen['jobs']},'unique full Java audit required')
    rows=[];cost_total=Decimal(0);live=report['runKind']=='LIVE'
    for i,(job,u) in enumerate(zip(frozen['jobs'],report['calls'],strict=True),1):
        require((u['unitIndex'],u['case'],u['arm'],u['repeat'])==(i,job['case'],job['arm'],job['repeat'])
            and len(u['stages'])==2 and u['requestHash']==job['baseRequestHash'],'ordered complete two-stage units required')
        locate,judge=u['stages'];require(locate['stage']=='LOCATE' and judge['stage']=='JUDGE'
            and locate['body']==job['stages'][0]['body'],'locator input changed')
        chosen=accept(parse_response(locate['providerResponse'],locate['body']['model']),job['baseBody'],job['fixedSelectedScope'])
        ticket=prepare_attempt(chosen['baseBody'],chosen['scope'],f'{frozen["artifactHash"]}-{i:03d}',judgment_variant=job['judgmentVariant'])
        require(judge['body']==ticket['requestBody'] and digest(judge['body']) in job['allowedJudgeRequestHashes']
            and judge['localAttempt']==ticket and u['selectedScope']==chosen['scope'] and u['anchorAction']==chosen['status'],
            'automatic choice/request association changed')
        payload=json.loads(chosen['baseBody']['messages'][1]['content']);cand=payload['candidates'][0]
        require(u['actualCandidateProposal']==dict(axis=cand['axis'],anchorId=cand['anchorId'],reason=cand['hypothesisNotEvidence'],
            evidence=cand['proposedEvidence']),'Java proposal does not match actual judged candidate')
        for stage,cap in zip(u['stages'],job['stages'],strict=True):
            parsed=parse_response(stage['providerResponse'],stage['body']['model'])
            require(parsed==stage['rawOutput'],'stored raw output changed')
            usage=stage['providerResponse']['usage'];require(usage==stage['usage']
                and all(type(usage.get(k)) is int and usage[k]>=0 for k in ('prompt_tokens','completion_tokens'))
                and usage['completion_tokens']<=stage['body']['max_completion_tokens']
                and usage['prompt_tokens']<=cap['reservedInputTokensHeuristic'] and stage['requestHash']==digest(stage['body']), 'usage/body changed')
            p=frozen['config']['pricing'];cost=(Decimal(usage['prompt_tokens'])*positive(p['inputUsdPerMillion'])+Decimal(usage['completion_tokens'])*positive(p['outputUsdPerMillion']))/1000000
            require(cost==Decimal(stage['estimatedCostUsd']) and cost<=Decimal(cap['reservedCostUsd']),'usage cost changed');cost_total+=cost
        a=indexed[(job['case'],job['arm'],job['repeat'])];assessment=None
        try:receipt=receive(ticket,judge['body'],judge['providerResponse'])
        except (ValueError,KeyError,TypeError) as error:
            require(u.get('contractError')==str(error) and not a['accepted'] and 'output' not in u,'contract rejection changed')
        else:
            require(judge['boundReceipt']==receipt and u['output']==receipt['compatiblePacket']['compatibleAssessment'],'judgment receipt changed')
            assessment=receipt['modelOutput']['assessment'];require(a.get('decision')==assessment['decision']
                and 'contractError' not in u and u['status']=='CONTRACT_ONLY','Java decision changed')
        expected=next(c['developmentExpectation'] for c in frozen['source']['cases'] if c['name']==job['case'])
        rows.append(dict(case=job['case'],repeat=job['repeat'],accepted=a['accepted'],anchorAction=chosen['status'],
            selectedAnchor=cand['anchorId'],assessment=assessment,matchedDevelopmentDecision=(bool(a['accepted'] and assessment and assessment['decision']==expected) if live else None),
            semanticIssueMatched=None))
    require(report['apiCalls']==(54 if live else 0) and report['unknownCostAttempts']==0
        and report['simulatedCalls']==(0 if live else 54)
        and report['consumedReservationUsd']==(frozen['reservedCostUsd'] if live else '0')
        and Decimal(report['estimatedCostUsd'] if live else report['mockTokenRateEquivalentUsd'])==cost_total,'accounting changed')
    return dict(schemaVersion='anchor-coupling-score-1',results=rows,runKind=report['runKind'],
        mechanicalContractGateMet=all(r['accepted'] for r in rows),semanticApprovalPending=True,
        automaticDiscoveryEvaluated=False,fullPipelineReplayed=False,runtimeUseAuthorized=False,
        estimatedUsageCostUsd=str(cost_total) if live else None,actualBilledCostUsd=None)
