"""Offline intake only: preserve pasted YouTube captions without correcting speech.

No network, model invocation, database writes, or runtime dictionary changes.
End times are next-cue boundaries, not measured speech ends. Last cue is a point.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

CLOCK = re.compile(r"^(\d{1,2}:\d{2}(?::\d{2})?)(.*)$")
KOREAN_TIME = re.compile(r"^(?:(\d+)시간\s*)?(?:(\d+)분\s*)?(?:(\d+)초\s*)?")


def parse_script(source):
    cues, chapters = [], []
    for number, line in enumerate(source.splitlines(), 1):
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("챕터 "):
            chapters.append({"sourceLine": number, "raw": line})
            continue
        match = CLOCK.fullmatch(stripped)
        if not match:
            raise ValueError(f"Unrecognized line {number}; no silent text drop")
        clock, remainder = match.groups()
        parts = [int(p) for p in clock.split(":")]
        if parts[-1] >= 60 or (len(parts) == 3 and parts[-2] >= 60):
            raise ValueError(f"Invalid clock on line {number}")
        seconds = sum(value * 60 ** index for index, value in enumerate(reversed(parts)))
        duplicated = KOREAN_TIME.match(remainder)
        if duplicated and duplicated.group(0):
            hour, minute, second = (int(v or 0) for v in duplicated.groups())
            if hour * 3600 + minute * 60 + second != seconds:
                raise ValueError(f"Conflicting timestamp on line {number}")
            remainder = remainder[len(duplicated.group(0)):]
        text = remainder.strip()
        if not text:
            raise ValueError(f"Empty cue on line {number}")
        start = seconds * 1000
        if cues and start < cues[-1]["startMs"]:
            raise ValueError(f"Non-monotonic timestamp on line {number}")
        row = {"sourceLine": number, "raw": line, "text": text}
        if cues and start == cues[-1]["startMs"]:
            cues[-1]["sourceRows"].append(row)
            cues[-1]["text"] += "\n" + text
        else:
            cues.append({"id": f"script-{len(cues)}", "startMs": start, "text": text, "sourceRows": [row]})
    if not cues:
        raise ValueError("No timestamped cues")
    for index, cue in enumerate(cues):
        cue["endMs"] = cues[index + 1]["startMs"] if index + 1 < len(cues) else cue["startMs"]
        cue["timePrecision"] = "NEXT_CUE_BOUNDARY_NOT_SPEECH_END" if index + 1 < len(cues) else "POINT_ONLY_END_UNKNOWN"
    return {"segments": cues, "chapters": chapters}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--request-output", type=Path, help="Optional local API request; never sends it")
    parser.add_argument("--title", default="사용자 제공 팟캐스트 스크립트")
    args = parser.parse_args()
    if args.output.exists() or (args.request_output and args.request_output.exists()):
        raise ValueError("Output already exists; preserve prior intake")
    raw = args.source.read_bytes()
    result = parse_script(raw.decode("utf-8-sig"))
    result.update({"schemaVersion": "youtube-pasted-script-intake-1", "sourceUrl": args.url,
                   "sourceSha256": hashlib.sha256(raw).hexdigest(), "sourceKind": "USER_SUPPLIED_YOUTUBE_SCRIPT",
                   "audioVerified": False, "speakerAttributionVerified": False, "serviceSubmitted": False,
                   "textCorrected": False, "providerCalls": 0,
                   "limitations": ["No speaker labels inferred", "No transcription correction", "Final cue has unknown speech end",
                                   "Script test is not our STT performance test", "No human risk labels in analysis input"]})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as handle:
        handle.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    args.output.chmod(0o600)
    if args.request_output:
        request = {"sourceUrl": args.url, "title": args.title, "timePrecision": "NEXT_CUE_BOUNDARIES_LAST_POINT",
                   "segments": [{k: s[k] for k in ("startMs", "endMs", "text")} for s in result["segments"]]}
        args.request_output.parent.mkdir(parents=True, exist_ok=True)
        with args.request_output.open("x", encoding="utf-8") as handle:
            handle.write(json.dumps(request, ensure_ascii=False, indent=2) + "\n")
        args.request_output.chmod(0o600)
    print(json.dumps({"segments": len(result["segments"]), "chapters": len(result["chapters"]),
                      "sourceRows": sum(len(s["sourceRows"]) for s in result["segments"]),
                      "lastTimestampMs": result["segments"][-1]["startMs"], "providerCalls": 0}))


if __name__ == "__main__":
    main()
