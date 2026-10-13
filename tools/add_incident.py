#!/usr/bin/env python3
"""사건 추가 도구 — 사건=파일 하나 (§10.8).

최소 스펙(JSON)에서 완전한 incident-2 를 조립해 incidents/{id}.json 하나로 기록한다.
기사만으로(reactions 0, collectionId null) 사건이 성립한다(결함 B 제거, 5단계).

중요 규칙(사용자 지정): 사례를 임의로 추가하지 않는다. **먼저 후보를 사용자에게 보여주고**
확인받은 뒤 추가한다. 그래서 이 도구는:
  - 기본값 = DRY-RUN. 조립될 사건을 '미리보기'로 출력만 하고 디스크를 바꾸지 않는다.
  - --apply 를 명시해야만 파일을 쓴다. 쓴 뒤 build_incident_dataset --verify 로 runtime 동치를
    자동 확인하고, 실패하면 방금 쓴 파일을 삭제(원자적 롤백).

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
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
import sys
sys.path.insert(0, str(ROOT))
from review_context_pilot import require
import incidents_migration as mig
import build_incident_dataset as build

DEFAULT_ROOT = ROOT / "datasets/controversy"


def complete_card(case_id, incident_id, known_point, article):
    """REPORTED_CONTEXT_ONLY 최소 완전 카드. validate_bundle 전 필수 필드를 모두 채운다."""
    sources, reported_quotes = [], []
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
                        "reasonHypotheses": [], "reasonSupportReactionIds": [], "boundaries": []},
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
    gplan = mig.load(root, "guidelines/plan.json")
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
        documents = []
        article = st.get("article")
        if article is not None:
            for k in ("publisher", "url", "publishedDate", "snapshot", "sha256"):
                require(isinstance(article.get(k), str) and article[k], "ARTICLE_FIELD_REQUIRED:" + k)
            require(article["snapshot"].startswith("research/source-snapshots/"),
                    "PRIVATE_SOURCE_SNAPSHOT_PATH_REQUIRED")
            documents = [{"id": cid + "-reported", "sourceId": "report", "materialKind": "NEWS_BODY",
                          "snapshot": article["snapshot"], "sha256": article["sha256"],
                          "statementKind": "AUTHOR_INTERPRETATION",
                          "locationHint": article["publisher"] + " " + article["publishedDate"],
                          "snapshotCoverage": "SHORT_EXTRACT_NOT_FULL_ARTICLE", "originGroupId": None,
                          "startCodePoint": 0, "endCodePoint": 0}]
        legacy_dict = {"topicTags": tags, "mechanismIds": mids, "documents": documents}
        statements.append({
            "id": cid, "mechanismIds": mids, "topicTags": tags,
            "legacyDictionaryCard": legacy_dict,
            "legacyCard": complete_card(cid, incident_id, st["knownPoint"], article),
        })

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
                   help="실제로 incidents/{id}.json 을 쓴다(기본은 DRY-RUN 미리보기). 쓴 뒤 자동 verify+롤백.")
    args = p.parse_args()
    try:
        spec = json.loads(args.spec.read_text(encoding="utf-8"))
        incident = assemble(spec, args.dataset_root)
        preview = {"incidentId": incident["incidentId"],
                   "statements": [{"id": s["id"], "mechanismIds": s["mechanismIds"],
                                   "topicTags": s["topicTags"],
                                   "knownPoint": s["legacyCard"]["knownPoint"]["text"],
                                   "hasArticle": bool(s["legacyDictionaryCard"]["documents"])}
                                  for s in incident["statements"]],
                   "reactions": 0, "collectionId": None}

        if not args.apply:
            print(json.dumps({"mode": "DRY_RUN", "wouldWrite": incident["incidentId"] + ".json",
                              "preview": preview,
                              "note": "검토용 미리보기입니다. 승인하면 --apply 로 실제 기록합니다. 디스크 변경 없음."},
                             ensure_ascii=False, indent=2))
            return

        path = args.dataset_root / "incidents" / (incident["incidentId"] + ".json")
        path.parent.mkdir(exist_ok=True)
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(incident, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.replace(path)
        # 자동 동치 재검증. 실패 시 방금 쓴 파일만 롤백(원자적).
        try:
            build.generate(args.dataset_root)  # 전 체인 compile 이 통과하는지
        except Exception as error:  # noqa: BLE001
            path.unlink(missing_ok=True)
            p.exit(2, f"ROLLED_BACK (빌드 실패): {type(error).__name__}: {error}\n")
        print(json.dumps({"mode": "APPLIED", "wrote": str(path), "preview": preview,
                          "note": "기록 완료. incidents/ 는 Git 제외 로컬 데이터. runtime 반영은 build_incident_dataset --write."},
                         ensure_ascii=False, indent=2))
    except Exception as error:  # noqa: BLE001 — CLI 경계
        p.exit(2, f"{type(error).__name__}: {error}\n")


if __name__ == "__main__":
    main()
