"""Supplemental saved-output diagnostics; never repairs or approves responses.

The current Java validator remains authoritative. Multiple defects can coexist;
these observations explain a broad failure code without changing acceptance.
"""
import argparse
import copy
import json
from pathlib import Path

from fixed_review_suite import digest, read, require, write_private


def diagnose(assessment, raw, wire=False, selection=False):
    evidence = assessment.get('evidence')
    if not isinstance(evidence,list):
        return {'evidenceCount':None,'defects':['EVIDENCE_NOT_LIST'],'quoteMismatches':[]}
    rows = {r['id']:r['text'] for r in raw}
    mismatches = []
    for i,q in enumerate(evidence):
        if selection:
            if not isinstance(q,dict) or q.get('segmentId') not in rows:
                mismatches.append(i)
            continue
        if not isinstance(q,dict) or not isinstance(q.get('quote'),str) or not q['quote'].strip() \
                or q.get('segmentId') not in rows or q['quote'] not in rows[q['segmentId']]:
            mismatches.append(i)
    defects = []
    if len(evidence) > 8: defects.append('EVIDENCE_OBJECT_BUDGET_EXCEEDED')
    if mismatches: defects.append('SELECTION_ID_NOT_RAW' if selection else 'QUOTE_NOT_IN_OWN_RAW_ROW')
    if selection:
        if 'evidenceText' in assessment or any(isinstance(q,dict) and set(q) != {'segmentId','role'} for q in evidence):
            defects.append('GENERATED_FIELDS_IN_ID_SELECTION')
        pairs = [(q.get('segmentId'),q.get('role')) for q in evidence if isinstance(q,dict)]
        if len(pairs) != len(set(pairs)): defects.append('DUPLICATE_ID_ROLE_SELECTION')
        if sum(isinstance(q,dict) and q.get('role') == 'PRIMARY' for q in evidence) != 1:
            defects.append('SELECTION_SINGLE_PRIMARY_REQUIRED')
    if wire and assessment.get('decision') == 'REVIEW_REQUIRED' and assessment.get('target') is not None:
        if sum(isinstance(q,dict) and q.get('role') == 'TARGET' for q in evidence) != 1:
            defects.append('WIRE_SINGLE_TARGET_REQUIRED')
    if assessment.get('target') is not None:
        mention = assessment.get('targetMention') or assessment['target']
        if mention != assessment['target'] and assessment.get('targetRelation') != 'CONTEXTUAL':
            defects.append('RESOLVED_TARGET_REQUIRES_CONTEXTUAL_RELATION')
    return {'evidenceCount':len(evidence),'defects':defects,'quoteMismatches':mismatches}


def report_diagnostics(report):
    cases = {c['name']:c['payload'] for c in report['cases']}
    results = []
    for call in report['calls']:
        source = 'output' if 'output' in call else 'wireOutput'
        for v in call.get(source,{}).get('verifications',[]):
            results.append({**{k:call[k] for k in ('case','arm','repeat')},'candidateId':v['candidateId'],
                            'outputSource':source,
                            **diagnose(v['assessment'],cases[call['case']]['raw'],
                                       wire=call.get('outputAdapter') in {'single-target-evidence-wire-1','evidence-id-selection-wire-1'},
                                       selection=source == 'wireOutput' and call.get('outputAdapter') == 'evidence-id-selection-wire-1')})
    return {'schemaVersion':'candidate-output-diagnostics-1','sourceReportHash':digest(report),
            'supplementalOnly':True,'responsesModified':False,'qualityGateMet':None,'results':results}


