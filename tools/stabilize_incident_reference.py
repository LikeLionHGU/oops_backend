"""One-variable target-focus trial on the frozen faithful card, no adoption."""
import copy
from decimal import Decimal
from pathlib import Path
from bound_review_contract import prepare_attempt
from experiment_costs import reserve,positive
from fixed_review_suite import read,digest,require
from private_report_bundle import load

ROOT=Path(__file__).resolve().parents[1]/'datasets/controversy/evaluation/v1'


def source_inputs():
    from run_incident_reference_comparison import score
    directory=ROOT/'faithful-incident-live-20261012'
    frozen=read(directory/'frozen.json');report=load(directory/'live-run/report-index.json')
    audit=read(directory/'java-audit.json')
    require(digest(frozen)=='38053b3247a43682ca915feb5c838573ced78bc836d18e3463bbbd291f279352'
            and read(directory/'score.json')==score(frozen,report,audit), 'audited faithful card source required')
    return frozen


def freeze(source,available,step='TARGET_FOCUS_1'):
    require(step in {'TARGET_FOCUS_1','MECHANISM_FOCUS_1','COMPACT_REVIEW_1','CHRONOLOGY_1'}, 'unknown stabilization step')
    available=positive(available)
    require(available<=Decimal(source['remainingBudgetUsd']), 'do not increase existing approval')
    templates={j['case']:j for j in source['jobs'] if j['arm']=='CANDIDATE' and j['repeat']==1}
    required={'F2','C','D','F4','F5'}
    if step=='CHRONOLOGY_1':required|={'Control-Absence','Control-Review','Control-Nostalgia','Control-SingleMenu'}
    require(set(templates)==required, 'fixed faithful inputs and controls required')
    jobs=[]
    for repeat in range(1,4):
        for i,case in enumerate(source['source']['cases']):
            old=templates[case['name']]
            for arm in (('BASELINE','CANDIDATE') if (i+repeat)%2 else ('CANDIDATE','BASELINE')):
                variants={'TARGET_FOCUS_1':('CLOSED_SCOPE','TARGET_FOCUS_CLOSED_SCOPE'),
                    'MECHANISM_FOCUS_1':('TARGET_FOCUS_CLOSED_SCOPE','MECHANISM_FOCUS_CLOSED_SCOPE'),
                    'COMPACT_REVIEW_1':('MECHANISM_FOCUS_CLOSED_SCOPE','COMPACT_REVIEW_CLOSED_SCOPE'),
                    'CHRONOLOGY_1':('COMPACT_REVIEW_CLOSED_SCOPE','CHRONOLOGY_COMPACT_REVIEW_CLOSED_SCOPE')}[step]
                variant=variants[arm=='CANDIDATE']
                request=prepare_attempt(old['baseBody'],old['fixedSelectedScope'],'freeze-preview',
                                        judgment_variant=variant)['requestBody']
                tokens,cost=reserve(request,source['config'])
                jobs.append(dict(case=case['name'],arm=arm,repeat=repeat,
                    baseBody=copy.deepcopy(old['baseBody']),baseRequestHash=old['baseRequestHash'],
                    fixedSelectedScope=copy.deepcopy(old['fixedSelectedScope']),judgmentVariant=variant,
                    stages=[dict(stage='JUDGE',body=request,requestHash=digest(request),
                    reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))]))
    total=sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    require(total<=available, 'full paired trial exceeds balance')
    result=copy.deepcopy(source);result.pop('artifactHash')
    result.pop('confirmationParentStep',None);result.pop('sourceScoreHash',None)
    result.update(incidentReferenceStudy='FAITHFUL_'+step,stabilizationStep=step,
        previewHash=digest(dict(source=digest(source),jobs=jobs)),sourceTrialHash=digest(source),
        jobs=jobs,availableBudgetUsd=str(available),reservedCostUsd=str(total),
        remainingBudgetUsd=str(available-total),plannedUnits=len(jobs),maxApiCalls=len(jobs),
        predeclaredCandidateGate=f'ALL_{len(jobs)//2}_OWNER_DECISIONS_AND_JAVA_CONTRACTS',
        semanticQualityGateMet=None,qualityGateMet=None,runtimeChanged=False)
    result['artifactHash']=digest(result)
    return result


def prepare(available,step='TARGET_FOCUS_1'):
    source=source_inputs()
    if step in {'MECHANISM_FOCUS_1','COMPACT_REVIEW_1','CHRONOLOGY_1'}:
        from run_incident_reference_comparison import score
        directory=ROOT/{'MECHANISM_FOCUS_1':'faithful-target-focus-20261012',
            'COMPACT_REVIEW_1':'faithful-mechanism-focus-20261012',
            'CHRONOLOGY_1':'faithful-anchor-confirmation-20261012'}[step]
        source=read(directory/'frozen.json')
        require(read(directory/'score.json')==score(source,load(directory/'live-run/report-index.json'),
            read(directory/'java-audit.json')), 'audited preceding comparison required')
    return freeze(source,available,step)
def validate(frozen):require(frozen==prepare(frozen['availableBudgetUsd'],frozen['stabilizationStep']),
    'stabilization trial changed')


def development_gate(score):
    rows=[r for r in score['results'] if r['arm']=='CANDIDATE']
    return (len(rows) in {15,27} and all(r['accepted'] and r['matchedDevelopmentDecision'] is True for r in rows))
