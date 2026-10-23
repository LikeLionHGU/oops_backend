#!/usr/bin/env python3
"""incidents/*.json 단일 입력 빌드 — 생성↔검증 분리 (마이그레이션 4단계).

왜 이 도구인가 (§3, 결함 A 제거):
  레거시 build_bundle 은 cards 를 생성하기 '전에' validate_catalog 가 옛 cards.json 과 catalog 의
  일치를 선검증한다(controversy_cards.py:184 -> review_dataset_catalog.commonCardsDraft 대조).
  이게 'cards 를 바꾸려면 옛 cards 가 catalog 와 맞아야 하는' 닭-달걀 순환을 만든다.

  이 빌드는 incidents/*.json 을 단일 입력으로 bundle 을 '직접 조립'한다. incidents 에는 이미
  convert 된 카드(legacyCard)가 무손실 보존돼 있으므로 convert·댓글 대조·catalog 순환이 전부
  불필요하다. v4 기반을 재생성하고 명시적 runtime 정책에 따라 v7 맥락 단위를 결합한다.

  --write : incidents + definitions/build.json + 기사 발췌에서 runtime 생성. 기존 출력은 읽지 않는다.
  --verify: 같은 입력에서 재생성하고 저장 runtime 및 입력/출력 해시 manifest를 대조한다.

실제 incidents/*.json 은 /datasets/ 아래 Git 제외 로컬 생성물이다.
"""
import argparse
import datetime
import hashlib
import json
import shutil
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
import sys
sys.path.insert(0, str(ROOT))
from review_context_pilot import parse, read_bytes, require
from build_context_dictionary import compile_dictionary
from build_review_guidelines import compile_guidelines
from build_review_guidelines import write_private
from review_dataset_catalog import inside
import incidents_migration as mig

DEFAULT_ROOT = ROOT / "datasets/controversy"
POLICY = "definitions/runtime-selection.json"


def runtime_target(root):
    path = root / POLICY
    require(not path.is_symlink(), "RUNTIME_POLICY_SYMLINK_REFUSED")
    if not path.exists():
        return "review-guidelines-4"  # Historical datasets are not implicitly promoted.
    value = parse(read_bytes(path))
    require(set(value) == {"schemaVersion", "targetSchema"}
            and value["schemaVersion"] == "incident-runtime-selection-1"
            and value["targetSchema"] in {"review-guidelines-4", "review-guidelines-7"}, "INVALID_RUNTIME_POLICY")
    return value["targetSchema"]


def context_inputs(root):
    """Canonical authored inputs only, never a generated preview/runtime."""
    units = sorted((root / "context-units").glob("*.json"))
    profiles = sorted((root / "context-relations").glob("*.json"))
    require(units and profiles, "V7_CONTEXT_UNITS_AND_PROFILES_REQUIRED")
    return units, profiles


def load_incidents(root: Path):
    directory = root / "incidents"
    require(directory.is_dir(), "INCIDENTS_DIR_REQUIRED")
    files = sorted(directory.glob("*.json"))
    require(len(files) > 0, "NO_INCIDENTS")
    incidents = [parse(read_bytes(p)) for p in files]
    for p, incident in zip(files, incidents):
        require(incident.get("schemaVersion") == "incident-2"
                and incident.get("status") == "UNREVIEWED_DRAFT"
                and all(incident.get(k) is False for k in
                        ("humanApproved", "runtimeEligible", "trainingUseAuthorized")), "INCIDENT_CONTRACT_REQUIRED")
        require(p.stem == incident.get("incidentId"), "INCIDENT_FILENAME_MISMATCH")
    return incidents


