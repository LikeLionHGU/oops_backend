import sys
from pathlib import Path
from unittest import mock

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import audit_prompt_comparison as tool


def test_contract_rejection_is_not_counted_as_a_detected_warning():
    calls = [{"arm": "current", "repeat": 0, "output": {"verifications": [
        {"candidateId": "candidate-1", "assessment": {"decision": "REVIEW_REQUIRED"}}]}}]
    audit = {"arms": [{"name": "current-0", "assessments": [
        {"accepted": False, "failureCode": "TARGET_EVIDENCE_REQUIRED"}]}]}
    result = tool.summarize({"name": "synthetic", "developmentExpectation": "REVIEW_REQUIRED"}, calls, audit)[0]
    assert result["decision"] == "REVIEW_REQUIRED" and not result["accepted"]
    assert not result["matchesDevelopmentExpectationAndContract"]


def test_missing_duplicate_and_wrong_candidate_responses_are_not_passes():
    for verifications in [[], [{"candidateId": "wrong"}], [{"candidateId": "candidate-1"}] * 2]:
        calls = [{"arm": "current", "repeat": 0, "output": {"verifications": verifications}}]
        result = tool.summarize({"name": "synthetic"}, calls, {"arms": []})[0]
        assert not result["accepted"] and result["failureCode"] == "MODEL_VERIFICATION_SHAPE"
    result = tool.summarize({"name": "synthetic"}, [{"arm": "current", "repeat": 0, "error": "RuntimeError"}], {"arms": []})[0]
    assert result["failureCode"] == "PROVIDER_FAILURE"


def test_existing_audit_is_preserved_without_running_gradle(tmp_path):
    output = tmp_path / "audit.json"
    output.write_text("existing")
    with mock.patch.object(tool.subprocess, "run") as run:
        with pytest.raises(ValueError, match="NEW_AUDIT_OUTPUT_REQUIRED"):
            tool.run(tmp_path / "report.json", tmp_path / "archive.json", output)
        run.assert_not_called()
    assert output.read_text() == "existing"
