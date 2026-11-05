"""Shared Gradle audit runner: fake gradlew scripts only, no Java build."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import local_audit_runner as tool
from review_context_pilot import PilotError


def backend(tmp_path, script):
    (tmp_path / "gradlew").write_text("#!/bin/bash\n" + script + "\n")
    return tmp_path


def run(path):
    return tool.run_gradle_audit("someAudit", {"request": "/tmp/r.json", "baseline": "/tmp/b.json"},
                                 "AUDIT=", "AUDIT_UNAVAILABLE", "AUDIT_FAILED", backend=path)


def test_single_marker_line_is_parsed_and_properties_are_passed_in_order(tmp_path):
    path = backend(tmp_path, 'echo noise; echo "AUDIT={\\"args\\":\\"$*\\"}"')
    assert run(path) == {"args": "--offline --console=plain -q someAudit -Prequest=/tmp/r.json -Pbaseline=/tmp/b.json"}


def test_missing_duplicate_marker_or_failed_exit_is_rejected(tmp_path):
    for script in ('echo nothing', 'echo "AUDIT={}"; echo "AUDIT={}"', 'echo "AUDIT={}"; exit 3'):
        with pytest.raises(PilotError, match="AUDIT_FAILED"):
            run(backend(tmp_path, script))


def test_timeout_reports_unavailable(tmp_path, monkeypatch):
    monkeypatch.setenv(tool.TIMEOUT_ENV, "1")
    with pytest.raises(PilotError, match="AUDIT_UNAVAILABLE"):
        run(backend(tmp_path, "sleep 5"))


def test_timeout_defaults_to_cold_build_budget_and_validates_override(monkeypatch):
    monkeypatch.delenv(tool.TIMEOUT_ENV, raising=False)
    assert tool.timeout_seconds() == 600
    monkeypatch.setenv(tool.TIMEOUT_ENV, "900")
    assert tool.timeout_seconds() == 900
    for bad in ("0", "-1", "abc", "3601"):
        monkeypatch.setenv(tool.TIMEOUT_ENV, bad)
        with pytest.raises(PilotError, match="AUDIT_TIMEOUT_INVALID"):
            tool.timeout_seconds()


def test_child_environment_excludes_secrets(tmp_path, monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "must-not-leak")
    path = backend(tmp_path, 'echo "AUDIT={\\"key\\":\\"${OPENAI_API_KEY:-absent}\\"}"')
    assert run(path) == {"key": "absent"}
