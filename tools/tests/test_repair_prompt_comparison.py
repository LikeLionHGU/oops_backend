import json
import sys
from pathlib import Path
from unittest import mock

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import compare_neutral_contract as comparison
import repair_prompt_comparison as tool
from test_ablate_review_prompts import inputs


def fixture(tmp_path):
    paths = inputs(tmp_path)
    snapshot = json.loads(paths[0].read_text())
    snapshot["transcript"].extend([
        {"startMs": 75000, "endMs": 77000, "text": "앞 원문"},
        {"startMs": 79000, "endMs": 80500, "text": "비유 원문"},
    ])
    paths[0].write_text(json.dumps(snapshot))
    with mock.patch.object(comparison, "read_key", return_value="test-key"), \
            mock.patch.object(comparison, "complete", return_value={"output": {"verifications": []}}):
        report = comparison.run(paths[0], paths[1], tmp_path / "source" / "run.json",
                                execute=True, profile="contrast-only")
    audit = {"sourceReportSha256": tool.digest(report), "results": [
        {"case": "B-23.5", "arm": "contrast-only-proposal", "repeat": i,
         "accepted": False, "failureCode": "TARGET_EVIDENCE_REQUIRED"} for i in range(3)]}
    audit["results"].append({"case": "C", "arm": "current-32", "repeat": 0,
                              "accepted": False, "failureCode": "TARGET_EVIDENCE_REQUIRED"})
    audit_path = tmp_path / "audit.json"
    audit_path.write_text(json.dumps(audit))
    return tmp_path / "source" / "run.json", audit_path, paths[1]


def test_repairs_are_bounded_frozen_and_do_not_force_warning(tmp_path):
    paths = fixture(tmp_path)
    with mock.patch.object(tool, "read_key", return_value="test-key") as key, \
            mock.patch.object(tool, "complete", return_value={"output": {"verifications": []}}) as complete:
        result = tool.run(*paths, tmp_path / "plan" / "run.json")
        key.assert_not_called(); complete.assert_not_called()
        result = tool.run(*paths, tmp_path / "paid" / "run.json", execute=True)
    assert complete.call_count == result["plannedCalls"] == 3
    assert {c["name"] for c in result["cases"]} == {"B-23.5"}
    for args in complete.call_args_list:
        assert "경고로 복구할 의무는 없다" in args.args[1]
        assert args.args[2]["repair"] == {"attempt": 1, "failureCode": "TARGET_EVIDENCE_REQUIRED"}
    with pytest.raises(ValueError, match="NEW_OUTPUT_DIRECTORY_REQUIRED"):
        tool.run(*paths, tmp_path / "paid" / "run.json")


def test_mismatched_audit_or_changed_reference_blocks_api(tmp_path):
    paths = fixture(tmp_path)
    archive = json.loads(paths[2].read_text())
    archive["guidelines"] = []
    paths[2].write_text(json.dumps(archive))
    with mock.patch.object(tool, "complete") as complete:
        with pytest.raises(ValueError, match="FROZEN_REFERENCE_REQUIRED"):
            tool.run(*paths, tmp_path / "bad-reference" / "run.json", execute=True)
        audit = json.loads(paths[1].read_text()); audit["sourceReportSha256"] = "changed"
        paths[1].write_text(json.dumps(audit))
        with pytest.raises(ValueError, match="MATCHED_CONTRAST_AUDIT_REQUIRED"):
            tool.run(*paths, tmp_path / "bad-audit" / "run.json", execute=True)
    complete.assert_not_called()


def test_failures_are_redacted_and_not_retried(tmp_path):
    paths = fixture(tmp_path)
    with mock.patch.object(tool, "read_key", return_value="test-key"), \
            mock.patch.object(tool, "complete", side_effect=RuntimeError("SECRET")) as complete:
        result = tool.run(*paths, tmp_path / "failure" / "run.json", execute=True)
    assert complete.call_count == 3
    assert all(c["error"] == "RuntimeError" for c in result["calls"])
    assert "SECRET" not in (tmp_path / "failure" / "run.json").read_text()
