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
from run_issue_scoped_comparison import validate,CATALOG_SCHEMA,BOUND_SCHEMA


def score(frozen, report, audit):
    validate(frozen)
    is_bound = frozen['schemaVersion'] == BOUND_SCHEMA
    is_mock = is_bound and report.get('runKind') == 'MOCK_OFFLINE'
    if is_bound:
        require(report.get('additionalFundingHash') == frozen.get('additionalFundingHash'), 'funding provenance mismatch')
        require(report.get('runKind') in {'MOCK_OFFLINE', 'LIVE'}, 'bound execution kind required')
        stage_count = sum(len(c['stages']) for c in report['calls'])
        require(report['apiCalls'] == (0 if is_mock else stage_count)
                and report['simulatedCalls'] == (stage_count if is_mock else 0)
                and report['consumedReservationUsd'] == ('0' if is_mock else frozen['reservedCostUsd']),
                'mock/live accounting mismatch')
        require(is_mock or frozen['executionEnabled'], 'unfunded live report forbidden')
    require(report['artifactHash']==frozen['artifactHash']
            and report['authorizationHash']==frozen['authorizationHash']
            and report['status']==('MOCK_COMPLETE_PENDING_JAVA_AUDIT' if is_mock else 'COMPLETED_PENDING_JAVA_AUDIT')
            and report['cases']==frozen.get('evaluatedCases', frozen['source']['cases']) and len(report['calls'])==frozen['plannedUnits'],
            'complete unchanged report required')
    exported=audit_export(json.loads(json.dumps(report,ensure_ascii=False)))
    exported.update(trialSchemaVersion='recorded-flow-regression-1',repetitionsPerCondition=3)
    require(audit['sourceReportSha256']==audit_digest(exported), 'current Java audit provenance mismatch')
    indexed={(r['case'],r['arm'],r['repeat']):r for r in audit['results']}
    expected={(r['case'],r['arm'],r['repeat']) for r in frozen['jobs']}
    require(set(indexed)==expected and len(indexed)==len(audit['results']), 'unique complete Java audits required')
    cases={r['name']:r['payload'] for r in frozen['source']['cases']}
    is_catalog=frozen['schemaVersion'] in {CATALOG_SCHEMA, BOUND_SCHEMA}
    if is_catalog:
        from span_catalog_review import hydrate as hydrate_scope,judgment_request as judge_request
        if is_bound:
            from bound_review_contract import judgment_request as judge_request, receive, prepare_attempt, parse_response
    else:
        judge_request=judgment_request
    rows=[]
    for index, (job,call) in enumerate(zip(frozen['jobs'],report['calls']), 1):
        require(all(call[k]==job[k] for k in ('case','arm','repeat'))
                and call['requestHash']==job['baseRequestHash'] and 'error' not in call, 'report order or request mismatch')
        require(len(call['stages']) in (1,2) and len(call['stages'])<=len(job['stages']), 'unexpected stages')
        payload=cases[call['case']]
        for i,s in enumerate(call['stages']):
            planned=job['stages'][i]
            body=judge_request(job['baseBody'],call['selectedScope']) if s['stage']=='JUDGE' else planned['body']
            require(s['stage']==planned['stage'] and s['body']==body and s['requestHash']==digest(body)
                    and 'providerResponse' in s and 'error' not in s, 'stage provenance changed')
            if is_bound:
                require(s['rawOutput'] == parse_response(s['providerResponse'], body['model']), 'bound raw response changed')
                if s['stage'] == 'JUDGE':
                    require(s['localAttempt'] == prepare_attempt(job['baseBody'], call['selectedScope'],
                            f'{frozen["artifactHash"]}-{index:03d}'), 'local attempt assigned to wrong unit')
        if 'output' in call:
            if call['arm']=='CANDIDATE':
                require(len(call['stages'])==2, 'candidate missing judgment')
                validate_scope(call['selectedScope'],payload)
                if is_catalog:require(hydrate_scope(call['stages'][0]['rawOutput'],payload)==call['selectedScope'],'catalog selection changed')
                if is_bound:
                    stage = call['stages'][1]
                    receipt = receive(stage['localAttempt'], stage['body'], stage['providerResponse'])
                    require(receipt == stage['boundReceipt'] and stage['rawOutput'] == receipt['modelOutput'], 'bound response changed')
                    packet = receipt['compatiblePacket']
                else:
                    packet=review_packet(call['stages'][1]['rawOutput'],call['selectedScope'],payload)
                require(call['reviewPacket']==packet and call['output']==packet['compatibleAssessment'], 'derived output changed')
            else:
                original=dict(providerResponse=call['stages'][0]['providerResponse'],outputAdapter='single-target-evidence-wire-1')
                checked_output(original,payload)
                require(original.get('output')==call['output'], 'baseline response changed')
        else:
            require(call.get('wireAdaptationError'), 'missing output without contract failure')
            if is_bound:
                try:
                    if len(call['stages']) == 1:
                        hydrate_scope(call['stages'][0]['rawOutput'], payload)
                    else:
                        s = call['stages'][1]
                        receive(s['localAttempt'], s['body'], s['providerResponse'])
                except (ValueError, KeyError, TypeError) as error:
                    require(call['contractError'] == str(error), 'bound rejection reason changed')
                else:
                    raise ValueError('bound valid response cannot be recorded as rejected')
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
    result = dict(schemaVersion='issue-scoped-comparison-score-1',frozenHash=frozen['artifactHash'],
                reportHash=digest(report),auditHash=digest(audit),summary=summary,results=rows,armCosts=arm_costs,
                developmentRegressionGateMet=all(r['matched'] for r in rows if r['arm']=='CANDIDATE'),
                semanticQualityGateMet=None,runtimeUseAuthorized=False,independentGoldApprovals=0,
                fullPipelineReplayed=False,responsesModified=False)
    if is_mock:
        result.update(runKind='MOCK_OFFLINE', developmentRegressionGateMet=None,
                      mockContractGateMet=all(r['accepted'] for r in rows),
                      apiCalls=0, simulatedCalls=report['simulatedCalls'], consumedReservationUsd='0')
        for r in result['results']:
            r['matched'] = None
            r['simulatedCalls'] = r.pop('apiCalls')
            r['mockTokenRateEquivalentUsd'] = r.pop('estimatedCostUsd')
        for s in result['summary']: s['matched'] = None
        for cost in result['armCosts']:
            cost['simulatedCalls'] = cost.pop('apiCalls')
            cost['mockTokenRateEquivalentUsd'] = cost.pop('estimatedCostUsd')
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for flag in ('frozen','report','audit','output'): p.add_argument('--'+flag,required=True)
    a=p.parse_args()
    result=score(read(a.frozen),read(a.report),read(a.audit))
    write_private(Path(a.output).resolve(),result)
    print({'developmentRegressionGateMet':result['developmentRegressionGateMet'],
           'semanticQualityGateMet':None,'runtimeUseAuthorized':False})
