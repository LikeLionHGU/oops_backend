#!/usr/bin/env python3
"""사건 추가 도구 — 사건=파일 하나 (§10.8).

최소 스펙(JSON)에서 완전한 incident-2 를 조립해 incidents/{id}.json 하나로 기록한다.
기사만으로(reactions 0, collectionId null) 사건이 성립한다(결함 B 제거, 5단계).

중요 규칙: 후보 사건 초안만 격리 경로에 만들 수 있다. 런타임 입력에 넣는 작업은
incident_intake.py의 별도 승인·해시 미리보기·영향 빌드 경로만 사용한다.
이 도구는 기본적으로 DRY-RUN이며, --candidate-output은 research/pending-incidents/ 아래에
새 초안 파일만 기록한다. 기존 파일 덮어쓰기와 직접 --apply는 지원하지 않는다.

입력 스펙(최소):
  {
    "incidentId": "channel-topic-yyyymmdd",
    "statements": [
      {
        "caseId": "...#s1" | 임의 유일 id,
        "knownPoint": "이 발언이 왜 논란인지 한 줄",
        "mechanismIds": ["local-life-devaluation", ...],   // 1개 이상, gplan 에 이미 있는 id
        "topicTags": ["지역","음식"],                       // 1~8개
        "article": {                                        // 선택 — 기사 근거
          "publisher": "...", "url": "...", "publishedDate": "YYYY-MM-DD",
          "snapshot": "research/source-snapshots/..-extract.txt", "sha256": "..."
        }
      }
    ]
  }
reactions·댓글은 받지 않는다(기사-only). 필요하면 승인 후 별도 보강.

실제 incidents/*.json 은 /datasets/ 아래 Git 제외 로컬 생성물이다.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
import sys
sys.path.insert(0, str(ROOT))
from review_context_pilot import require
import incidents_migration as mig

DEFAULT_ROOT = ROOT / "datasets/controversy"


def _article_snapshot(root, relative):
    require(isinstance(relative, str) and relative.startswith("research/source-snapshots/")
            and "\\" not in relative and "\0" not in relative,
            "PRIVATE_SOURCE_SNAPSHOT_PATH_REQUIRED")
    parts = PurePosixPath(relative)
    require(not parts.is_absolute() and ".." not in parts.parts and parts.as_posix() == relative,
            "UNSAFE_SOURCE_SNAPSHOT_PATH")
    root = Path(root).resolve()
    path = root
    for part in parts.parts:
        path = path / part
        require(not path.is_symlink(), "SOURCE_SNAPSHOT_SYMLINK_REFUSED")
    resolved = path.resolve()
    require(resolved != root and resolved.is_relative_to(root) and resolved.is_file(), "SNAPSHOT_NOT_FOUND")
    return resolved


def complete_card(case_id, incident_id, known_point, article, boundaries=None, source_id=None):
    """REPORTED_CONTEXT_ONLY 최소 완전 카드. validate_bundle 전 필수 필드를 모두 채운다."""
    sources, reported_quotes = [], []
    if article is not None:
        # 기사 발췌 document 의 sourceId 는 evidence.sources 에 등록돼 있어야 한다
        # (compile_dictionary SOURCE_DOCUMENT_REFERENCE_REQUIRED).
        sources = [{"id": source_id, "publisher": article["publisher"],
                    "url": article["url"], "publishedDate": article["publishedDate"],
                    "retrievedOn": article.get("retrievedOn", article["publishedDate"]),
                    "kind": "NEWS_REPORT"}]
    # 기사 발췌가 있으면 evidence.sources 에 등록(인용 자체는 reportedQuotes 없이 — documents 경유).
    return {
        "id": case_id, "familyId": incident_id, "status": "UNREVIEWED_DRAFT",
        "knownPoint": {"text": known_point, "basis": "SOURCE_RESEARCH_NOT_INDEPENDENTLY_VERIFIED"},
        "evidence": {"level": "REPORTED_CONTEXT_ONLY", "originalVideoVerified": False,
                     "segments": [], "reportedQuotes": reported_quotes, "frames": [],
                     "sources": sources, "speechProvenance": None,
                     "windowMs": None, "anchorSegmentId": None},
        "context": {"coverage": "REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT", "target": None,
                    "targetStatus": "NOT_RECONSTRUCTED",
                    "missingInformation": [
                        "기사 서술에 근거한 참조 사례다. 원영상·원음·화자·억양·전후 전체 맥락은 재확인하지 않았으며 "
                        "게시 전 탐지의 직접 근거로 쓰지 않는다(같은 family 평가 입력 제외)."],
                    "sequenceInterpretations": []},
        "controversy": {"status": "KNOWN_POINT_REASON_MAPPING_PENDING_REVIEW",
                        "reasonHypotheses": [], "reasonSupportReactionIds": [],
                        "boundaries": list(boundaries or [])},
        "reactions": [],
        "review": {"status": "PENDING", "humanApproved": False, "privacyReviewed": False,
                   "datasetUseAuthorized": False, "trainingUseAuthorized": False, "runtimeEligible": False},
        "provenance": {"generatedBy": "add_incident", "legacyFilename": None, "legacySha256": None},
    }


def assemble(spec, root: Path):
    require(isinstance(spec.get("incidentId"), str) and spec["incidentId"], "INCIDENT_ID_REQUIRED")
    require(isinstance(spec.get("statements"), list) and spec["statements"], "STATEMENTS_REQUIRED")
    incident_id = spec["incidentId"]

    # 중복 사건 금지: incidents/ 에 이미 있으면 거부.
    if (root / "incidents" / (incident_id + ".json")).exists():
        require(False, "INCIDENT_ALREADY_EXISTS")

    # gplan 에 등록된 mechanism 만 허용(UNKNOWN_MECHANISM 사전 차단, 친절한 메시지).
    from incident_build_definition import load as load_definition
    gplan = load_definition(root)["guidelines"]
    known_mechanisms = {m["id"] for m in gplan["mechanisms"]}

    statements, case_ids = [], set()
    for st in spec["statements"]:
        cid = st.get("caseId")
        require(isinstance(cid, str) and cid and cid not in case_ids, "CASE_ID_REQUIRED_UNIQUE")
        case_ids.add(cid)
        mids = st.get("mechanismIds")
        require(isinstance(mids, list) and mids and len(set(mids)) == len(mids), "MECHANISM_IDS_REQUIRED")
        unknown = set(mids) - known_mechanisms
        require(not unknown, "UNKNOWN_MECHANISM:" + ",".join(sorted(unknown)))
        tags = st.get("topicTags")
        require(isinstance(tags, list) and 1 <= len(tags) <= 8, "TOPIC_TAGS_1_8_REQUIRED")
        require(isinstance(st.get("knownPoint"), str) and st["knownPoint"], "KNOWN_POINT_REQUIRED")
        boundaries = st.get("boundaries")
        if boundaries is not None:
            require(isinstance(boundaries, list)
                    and all(isinstance(b, str) and b for b in boundaries), "BOUNDARIES_LIST_REQUIRED")
        documents = []
        source_id = None
        article = st.get("article")
        if article is not None:
            for k in ("publisher", "url", "publishedDate", "snapshot", "sha256"):
                require(isinstance(article.get(k), str) and article[k], "ARTICLE_FIELD_REQUIRED:" + k)
            # 발췌 본문을 읽어 실제 코드포인트 범위를 계산한다(0/0 금지, 해시 검증).
            snap_path = _article_snapshot(root, article["snapshot"])
            raw = snap_path.read_bytes()
            require(hashlib.sha256(raw).hexdigest() == article["sha256"], "SNAPSHOT_HASH_MISMATCH")
            body = raw.decode("utf-8")
            end_cp = len(body)  # 발췌 전체를 참조 범위로
            require(end_cp <= 2000, "EXTRACT_TOO_LONG_MAX_2000_CODEPOINTS")
            source_id = "src-" + cid
            documents = [{"id": cid + "-reported", "sourceId": source_id, "materialKind": "NEWS_BODY",
                          "snapshot": article["snapshot"], "sha256": article["sha256"],
                          "statementKind": "AUTHOR_INTERPRETATION",
                          "locationHint": article["publisher"] + " " + article["publishedDate"],
                          "snapshotCoverage": "SHORT_EXTRACT_NOT_FULL_ARTICLE", "originGroupId": None,
                          "retrievedOn": article.get("retrievedOn", article["publishedDate"]),
                          "startCodePoint": 0, "endCodePoint": end_cp, "excerpt": body[0:end_cp]}]
        legacy_dict = {"topicTags": tags, "mechanismIds": mids, "documents": documents}
        statements.append({
            "id": cid, "mechanismIds": mids, "topicTags": tags,
            "legacyDictionaryCard": legacy_dict,
            "legacyCard": complete_card(cid, incident_id, st["knownPoint"], article, boundaries, source_id),
        })
        if "criticismHypotheses" in st:
            from criticism_hypotheses import validate as validate_criticism_hypotheses
            validate_criticism_hypotheses(st["criticismHypotheses"], statements[-1]["legacyCard"])
            statements[-1]["criticismHypotheses"] = st["criticismHypotheses"]

    return {
        "schemaVersion": "incident-2", "incidentId": incident_id,
        "status": "UNREVIEWED_DRAFT", "humanApproved": False, "runtimeEligible": False,
        "trainingUseAuthorized": False, "split": "DEVELOPMENT",
        "catalogMeta": {"familyId": incident_id, "caseIds": list(case_ids),
                        "collectionId": None, "evidenceLevel": "REPORTED_CONTEXT_ONLY",
                        "selectedReactions": 0},
        "statements": statements,
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("spec", type=Path, help="사건 최소 스펙 JSON 파일")
    p.add_argument("--dataset-root", type=Path, default=DEFAULT_ROOT)
    p.add_argument("--apply", action="store_true",
                   help="폐기 예정 경로. 검수된 intake를 우회할 수 없어 항상 거부한다.")
    p.add_argument("--candidate-output", type=str,
                   help="선택: 새 연구 초안 JSON을 research/pending-incidents/ 아래에만 생성")
    args = p.parse_args()
    try:
        if args.apply:
            p.error("REVIEWED_INTAKE_REQUIRED: use tools/incident_intake.py")
        spec = json.loads(args.spec.read_text(encoding="utf-8"))
        incident = assemble(spec, args.dataset_root)
        preview = {"incidentId": incident["incidentId"],
                   "statements": [{"id": s["id"], "mechanismIds": s["mechanismIds"],
                                   "topicTags": s["topicTags"],
                                   "knownPoint": s["legacyCard"]["knownPoint"]["text"],
                                   "hasArticle": bool(s["legacyDictionaryCard"]["documents"])}
                                  for s in incident["statements"]],
                   "reactions": 0, "collectionId": None}

        candidate_info = None
        if args.candidate_output:
            rel = args.candidate_output
            require(rel.startswith("research/pending-incidents/") and rel.endswith(".json")
                    and ".." not in Path(rel).parts and "\\" not in rel,
                    "CANDIDATE_OUTPUT_MUST_BE_QUARANTINED")
            require(Path(rel).name == incident["incidentId"] + ".json", "CANDIDATE_OUTPUT_ID_MISMATCH")
            root = args.dataset_root.resolve()
            path = root / rel
            cursor = root
            for part in Path(rel).parts:
                cursor = cursor / part
                require(not cursor.is_symlink(), "SYMLINK_REFUSED")
            require(path.resolve().is_relative_to(root) and not path.exists(), "CANDIDATE_OUTPUT_EXISTS_OR_OUTSIDE_ROOT")
            path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            raw = (json.dumps(incident, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            candidate_info = {"path": rel, "sha256": hashlib.sha256(raw).hexdigest()}
        print(json.dumps({"mode": "DRAFT_PREVIEW", "preview": preview,
                          "candidate": candidate_info,
                          "note": "사람 검수 전에는 빌드 입력이 아닙니다. 런타임 추가는 incident_intake.py 승인 절차만 사용합니다."},
                         ensure_ascii=False, indent=2))
    except Exception as error:  # noqa: BLE001 — CLI 경계
        p.exit(2, f"{type(error).__name__}: {error}\n")


if __name__ == "__main__":
    main()
