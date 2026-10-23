"""Offline sampling/deadline checks, not native OCR latency or recall measurements."""
import math

import pytest

from app.config import Settings
from app import main, ocr


def test_fast_settings_reduce_work_not_just_deadline(monkeypatch):
    baseline = Settings(_env_file=None, analysis_profile="local", max_ocr_frames=300,
                        ocr_timeout_sec=None)
    fast = Settings(_env_file=None, analysis_profile="local", max_ocr_frames=120,
                    ocr_timeout_sec=600)
    monkeypatch.setattr(ocr, "get_settings", lambda: baseline)
    assert ocr._adjust_interval(978, 4.0) == 4.0
    assert math.ceil(978 / 4.0) == 245
    monkeypatch.setattr(ocr, "get_settings", lambda: fast)
    interval = ocr._adjust_interval(978, 4.0)
    assert interval == pytest.approx(8.15)
    assert math.ceil(978 / interval) == 120
    assert fast.effective_ocr_timeout_sec == 600
    assert baseline.effective_ocr_timeout_sec == 0


def test_short_video_sampling_is_unchanged_by_fast_frame_budget(monkeypatch):
    for limit in (120, 300):
        cfg = Settings(_env_file=None, max_ocr_frames=limit)
        monkeypatch.setattr(ocr, "get_settings", lambda: cfg)
        assert ocr._adjust_interval(8, 4) == 1


def test_health_exposes_actual_settings_without_starting_ocr(monkeypatch):
    cfg = Settings(_env_file=None, analysis_profile="local", max_ocr_frames=120,
                   ocr_timeout_sec=600)
    monkeypatch.setattr(main, "get_settings", lambda: cfg)
    monkeypatch.setattr(ocr, "is_available", lambda: True)
    monkeypatch.setattr(ocr, "_engine", lambda: pytest.fail("health must not load model"))
    result = main.health()
    assert result["analysisProfile"] == "local"
    assert result["maxOcrFrames"] == 120
    assert result["ocrTimeoutSec"] == 600
