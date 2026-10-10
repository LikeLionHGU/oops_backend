"""Offline comparison-tool checks; no keys or provider calls."""
import copy
import json
import sys
from pathlib import Path
from unittest import mock

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import compare_review_prompts as tool


def fixture():
    times = [1500, 35000, 36500, 39000, 40500, 69500, 70500, 72000]
    snapshot = {"transcript": [{"startMs": t, "endMs": t + 1000, "text": f"합성 원문 {t}"} for t in times]}
    rule = {k: "합성 기준" for k in ["id", "axis", "condition", "normalContrast", "requiredEvidence", "missingContext"]}
    archive = {"schemaVersion": "review-guidelines-4", "guidelines": [rule], "examples": [
        {"id": key, "mechanismIds": ["synthetic"], "context": {"flow": ["합성 흐름"]}}
        for key in ["pisik-B", "pisik-C"]]}
    return snapshot, archive


def test_inputs_are_frozen_raw_quotes_and_do_not_contain_expected_labels():
    snapshot, archive = fixture(); before = copy.deepcopy(snapshot)
    cases, reference = tool.prepare(snapshot, archive)
    assert snapshot == before
    assert [c["name"] for c in cases] == ["B", "C", "normal-choice", "normal-review"]
    for case in cases:
        payload = case["payload"]
        assert "expected" not in json.dumps(payload)
        by_id = {r["id"]: r for r in payload["raw"]}
        assert all(q["quote"] == by_id[q["segmentId"]]["text"] for q in payload["candidates"][0]["proposedEvidence"])
        assert case["inputSha256"] == tool.digest(payload)
    assert len(reference["referenceContexts"]) == 2


def test_missing_or_ambiguous_raw_quote_is_rejected():
    snapshot, archive = fixture()
    for rows in [snapshot["transcript"][:-1], snapshot["transcript"] + [snapshot["transcript"][1]]]:
        with pytest.raises(ValueError, match="UNIQUE_QUOTE_REQUIRED"):
            tool.prepare({"transcript": rows}, archive)


def test_plan_is_default_and_execution_has_sixteen_call_cap(tmp_path):
    snapshot, archive = fixture()
    input_path, archive_path, output = [tmp_path / name for name in ["input.json", "archive.json", "output.json"]]
    input_path.write_text(json.dumps(snapshot)); archive_path.write_text(json.dumps(archive))
    with mock.patch.object(tool, "complete", return_value={"output": {"verifications": []}}) as complete, \
            mock.patch.object(tool, "read_key", return_value="unused-test-key") as key:
        result = tool.run(input_path, archive_path, output)
        assert result["plannedCalls"] == 16
        complete.assert_not_called(); key.assert_not_called()
        result = tool.run(input_path, archive_path, output, True)
        assert complete.call_count == 16
        assert len(result["calls"]) == 16
        for case in result["cases"]:
            hashes = {c["inputSha256"] for c in result["calls"] if c["case"] == case["name"]}
            assert hashes == {case["inputSha256"]}
        with pytest.raises(ValueError, match="REPEAT_LIMIT"):
            tool.run(input_path, archive_path, output, True, 4)


def test_provider_failure_is_recorded_without_retry_or_sensitive_message(tmp_path):
    snapshot, archive = fixture()
    input_path, archive_path = tmp_path / "input.json", tmp_path / "archive.json"
    input_path.write_text(json.dumps(snapshot)); archive_path.write_text(json.dumps(archive))
    output = tmp_path / "output.json"
    with mock.patch.object(tool, "complete", side_effect=RuntimeError("SECRET-MUST-NOT-APPEAR")) as complete, \
            mock.patch.object(tool, "read_key", return_value="unused-test-key"):
        result = tool.run(input_path, archive_path, output, True, 1)
        assert complete.call_count == 8
        assert all(c["error"] == "RuntimeError" for c in result["calls"])
        assert "SECRET-MUST-NOT-APPEAR" not in output.read_text()
