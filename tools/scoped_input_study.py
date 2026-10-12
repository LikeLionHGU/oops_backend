"""OFFLINE-only study of versioned scope views; no paid authorization path."""
import argparse
import copy
from decimal import Decimal
import json
from pathlib import Path

from bound_review_contract import prepare_attempt,receive,parse_response
from compare_recorded_candidates import audit_export
from compare_review_prompts import digest as audit_digest
from fixed_review_suite import digest,read,require,write_private
from run_boundary_comparison import reserve
from run_issue_scoped_comparison import BOUND_SCHEMA
from scoped_judgment_contract import judgment_request


def prepare(source):
    from run_issue_scoped_comparison import validate as validate_source
    require(source.get('fixedScopeStudy')=='JUDGE_ONLY_DIAGNOSTIC_1' and 'scopedInputStudy' not in source,
            'observed fixed-scope source required')
    validate_source(source)
    jobs=[]
    for old in source['jobs']:
        for mode in ('RAW_REMOVED','CLOSED_SCOPE'):
            job=copy.deepcopy(old)
            body=judgment_request(job['baseBody'],job['fixedSelectedScope'],mode)
            tokens,cost=reserve(body,source['config'])
            job.update(arm=mode,judgmentVariant=mode,stages=[dict(stage='JUDGE',body=body,requestHash=digest(body),
                reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))])
            jobs.append(job)
    planned=sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    authorization=dict(schemaVersion='scoped-input-offline-1',userAuthorized=False,additionalBudgetUsd='0')
    result=dict(schemaVersion=BOUND_SCHEMA,scopedInputStudy='OFFLINE_VIEWS_1',sourceFrozen=copy.deepcopy(source),
        sourceFrozenHash=source['artifactHash'],source=copy.deepcopy(source['source']),config=copy.deepcopy(source['config']),
        authorization=authorization,authorizationHash=digest(authorization),jobs=jobs,plannedUnits=24,maxApiCalls=24,
        repetitions=3,requestTimeoutSeconds=90,maxConcurrency=3,executionEnabled=False,budgetSufficient=False,
        reservedCostUsd='0',plannedReservationUsd=str(planned),remainingBudgetUsd='0',paidAuthorizationGranted=False,
        selectionReplayed=False,fullPipelineReplayed=False,runtimeChanged=False,qualityGateMet=None)
    result['artifactHash']=digest(result)
    return result


def validate(frozen):
    require(frozen==prepare(frozen['sourceFrozen']), 'scoped offline study changed')


def score(frozen,report,audit):
    validate(frozen)
    require(report.get('runKind')=='MOCK_OFFLINE' and report['status']=='MOCK_COMPLETE_PENDING_JAVA_AUDIT'
        and report['artifactHash']==frozen['artifactHash'] and report['authorizationHash']==frozen['authorizationHash']
        and report['cases']==frozen['source']['cases'] and len(report['calls'])==24
        and report['apiCalls']==0 and report['simulatedCalls']==24 and report['consumedReservationUsd']=='0'
        and report['reservedCostUsd']=='0' and report['estimatedCostUsd']=='0' and report['unknownCostAttempts']==0,
        'complete offline report only; no paid scoring')
    exported=audit_export(json.loads(json.dumps(report,ensure_ascii=False)))
    exported.update(trialSchemaVersion='recorded-flow-regression-1',repetitionsPerCondition=3)
    require(audit['sourceReportSha256']==audit_digest(exported), 'offline Java audit mismatch')
    indexed={(a['case'],a['arm'],a['repeat']):a for a in audit['results']}
    require(len(indexed)==24 and len(audit['results'])==24
        and set(indexed)=={(j['case'],j['arm'],j['repeat']) for j in frozen['jobs']},'complete unique audit required')
    rows=[]
    for index,(job,unit) in enumerate(zip(frozen['jobs'],report['calls']),1):
        require(all(unit[k]==job[k] for k in ('case','arm','repeat')) and unit['unitIndex']==index
            and unit['requestHash']==job['baseRequestHash'] and unit['selectedScope']==job['fixedSelectedScope']
            and len(unit['stages'])==1 and 'error' not in unit,'scoped unit changed')
        stage=unit['stages'][0];body=job['stages'][0]['body']
        ticket=prepare_attempt(job['baseBody'],job['fixedSelectedScope'],f'{frozen["artifactHash"]}-{index:03d}',
                               judgment_variant=job['judgmentVariant'])
        require(stage['stage']=='JUDGE' and stage['body']==body and stage['requestHash']==digest(body)
            and stage['localAttempt']==ticket and 'error' not in stage
            and stage['rawOutput']==parse_response(stage['providerResponse'],body['model']),'scoped attempt changed')
        try:
            receipt=receive(ticket,body,stage['providerResponse'])
        except (ValueError,KeyError,TypeError) as error:
            require(unit.get('contractError')==str(error) and unit['status']=='JUDGMENT_REJECTED'
                and 'output' not in unit,'scoped rejection changed')
        else:
            require(receipt==stage['boundReceipt'] and unit['reviewPacket']==receipt['compatiblePacket']
                and unit['output']==receipt['compatiblePacket']['compatibleAssessment'],'scoped receipt changed')
        a=indexed[job['case'],job['arm'],job['repeat']]
        rows.append(dict(case=job['case'],mode=job['arm'],repeat=job['repeat'],accepted=a['accepted'],
            mockDecision=a.get('decision'),semanticIssueMatched=None))
    return dict(schemaVersion='scoped-input-offline-score-1',frozenHash=frozen['artifactHash'],reportHash=digest(report),
        auditHash=digest(audit),results=rows,mockContractGateMet=all(r['accepted'] for r in rows),
        apiCalls=0,simulatedCalls=24,consumedReservationUsd='0',developmentRegressionGateMet=None,
        semanticQualityGateMet=None,runtimeUseAuthorized=False,responsesModified=False,fullPipelineReplayed=False)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('frozen','report','audit','output'):p.add_argument('--'+name,required=True)
    a=p.parse_args();s=score(read(a.frozen),read(a.report),read(a.audit))
    write_private(Path(a.output).resolve(),s)
    print(dict(mockContractGateMet=s['mockContractGateMet'],apiCalls=0,semanticQualityGateMet=None))
