"""Synthetic source-only v7 promotion/rebuild/rollback tests; no model or network."""
import copy
import hashlib
import json
import sys
from pathlib import Path
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_incident_publish import setup, update_test_source_lock
import test_build_context_units as units_fixture
import build_incident_dataset as build
import build_context_units as units_tool


def write(root, name, value):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    return path


def configured(root):
    incident = setup(root)
    first = build.publish(root)
    fixture = units_fixture.ContextUnitTests()
    fixture.setUp()
    try:
        unit = copy.deepcopy(fixture.unit)
        unit.update(id=incident["incidentId"] + "#unit", parentCaseId="synthetic-card", familyId=incident["incidentId"])
        fixture.authorize_source_use(unit)
        (root / "source.json").write_bytes((fixture.root / "source.json").read_bytes())
        for relative in ("rights/source.json", "rights/review.json"):
            target = root / relative; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((fixture.root / relative).read_bytes())
        profile = fixture.profile()
        profile["unitId"] = unit["id"]
        write(root, "context-units/unit.json", unit)
        write(root, "context-relations/unit.json", profile)
        write(root, "definitions/context-routing.json", fixture.catalog)
        write(root, build.POLICY, {"schemaVersion": "incident-runtime-selection-1", "targetSchema": "review-guidelines-7"})
    finally:
        fixture.doCleanups()
    return first


def test_v7_rebuild_is_repeatable_and_preserves_v4_history(tmp_path):
    old = configured(tmp_path)
    before = (tmp_path / "guidelines/runtime.json").read_bytes()
    v7 = build.publish(tmp_path)
    assert v7["runtimeSchema"] == "review-guidelines-7"
    assert build.verify(tmp_path) == v7 == build.publish(tmp_path)
    assert (tmp_path / "legacy/runtime-history" / (old["runtimeSha256"] + ".json")).read_bytes() == before
    runtime, _ = build.generate(tmp_path)
    assert runtime["humanValidated"] is False
    assert runtime["sourceCases"][0]["status"] == "UNREVIEWED_DRAFT"
    assert build.generate(tmp_path, apply_runtime_selection=False)[0]["schemaVersion"] == "review-guidelines-4"
    assert {build.POLICY, "context-units/unit.json", "context-relations/unit.json", "source.json",
            "definitions/context-routing.json"} <= set(v7["inputHashes"])


def test_profile_change_is_stale_until_rebuilt_and_preserves_raw(tmp_path):
    configured(tmp_path)
    first = build.publish(tmp_path)
    before = json.loads((tmp_path / "guidelines/runtime.json").read_text())["examples"][0]["context"]
    path = tmp_path / "context-relations/unit.json"
    profile = json.loads(path.read_text())
    profile["linkage"] = "새로운 검색 설명"
    write(tmp_path, "context-relations/unit.json", profile)
    with pytest.raises(ValueError, match="RUNTIME_NOT_EQUIVALENT"):
        build.verify(tmp_path)
    second = build.publish(tmp_path)
    assert second["runtimeSha256"] != first["runtimeSha256"]
    assert json.loads((tmp_path / "guidelines/runtime.json").read_text())["examples"][0]["context"] == before


def test_missing_policy_never_silently_downgrades_active_v7(tmp_path):
    configured(tmp_path)
    build.publish(tmp_path)
    before = (tmp_path / "guidelines/runtime.json").read_bytes()
    (tmp_path / build.POLICY).unlink()
    with pytest.raises(ValueError, match="EXPLICIT_SCHEMA_DOWNGRADE_REQUIRED"):
        build.publish(tmp_path)
    assert (tmp_path / "guidelines/runtime.json").read_bytes() == before
    restored = build.publish(tmp_path, allow_schema_downgrade=True)
    assert restored["runtimeSchema"] == "review-guidelines-4"
    assert build.verify(tmp_path) == restored


def test_missing_profile_or_changed_source_does_not_replace_runtime(tmp_path):
    configured(tmp_path)
    build.publish(tmp_path)
    before = (tmp_path / "guidelines/runtime.json").read_bytes()
    profile = tmp_path / "context-relations/unit.json"
    original = profile.read_bytes()
    profile.unlink()
    with pytest.raises(ValueError, match="V7_CONTEXT_UNITS_AND_PROFILES_REQUIRED"):
        build.publish(tmp_path)
    profile.write_bytes(original)
    (tmp_path / "source.json").write_text("{}")
    with pytest.raises(ValueError, match="UNIT_SOURCE_HASH_MISMATCH"):
        build.publish(tmp_path)
    assert (tmp_path / "guidelines/runtime.json").read_bytes() == before


def test_unknown_policy_never_falls_back(tmp_path):
    setup(tmp_path)
    write(tmp_path, build.POLICY, {"schemaVersion": "incident-runtime-selection-1", "targetSchema": "latest"})
    with pytest.raises(ValueError, match="INVALID_RUNTIME_POLICY"):
        build.prepare(tmp_path)


