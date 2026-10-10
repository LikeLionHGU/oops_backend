import json
import subprocess
import sys
from pathlib import Path
from unittest import mock

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ablate_review_prompts as tool
from test_compare_review_prompts import fixture


def inputs(tmp_path):
    snapshot, archive = fixture()
    for time in [14500, 21000, 23500, 24500, 26000]:
        snapshot["transcript"].append({"startMs": time, "endMs": time + 1000, "text": f"합성 원문 {time}"})
    paths = [tmp_path / x for x in ["snapshot.json", "archive.json", "report.json"]]
    paths[0].write_text(json.dumps(snapshot)); paths[1].write_text(json.dumps(archive))
    return paths


def test_ablation_changes_only_the_two_additions():
    old = tool.prompts(subprocess.check_output(["git", "show", tool.BASELINE + ":" + tool.ENGINE], cwd=tool.ROOT, text=True))[2]
    current = tool.prompts((tool.ROOT / tool.ENGINE).read_text())[2]
    arms = dict(tool.ablation_arms(old, current))
    assert arms["baseline-28"] == old and arms["current-31"] == current
    assert "상황 → 평가 → 평가 대상" in arms["judgment-only"]
    assert "PASS에도 대조 해석이 필수" not in arms["judgment-only"]
    assert "상황 → 평가 → 평가 대상" not in arms["pass-contract-only"]
    assert "PASS에도 대조 해석이 필수" in arms["pass-contract-only"]
    with pytest.raises(ValueError):
        tool.ablation_arms(old, current + "unexpected")


def test_dry_run_does_not_read_key_and_twenty_two_call_cap(tmp_path):
    paths = inputs(tmp_path)
    with mock.patch.object(tool, "read_key", return_value="test-key") as key, \
            mock.patch.object(tool, "complete", return_value={"output": {"verifications": []}}) as complete:
        report = tool.run(*paths)
        assert report["plannedCalls"] == 22
        key.assert_not_called(); complete.assert_not_called()
        report = tool.run(*paths, execute=True)
        assert complete.call_count == 22
        assert len(report["calls"]) == 22
        for case in report["cases"]:
            assert {c["inputSha256"] for c in report["calls"] if c["case"] == case["name"]} == {case["inputSha256"]}
        for case in report["cases"]:
            data = json.loads((tmp_path / f'ablation-{case["name"]}-input.json').read_text())
            assert data["transcript"][0]["text"] == case["payload"]["raw"][0]["text"]
        with pytest.raises(ValueError, match="REPEAT_LIMIT"):
            tool.run(*paths, execute=True, repeats=4)


def test_failures_do_not_retry_or_expose_messages(tmp_path):
    paths = inputs(tmp_path)
    with mock.patch.object(tool, "read_key", return_value="test-key"), \
            mock.patch.object(tool, "complete", side_effect=RuntimeError("SECRET")) as complete:
        report = tool.run(*paths, execute=True, repeats=1)
        assert complete.call_count == 10
        assert all(c["error"] == "RuntimeError" for c in report["calls"])
        assert "SECRET" not in paths[2].read_text()


def test_audit_export_keeps_assessments_and_fixed_quote_ids(tmp_path):
    paths = inputs(tmp_path)
    report = tool.run(*paths)
    case = report["cases"][0]
    assessment = {"candidateId": "candidate-1", "assessment": {"decision": "PASS", "segmentId": "stt-replay-2"}}
    report["calls"] = [{"case": case["name"], "arm": "baseline-28", "repeat": 0,
                        "output": {"verifications": [assessment]}}]
    tool.export_audits(report, tmp_path)
    exported = json.loads((tmp_path / "ablation-B-23.5-report.json").read_text())["arms"][0]
    assert exported["verifications"] == [assessment]
    proposal = exported["calls"][0]["output"]["candidates"][0]
    assert proposal["anchorId"] == case["payload"]["candidates"][0]["anchorId"]
    assert proposal["evidence"] == case["payload"]["candidates"][0]["proposedEvidence"]
