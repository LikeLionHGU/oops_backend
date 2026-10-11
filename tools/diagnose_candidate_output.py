"""Supplemental saved-output diagnostics; never repairs or approves responses.

The current Java validator remains authoritative. Multiple defects can coexist;
these observations explain a broad failure code without changing acceptance.
"""
import argparse
import json
from pathlib import Path

from fixed_review_suite import digest, read, require, write_private


def diagnose(assessment, raw):
    evidence = assessment.get('evidence')
    if not isinstance(evidence,list):
        return {'evidenceCount':None,'defects':['EVIDENCE_NOT_LIST'],'quoteMismatches':[]}
    rows = {r['id']:r['text'] for r in raw}
    mismatches = []
    for i,q in enumerate(evidence):
        if not isinstance(q,dict) or not isinstance(q.get('quote'),str) or not q['quote'].strip() \
                or q.get('segmentId') not in rows or q['quote'] not in rows[q['segmentId']]:
            mismatches.append(i)
    defects = []
    if len(evidence) > 8: defects.append('EVIDENCE_OBJECT_BUDGET_EXCEEDED')
    if mismatches: defects.append('QUOTE_NOT_IN_OWN_RAW_ROW')
    if assessment.get('target') is not None:
        mention = assessment.get('targetMention') or assessment['target']
        if mention != assessment['target'] and assessment.get('targetRelation') != 'CONTEXTUAL':
            defects.append('RESOLVED_TARGET_REQUIRES_CONTEXTUAL_RELATION')
    return {'evidenceCount':len(evidence),'defects':defects,'quoteMismatches':mismatches}


def report_diagnostics(report):
    cases = {c['name']:c['payload'] for c in report['cases']}
    results = []
    for call in report['calls']:
        for v in call.get('output',{}).get('verifications',[]):
            results.append({**{k:call[k] for k in ('case','arm','repeat')},'candidateId':v['candidateId'],
                            **diagnose(v['assessment'],cases[call['case']]['raw'])})
    return {'schemaVersion':'candidate-output-diagnostics-1','sourceReportHash':digest(report),
            'supplementalOnly':True,'responsesModified':False,'qualityGateMet':None,'results':results}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('report'); p.add_argument('--output',required=True)
    a = p.parse_args()
    path = Path(a.output).resolve()
    require('datasets' in path.parts and not path.exists(),'new private output required')
    result = report_diagnostics(read(a.report))
    write_private(path,result)
    print(json.dumps({'responses':len(result['results']),'supplementalOnly':True}))
