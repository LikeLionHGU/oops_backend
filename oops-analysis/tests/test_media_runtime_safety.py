import json
from pathlib import Path
import shutil
import subprocess
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from app import main, media


@pytest.mark.parametrize("value", ["0", "-1", "NaN", "Infinity", "missing", None])
def test_probe_unknown_duration_is_failure_not_zero(monkeypatch,value):
    monkeypatch.setattr(media.subprocess,"run",lambda *a,**k: SimpleNamespace(
        returncode=0,stdout=json.dumps({"format":{"duration":value}})))
    with pytest.raises(media.MediaError):media.probe_duration(Path("fixture"))


def test_probe_nonzero_exit_is_failure(monkeypatch):
    monkeypatch.setattr(media.subprocess,"run",lambda *a,**k:SimpleNamespace(returncode=1))
    with pytest.raises(media.MediaError):media.probe_duration(Path("fixture"))


def test_valid_probe_preserves_fractional_duration_and_bounded_command(monkeypatch):
    def run(command,**kw):
        assert kw['timeout']==20
        return SimpleNamespace(returncode=0,stdout='{"format":{"duration":"10.75"}}')
    monkeypatch.setattr(media.subprocess,'run',run)
    assert media.probe_duration(Path('fixture'))==10.75


def test_command_timeout_is_controlled_media_error(monkeypatch):
    def run(command,**kw):raise subprocess.TimeoutExpired(command,kw['timeout'])
    monkeypatch.setattr(media.subprocess,'run',run)
    with pytest.raises(media.MediaError,match='제한 시간'):
        media.run_command(['ffmpeg'],timeout=20,label='fixture')


@pytest.mark.parametrize('value',[0,-1,float('nan'),float('inf')])
def test_duration_guard_rejects_unknown_nonfinite_and_nonpositive(value):
    with pytest.raises(media.MediaError):main._guard_duration(value)
