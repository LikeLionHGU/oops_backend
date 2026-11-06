"""Freeze a YouTube video's transcript through the production media and STT path, for P1 evaluation sets.

transcribe: the service's own media.prepare (yt-dlp, same format) and stt.transcribe (Groq whisper-large-v3)
            from oops-analysis. Paid Groq call; run it yourself with the oops-analysis environment and its .env.
freeze:     maps STT segments as the Java service does (drop blank, trim) and orders them like ReviewInput,
            so IDs are stt-index-N; writes the transcript and a structural report once.

/opt/anaconda3/envs/oops-analysis/bin/python tools/service_stt_transcript.py transcribe URL NEW_DIR --approved-usd-cap 0.5
python3 tools/service_stt_transcript.py freeze NEW_DIR/stt-raw.json NEW_DIR/stt-manifest.json NEW_TRANSCRIPT.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRIVATE_ROOT = ROOT / "datasets/controversy/experiments"
MODEL = "whisper-large-v3"
USD_PER_HOUR = .111


def to_frozen_transcript(stt_result: dict) -> dict:
    lines = [{"startMs": s["startMs"], "endMs": s["endMs"], "text": s["text"].strip()}
             for s in stt_result["segments"] if s.get("text") and s["text"].strip()]
    if not lines:
        raise ValueError("STT result has no non-blank segments")
    lines.sort(key=lambda s: (s["startMs"], s["endMs"]))
    return {"segments": [{"id": f"stt-index-{i}", **s} for i, s in enumerate(lines)]}


def check_structure(frozen: dict, media_duration_ms: int) -> dict:
    segments = frozen["segments"]
    ids = [s["id"] for s in segments]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    invalid = [s["id"] for s in segments if s["startMs"] < 0 or s["endMs"] < s["startMs"]]
    non_sequential = [s["id"] for n, s in enumerate(segments) if s["id"] != f"stt-index-{n}"]
    beyond = [s["id"] for s in segments if s["endMs"] > media_duration_ms]
    overlaps = sum(1 for a, b in zip(segments, segments[1:]) if b["startMs"] < a["endMs"])
    last_end = max(s["endMs"] for s in segments)
    return {"segments": len(segments), "textCharacters": sum(len(s["text"]) for s in segments),
            "duplicateIds": duplicates, "invalidTimes": invalid, "nonSequentialIds": non_sequential,
            "beyondMedia": beyond, "overlaps": overlaps, "firstStartMs": segments[0]["startMs"],
            "lastEndMs": last_end, "mediaDurationMs": media_duration_ms,
            "tailWithoutSpeechMs": media_duration_ms - last_end,
            "valid": not (duplicates or invalid or non_sequential or beyond)}


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _write_once(path: Path, value: dict) -> str:
    data = json.dumps(value, ensure_ascii=False, indent=1).encode("utf-8")
    with path.open("xb") as out:
        out.write(data)
    os.chmod(path, 0o444)
    return _sha(data)


def transcribe(url: str, output: Path, approved_usd_cap: float) -> None:
    output = output.resolve()
    if not output.is_relative_to(PRIVATE_ROOT.resolve()) or output.exists():
        raise ValueError("A new private directory under datasets/controversy/experiments is required")
    sys.path.insert(0, str(ROOT / "oops-analysis"))
    from app import media, stt
    from app.config import Settings
    import yt_dlp

    settings = Settings(_env_file=ROOT / "oops-analysis/.env")
    if (settings.stt_model or settings.whisper_model) != MODEL or not settings.groq_api_key:
        raise ValueError(f"Configured Groq {MODEL} and its key are required")
    stt.get_settings = lambda: settings
    output.mkdir()
    manifest = {"schemaVersion": "service-stt-transcript-1", "state": "RUNNING", "url": url, "model": MODEL,
                "chunkSec": stt.CHUNK_SEC, "promptAndLanguageHintsAdded": False, "ytDlpVersion": yt_dlp.version.__version__,
                "path": "oops-analysis app.media.prepare + app.stt.transcribe", "approvedUsdCap": approved_usd_cap,
                "actualBillingConfirmed": False}
    manifest_path = output / "stt-manifest.json"
    video = media.prepare(url, None)
    try:
        manifest["mediaDurationSec"] = video.duration_sec
        manifest["estimatedUsd"] = round(video.duration_sec / 3600 * USD_PER_HOUR, 4)
        manifest["mediaSha256"] = _sha(Path(video.path).read_bytes())
        if manifest["estimatedUsd"] > approved_usd_cap:
            manifest["state"] = "STOPPED_OVER_CAP"
            raise ValueError("Estimated STT cost exceeds the approved cap")
        started = time.monotonic()
        result = stt.transcribe(video)
        manifest["elapsedSec"] = round(time.monotonic() - started, 3)
        manifest["rawSha256"] = _write_once(output / "stt-raw.json", result)
        manifest["state"] = "COMPLETE"
    finally:
        video.cleanup()
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({k: manifest[k] for k in ("state", "mediaDurationSec", "estimatedUsd", "rawSha256")}))


def freeze(raw_path: Path, manifest_path: Path, out_path: Path) -> dict:
    raw_bytes = raw_path.read_bytes()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("state") != "COMPLETE" or manifest.get("rawSha256") != _sha(raw_bytes):
        raise ValueError("Complete STT manifest matching the raw file is required")
    frozen = to_frozen_transcript(json.loads(raw_bytes))
    report = check_structure(frozen, int(manifest["mediaDurationSec"] * 1000))
    if not report["valid"]:
        raise ValueError(f"Structural check failed: {report}")
    transcript_sha = _write_once(out_path, frozen)
    report.update({"transcriptSha256": transcript_sha, "rawSha256": manifest["rawSha256"],
                   "manifestSha256": _sha(manifest_path.read_bytes()), "mapping": "drop blank, trim; order by startMs, endMs"})
    _write_once(out_path.with_name(out_path.stem + "-structure.json"), report)
    return report


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="mode", required=True)
    t = sub.add_parser("transcribe")
    t.add_argument("url")
    t.add_argument("output", type=Path)
    t.add_argument("--approved-usd-cap", type=float, required=True)
    f = sub.add_parser("freeze")
    f.add_argument("raw", type=Path)
    f.add_argument("manifest", type=Path)
    f.add_argument("output", type=Path)
    args = parser.parse_args(argv)
    if args.mode == "transcribe":
        transcribe(args.url, args.output, args.approved_usd_cap)
    else:
        print(json.dumps(freeze(args.raw, args.manifest, args.output), ensure_ascii=False))


if __name__ == "__main__":
    main()
