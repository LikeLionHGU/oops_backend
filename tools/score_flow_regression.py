"""Score saved paired development regressions; never labels semantics or deploys."""
import argparse
import copy
from collections import Counter
from pathlib import Path

from fixed_review_suite import digest, read, require, write_private
from prepare_flow_regression import validate


def score(frozen, report, audit):
    from compare_recorded_candidates import audit_export
    from compare_review_prompts import digest as audit_digest
    validate(frozen)
    require(frozen['artifactHash']==digest({k:v for k,v in frozen.items() if k!='artifactHash'})
            and report['artifactHash']==frozen['artifactHash']
            and report.get('trialSchemaVersion')==frozen['schemaVersion']
            and report.get('authorizationHash')==frozen['authorizationHash']
            and report['status']=='COMPLETED_PENDING_JAVA_AUDIT'
            and len(report['calls'])==frozen['plannedCalls'], 'complete frozen report required')
    require(audit['sourceReportSha256']==audit_digest(audit_export(report)), 'audit provenance mismatch')
    audits={(r['case'],r['arm'],r['repeat']):r for r in audit['results']}
    expected={(r['case'],r['arm'],r['repeat']) for r in frozen['requests']}
    require(set(audits)==expected and len(audits)==len(audit['results']), 'complete unique audits required')
    rows=[]
    for request,call in zip(frozen['requests'],report['calls']):
        require(all(request[k]==call[k] for k in ('case','arm','repeat','requestHash'))
                and request['requestHash']==digest(request['body'])
                and request.get('outputAdapter')==call.get('outputAdapter'), 'call order or provenance changed')
        a=audits[call['case'],call['arm'],call['repeat']]
        wanted='REVIEW_REQUIRED' if call['case'] in {'B','C','D'} else 'PASS'
        rows.append(dict(case=call['case'],arm=call['arm'],repeat=call['repeat'],
                         developmentExpectedDecision=wanted,decision=a.get('decision'),
                         accepted=a['accepted'],failureCode=a.get('failureCode'),
                         matchesDevelopmentExpectationAndContract=a['accepted'] and a.get('decision')==wanted))
    summary=[]
    for arm in sorted({r['arm'] for r in rows}):
        for name in [c['name'] for c in frozen['cases']]:
            group=[r for r in rows if r['arm']==arm and r['case']==name]
            summary.append(dict(arm=arm,case=name,observations=len(group),
                                decisions=dict(Counter(r['decision'] for r in group)),
                                accepted=sum(r['accepted'] for r in group),
                                matched=sum(r['matchesDevelopmentExpectationAndContract'] for r in group)))
    candidate=[r for r in rows if r['arm']=='CANDIDATE']
    return dict(schemaVersion='flow-regression-score-1',frozenHash=frozen['artifactHash'],
                trialDesign=frozen.get('trialDesign','PAIRED_COMPARISON'),
                diagnosticOnly=frozen.get('diagnosticOnly',False),
                sourceReportHash=digest(report),sourceAuditHash=digest(audit),summary=summary,results=rows,
                developmentRegressionGateMet=all(r['matchesDevelopmentExpectationAndContract'] for r in candidate),
                semanticQualityGateMet=None,independentGoldApprovals=0,runtimeUseAuthorized=False,
                fullPipelineReplayed=False,responsesModified=False)


def compare_requests(left,right,case='B',left_arm='CANDIDATE',right_arm='BASELINE'):
    """Prove identical provider inputs without claiming identical provider behavior."""
    for a in (left,right):
        require(a['artifactHash']==digest({k:v for k,v in a.items() if k!='artifactHash'}),'source changed')
    def selected(a,arm):
        rows=[r for r in a['requests'] if r['case']==case and r['arm']==arm]
        require(rows and all(r['body']==rows[0]['body'] for r in rows),'consistent source requests required')
        return copy.deepcopy(rows[0]['body'])
    a,b=selected(left,left_arm),selected(right,right_arm)
    return dict(schemaVersion='saved-request-equivalence-1',case=case,leftArtifactHash=left['artifactHash'],
                rightArtifactHash=right['artifactHash'],leftArm=left_arm,rightArm=right_arm,
                leftBodyHash=digest(a),rightBodyHash=digest(b),sameProviderBody=a==b,
                sameUserPayload=a['messages'][1]==b['messages'][1],sameSystem=a['messages'][0]==b['messages'][0],
                sameSettings={k:v for k,v in a.items() if k!='messages'}=={k:v for k,v in b.items() if k!='messages'},
                noCausalModelDeterminismClaim=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--frozen',required=True); p.add_argument('--report'); p.add_argument('--audit')
    p.add_argument('--compare-source'); p.add_argument('--output',required=True)
    a=p.parse_args(); out=Path(a.output).resolve()
    require('datasets' in out.parts and not out.exists(),'new private output required')
    require(bool(a.compare_source)!=bool(a.report and a.audit),'one mode required')
    value=compare_requests(read(a.compare_source),read(a.frozen)) if a.compare_source else score(read(a.frozen),read(a.report),read(a.audit))
    write_private(out,value)
    print({k:value[k] for k in ('developmentRegressionGateMet','sameProviderBody') if k in value})
