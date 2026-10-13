"""Offline draft registry. Never approves labels, calls AI, or edits runtime data."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import unicodedata


BASELINE = "a1cd646"
BASELINE_COMMIT = "a1cd646b56ed3b8a21cd803d04e681dace1c1476"
BUCKETS = {"CONTROVERSY_RESEARCH", "NORMAL_CONTROL", "AMBIGUOUS_CONTROL"}
SPLITS = {"DEVELOPMENT", "CONFIRMATION_CANDIDATE"}


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")).encode()).hexdigest()


def read(path):
    def unique(pairs):
        obj = {}
        for key, value in pairs:
            if key in obj:
                raise ValueError("duplicate JSON key")
            obj[key] = value
        return obj
    if Path(path).stat().st_size > 4 * 1024 * 1024:
        raise ValueError("input exceeds 4 MiB")
    return json.loads(Path(path).read_text(), object_pairs_hook=unique)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate(suite):
    require(suite["schemaVersion"] == "fixed-review-draft-1", "unsupported schema")
    require(suite["baselineRevision"] == BASELINE, "baseline changed")
    require(suite["baselineCommit"] == BASELINE_COMMIT, "baseline commit changed")
    rows = suite["cases"]
    require(len(rows) == 30, "exactly 30 review slots required")
    require(Counter(r["bucket"] for r in rows) == Counter({b: 10 for b in BUCKETS}),
            "bucket counts must be 10/10/10")
    require(Counter(r["split"] for r in rows) == Counter(
        DEVELOPMENT=20, CONFIRMATION_CANDIDATE=10), "split counts must be 20/10")
    ids, families, groups, inputs = set(), {}, {}, {}
    for row in rows:
        require(row["id"] not in ids, "duplicate case ID")
        ids.add(row["id"])
        require(row["split"] in SPLITS, "invalid split")
        for field, registry in (("familyId", families), ("contrastGroupId", groups)):
            require(bool(row[field]), "missing grouping key")
            require(registry.setdefault(row[field], row["split"]) == row["split"],
                    "family/contrast split leakage")
        require(row["humanReview"] == "PENDING" and row["approvedDecision"] is None,
                "draft registry cannot approve labels; use curated dataset contract")
        payload = row["input"]
        require(set(payload) == {"mode", "segments"}, "invalid input fields")
        require(payload["mode"] in {"SAVED_STT_WINDOW", "REPORTED_QUOTE", "SYNTHETIC_TEXT", "SOURCE_MISSING"},
                "invalid input mode")
        require(isinstance(payload["segments"], list), "segments must be a list")
        require(bool(payload["segments"]) == (payload["mode"] != "SOURCE_MISSING"),
                "missing source must remain empty; supplied source must have text")
        require(row["inputHash"] == digest(payload), "frozen input mismatch")
        require(not any(k in payload for k in ("knownPoint", "expectedDecision", "reactions")),
                "answer/reaction leaked into model input")
        texts = payload["segments"]
        normalized = "".join(c for c in unicodedata.normalize(
            "NFKC", " ".join(s["text"] for s in texts)).lower() if c.isalnum())
        if normalized:
            require(inputs.setdefault(normalized, row["split"]) == row["split"],
                    "duplicate input across splits")
        for segment in texts:
            require(bool(segment["text"].strip()), "empty text")
            if payload["mode"] != "SAVED_STT_WINDOW":
                require(segment["startMs"] is None and segment["endMs"] is None,
                        "reported/synthetic text must not invent audio timestamps")
            else:
                require(type(segment["startMs"]) is int and type(segment["endMs"]) is int
                        and 0 <= segment["startMs"] < segment["endMs"], "invalid STT time")
    require(suite["registryHash"] == digest(rows), "registry mismatch")
    return {"slots": 30, "approvedLabels": 0, "qualityGateMet": None,
            "adoption": "BLOCKED", "blockingReasons": [
                "HUMAN_LABELS_PENDING", "SOURCE_AND_CONTEXT_REVIEW_PENDING",
                "CONFIRMATION_FAMILIES_PREVIOUSLY_EXPOSED", "PAIRED_RUNS_NOT_EXECUTED"],
            "inputModes": dict(Counter(r["input"]["mode"] for r in rows))}


def build(cards, run):
    """Copy source excerpts; never infer missing original dialogue from news context."""
    controls = {
        "pisik-yeongyang": [
            ("이 동네에는 햄버거 가게가 없어서 제가 집에서 만들어 먹어요.", "여기 뭐 있겠나?"),
            ("이 메뉴는 제 취향에는 안 맞고 가격도 아까웠어요.", "메뉴가 의미가 없어."),
            ("할머니가 해주시던 팥죽 맛이 생각나요.", "반산두종 하면 안 된다."),
            ("선택 가능한 메뉴가 하나라서 주문 없이 주시는 걸 먹었어요.", "이걸로 대신 먹는 거야.")],
        "sgbg-military-ad-20240623": [
            ("훈련 뒤 근육통이 걱정돼서 허용되는 물품을 확인해 선물하려고요.", "마사지기라도 가져갈래?"),
            ("이 광고는 운동 후 피로 회복을 소개합니다.", "게시 날짜도 관련 사건도 알려지지 않은 군 생활 농담이다.")],
        "workman-caption-20200311": [
            ("작업 일지: 오늘 노무 비용과 근로 시간을 정산합니다.", "출처와 화면 배치를 모르는 자막: 18개 노무 시작")],
        "chim-football-20240715": [
            ("그 감독의 전술이 제 취향과 달라서 싫어요. 다른 팬의 문제 제기는 별개예요.", "그냥 싫어서 그러는 거 아니냐?")],
        "jang-gender-201504": [
            ("여성이 멍청하다는 주장은 편견입니다. 성별로 능력을 판단하지 마세요.", "여자들은 머리가 안 된다 — 앞뒤가 잘린 발언")],
        "kwak-naeun-20240916": [
            ("제 일은 제가 용서해도 다른 피해자가 입은 피해까지 대신 용서할 수는 없어요.", "오해를 받는 사람한테도 피해를 주는 것 같아서 그렇더라.")],
    }
    rows = []
    def add(case_id, family, bucket, mode, segments, provenance, proposal=None, research=None):
        split = "DEVELOPMENT" if family in list(controls)[:3] else "CONFIRMATION_CANDIDATE"
        payload = {"mode": mode, "segments": segments}
        rows.append({"id": case_id, "familyId": family, "contrastGroupId": family,
                     "split": split, "bucket": bucket, "input": payload,
                     "inputHash": digest(payload), "provenance": provenance,
                     "exposure": "PREVIOUSLY_USED_IN_DEVELOPMENT" if family in controls else "SOURCE_MISSING",
                     "humanReview": "PENDING", "approvedDecision": None,
                     "proposedDecision": proposal, "researchNote": research,
                     "missingInformation": ["독립 검수·입력 충분성·출처 이용 범위 확인 필요"]})
    for incident in cards["incidents"]:
        family = incident["familyId"]
        require(family in controls, "unknown family: review allocation before expanding")
        for card in incident["cards"]:
            evidence = card["evidence"]
            if card["id"] in ("pisik-B", "pisik-C", "pisik-D"):
                bounds = {"pisik-B": (14500, 61500), "pisik-C": (53000, 75000),
                          "pisik-D": (70000, 90000)}[card["id"]]
                segments = [dict(s) for s in run["transcript"]
                            if bounds[0] <= s["startMs"] < bounds[1]]
                mode, provenance = "SAVED_STT_WINDOW", "run-51.json transcript; audio/speakers unverified"
            else:
                segments = [{"text": q["text"], "startMs": None, "endMs": None}
                            for q in evidence["reportedQuotes"]]
                mode = "REPORTED_QUOTE" if segments else "SOURCE_MISSING"
                provenance = {"sources": evidence["sources"], "quoteStatus": "NOT_ORIGINAL_TRANSCRIPT"}
            add(card["id"], family, "CONTROVERSY_RESEARCH", mode, segments,
                provenance, research=card["knownPoint"])
        for index, (normal, ambiguous) in enumerate(controls[family], 1):
            for bucket, text, proposed in (("NORMAL_CONTROL", normal, "PASS"),
                                            ("AMBIGUOUS_CONTROL", ambiguous, "UNCERTAIN")):
                add(f"{family}-{bucket.lower()}-{index}", family, bucket, "SYNTHETIC_TEXT",
                    [{"text": text, "startMs": None, "endMs": None}],
                    "ASSISTANT_SYNTHETIC_NOT_REAL_VIDEO; proposal requires human review", proposed)
    add("unfilled-real-case-10", "unfilled-family", "CONTROVERSY_RESEARCH", "SOURCE_MISSING", [],
        "새 사건과 실제 입력을 확보한 뒤 대조 사례까지 같은 분할로 재설계해야 함")
    suite = {"schemaVersion": "fixed-review-draft-1", "baselineRevision": BASELINE,
             "baselineCommit": BASELINE_COMMIT,
             "sourceHashes": {"cards": digest(cards), "savedRun": digest(run)},
             "scope": "OFFLINE_DRAFT_NOT_RUNTIME_REFERENCE_NOT_APPROVED_BENCHMARK",
             "cases": rows, "registryHash": digest(rows)}
    validate(suite)
    return suite


def review_packet(suite, case_ids, owner_review=None):
    """Separate model data, blank review forms, and development-owner labels.

    This is an offline export, not an API request or a full pipeline replay.
    """
    validate(suite)
    require(bool(case_ids) and len(case_ids) == len(set(case_ids)), "unique case IDs required")
    indexed = {r["id"]: r for r in suite["cases"]}
    require(set(case_ids) <= set(indexed), "unknown requested case")
    owner_labels = {}
    if owner_review is not None:
        require(owner_review.get("schemaVersion") == "owner-development-review-1"
                and owner_review.get("reviewerRole") == "PROJECT_OWNER", "unsupported owner review")
        require(owner_review.get("suiteRegistryHash") == suite["registryHash"], "owner suite mismatch")
        require(all(owner_review.get(k) is False for k in (
            "independentReviewCompleted", "curatedPromotionAuthorized", "runtimeUseAuthorized", "trainingUseAuthorized")),
            "owner review cannot promote labels")
        for item in owner_review["cases"]:
            cid = item["caseId"]
            require(cid in indexed and cid not in owner_labels, "invalid or duplicate owner case")
            require(item["inputHash"] == indexed[cid]["inputHash"], "owner input mismatch")
            require(item["developmentExpectedDecision"] in {"PASS", "REVIEW_REQUIRED", "UNCERTAIN"},
                    "invalid development decision")
            owner_labels[cid] = item
    model_inputs, worksheets, manifest_rows, development_labels = [], [], [], []
    for i, cid in enumerate(case_ids, 1):
        row = indexed[cid]
        require(row["input"]["mode"] in {"SAVED_STT_WINDOW", "SYNTHETIC_TEXT"},
                "reported or missing source is not ready for a text-input comparison")
        unit = f"eval-{i:03d}"
        # No incident IDs, proposal labels, research notes, comments, or prior responses.
        model_inputs.append({"unitId": unit, "input": row["input"]})
        manifest_rows.append({"unitId": unit, "caseId": cid, "inputHash": row["inputHash"],
                              "split": row["split"], "familyId": row["familyId"]})
        worksheets.append({"unitId": unit, "status": "PENDING", "reviewerId": None,
                           "decision": None, "evidence": [], "reason": None,
                           "normalInterpretation": None, "missingInformation": [],
                           "inputSufficient": None, "reviewedModality": None,
                           "externalEvidence": [], "duplicateIssueUnitIds": []})
        if cid in owner_labels:
            development_labels.append({"unitId": unit, "reviewerRole": "PROJECT_OWNER",
                                       "reviewStatus": "OWNER_DEVELOPMENT_ONLY",
                                       "assessment": owner_labels[cid]})
    inputs = {"schemaVersion": "offline-review-inputs-1", "units": model_inputs}
    return {
        "model-inputs.json": inputs,
        "worksheet.json": {"schemaVersion": "offline-review-worksheet-1", "units": worksheets},
        "owner-labels.json": {"schemaVersion": "owner-development-labels-1", "labels": development_labels},
        "manifest.json": {"schemaVersion": "offline-review-manifest-1", "suiteRegistryHash": suite["registryHash"],
                          "baselineCommit": suite["baselineCommit"], "modelInputsHash": digest(inputs),
                          "ownerReviewHash": digest(owner_review), "cases": manifest_rows,
                          "scope": "TEXT_INPUT_EXPORT_NOT_FULL_RUNTIME_REPLAY",
                          "apiExecutionEnabled": False, "qualityGateMet": None,
                          "curatedPromotionAuthorized": False}}


def write_private(path, payload):
    require("datasets" in path.resolve().parts, "private output must be inside datasets")
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--cards", required=True)
    p.add_argument("--run", required=True)
    p.add_argument("--output", required=True)
    p = sub.add_parser("check")
    p.add_argument("suite")
    p = sub.add_parser("packet")
    p.add_argument("suite")
    p.add_argument("--case-ids", nargs="+", required=True)
    p.add_argument("--owner-review")
    p.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        suite = build(read(args.cards), read(args.run))
        path = Path(args.output)
        write_private(path, suite)
    elif args.command == "packet":
        suite = read(args.suite)
        packet = review_packet(suite, args.case_ids, read(args.owner_review) if args.owner_review else None)
        directory = Path(args.output_dir)
        require("datasets" in directory.resolve().parts, "private output must be inside datasets")
        directory.parent.mkdir(parents=True, exist_ok=True)
        directory.mkdir(mode=0o700)  # Never reuse/overwrite a prior packet.
        for name, payload in packet.items():
            write_private(directory / name, payload)
        print(json.dumps({"units": len(args.case_ids), "ownerDevelopmentLabels": len(packet["owner-labels.json"]["labels"]),
                          "apiExecutionEnabled": False, "qualityGateMet": None}))
        return
    else:
        suite = read(args.suite)
    print(json.dumps(validate(suite), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
