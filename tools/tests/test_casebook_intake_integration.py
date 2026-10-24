"""Opt-in local Java/CLI integration on synthetic temp data, never providers."""
import json
import os
import subprocess
import sys
from pathlib import Path
import pytest

from test_casebook_intake import dataset, spec
import casebook_intake as tool
import build_incident_dataset as builder


@pytest.mark.skipif(os.environ.get('OOPS_RUN_LOCAL_JAVA_INTAKE') != '1', reason='Explicit offline Java integration opt-in')
def test_real_offline_java_gate_and_cli_apply_end_to_end(dataset):
    value = spec(dataset)
    input_path = dataset / 'research/intake.json'; input_path.write_bytes(tool.encoded(value))
    preview = tool.plan(dataset, value)
    process = subprocess.run([sys.executable, str(Path(tool.__file__)), str(input_path), '--dataset-root', str(dataset),
                              '--apply', '--expected-plan-sha', preview['sha']], capture_output=True, text=True, timeout=60)
    assert process.returncode == 0, process.stderr
    result = json.loads(process.stdout)
    assert result['applied'] and result['retrievalAudit']['providerCalls'] == 0
    assert result['retrievalAudit']['retrieved'] is True
    assert value['id'] in result['retrievalAudit']['selectedIds']
    assert builder.verify(dataset)['examples'] == 3
    receipt = json.loads((dataset / preview['receipt']).read_bytes())
    assert receipt['retrievalAudit']['modelAccuracyMeasured'] is False
    assert not (dataset / '.casebook-intake.lock').exists()
