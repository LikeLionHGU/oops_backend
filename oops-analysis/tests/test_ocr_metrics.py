import logging

import pytest

from app.ocr_metrics import phase


def test_phase_logs_success_and_failure_without_swallowing_errors(caplog):
    with caplog.at_level(logging.INFO):
        with phase("synthetic_success"):
            pass
        with pytest.raises(ValueError):
            with phase("synthetic_failure"):
                raise ValueError("not logged as raw data")
    assert "phase=synthetic_success status=SUCCESS elapsedSec=" in caplog.text
    assert "phase=synthetic_failure status=FAILED elapsedSec=" in caplog.text
    assert "not logged as raw data" not in caplog.text
