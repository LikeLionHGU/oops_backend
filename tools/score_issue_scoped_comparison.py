"""Score only complete, provenance-matched two-stage comparisons, not semantics."""
import argparse
from collections import Counter
from decimal import Decimal
import json
from pathlib import Path

from compare_recorded_candidates import audit_export, checked_output
from compare_review_prompts import digest as audit_digest
from fixed_review_suite import digest, read, require, write_private
from issue_scoped_review import judgment_request, review_packet, validate_scope
from run_issue_scoped_comparison import validate,CATALOG_SCHEMA


def score(frozen, report, audit):
    validate(frozen)
    require(report['artifactHash']==frozen['artifactHash']
            and report['authorizationHash']==frozen['authorizationHash']
            and report['status']=='COMPLETED_PENDING_JAVA_AUDIT'
            and report['cases']==frozen['source']['cases'] and len(report['calls'])==frozen['plannedUnits'],
            'complete unchanged report required')
    exported=audit_export(json.loads(json.dumps(report,ensure_ascii=False)))
    exported.update(trialSchemaVersion='recorded-flow-regression-1',repetitionsPerCondition=3)
    require(audit['sourceReportSha256']==audit_digest(exported), 'current Java audit provenance mismatch')
    indexed={(r['case'],r['arm'],r['repeat']):r for r in audit['results']}
    expected={(r['case'],r['arm'],r['repeat']) for r in frozen['jobs']}
    require(set(indexed)==expected and len(indexed)==len(audit['results']), 'unique complete Java audits required')
    cases={r['name']:r['payload'] for r in frozen['source']['cases']}
    is_catalog=frozen['schemaVersion']==CATALOG_SCHEMA
    if is_catalog:
        from span_catalog_review import hydrate as hydrate_scope,judgment_request as judge_request
    else:
        judge_request=judgment_request
    rows=[]
    for job,call in zip(frozen['jobs'],report['calls']):
        require(all(call[k]==job[k] for k in ('case','arm','repeat'))
                and call['requestHash']==job['baseRequestHash'] and 'error' not in call, 'report order or request mismatch')
        require(len(call['stages']) in (1,2) and len(call['stages'])<=len(job['stages']), 'unexpected stages')
        payload=cases[call['case']]
        for i,s in enumerate(call['stages']):
            planned=job['stages'][i]
            body=judge_request(job['baseBody'],call['selectedScope']) if s['stage']=='JUDGE' else planned['body']
            require(s['stage']==planned['stage'] and s['body']==body and s['requestHash']==digest(body)
                    and 'providerResponse' in s and 'error' not in s, 'stage provenance changed')
        if 'output' in call:
            if call['arm']=='CANDIDATE':
                require(len(call['stages'])==2, 'candidate missing judgment')
                validate_scope(call['selectedScope'],payload)
                if is_catalog:require(hydrate_scope(call['stages'][0]['rawOutput'],payload)==call['selectedScope'],'catalog selection changed')
                packet=review_packet(call['stages'][1]['rawOutput'],call['selectedScope'],payload)
                require(call['reviewPacket']==packet and call['output']==packet['compatibleAssessment'], 'derived output changed')
            else:
                original=dict(providerResponse=call['stages'][0]['providerResponse'],outputAdapter='single-target-evidence-wire-1')
                checked_output(original,payload)
                require(original.get('output')==call['output'], 'baseline response changed')
        else:
            require(call.get('wireAdaptationError'), 'missing output without contract failure')
        a=indexed[call['case'],call['arm'],call['repeat']]
        wanted='REVIEW_REQUIRED' if call['case'] in {'B','C','D'} else 'PASS'
        rows.append(dict(case=call['case'],arm=call['arm'],repeat=call['repeat'],decision=a.get('decision'),
                         accepted=a['accepted'],failureCode=a.get('failureCode'),
                         developmentExpectedDecision=wanted,
                         matched=a['accepted'] and a.get('decision')==wanted,
                         apiCalls=len(call['stages']),latencyMs=call['latencyMs'],
                         estimatedCostUsd=str(sum(Decimal(s['estimatedCostUsd']) for s in call['stages'])),
                         semanticIssueMatched=None))
    summary=[]
    for arm in ('BASELINE','CANDIDATE'):
        for c in frozen['source']['cases']:
            g=[r for r in rows if r['arm']==arm and r['case']==c['name']]
            if not g:continue
            summary.append(dict(arm=arm,case=c['name'],observations=len(g),
                                decisions=dict(Counter(r['decision'] for r in g)),accepted=sum(r['accepted'] for r in g),
                                matched=sum(r['matched'] for r in g)))
    arm_costs=[]
    for arm in ('BASELINE','CANDIDATE'):
        units=[c for c in report['calls'] if c['arm']==arm]
        if not units:continue
        stages=[s for c in units for s in c['stages']]
        arm_costs.append(dict(arm=arm,units=len(units),apiCalls=len(stages),
                             promptTokens=sum(s['usage']['prompt_tokens'] for s in stages),
                             completionTokens=sum(s['usage']['completion_tokens'] for s in stages),
                             estimatedCostUsd=str(sum(Decimal(s['estimatedCostUsd']) for s in stages)),
                             meanUnitLatencyMs=round(sum(c['latencyMs'] for c in units)/len(units))))
    return dict(schemaVersion='issue-scoped-comparison-score-1',frozenHash=frozen['artifactHash'],
                reportHash=digest(report),auditHash=digest(audit),summary=summary,results=rows,armCosts=arm_costs,
                developmentRegressionGateMet=all(r['matched'] for r in rows if r['arm']=='CANDIDATE'),
                semanticQualityGateMet=None,runtimeUseAuthorized=False,independentGoldApprovals=0,
                fullPipelineReplayed=False,responsesModified=False)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for flag in ('frozen','report','audit','output'): p.add_argument('--'+flag,required=True)
    a=p.parse_args()
    result=score(read(a.frozen),read(a.report),read(a.audit))
    write_private(Path(a.output).resolve(),result)
    print({'developmentRegressionGateMet':result['developmentRegressionGateMet'],
           'semanticQualityGateMet':None,'runtimeUseAuthorized':False})
