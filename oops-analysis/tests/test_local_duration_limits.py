from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from app.config import Settings
from app import main
from app.media import MediaError


def settings(profile="default", limit=5400):
    return Settings(_env_file=None, analysis_profile=profile, max_duration_sec=limit)


def test_default_keeps_duration_limit_and_custom_positive_limit(monkeypatch):
    monkeypatch.setattr(main, "get_settings", lambda: settings())
    main._guard_duration(5400)
    with pytest.raises(MediaError, match="최대"):
        main._guard_duration(5401)
    monkeypatch.setattr(main, "get_settings", lambda: settings(limit=600))
    with pytest.raises(MediaError):
        main._guard_duration(601)


@pytest.mark.parametrize("value", [0, -1, float("nan"), float("inf")])
def test_local_still_rejects_invalid_duration(monkeypatch, value):
    monkeypatch.setattr(main, "get_settings", lambda: settings("local"))
    with pytest.raises(MediaError):
        main._guard_duration(value)


def test_local_allows_long_video_and_probe_reports_no_ceiling(monkeypatch):
    local = settings("local")
    assert local.effective_max_duration_sec == 0
    monkeypatch.setattr(main, "get_settings", lambda: local)
    main._guard_duration(10 * 3600)
    cleaned = []
    monkeypatch.setattr(main, "prepare", lambda *args: SimpleNamespace(
        duration_sec=10 * 3600, title="synthetic", cleanup=lambda: cleaned.append(True)))
    response = TestClient(main.app).post("/probe", json={"filePath": "synthetic.mp4"})
    assert response.status_code == 200
    assert response.json()["maxDurationSec"] == 0
    assert response.json()["withinLimit"] is True
    assert cleaned == [True]


@pytest.mark.parametrize("limit", [0, -1])
def test_default_cannot_bypass_by_nonpositive_limit(limit):
    with pytest.raises(ValidationError):
        settings(limit=limit)


def test_profile_typo_is_not_silently_accepted():
    with pytest.raises(ValidationError):
        settings("locla")
