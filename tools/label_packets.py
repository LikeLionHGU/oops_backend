"""Development-set label packets, batch validation and export for the P0 context experiment.

Roles stay separated: T1 sees the target utterance only; T2 sees the frozen transcript after T1 is
frozen (its SHA is recorded in the T2 file); speaker and topic annotators see segments but never
targets or risk labels and must not be T1/T2 annotators. Validation collects every error instead of
stopping; export writes only valid cases.

python tools/label_packets.py packets MANIFEST NEW_DIR
python tools/label_packets.py validate MANIFEST T1 T2 SPEAKERS TOPICS
python tools/label_packets.py export MANIFEST T1 T2 SPEAKERS TOPICS NEW_DIR
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

DECISIONS = {"PASS", "REVIEW_REQUIRED", "UNCERTAIN"}
DISTANCES = ("Near", "Far-topic", "Far-video")
T2_FIELDS = ("videoId", "targetSegmentId", "finalDecision", "goldContextSegmentIds", "contextNecessary",
             "contextDistance", "evidenceSegmentIds", "necessityRationale")


def _read(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _write_once(path: Path, value: dict) -> None:
    with path.open("x", encoding="utf-8") as out:
        json.dump(value, out, ensure_ascii=False, indent=2, sort_keys=True)


def _transcripts(manifest_path: Path) -> tuple[dict, dict[str, list[dict]]]:
    manifest = _read(manifest_path)
    base = Path(manifest_path).resolve().parent
    transcripts = {}
    for video in manifest["videos"]:
        transcripts[str(video["videoId"])] = _read(base / video["transcriptPath"])["segments"]
    return manifest, transcripts


def make_packets(manifest_path: Path, out_dir: Path) -> None:
    manifest, transcripts = _transcripts(manifest_path)
    Path(out_dir).mkdir(parents=False, exist_ok=False)
    t1_cases, t2_cases, t2_videos, segment_videos = [], [], [], []
    for video in manifest["videos"]:
        vid = str(video["videoId"])
        by_id = {s["id"]: s for s in transcripts[vid]}
        for target in video["targets"]:
            t1_cases.append({"videoId": video["videoId"], "targetSegmentId": target, "targetText": by_id[target]["text"],
                             "targetDecisionT1": None, "t1Rationale": None})
            t2_cases.append({**{f: None for f in T2_FIELDS}, "videoId": video["videoId"], "targetSegmentId": target,
                             "goldContextSegmentIds": [], "evidenceSegmentIds": []})
        t2_videos.append({"videoId": video["videoId"], "segments": transcripts[vid]})
        segment_videos.append({"videoId": video["videoId"],
                               "segments": [{k: s[k] for k in ("id", "startMs", "endMs", "text")} for s in transcripts[vid]]})
    version = manifest["labelVersion"]
    _write_once(out_dir / "t1-packet.json", {"role": "T1", "labelVersion": version, "cases": t1_cases})
    _write_once(out_dir / "t2-packet.json", {"role": "T2", "labelVersion": version, "videos": t2_videos, "cases": t2_cases})
    _write_once(out_dir / "speaker-packet.json", {"role": "SPEAKER", "labelVersion": version, "videos": segment_videos})
    _write_once(out_dir / "topic-packet.json", {"role": "TOPIC", "labelVersion": version, "videos": segment_videos})


def _gap(target: dict, other: dict) -> int:
    if other["startMs"] >= target["endMs"]:
        return other["startMs"] - target["endMs"]
    return max(0, target["startMs"] - other["endMs"])


def context_distance(target: dict, context: list[dict], episodes: dict[str, int], near_window_ms: int) -> str | None:
    """Farthest category over the context: Near within the window, else Far-topic in the same episode, else Far-video."""
    if not context:
        return None
    rank = 0
    for c in context:
        if _gap(target, c) <= near_window_ms:
            continue
        rank = max(rank, 1 if episodes[c["id"]] == episodes[target["id"]] else 2)
    return DISTANCES[rank]


def case_type(t2: dict) -> str:
    if t2["finalDecision"] == "UNCERTAIN":
        return "INTRINSICALLY_UNCERTAIN"
    return ("CONTEXT_DEPENDENT_" if t2["contextNecessary"] else "CONTEXT_INDEPENDENT_") + t2["finalDecision"]


def _episodes(starts: list[str], segments: list[dict]) -> dict[str, int]:
    index, result, boundaries = 0, {}, set(starts)
    for i, s in enumerate(segments):
        if i > 0 and s["id"] in boundaries:
            index += 1
        result[s["id"]] = index
    return result


def _validate_t2(label: dict, segments: dict[str, dict], episodes: dict[str, int] | None, near_window_ms: int) -> list[str]:
    missing = [f for f in T2_FIELDS if f not in label]
    if missing:
        return ["MISSING_FIELD"]
    errors = []
    target, context, evidence = label["targetSegmentId"], label["goldContextSegmentIds"], label["evidenceSegmentIds"]
    decision, necessary, distance = label["finalDecision"], label["contextNecessary"], label["contextDistance"]
    if decision not in DECISIONS:
        errors.append("INVALID_DECISION")
    if not isinstance(necessary, bool):
        errors.append("INVALID_CONTEXT_NECESSARY")
    if distance is not None and distance not in DISTANCES:
        errors.append("INVALID_DISTANCE")
    if target not in segments:
        errors.append("INVALID_TARGET_ID")
    if target in context:
        errors.append("SELF_CONTEXT_REFERENCE")
    if set(context) - segments.keys():
        errors.append("INVALID_CONTEXT_IDS")
    if set(evidence) - segments.keys():
        errors.append("INVALID_EVIDENCE_IDS")
    if len(set(context)) != len(context) or len(set(evidence)) != len(evidence):
        errors.append("DUPLICATE_IDS")
    if bool(context) != bool(necessary):
        errors.append("CONTEXT_NECESSITY_MISMATCH")
    if necessary and distance is None:
        errors.append("MISSING_CONTEXT_DISTANCE")
    if not necessary and distance is not None:
        errors.append("UNEXPECTED_CONTEXT_DISTANCE")
    if necessary and not (label["necessityRationale"] or "").strip():
        errors.append("MISSING_NECESSITY_RATIONALE")
    if decision == "UNCERTAIN" and necessary:
        errors.append("UNCERTAIN_CONTEXT_CONFLICT")
    if decision == "REVIEW_REQUIRED" and not evidence:
        errors.append("MISSING_RISK_EVIDENCE")
    resolvable = target in segments and not set(context) - segments.keys() and target not in context
    if episodes is not None and resolvable and distance in DISTANCES:
        expected = context_distance(segments[target], [segments[c] for c in context], episodes, near_window_ms)
        if expected != distance:
            errors.append("CONTEXT_DISTANCE_MISMATCH")
    return errors


def validate(manifest_path: Path, t1_path: Path, t2_path: Path, speakers_path: Path, topics_path: Path) -> dict:
    manifest, transcripts = _transcripts(manifest_path)
    t1, t2, speakers, topics = (_read(p) for p in (t1_path, t2_path, speakers_path, topics_path))
    file_errors = []
    for doc, role in ((t1, "T1"), (t2, "T2"), (speakers, "SPEAKER"), (topics, "TOPIC")):
        if doc.get("role") != role or not doc.get("annotatorId"):
            file_errors.append(f"INVALID_{role}_FILE")
    if t2.get("t1FileSha256") != hashlib.sha256(Path(t1_path).read_bytes()).hexdigest():
        file_errors.append("T1_NOT_FROZEN")
    if {speakers.get("annotatorId"), topics.get("annotatorId")} & {t1.get("annotatorId"), t2.get("annotatorId")}:
        file_errors.append("BLINDNESS_VIOLATION")
    episodes_by_video = {}
    for vid, segments in transcripts.items():
        labels = speakers.get("labels", {}).get(vid, {})
        if set(labels) != {s["id"] for s in segments} or any(
                (l.get("ambiguous") is not True) and not (l.get("speakerId") or "").strip() for l in labels.values()):
            file_errors.append("SPEAKER_LABELS_INCOMPLETE")
        starts = topics.get("episodeStarts", {}).get(vid)
        if starts is None or set(starts) - {s["id"] for s in segments}:
            file_errors.append("TOPIC_BOUNDARIES_INVALID")
        else:
            episodes_by_video[vid] = _episodes(starts, segments)
    t1_keys = {(str(l["videoId"]), l["targetSegmentId"]) for l in t1.get("labels", [])
               if l.get("targetDecisionT1") in DECISIONS}
    t2_by_key: dict[tuple[str, str], list[dict]] = {}
    for label in t2.get("labels", []):
        t2_by_key.setdefault((str(label.get("videoId")), label.get("targetSegmentId")), []).append(label)
    planned = [(str(v["videoId"]), t) for v in manifest["videos"] for t in v["targets"]]
    case_errors, valid = [], []
    for key in planned:
        vid, target = key
        errors = []
        labels = t2_by_key.get(key, [])
        if key not in t1_keys:
            errors.append("MISSING_T1")
        if not labels:
            errors.append("MISSING_T2")
        elif len(labels) > 1:
            errors.append("DUPLICATE_LABEL")
        else:
            segments = {s["id"]: s for s in transcripts[vid]}
            errors += _validate_t2(labels[0], segments, episodes_by_video.get(vid), manifest["nearWindowMs"])
        if errors:
            case_errors.append({"videoId": int(vid) if vid.isdigit() else vid, "targetSegmentId": target, "errors": errors})
        else:
            valid.append(labels[0])
    unplanned = sorted(set(t2_by_key) - set(planned))
    if unplanned:
        file_errors.append("UNPLANNED_T2_LABELS")
    return {"labelVersion": manifest["labelVersion"], "fileErrors": sorted(set(file_errors)), "caseErrors": case_errors,
            "plannedCases": len(planned), "validCases": len(valid), "_valid": valid}


def export(manifest_path: Path, t1_path: Path, t2_path: Path, speakers_path: Path, topics_path: Path, out_dir: Path) -> dict:
    report = validate(manifest_path, t1_path, t2_path, speakers_path, topics_path)
    if report["fileErrors"]:
        raise ValueError(f"File-level label errors: {report['fileErrors']}")
    manifest, _ = _transcripts(manifest_path)
    clusters = {str(v["videoId"]): v["clusterId"] for v in manifest["videos"]}
    speakers = _read(speakers_path)
    Path(out_dir).mkdir(parents=False, exist_ok=False)
    oracle: dict[str, dict] = {}
    binary, uncertain = [], []
    for label in report["_valid"]:
        vid = str(label["videoId"])
        oracle.setdefault(vid, {})[label["targetSegmentId"]] = {
            "goldContextSegmentIds": label["goldContextSegmentIds"], "evidenceSegmentIds": label["evidenceSegmentIds"]}
        row = {"videoId": label["videoId"], "targetId": label["targetSegmentId"], "clusterId": clusters[vid],
               "caseType": case_type(label), "contextDistance": label["contextDistance"]}
        if label["finalDecision"] == "UNCERTAIN":
            uncertain.append(row)
        else:
            binary.append({**row, "expected": "RISK" if label["finalDecision"] == "REVIEW_REQUIRED" else "NO_RISK"})
    version = report["labelVersion"]
    hashes = {name: hashlib.sha256(Path(p).read_bytes()).hexdigest() for name, p in
              (("manifest", manifest_path), ("t1", t1_path), ("t2", t2_path), ("speakers", speakers_path), ("topics", topics_path))}
    _write_once(out_dir / "gold-oracle.json", {"labelVersion": version, "labels": oracle})
    _write_once(out_dir / "speakers-a6h.json", {"labelVersion": version, "labels": {
        vid: {seg: {"speakerId": None if l.get("ambiguous") else l["speakerId"], "ambiguous": bool(l.get("ambiguous"))}
              for seg, l in labels.items()} for vid, labels in speakers["labels"].items()}})
    _write_once(out_dir / "gold-cases.json", {"labelVersion": version, "nearWindowMs": manifest["nearWindowMs"],
                                              "binary": binary, "intrinsicallyUncertain": uncertain})
    summary = {"labelVersion": version, "exportedCases": len(report["_valid"]), "excludedCases": report["caseErrors"],
               "inputSha256": hashes}
    _write_once(out_dir / "export-report.json", summary)
    return summary


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("mode", choices=("packets", "validate", "export"))
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args(argv)
    if args.mode == "packets" and len(args.paths) == 2:
        make_packets(*args.paths)
    elif args.mode == "validate" and len(args.paths) == 5:
        report = validate(*args.paths)
        report.pop("_valid")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        sys.exit(1 if report["fileErrors"] or report["caseErrors"] else 0)
    elif args.mode == "export" and len(args.paths) == 6:
        print(json.dumps(export(*args.paths), ensure_ascii=False, indent=2))
    else:
        parser.error("Wrong number of paths for mode")


if __name__ == "__main__":
    main()
