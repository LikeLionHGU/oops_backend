"""Local click-to-label page for the J1 blind human review (127.0.0.1 only, no paid calls).

python3 tools/j1_review_server.py datasets/controversy/experiments/j1-baseline-20261107 5001 5002 5003 [--port 8765]
Reads review-<ID>/human-labels-template.json and the transcript named in draw-<ID>/manifest.json; never reads AI labels,
nominations, layers or review reasons. Every change autosaves to review-<ID>/human-labels-draft.json; "확정" writes
review-<ID>/human-labels.json once (read-only) after every case has a decision and a reviewer ID is set.
"""
from __future__ import annotations

import argparse
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

DECISIONS = ("PASS", "REVIEW_REQUIRED", "UNCERTAIN")
WINDOW_MS = 120_000
PAGE = Path(__file__).resolve().parent / "j1_review_ui.html"


class ReviewStore:
    def __init__(self, base: Path, video_id: int, window_ms: int = WINDOW_MS):
        self.video_id = video_id
        self.review = base / f"review-{video_id}"
        manifest_path = base / f"draw-{video_id}" / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))["videos"][0]
        transcript = manifest_path.parent / manifest["transcriptPath"]
        self.segments = json.loads(transcript.read_text(encoding="utf-8"))["segments"]
        self.order = {s["id"]: n for n, s in enumerate(self.segments)}
        self.template = json.loads((self.review / "human-labels-template.json").read_text(encoding="utf-8"))
        self.window_ms = window_ms

    @property
    def final_path(self) -> Path:
        return self.review / "human-labels.json"

    @property
    def draft_path(self) -> Path:
        return self.review / "human-labels-draft.json"

    def _saved(self) -> dict:
        for path in (self.final_path, self.draft_path):
            if path.exists():
                return json.loads(path.read_text(encoding="utf-8"))
        return self.template

    def _window(self, target: str) -> list[dict]:
        start = self.segments[self.order[target]]["startMs"]
        return [{k: s[k] for k in ("id", "startMs", "text")} for s in self.segments
                if abs(s["startMs"] - start) <= self.window_ms]

    def state(self) -> dict:
        saved = self._saved()
        by_target = {c["targetSegmentId"]: c for c in saved["cases"]}
        cases = []
        for case in self.template["cases"]:
            target = case["targetSegmentId"]
            entry = by_target.get(target, case)
            cases.append({"targetSegmentId": target, "startMs": self.segments[self.order[target]]["startMs"],
                          "humanDecision": entry["humanDecision"], "contextSegmentIds": entry["contextSegmentIds"],
                          "evidenceSegmentIds": entry["evidenceSegmentIds"], "note": entry["note"],
                          "window": self._window(target)})
        return {"videoId": self.video_id, "reviewerId": saved.get("reviewerId"), "finalized": self.final_path.exists(),
                "cases": cases}

    def _document(self, state: dict) -> dict:
        if self.final_path.exists():
            raise ValueError("Already finalized")
        incoming = {c["targetSegmentId"]: c for c in state["cases"]}
        cases = []
        for case in self.template["cases"]:
            target = case["targetSegmentId"]
            entry = incoming.get(target)
            if entry is None:
                raise ValueError(f"Missing case {target}")
            decision = entry.get("humanDecision")
            if decision is not None and decision not in DECISIONS:
                raise ValueError(f"Bad decision for {target}: {decision}")
            context, evidence = list(entry.get("contextSegmentIds") or []), list(entry.get("evidenceSegmentIds") or [])
            unknown = [i for i in context + evidence if i not in self.order]
            if unknown:
                raise ValueError(f"Unknown segment IDs for {target}: {unknown}")
            if target in context:
                raise ValueError(f"Target cannot be its own context: {target}")
            note = (entry.get("note") or "").strip() or None
            cases.append({"videoId": self.video_id, "targetSegmentId": target, "humanDecision": decision,
                          "contextSegmentIds": sorted(set(context), key=self.order.get),
                          "evidenceSegmentIds": sorted(set(evidence), key=self.order.get), "note": note})
        reviewer = (state.get("reviewerId") or "").strip() or None
        return {**{k: self.template[k] for k in ("labelVersion", "role")}, "reviewerId": reviewer, "cases": cases}

    def save(self, state: dict) -> dict:
        doc = self._document(state)
        temp = self.draft_path.with_suffix(".tmp")
        temp.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        os.replace(temp, self.draft_path)
        return doc

    def finalize(self, state: dict) -> dict:
        doc = self._document(state)
        missing = [c["targetSegmentId"] for c in doc["cases"] if c["humanDecision"] is None]
        if missing or not doc["reviewerId"]:
            raise ValueError(f"Not ready: undecided {missing}, reviewer {doc['reviewerId']!r}")
        self.save(state)
        with self.final_path.open("x", encoding="utf-8") as out:
            out.write(json.dumps(doc, ensure_ascii=False, indent=1) + "\n")
        self.final_path.chmod(0o444)
        return doc

    def progress(self) -> dict:
        cases = self._saved()["cases"]
        return {"videoId": self.video_id, "decided": sum(c["humanDecision"] is not None for c in cases),
                "total": len(self.template["cases"]), "finalized": self.final_path.exists()}


def make_server(base: Path, video_ids: list[int], port: int = 8765) -> ThreadingHTTPServer:
    stores = {v: ReviewStore(base, v) for v in video_ids}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args) -> None:
            pass

        def _send(self, status: int, body: bytes, kind: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _json(self, status: int, value) -> None:
            self._send(status, json.dumps(value, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

        def _store(self, url) -> ReviewStore | None:
            try:
                return stores.get(int(parse_qs(url.query).get("video", [""])[0]))
            except ValueError:
                return None

        def do_GET(self) -> None:
            url = urlparse(self.path)
            if url.path == "/":
                self._send(200, PAGE.read_bytes(), "text/html; charset=utf-8")
            elif url.path == "/api/videos":
                self._json(200, [stores[v].progress() for v in video_ids])
            elif url.path == "/api/state" and (store := self._store(url)):
                self._json(200, store.state())
            else:
                self._json(404, {"error": "not found"})

        def do_POST(self) -> None:
            url = urlparse(self.path)
            store = self._store(url)
            if store is None or url.path not in ("/api/save", "/api/finalize"):
                self._json(404, {"error": "not found"})
                return
            try:
                state = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                (store.save if url.path == "/api/save" else store.finalize)(state)
                self._json(200, {"ok": True, "progress": store.progress()})
            except (ValueError, KeyError, TypeError) as error:
                self._json(400, {"ok": False, "error": str(error)})

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("base", type=Path)
    parser.add_argument("videos", nargs="+", type=int)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args(argv)
    server = make_server(args.base, args.videos, args.port)
    print(f"http://127.0.0.1:{server.server_address[1]}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