def semantic_packet(frozen, report, java_audit):
    """Read-only human worksheet. No automatic meaning labels or model requests."""
    from compare_recorded_candidates import audit_export
    from compare_review_prompts import digest as audit_digest
    require(frozen.get('schemaVersion') == 'recorded-evidence-selection-trial-1'
            and frozen['artifactHash'] == digest({k:v for k,v in frozen.items() if k != 'artifactHash'})
            and report['artifactHash'] == frozen['artifactHash'], 'frozen report provenance mismatch')
    require(report['status'] == 'COMPLETED_PENDING_JAVA_AUDIT'
            and len(report['calls']) == len(frozen['requests']) == 42, 'complete comparison required')
    require(java_audit['sourceReportSha256'] == audit_digest(audit_export(report)), 'audit report provenance mismatch')
    cases = {c['name']:c for c in frozen['cases']}
    require(len(cases) == 7 and set(cases) == {'B','C','D','N1','N2','N3','N4'}, 'fixed regression cases required')
    audits = {(r['case'],r['arm'],r['repeat']):r for r in java_audit['results']}
    require(len(audits) == len(java_audit['results']) == 42, 'complete unique audits required')
    worksheet = []
    for request, call in zip(frozen['requests'],report['calls']):
        require(all(request[k] == call[k] for k in ('case','arm','repeat','requestHash'))
                and request['requestHash'] == digest(request['body'])
                and call.get('outputAdapter') == request.get('outputAdapter'), 'response order or request mismatch')
        key = (call['case'],call['arm'],call['repeat'])
        require(key in audits, 'response audit missing')
        payload = cases[call['case']]['payload']
        require(json.loads(request['body']['messages'][1]['content']) == payload, 'raw input changed')
        source = 'output' if 'output' in call else 'wireOutput'
        require(source in call, 'response unavailable')
        worksheet.append({
            'case':call['case'],'arm':call['arm'],'repeat':call['repeat'],
            'requestHash':call['requestHash'],'savedCallHash':digest(call),
            'rawInputHash':digest(payload),'responseSource':source,
            'savedAssessment':copy.deepcopy(call[source]),
            'contractAudit':copy.deepcopy(audits[key]),
            'review':{'reviewer':None,'status':'UNREVIEWED',
                      'normalExplanation':None,'criticismExplanation':None,
                      'normalUnsupportedPremises':[],'criticismUnsupportedPremises':[],
                      'sourceOfExplanation':None,'evaluationLinkQuotes':[],
                      'targetGroundingQuotes':[],'targetGroundingSufficient':None,
                      'issueMatchesCandidate':None,'textAloneSufficient':None,
                      'requiredAdditionalContext':[],'semanticDecision':None}})
    return {'schemaVersion':'saved-response-semantic-review-1','status':'UNREVIEWED',
            'frozenArtifactHash':frozen['artifactHash'],'sourceReportHash':digest(report),
            'sourceJavaAuditHash':digest(java_audit), 'cases':copy.deepcopy(frozen['cases']),
            'questions':[
                '부재→욕구→대체 설명 외에, 무엇을 어떻게 낮추는 평가 연결이 실제 원문에 있는가?',
                '자신의 경험·전해 들은 설명·타인의 생활에 대한 추측 중 어느 것으로 읽히며 원문 근거는 무엇인가?',
                '정상 해석과 비판 해석은 각각 어떤 전제를 원문 밖에서 추가하는가?',
                'TARGET 행이 실제 대상을 지칭하는가? 단순한 평가나 근처 대명사만 고르지 않았는가?',
                '텍스트만으로 설명할 수 있는가? 필요한 화자·억양·장면이 입력에 없는가?'
            ],
            'worksheet':worksheet,'apiExecutionEnabled':False,'runtimeChanged':False,
            'trainingUseAuthorized':False,'curatedPromotionAuthorized':False,
            'independentGoldApprovals':0,'qualityGateMet':None,
            'responsesModified':False}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('report'); p.add_argument('--output',required=True)
    p.add_argument('--frozen',help='Enable offline semantic review worksheet')
    p.add_argument('--java-audit')
    a = p.parse_args()
    path = Path(a.output).resolve()
    require('datasets' in path.parts and not path.exists(),'new private output required')
    require(bool(a.frozen) == bool(a.java_audit),'frozen and java audit required together')
    result = semantic_packet(read(a.frozen),read(a.report),read(a.java_audit)) if a.frozen else report_diagnostics(read(a.report))
    write_private(path,result)
    print(json.dumps({'responses':len(result.get('results',result.get('worksheet',[]))),
                      'supplementalOnly':True,'apiExecutionEnabled':False}))
