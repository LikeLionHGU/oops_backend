"""Retired paid paths must fail before input reads, credentials, or output writes."""
import importlib
import json
from pathlib import Path
import subprocess
import sys
from unittest.mock import patch

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from legacy_direct_execution import execution_mode, mark_report
import replay_draft_cases

MODULES = ('compare_review_prompts', 'compare_target_examples', 'compare_neutral_contract',
           'ablate_review_prompts', 'repair_prompt_comparison')


@pytest.mark.parametrize('name', MODULES)
def test_live_request_is_rejected_before_any_side_effect(name, tmp_path):
    module=importlib.import_module(name)
    output=tmp_path/'new-output/run.json'
    inputs=['missing-report', 'missing-audit', 'missing-archive'] if name=='repair_prompt_comparison' else ['missing-snapshot', 'missing-archive']
    with patch('collect_youtube_comments.read_key', side_effect=AssertionError('key forbidden')), \
         patch('urllib.request.OpenerDirector.open', side_effect=AssertionError('network forbidden')), \
         patch.object(module, 'complete', side_effect=AssertionError('callback forbidden')):
        with pytest.raises(ValueError, match='LEGACY_DIRECT_EXECUTION_DISABLED'):
            module.run(*inputs, output, execute=True)
    assert not output.parent.exists()


@pytest.mark.parametrize('name', MODULES)
def test_simulation_requires_an_explicit_mock_callback(name, tmp_path):
    module=importlib.import_module(name)
    inputs=['missing-report', 'missing-audit', 'missing-archive'] if name=='repair_prompt_comparison' else ['missing-snapshot', 'missing-archive']
    with pytest.raises(ValueError, match='MOCK_REQUIRES_EXPLICIT_CALLBACK'):
        module.run(*inputs, tmp_path/'run.json', execute=True, simulate=True)
    assert not (tmp_path/'run.json').exists()


def test_replay_cli_execute_is_blocked_before_loading_missing_files(tmp_path):
    script=Path(__file__).resolve().parents[1]/'replay_draft_cases.py'
    result=subprocess.run([sys.executable, str(script), '--transcript', str(tmp_path/'missing.json'),
                           '--cards', str(tmp_path/'missing-cards.json'), '--family', 'fixture', '--execute'],
                          capture_output=True, text=True, timeout=10)
    assert result.returncode==2
    assert 'LEGACY_DIRECT_EXECUTION_DISABLED' in result.stderr
    assert 'INVALID_OR_UNAVAILABLE_REPLAY_INPUT' not in result.stderr


def test_direct_imported_transport_is_not_callable_even_with_a_key():
    with patch('urllib.request.OpenerDirector.open', side_effect=AssertionError('network forbidden')):
        with pytest.raises(replay_draft_cases.PilotError, match='LEGACY_DIRECT_EXECUTION_DISABLED'):
            replay_draft_cases.complete('do-not-print-key', 'system', {})


def test_mock_markers_cannot_be_mistaken_for_paid_attempts():
    callback=lambda *args: {}
    report=dict(calls=[dict(output={'verifications': []})], execute=True)
    mark_report(report, execution_mode(True, True, callback))
    assert report['runKind']=='MOCK_OFFLINE'
    assert report['apiCalls']==0 and report['simulatedCalls']==1
    assert report['consumedReservationUsd']=='0' and report['actualBilledCostUsd'] is None
    assert json.loads(json.dumps(report))['calls'][0]['output']=={'verifications': []}
    with pytest.raises(ValueError, match='MOCK_REQUIRES_EXPLICIT_EXECUTE'):
        execution_mode(False, True, callback)


def test_actual_mock_run_preserves_plan_inputs_without_keys_network_or_ledger(tmp_path):
    import compare_review_prompts as module
    from test_compare_review_prompts import fixture
    snapshot, archive=fixture()
    source=tmp_path/'snapshot.json'; reference=tmp_path/'archive.json'
    source.write_text(json.dumps(snapshot)); reference.write_text(json.dumps(archive))
    with patch('collect_youtube_comments.read_key', side_effect=AssertionError('key forbidden')), \
         patch('reservation_ledger.transaction', side_effect=AssertionError('ledger forbidden')), \
         patch('urllib.request.OpenerDirector.open', side_effect=AssertionError('network forbidden')), \
         patch.object(module, 'complete', return_value=dict(output={'verifications': []})) as callback:
        plan=module.run(source, reference, tmp_path/'plan.json')
        report=module.run(source, reference, tmp_path/'mock.json', execute=True, simulate=True)
    assert plan['runKind']=='DRY_RUN' and plan['simulatedCalls']==0
    assert report['runKind']=='MOCK_OFFLINE' and report['apiCalls']==0
    assert report['simulatedCalls']==callback.call_count==16
    assert all(call.args[0] is None for call in callback.call_args_list)
    for field in ('cases', 'armPromptSha256', 'referenceSha256', 'sourceTranscriptSha256', 'plannedCalls'):
        assert report[field]==plan[field]
