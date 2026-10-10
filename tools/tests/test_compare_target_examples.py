import json
import subprocess
import sys
from pathlib import Path
from unittest import mock

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import compare_target_examples as tool
from test_ablate_review_prompts import inputs


def fixture(tmp_path):
    paths = inputs(tmp_path)
    snapshot = json.loads(paths[0].read_text())
    snapshot["transcript"].extend([
        {"startMs": 75000, "endMs": 77000, "text": "앞 원문"},
        {"startMs": 79000, "endMs": 80500, "text": "비유 원문"},
    ])
    paths[0].write_text(json.dumps(snapshot))
    return paths


def test_only_examples_are_added_without_judgment_change():
    source = subprocess.check_output(["git", "show", tool.CURRENT + ":" + tool.ENGINE], cwd=tool.ROOT, text=True)
    old = tool.prompts(source)[2]
    new = tool.with_examples(old)
    assert new.removesuffix(tool.TARGET_EXAMPLES) == old
    assert tool.prompts((tool.ROOT / tool.ENGINE).read_text())[2] == new
    assert "판정 예시가 아니다" in new
    assert "의미가 가까워도 CONTEXTUAL" in new
    assert "복사하지 않는다" in new
    assert all(name not in tool.TARGET_EXAMPLES for name in ("롯데리아", "영양", "피식대학", "할머니"))
    with pytest.raises(ValueError, match="PINNED_VERIFICATION_REQUIRED"):
        tool.with_examples(new)


def test_plan_is_bounded_and_paid_inputs_are_frozen(tmp_path):
    paths = fixture(tmp_path)
    with mock.patch.object(tool, "read_key", return_value="test-key") as key, \
            mock.patch.object(tool, "complete", return_value={"output": {"verifications": []}}) as complete:
        result = tool.run(paths[0], paths[1], tmp_path / "plan" / "run.json")
        assert result["plannedCalls"] == 22
        key.assert_not_called(); complete.assert_not_called()
        result = tool.run(paths[0], paths[1], tmp_path / "paid" / "run.json", execute=True)
        assert complete.call_count == 22
        assert [c["name"] for c in result["cases"]][:2] == ["B-23.5", "C"]
        assert result["cases"][-1]["name"] == "D"
        for case in result["cases"]:
            assert {c["inputSha256"] for c in result["calls"] if c["case"] == case["name"]} == {case["inputSha256"]}
            assert "developmentExpectation" not in case["payload"]
        with pytest.raises(ValueError, match="NEW_OUTPUT_DIRECTORY_REQUIRED"):
            tool.run(paths[0], paths[1], tmp_path / "paid" / "run.json", execute=True)
        with pytest.raises(ValueError, match="REPEAT_LIMIT"):
            tool.run(paths[0], paths[1], tmp_path / "extra" / "run.json", repeats=4)


def test_failure_redaction_and_no_automatic_retry(tmp_path):
    paths = fixture(tmp_path)
    with mock.patch.object(tool, "read_key", return_value="test-key"), \
            mock.patch.object(tool, "complete", side_effect=RuntimeError("SECRET")) as complete:
        result = tool.run(paths[0], paths[1], tmp_path / "failure" / "run.json", execute=True, repeats=1)
    assert complete.call_count == 14
    assert all(c["error"] == "RuntimeError" for c in result["calls"])
    assert "SECRET" not in (tmp_path / "failure" / "run.json").read_text()
