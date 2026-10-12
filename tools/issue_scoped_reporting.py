"""Report assembly/storage only: no credentials, transport, or budget authority.

The runner validates its frozen recipe and claims reservations before execution.
These helpers preserve historical report and audit contracts, not semantic labels.
"""
import copy
from decimal import Decimal
import json

from compare_recorded_candidates import audit_export
from fixed_review_suite import write_private


def build_report(artifact, results, *, stopped, is_bound, simulate):
    stages = [s for r in results for s in r['stages']]
    report = dict(schemaVersion=artifact['schemaVersion'], artifactHash=artifact['artifactHash'], authorizationHash=artifact['authorizationHash'],
                  status='COMPLETED_PENDING_JAVA_AUDIT' if len(results)==artifact['plannedUnits'] and not stopped else 'PARTIAL',
                  cases=copy.deepcopy(artifact.get('evaluatedCases', artifact['source']['cases'])), calls=results,
                  apiCalls=len(stages), reservedCostUsd=artifact['reservedCostUsd'],
                  estimatedCostUsd=str(sum(Decimal(s.get('estimatedCostUsd','0')) for s in stages)),
                  unknownCostAttempts=sum('estimatedCostUsd' not in s for s in stages),
                  actualBilledCostUsd=None, qualityGateMet=None, fullPipelineReplayed=False,
                  normalControlsIncluded=artifact.get('normalControlsIncluded', True),
                  repetitionsPerCondition=artifact.get('repetitionsPerCondition',3))
    if 'boundaryInputStudy' in artifact:
        report.update(boundaryInputStudy=artifact['boundaryInputStudy'],previewHash=artifact['previewHash'],
                      automaticSelectionEvaluated=False,fullRegressionReplayed=False)
    if 'targetFocusTrial' in artifact:
        report.update(targetFocusTrial=artifact['targetFocusTrial'], previewHash=artifact['previewHash'],
                      automaticSelectionEvaluated=False,fullRegressionReplayed=False)
    if 'referenceSplitStudy' in artifact:
        report.update(referenceSplitStudy=artifact['referenceSplitStudy'], previewHash=artifact['previewHash'],
                      automaticSelectionEvaluated=False, fullRegressionReplayed=False)
    if 'incidentReferenceStudy' in artifact:
        report.update(incidentReferenceStudy=artifact['incidentReferenceStudy'],previewHash=artifact['previewHash'],
                      automaticSelectionEvaluated=False,fullRegressionReplayed=False,
                      evaluationScope='KNOWN_CASE_REPRODUCTION_NOT_GENERALIZATION')
    if 'contextReselectionStudy' in artifact:
        report.update(contextReselectionStudy=artifact['contextReselectionStudy'],
                      sourceReportHash=artifact['sourceReportHash'],
                      skipped=copy.deepcopy(artifact['skipped']),
                      freshInitialJudgments=False, fullRegressionReplayed=False)
    if is_bound:
        report.update(runKind='MOCK_OFFLINE' if simulate else 'LIVE', simulatedCalls=len(stages) if simulate else 0,
                      apiCalls=0 if simulate else len(stages),
                      consumedReservationUsd='0' if simulate else artifact['reservedCostUsd'])
        if 'additionalFundingHash' in artifact:
            report['additionalFundingHash'] = artifact['additionalFundingHash']
        if simulate:
            report.update(mockTokenRateEquivalentUsd=report['estimatedCostUsd'], estimatedCostUsd='0',
                          status='MOCK_COMPLETE_PENDING_JAVA_AUDIT' if report['status']=='COMPLETED_PENDING_JAVA_AUDIT' else 'MOCK_PARTIAL')
    return report


def save_reports(directory, artifact, report):
    """Persist the original report and a separate normalized Java audit input.

    JSON round-trip deliberately breaks shared aliases before audit normalization.
    Neither normalization nor bundle sharding may mutate provider/raw responses.
    """
    if artifact.get('reportSerialization') == 'bundle-1':
        from private_report_bundle import save as save_bundle
        save_bundle(directory, report, 'report')
    else:
        write_private(directory / 'report.json', report)
    exported = audit_export(json.loads(json.dumps(report, ensure_ascii=False)))
    exported.update(trialSchemaVersion=('recorded-flow-regression-1' if 'targetFocusTrial' in artifact
                    and artifact['repetitionsPerCondition'] >= 3 else
                    'recorded-target-focus-1' if 'targetFocusTrial' in artifact or 'referenceSplitStudy' in artifact else
                    'recorded-boundary-input-1' if 'boundaryInputStudy' in artifact else 'recorded-flow-regression-1'),
                    repetitionsPerCondition=artifact.get('repetitionsPerCondition', 3))
    if artifact.get('reportSerialization') == 'bundle-1':
        save_bundle(directory, exported, 'audit')
    else:
        write_private(directory / 'audit-input.json', exported)
