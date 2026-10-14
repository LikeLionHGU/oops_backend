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
  --verify: 같은 입력에서 재생성하고 저장 runtime과 refreshOrDeleteBy 제외 동치를 비교한다.

실제 incidents/*.json 은 /datasets/ 아래 Git 제외 로컬 생성물이다.
"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
import sys
sys.path.insert(0, str(ROOT))
from review_context_pilot import parse, read_bytes, require
from build_context_dictionary import compile_dictionary
from build_review_guidelines import compile_guidelines
from review_dataset_catalog import inside
import incidents_migration as mig

DEFAULT_ROOT = ROOT / "datasets/controversy"


def load_incidents(root: Path):
    directory = root / "incidents"
    require(directory.is_dir(), "INCIDENTS_DIR_REQUIRED")
    files = sorted(directory.glob("*.json"))
    require(len(files) > 0, "NO_INCIDENTS")
    return [json.loads(p.read_text(encoding="utf-8")) for p in files]


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


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dataset-root", type=Path, default=DEFAULT_ROOT)
    p.add_argument("--write", action="store_true", help="runtime 을 생성해 기록(입력만 읽음)")
    p.add_argument("--verify", action="store_true", help="저장된 runtime 과 재생성본의 동치 검증(출력만 비교)")
    args = p.parse_args()
    if args.write and args.verify:
        p.error("WRITE_OR_VERIFY_NOT_BOTH")
    try:
        runtime, dictionary = generate(args.dataset_root)
        output = args.dataset_root / "guidelines/runtime.json"
        result = {"status": runtime["status"], "version": runtime["version"],
                  "mechanisms": len(runtime["guidelines"]), "examples": len(runtime["examples"]),
                  "written": False, "verified": False}
        if args.verify:
            saved = parse(read_bytes(output))
            require(_strip_ts(runtime) == _strip_ts(saved), "RUNTIME_NOT_EQUIVALENT")
            result["verified"] = True
        if args.write:
            tmp = output.with_suffix(".json.tmp")
            tmp.write_text(json.dumps(runtime, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            tmp.replace(output)
            result["written"] = True
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Exception as error:  # noqa: BLE001 — CLI 경계에서 사유를 그대로 전달
        p.exit(2, f"{type(error).__name__}: {error}\n")


if __name__ == "__main__":
    main()
