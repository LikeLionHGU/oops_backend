"""Development-set label packets, batch validation and export for the P0 context experiment.

Roles stay separated: T1 sees the target utterance only; T2 sees the frozen transcript after T1 is
frozen (its SHA is recorded in the T2 file); speaker and topic annotators see segments but never
targets or risk labels and must not be T1/T2 annotators. Validation collects every error instead of
stopping; export writes only valid cases.

contextDistance is never labelled. Export derives it from the A0 planner output (a0-provided-v1,
written by the Gradle task a0ProvidedContext) and the frozen topic episodes. Without that file the
distance is NOT_COMPUTED; no fixed time window stands in for the planner.

python tools/label_packets.py packets MANIFEST NEW_DIR
python tools/label_packets.py validate MANIFEST T1 T2 SPEAKERS TOPICS
python tools/label_packets.py export MANIFEST T1 T2 SPEAKERS TOPICS NEW_DIR [A0_PROVIDED]
python tools/label_packets.py diagnose MANIFEST T1 T2 TOPICS A0_PROVIDED NEW_FILE
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

DECISIONS = {"PASS", "REVIEW_REQUIRED", "UNCERTAIN"}
T2_FIELDS = ("videoId", "targetSegmentId", "finalDecision", "goldContextSegmentIds", "contextNecessary",
             "evidenceSegmentIds", "necessityRationale")
A0_FORMAT = "a0-provided-v1"
SPEAKER_GOLD_BASIS = "AUDIO_VERIFIED"


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


def derive_context_distance(target_id: str, gold_context_ids: list[str], context_necessary: bool,
                            final_decision: str, planner_provided_ids: list[str],
                            topic_by_segment: dict[str, int]) -> str | None:
    """Near first: every gold context segment is already in the A0 input. Otherwise any gold segment in
    another topic episode is Far-video, else Far-topic. IDs must be validated before this call."""
    if final_decision == "UNCERTAIN" or not context_necessary:
        return None
    gold = set(gold_context_ids)
    if gold <= set(planner_provided_ids):
        return "Near"
    target_topic = topic_by_segment[target_id]
    if any(topic_by_segment[segment_id] != target_topic for segment_id in gold):
        return "Far-video"
    return "Far-topic"


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


def _validate_t2(label: dict, segments: dict[str, dict]) -> list[str]:
    if any(f not in label for f in T2_FIELDS):
        return ["MISSING_FIELD"]
    errors = []
    target, context, evidence = label["targetSegmentId"], label["goldContextSegmentIds"], label["evidenceSegmentIds"]
    decision, necessary = label["finalDecision"], label["contextNecessary"]
    if "contextDistance" in label:
        errors.append("CONTEXT_DISTANCE_IS_DERIVED")
    if decision not in DECISIONS:
        errors.append("INVALID_DECISION")
    if not isinstance(necessary, bool):
        errors.append("INVALID_CONTEXT_NECESSARY")
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
    if necessary and not (label["necessityRationale"] or "").strip():
        errors.append("MISSING_NECESSITY_RATIONALE")
    if decision == "UNCERTAIN" and necessary:
        errors.append("UNCERTAIN_CONTEXT_CONFLICT")
    if decision == "REVIEW_REQUIRED" and not evidence:
        errors.append("MISSING_RISK_EVIDENCE")
    return errors


def validate(manifest_path: Path, t1_path: Path, t2_path: Path, speakers_path: Path, topics_path: Path) -> dict:
    return _check(manifest_path, t1_path, t2_path, speakers_path, topics_path)


def _check(manifest_path: Path, t1_path: Path, t2_path: Path, speakers_path: Path | None, topics_path: Path) -> dict:
    """Without a speaker file (diagnostics only) the speaker rules are skipped, not passed."""
    manifest, transcripts = _transcripts(manifest_path)
    t1, t2, topics = (_read(p) for p in (t1_path, t2_path, topics_path))
    speakers = None if speakers_path is None else _read(speakers_path)
    file_errors = []
    docs = [(t1, "T1"), (t2, "T2"), (topics, "TOPIC")] + ([(speakers, "SPEAKER")] if speakers is not None else [])
    for doc, role in docs:
        if doc.get("role") != role or not doc.get("annotatorId"):
            file_errors.append(f"INVALID_{role}_FILE")
    if t2.get("t1FileSha256") != hashlib.sha256(Path(t1_path).read_bytes()).hexdigest():
        file_errors.append("T1_NOT_FROZEN")
    blind = {topics.get("annotatorId")} | ({speakers.get("annotatorId")} if speakers is not None else set())
    if blind & {t1.get("annotatorId"), t2.get("annotatorId")}:
        file_errors.append("BLINDNESS_VIOLATION")
    if speakers is not None and speakers.get("basis") != SPEAKER_GOLD_BASIS:
        file_errors.append("SPEAKER_LABELS_NOT_AUDIO_VERIFIED")
    episodes_by_video = {}
    for vid, segments in transcripts.items():
        labels = {} if speakers is None else speakers.get("labels", {}).get(vid, {})
        if speakers is not None and (set(labels) != {s["id"] for s in segments} or any(
                (l.get("ambiguous") is not True) and not (l.get("speakerId") or "").strip() for l in labels.values())):
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
            errors += _validate_t2(labels[0], {s["id"]: s for s in transcripts[vid]})
        if errors:
            case_errors.append({"videoId": int(vid) if vid.isdigit() else vid, "targetSegmentId": target, "errors": errors})
        else:
            valid.append(labels[0])
    unplanned = sorted(set(t2_by_key) - set(planned))
    if unplanned:
        file_errors.append("UNPLANNED_T2_LABELS")
    return {"labelVersion": manifest["labelVersion"], "fileErrors": sorted(set(file_errors)), "caseErrors": case_errors,
            "plannedCases": len(planned), "validCases": len(valid), "_valid": valid, "_episodes": episodes_by_video,
            "_t1": {(str(l["videoId"]), l["targetSegmentId"]): l for l in t1.get("labels", [])}, "_t2": t2_by_key}


def _sha(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _load_a0(a0_path: Path, manifest_path: Path, manifest: dict) -> dict:
    a0 = _read(a0_path)
    base = Path(manifest_path).resolve().parent
    transcripts = {str(v["videoId"]): _sha(base / v["transcriptPath"]) for v in manifest["videos"]}
    targets = {str(v["videoId"]): set(v["targets"]) for v in manifest["videos"]}
    problems = []
    if a0.get("format") != A0_FORMAT or not a0.get("selectorId"):
        problems.append("format")
    if a0.get("manifestSha256") != _sha(manifest_path) or a0.get("labelVersion") != manifest["labelVersion"]:
        problems.append("manifest")
    if a0.get("transcriptSha256") != transcripts:
        problems.append("transcripts")
    provided = a0.get("provided", {})
    if {vid: set(entries) for vid, entries in provided.items()} != targets:
        problems.append("targets")
    if any(e.get("status") not in ("PROVIDED", "NOT_PLANNED") for entries in provided.values() for e in entries.values()):
        problems.append("status")
    if problems:
        raise ValueError(f"A0 planner output does not match this manifest: {problems}")
    return a0


def _distance(label: dict, a0: dict | None, episodes: dict[str, int]) -> tuple[str | None, str]:
    if label["finalDecision"] == "UNCERTAIN" or not label["contextNecessary"]:
        return None, "NOT_APPLICABLE"
    if a0 is None:
        return None, "NOT_COMPUTED"
    entry = a0["provided"][str(label["videoId"])][label["targetSegmentId"]]
    if entry["status"] == "NOT_PLANNED":
        return None, "TARGET_NOT_PLANNED"
    return derive_context_distance(label["targetSegmentId"], label["goldContextSegmentIds"], True,
                                   label["finalDecision"], entry["contextSegmentIds"], episodes), "COMPUTED"


def export(manifest_path: Path, t1_path: Path, t2_path: Path, speakers_path: Path, topics_path: Path, out_dir: Path,
           a0_path: Path | None) -> dict:
    report = validate(manifest_path, t1_path, t2_path, speakers_path, topics_path)
    if report["fileErrors"]:
        raise ValueError(f"File-level label errors: {report['fileErrors']}")
    manifest, _ = _transcripts(manifest_path)
    a0 = None if a0_path is None else _load_a0(a0_path, manifest_path, manifest)
    clusters = {str(v["videoId"]): v["clusterId"] for v in manifest["videos"]}
    speakers = _read(speakers_path)
    Path(out_dir).mkdir(parents=False, exist_ok=False)
    oracle: dict[str, dict] = {}
    binary, uncertain = [], []
    for label in report["_valid"]:
        vid = str(label["videoId"])
        oracle.setdefault(vid, {})[label["targetSegmentId"]] = {
            "goldContextSegmentIds": label["goldContextSegmentIds"], "evidenceSegmentIds": label["evidenceSegmentIds"]}
        distance, status = _distance(label, a0, report["_episodes"][vid])
        row = {"videoId": label["videoId"], "targetId": label["targetSegmentId"], "clusterId": clusters[vid],
               "caseType": case_type(label), "contextDistance": distance, "distanceStatus": status}
        if label["finalDecision"] == "UNCERTAIN":
            uncertain.append(row)
        else:
            binary.append({**row, "expected": "RISK" if label["finalDecision"] == "REVIEW_REQUIRED" else "NO_RISK"})
    version = report["labelVersion"]
    inputs = [("manifest", manifest_path), ("t1", t1_path), ("t2", t2_path), ("speakers", speakers_path), ("topics", topics_path)]
    hashes = {name: _sha(p) for name, p in inputs + ([("a0Provided", a0_path)] if a0_path else [])}
    _write_once(out_dir / "gold-oracle.json", {"labelVersion": version, "labels": oracle})
    _write_once(out_dir / "speakers-a6h.json", {"labelVersion": version, "labels": {
        vid: {seg: {"speakerId": None if l.get("ambiguous") else l["speakerId"], "ambiguous": bool(l.get("ambiguous"))}
              for seg, l in labels.items()} for vid, labels in speakers["labels"].items()}})
    _write_once(out_dir / "gold-cases.json", {"labelVersion": version, "distanceComputed": a0 is not None,
                                              "a0SelectorId": a0 and a0["selectorId"],
                                              "binary": binary, "intrinsicallyUncertain": uncertain})
    rows = binary + uncertain
    statuses: dict[str, int] = {}
    for row in rows:
        statuses[row["distanceStatus"]] = statuses.get(row["distanceStatus"], 0) + 1
    distances = {d: sum(1 for row in rows if row["contextDistance"] == d) for d in ("Near", "Far-topic", "Far-video")}
    summary = {"labelVersion": version, "exportedCases": len(report["_valid"]), "excludedCases": report["caseErrors"],
               "distanceStatusCounts": statuses, "contextDistanceCounts": distances, "inputSha256": hashes}
    _write_once(out_dir / "export-report.json", summary)
    return summary


def diagnose(manifest_path: Path, t1_path: Path, t2_path: Path, topics_path: Path, a0_path: Path, out_path: Path) -> dict:
    """Interim per-target table without speaker labels. Never an experiment input."""
    report = _check(manifest_path, t1_path, t2_path, None, topics_path)
    manifest, _ = _transcripts(manifest_path)
    a0 = _load_a0(a0_path, manifest_path, manifest)
    valid = {(str(l["videoId"]), l["targetSegmentId"]) for l in report["_valid"]}
    errors = {(str(e["videoId"]), e["targetSegmentId"]): e["errors"] for e in report["caseErrors"]}
    cases = []
    for video in manifest["videos"]:
        vid = str(video["videoId"])
        for target in video["targets"]:
            key = (vid, target)
            t1 = report["_t1"].get(key, {})
            t2 = (report["_t2"].get(key) or [{}])[0]
            entry = a0["provided"][vid][target]
            if key in valid and vid in report["_episodes"]:
                distance, status = _distance(t2, a0, report["_episodes"][vid])
            else:
                distance, status = None, "INVALID_LABEL" if key not in valid else "TOPICS_INVALID"
            cases.append({"videoId": video["videoId"], "targetSegmentId": target,
                          "t1Decision": t1.get("targetDecisionT1"), "t2Decision": t2.get("finalDecision"),
                          "contextNecessary": t2.get("contextNecessary"),
                          "goldContextSegmentIds": t2.get("goldContextSegmentIds"),
                          "evidenceSegmentIds": t2.get("evidenceSegmentIds"),
                          "a0Status": entry["status"], "a0ContextSegmentIds": entry["contextSegmentIds"],
                          "contextDistance": distance, "distanceStatus": status,
                          "exportStatus": "VALID" if key in valid else "EXCLUDED", "errors": errors.get(key, [])})
    table = {"format": "label-diagnostic-v1", "diagnosticOnly": True,
             "note": "No speaker labels; not an experiment input and not a formal export.",
             "labelVersion": report["labelVersion"], "fileErrors": report["fileErrors"], "cases": cases,
             "inputSha256": {name: _sha(p) for name, p in (("manifest", manifest_path), ("t1", t1_path), ("t2", t2_path),
                                                            ("topics", topics_path), ("a0Provided", a0_path))}}
    _write_once(Path(out_path), table)
    return table


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("mode", choices=("packets", "validate", "export", "diagnose"))
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args(argv)
    if args.mode == "packets" and len(args.paths) == 2:
        make_packets(*args.paths)
    elif args.mode == "validate" and len(args.paths) == 5:
        report = validate(*args.paths)
        for key in ("_valid", "_episodes", "_t1", "_t2"):
            report.pop(key)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        sys.exit(1 if report["fileErrors"] or report["caseErrors"] else 0)
    elif args.mode == "export" and len(args.paths) in (6, 7):
        paths = args.paths + [None] * (7 - len(args.paths))
        print(json.dumps(export(*paths), ensure_ascii=False, indent=2))
    elif args.mode == "diagnose" and len(args.paths) == 6:
        table = diagnose(*args.paths)
        print(json.dumps({"fileErrors": table["fileErrors"], "cases": len(table["cases"])}, ensure_ascii=False))
    else:
        parser.error("Wrong number of paths for mode")


if __name__ == "__main__":
    main()