def test_new_incident_is_retained_without_reverting_to_v4(tmp_path):
    configured(tmp_path)
    first = build.publish(tmp_path)
    original = json.loads(next((tmp_path / "incidents").glob("*.json")).read_text())
    new = copy.deepcopy(original)
    new["incidentId"] = "second-incident"
    new["catalogMeta"].update(familyId="second-incident", caseIds=["second-card"], collectionId=None)
    s = new["statements"][0]
    s["id"] = "second-card"
    s["legacyCard"].update(id="second-card", familyId="second-incident", reactions=[])
    s["legacyCard"]["controversy"].update(reasonHypotheses=[], reasonSupportReactionIds=[])
    write(tmp_path, "incidents/second-incident.json", new)
    with pytest.raises(ValueError, match="INCIDENT_SOURCE_REVIEW_REQUIRED"):
        build.publish(tmp_path)
    update_test_source_lock(tmp_path)  # explicit synthetic review/pin for this builder test
    second = build.publish(tmp_path)
    assert second["runtimeSchema"] == "review-guidelines-7"
    assert second["examples"] == first["examples"] + 1
    added = [e for e in build.generate(tmp_path)[0]["examples"] if e["id"] == "second-card"][0]
    assert added["routingKeys"] == [] and "relationProfile" not in added
    assert build.verify(tmp_path) == second


def test_policy_change_during_publish_restores_runtime_and_manifest(tmp_path, monkeypatch):
    configured(tmp_path)
    build.publish(tmp_path)
    runtime_path = tmp_path / "guidelines/runtime.json"
    manifest_path = tmp_path / "guidelines/build-manifest.json"
    previous_runtime = runtime_path.read_bytes()
    previous_manifest = manifest_path.read_bytes()
    relation_path = tmp_path / "context-relations/unit.json"
    relation = json.loads(relation_path.read_text())
    relation["linkage"] = "새 검색 관계 설명"
    write(tmp_path, "context-relations/unit.json", relation)

    original_write = build.write_private
    changed = False

    def mutate_policy_after_runtime_write(path, value):
        nonlocal changed
        original_write(path, value)
        if Path(path) == runtime_path and not changed:
            lock_path = tmp_path / "definitions/incident-source-lock.json"
            lock = json.loads(lock_path.read_text())
            lock["testMutation"] = True
            write(tmp_path, "definitions/incident-source-lock.json", lock)
            changed = True

    monkeypatch.setattr(build, "write_private", mutate_policy_after_runtime_write)
    with pytest.raises(ValueError, match="BUILD_MANIFEST_MISMATCH"):
        build.publish(tmp_path)

    assert changed
    assert runtime_path.read_bytes() == previous_runtime
    assert manifest_path.read_bytes() == previous_manifest


def test_policy_change_after_prepare_is_rejected_before_any_write(tmp_path, monkeypatch):
    configured(tmp_path)
    build.publish(tmp_path)
    runtime_path = tmp_path / "guidelines/runtime.json"
    manifest_path = tmp_path / "guidelines/build-manifest.json"
    previous_runtime = runtime_path.read_bytes()
    previous_manifest = manifest_path.read_bytes()
    relation_path = tmp_path / "context-relations/unit.json"
    relation = json.loads(relation_path.read_text())
    relation["linkage"] = "게시 전 정책 변경 검사"
    write(tmp_path, "context-relations/unit.json", relation)

    original_prepare = build.prepare

    def mutate_policy_after_prepare(root):
        result = original_prepare(root)
        lock_path = tmp_path / "definitions/incident-source-lock.json"
        lock = json.loads(lock_path.read_text())
        lock["testMutation"] = True
        write(tmp_path, "definitions/incident-source-lock.json", lock)
        return result

    monkeypatch.setattr(build, "prepare", mutate_policy_after_prepare)
    with pytest.raises(ValueError, match="INCIDENT_SOURCE_POLICY_CHANGED_DURING_BUILD"):
        build.publish(tmp_path)

    assert runtime_path.read_bytes() == previous_runtime
    assert manifest_path.read_bytes() == previous_manifest


def test_preview_works_after_promotion_without_reading_v7_as_its_base(tmp_path, monkeypatch):
    configured(tmp_path)
    active = build.publish(tmp_path)
    output = tmp_path / "experiments/preview"
    output.parent.mkdir()
    monkeypatch.setattr(sys, "argv", ["build_context_units", "--dataset-root", str(tmp_path), "--output", str(output),
                                    "--relation-profiles", str(tmp_path / "context-relations")])
    units_tool.main()
    manifest = json.loads((output / "manifest.json").read_text())
    assert manifest["activeRuntimeFileSha256"] == active["runtimeSha256"]
    assert manifest["baseRuntimeFileSha256"] != active["runtimeSha256"]
    assert hashlib.sha256((output / "runtime.json").read_bytes()).hexdigest() == active["runtimeSha256"]
    assert build.verify(tmp_path) == active
