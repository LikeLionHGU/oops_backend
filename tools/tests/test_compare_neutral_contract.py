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
