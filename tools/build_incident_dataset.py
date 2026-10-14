#!/usr/bin/env python3
"""incidents/*.json 단일 입력 빌드 — 생성↔검증 분리 (마이그레이션 4단계).

왜 이 도구인가 (§3, 결함 A 제거):
  레거시 build_bundle 은 cards 를 생성하기 '전에' validate_catalog 가 옛 cards.json 과 catalog 의
  일치를 선검증한다(controversy_cards.py:184 -> review_dataset_catalog.commonCardsDraft 대조).
  이게 'cards 를 바꾸려면 옛 cards 가 catalog 와 맞아야 하는' 닭-달걀 순환을 만든다.

  이 빌드는 incidents/*.json 을 단일 입력으로 bundle 을 '직접 조립'한다. incidents 에는 이미
  convert 된 카드(legacyCard)가 무손실 보존돼 있으므로 convert·댓글 대조·catalog 순환이 전부
  불필요하다. compile 로직은 기존 함수를 그대로 재사용한다(전략 A — runtime 동치 보존).

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


def generate(root: Path):
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
    return runtime, dictionary


def _strip_ts(obj):
    o = dict(obj)
    o.pop("refreshOrDeleteBy", None)
    return o


def input_hashes(root):
    """Only canonical inputs; historical files and generated output never participate."""
    inputs = {"definitions/build.json"}
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


def publish(root):
    runtime, manifest = prepare(root)
    output = root / "guidelines/runtime.json"
    if output.exists():
        previous = read_bytes(output)
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
    args = p.parse_args()
    if args.write and args.verify:
        p.error("WRITE_OR_VERIFY_NOT_BOTH")
    try:
        if args.verify:
            manifest = verify(args.dataset_root)
        elif args.write:
            manifest = publish(args.dataset_root)
        else:
            _, manifest = prepare(args.dataset_root)
        result = {k: v for k, v in manifest.items() if k != "inputHashes"}
        result.update(written=args.write, verified=args.verify or args.write)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Exception as error:  # noqa: BLE001 — CLI 경계에서 사유를 그대로 전달
        p.exit(2, f"{type(error).__name__}: {error}\n")


if __name__ == "__main__":
    main()
