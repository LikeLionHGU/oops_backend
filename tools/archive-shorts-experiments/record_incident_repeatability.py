"""Record identical frozen-input repeats; never promote runtime or training."""
from fixed_review_suite import digest,require
from run_incident_reference_comparison import score
from stabilize_incident_reference import development_gate
from confirm_incident_stability import gate


def record(primary,primary_report,primary_audit,confirmed,confirmed_report,confirmed_audit):
    first=score(primary,primary_report,primary_audit)
    second=score(confirmed,confirmed_report,confirmed_audit)
    require(primary_report['runKind']==confirmed_report['runKind']=='LIVE'
        and development_gate(first) and gate(second),'complete live repeated gates required')
    require(confirmed['sourceTrialHash']==digest(primary) and confirmed['sourceScoreHash']==digest(first)
        and confirmed['confirmationParentStep']==primary['stabilizationStep']
        and confirmed['authorization']==primary['authorization']
        and confirmed['source']['cases']==primary['source']['cases'],'identical confirmed parent required')
    jobs={(j['case'],j['repeat']):j for j in primary['jobs'] if j['arm']=='CANDIDATE'}
    require(len(jobs)==27 and len(confirmed['jobs'])==27,'nine conditions repeated three times required')
    hashes=[]
    for j in confirmed['jobs']:
        old=jobs[(j['case'],j['repeat'])]
        require(j['baseBody']==old['baseBody'] and j['fixedSelectedScope']==old['fixedSelectedScope']
            and j['judgmentVariant']==old['judgmentVariant'] and j['stages']==old['stages'],
            'confirmation request, selection, reference or configuration changed')
        hashes.append(dict(case=j['case'],repeat=j['repeat'],requestHash=j['stages'][0]['requestHash']))
    result=dict(schemaVersion='fixed-input-repeatability-manifest-1',
        state='FIXED_INPUT_REPEATABILITY_GATE_PASSED',productionStable=False,runtimeUseAuthorized=False,
        trainingUseAuthorized=False,independentGoldApprovals=0,semanticApprovalPending=True,
        evaluationScope='KNOWN_CASE_REPRODUCTION_WITH_OWNER_SYNTHETIC_CONTROLS',
        automaticSelectionTested=False,fullPipelineReplayed=False,
        primaryFrozenHash=digest(primary),confirmationFrozenHash=digest(confirmed),
        primaryReportHash=digest(primary_report),confirmationReportHash=digest(confirmed_report),
        primaryAuditHash=digest(primary_audit),confirmationAuditHash=digest(confirmed_audit),
        primaryScoreHash=digest(first),confirmationScoreHash=digest(second),
        modelSettings=primary['config'],authorizationHash=primary['authorizationHash'],
        controlPacketHash=primary['controlPacketHash'],conditions=9,repetitionsPerCondition=6,
        candidateAccepted=54,requestIdentities=hashes,actualBilledCostUsd=None,
        nextChecks=['INDEPENDENT_SEMANTIC_REVIEW','AUTOMATIC_CANDIDATE_AND_SELECTION_REGRESSION',
                    'FULL_VIDEO_REPLAY','UNSEEN_INCIDENT_AND_NORMAL_BOUNDARIES'])
    result['manifestHash']=digest(result)
    return result
