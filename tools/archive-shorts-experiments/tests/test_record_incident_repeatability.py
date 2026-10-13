from pathlib import Path
import sys
import copy
from unittest.mock import patch
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from record_incident_repeatability import record
from fixed_review_suite import digest


def test_mock_cannot_award_repeatability_even_with_declared_matching_decisions():
    with patch('record_incident_repeatability.score',return_value={'results':[]}):
        with pytest.raises(ValueError):record({},dict(runKind='MOCK_OFFLINE'),{},
            {},dict(runKind='MOCK_OFFLINE'),{})


def test_live_reports_need_both_complete_development_gates():
    with patch('record_incident_repeatability.score',return_value={'results':[]}):
        with pytest.raises(ValueError):record({},dict(runKind='LIVE'),{}, {},dict(runKind='LIVE'),{})


def fixture():
    jobs=[dict(case=str(c),repeat=r,arm='CANDIDATE',baseBody={'fixed':True},fixedSelectedScope={'fixed':True},
        judgmentVariant='fixture',stages=[dict(requestHash='fixed')]) for r in (1,2,3) for c in range(9)]
    scored=dict(results=[dict(arm='CANDIDATE',accepted=True,matchedDevelopmentDecision=True)]*27)
    p=dict(jobs=jobs,source=dict(cases=list(range(9))),stabilizationStep='fixture',config={},
        authorization={},authorizationHash='fixed',controlPacketHash='fixed')
    q=copy.deepcopy(p);q.update(sourceTrialHash=digest(p),sourceScoreHash=digest(scored),confirmationParentStep='fixture')
    return p,q,scored


def test_success_is_only_nonproduction_repeatability_and_changed_body_is_rejected():
    p,q,s=fixture()
    with patch('record_incident_repeatability.score',return_value=s):
        result=record(p,dict(runKind='LIVE'),{},q,dict(runKind='LIVE'),{})
        assert result['candidateAccepted']==54 and not result['productionStable']
        assert result['semanticApprovalPending'] and not result['runtimeUseAuthorized']
        q['jobs'][0]['baseBody']['fixed']=False
        with pytest.raises(ValueError):record(p,dict(runKind='LIVE'),{},q,dict(runKind='LIVE'),{})