def generate(root: Path, apply_runtime_selection=True):
    """Read incidents, explicit shared definitions and snapshots; never old outputs."""
    incidents = load_incidents(root)
    # bundle/plan/catalog 조각을 incidents 에서 직접 재조립. 순환 검증(validate_catalog) 미경유.
    cards, gplan, dplan, catalog = mig.reassemble(root, incidents)
    snapshots = {}
    for cfg in dplan["cards"].values():
        for d in cfg.get("documents", []):
            snapshots[d["snapshot"]] = read_bytes(inside(root, d["snapshot"]))
    # sourceBundleSha256 는 cards.json 의 '디스크 바이트' 해시다. 레거시 write_private 포맷
    # (json.dump(..., ensure_ascii=False, indent=2) + 끝 개행)을 그대로 재현해야 해시가 일치한다.
    # drafts/cards.json 을 읽지 않는다 — incidents 단일 입력 원칙(순환 제거).
    source_bytes = (json.dumps(cards, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    dictionary = compile_dictionary(cards, dplan, gplan, source_bytes, snapshots)
    runtime = compile_guidelines(cards, gplan, source_bytes, dictionary)
    if apply_runtime_selection and runtime_target(root) == "review-guidelines-7":
        from build_context_units import compile_preview
        units, profiles = context_inputs(root)
        runtime, _ = compile_preview(runtime, [parse(read_bytes(p)) for p in units],
                                     parse(read_bytes(inside(root, "definitions/context-routing.json"))), root,
                                     [parse(read_bytes(p)) for p in profiles])
        require(runtime["schemaVersion"] == "review-guidelines-7", "V7_COMPILE_REQUIRED")
    return runtime, dictionary


def _strip_ts(obj):
    o = dict(obj)
    o.pop("refreshOrDeleteBy", None)
    return o


def input_hashes(root):
    """Only canonical inputs; historical files and generated output never participate."""
    inputs = {"definitions/build.json"}
    if (root / POLICY).exists():
        inputs.add(POLICY)
    if runtime_target(root) == "review-guidelines-7":
        units, profiles = context_inputs(root)
        inputs.add("definitions/context-routing.json")
        for path in units + profiles:
            inputs.add(path.relative_to(root).as_posix())
        for path in units:
            unit = parse(read_bytes(path))
            source = unit["source"]
            inputs.update(c["approvalSource"]["path"] for c in unit["normalContrasts"] if "approvalSource" in c)
            inputs.add(source["path"])
            if source["kind"] == "STORED_OCR_FRAME_SNAPSHOT":
                document = parse(read_bytes(inside(root, source["path"])))
                inputs.update(document[k]["path"] for k in ("frame", "ocrSnapshot"))
    for p in (root / "incidents").glob("*.json"):
        inputs.add(p.relative_to(root).as_posix())
        incident = parse(read_bytes(p))
        for s in incident["statements"]:
            for document in s["legacyDictionaryCard"].get("documents", []):
                inputs.add(document["snapshot"])
    return {name: hashlib.sha256(read_bytes(inside(root, name))).hexdigest()
            for name in sorted(inputs)}


def prepare(root):
    hashes = input_hashes(root)
    runtime, dictionary = generate(root)
    require(all(re.fullmatch(r"[a-zA-Z0-9_-]{1,64}(?:#[a-zA-Z0-9_-]{1,64})?", c["caseId"])
                and re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", c["familyId"])
                for c in runtime["sourceCases"]), "JAVA_SOURCE_ID_CONTRACT")
    require(hashes == input_hashes(root), "INPUT_CHANGED_DURING_BUILD")
    raw = (json.dumps(runtime, ensure_ascii=False, indent=2) + "\n").encode()
    require(len(raw) <= 1_048_576, "RUNTIME_EXCEEDS_JAVA_LIMIT")
    require(datetime.datetime.fromisoformat(runtime["refreshOrDeleteBy"]) >
            datetime.datetime.now(datetime.timezone.utc), "REFERENCE_EXPIRED")
    manifest = {"schemaVersion": "incident-build-manifest-1", "version": runtime["version"],
                "runtimeSchema": runtime["schemaVersion"],
                "inputHashes": hashes, "runtimeSha256": hashlib.sha256(raw).hexdigest(),
                "sourceBundleSha256": runtime["sourceBundleSha256"],
                "contextDictionarySha256": runtime["contextDictionarySha256"],
                "incidents": len(load_incidents(root)), "examples": len(runtime["examples"]),
                "mechanisms": len(runtime["guidelines"])}
    return runtime, manifest


def verify(root):
    runtime, expected = prepare(root)
    saved_bytes = read_bytes(root / "guidelines/runtime.json")
    require(_strip_ts(runtime) == _strip_ts(parse(saved_bytes)), "RUNTIME_NOT_EQUIVALENT")
    manifest = parse(read_bytes(root / "guidelines/build-manifest.json"))
    require(manifest == expected and manifest["runtimeSha256"] == hashlib.sha256(saved_bytes).hexdigest(),
            "BUILD_MANIFEST_MISMATCH")
    return expected


def publish(root, allow_schema_downgrade=False):
    runtime, manifest = prepare(root)
    output = root / "guidelines/runtime.json"
    if output.exists():
        previous = read_bytes(output)
        require(not (parse(previous).get("schemaVersion") == "review-guidelines-7"
                     and runtime["schemaVersion"] == "review-guidelines-4") or allow_schema_downgrade,
                "EXPLICIT_SCHEMA_DOWNGRADE_REQUIRED")
        sha = hashlib.sha256(previous).hexdigest()
        history = root / "legacy/runtime-history" / (sha + ".json")
        if history.exists():
            require(read_bytes(history) == previous, "HISTORY_HASH_MISMATCH")
        else:
            history.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            shutil.copyfile(output, history)
            history.chmod(0o600)
            require(read_bytes(history) == previous, "HISTORY_COPY_MISMATCH")
    require(manifest["inputHashes"] == input_hashes(root), "INPUT_CHANGED_BEFORE_PUBLISH")
    write_private(output, runtime)
    # A crash between the two replacements is detected by verify; rerun --write to repair.
    write_private(root / "guidelines/build-manifest.json", manifest)
    verify(root)
    return manifest


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dataset-root", type=Path, default=DEFAULT_ROOT)
    p.add_argument("--write", action="store_true", help="runtime 을 생성해 기록(입력만 읽음)")
    p.add_argument("--verify", action="store_true", help="저장된 runtime 과 재생성본의 동치 검증(출력만 비교)")
    p.add_argument("--allow-schema-downgrade", action="store_true", help="--write 시에만 명시적 v7→v4 복원 허용")
    args = p.parse_args()
    if args.write and args.verify:
        p.error("WRITE_OR_VERIFY_NOT_BOTH")
    if args.allow_schema_downgrade and not args.write:
        p.error("SCHEMA_DOWNGRADE_REQUIRES_WRITE")
    try:
        if args.verify:
            manifest = verify(args.dataset_root)
        elif args.write:
            manifest = publish(args.dataset_root, args.allow_schema_downgrade)
        else:
            _, manifest = prepare(args.dataset_root)
        result = {k: v for k, v in manifest.items() if k != "inputHashes"}
        result.update(written=args.write, verified=args.verify or args.write)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Exception as error:  # noqa: BLE001 — CLI 경계에서 사유를 그대로 전달
        p.exit(2, f"{type(error).__name__}: {error}\n")


if __name__ == "__main__":
    main()
