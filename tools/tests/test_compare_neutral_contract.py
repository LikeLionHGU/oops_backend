import json
import subprocess
import sys
from pathlib import Path
from unittest import mock

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import compare_neutral_contract as tool
from test_ablate_review_prompts import inputs


def test_replacement_is_pinned_and_preserves_policy_and_json():
    source = subprocess.check_output(["git", "show", tool.PREVIOUS + ":" + tool.ENGINE], cwd=tool.ROOT, text=True)
    old = tool.prompts(source)[2]
    new = tool.neutralize(old)
    assert new.replace(tool.NEW_CONTRAST, tool.OLD_CONTRAST).replace(tool.NEW_TARGET, tool.OLD_TARGET) == old
    assert "가장 강한 비판 가설" not in new
    assert "TARGET 역할 인용 존재" in new
    with pytest.raises(ValueError, match="PINNED_PROMPT_BLOCK_REQUIRED"):
        tool.neutralize(new)


def test_plan_has_twenty_call_limit_and_frozen_inputs(tmp_path):
    paths = inputs(tmp_path)
    with mock.patch.object(tool, "read_key", return_value="test-key") as key, \
            mock.patch.object(tool, "complete", return_value={"output": {"verifications": []}}) as complete:
        result = tool.run(paths[0], paths[1], tmp_path / "plan" / "run.json")
        assert result["plannedCalls"] == 20
        key.assert_not_called(); complete.assert_not_called()
        result = tool.run(paths[0], paths[1], tmp_path / "paid" / "run.json", execute=True)
        assert complete.call_count == 20
        assert [c["name"] for c in result["cases"]][:2] == ["B-23.5", "C"]
        for case in result["cases"]:
            assert {c["inputSha256"] for c in result["calls"] if c["case"] == case["name"]} == {case["inputSha256"]}
        with pytest.raises(ValueError, match="NEW_OUTPUT_DIRECTORY_REQUIRED"):
            tool.run(paths[0], paths[1], tmp_path / "paid" / "run.json", execute=True)
        with pytest.raises(ValueError, match="REPEAT_LIMIT"):
            tool.run(paths[0], paths[1], tmp_path / "extra" / "run.json", execute=True, repeats=4)


def test_failures_are_redacted_without_retry(tmp_path):
    paths = inputs(tmp_path)
    with mock.patch.object(tool, "read_key", return_value="test-key"), \
            mock.patch.object(tool, "complete", side_effect=RuntimeError("SECRET")) as complete:
        result = tool.run(paths[0], paths[1], tmp_path / "failure" / "run.json", execute=True, repeats=1)
    assert complete.call_count == 12
    assert all(c["error"] == "RuntimeError" for c in result["calls"])
    assert "SECRET" not in (tmp_path / "failure" / "run.json").read_text()


def test_contrast_only_changes_one_block_preserving_target_contract():
    source = subprocess.check_output(["git", "show", tool.CURRENT + ":" + tool.ENGINE], cwd=tool.ROOT, text=True)
    old = tool.prompts(source)[2]
    new = tool.neutralize(old, "contrast-only")
    assert new.replace(tool.NEW_CONTRAST, tool.OLD_CONTRAST) == old
    assert tool.OLD_TARGET in new
    assert tool.NEW_TARGET not in new
    with pytest.raises(ValueError, match="PROFILE_REQUIRED"):
        tool.neutralize(old, "unknown")


def test_contrast_33_preserves_format_examples_and_only_changes_contrast():
    source = subprocess.check_output(["git", "show", tool.CURRENT_33 + ":" + tool.ENGINE], cwd=tool.ROOT, text=True)
    old = tool.prompts(source)[2]
    new = tool.neutralize(old, "contrast-33")
    assert new.replace(tool.NEW_CONTRAST, tool.OLD_CONTRAST) == old
    assert "# 대상 필드 형식 예시 — 판정 예시가 아니다" in new
    assert tool.OLD_TARGET in new


@pytest.mark.parametrize("profile,pinned", [("contrast-only", tool.CURRENT), ("contrast-33", tool.CURRENT_33)])
def test_contrast_only_plan_includes_raw_expression_control_without_calls(tmp_path, profile, pinned):
    paths = inputs(tmp_path)
    snapshot = json.loads(paths[0].read_text())
    snapshot["transcript"].extend([
        {"startMs": 75000, "endMs": 77000, "text": "비유 앞 원문"},
        {"startMs": 79000, "endMs": 80500, "text": "비유 원문"},
    ])
    paths[0].write_text(json.dumps(snapshot))
    with mock.patch.object(tool, "read_key") as key, mock.patch.object(tool, "complete") as complete:
        result = tool.run(paths[0], paths[1], tmp_path / "contrast" / "run.json", profile=profile)
    key.assert_not_called(); complete.assert_not_called()
    assert result["plannedCalls"] == 22
    assert result["previousCommit"] == pinned
    assert result["profile"] == profile
    case = result["cases"][-1]
    assert case["name"] == "D"
    assert case["payload"]["candidates"][0]["axis"] == "EXPRESSION_CONTENT"
    assert case["payload"]["candidates"][0]["proposedEvidence"][0]["quote"] == "비유 원문"
    assert "developmentExpectation" not in case["payload"]
    with pytest.raises(ValueError, match="UNIQUE_QUOTE_REQUIRED"):
        tool.expression_case({"transcript": snapshot["transcript"][:-1]})
