"""Local snapshot export: private from creation, no world-readable window."""
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import export_casebook_map as tool


def test_snapshot_is_written_private_and_atomically(tmp_path, monkeypatch):
    data = tmp_path / "controversy"
    (data / "guidelines").mkdir(parents=True)
    (data / "guidelines/runtime.json").write_text(json.dumps({"examples": []}), encoding="utf-8")
    out = data / "visualizations/casebook-snapshot.js"
    monkeypatch.setattr(tool, "DATA", data)
    monkeypatch.setattr(tool, "OUT", out)
    monkeypatch.setattr(tool, "LOCAL_VIEW", data / "visualizations/absent.json")
    created = []
    real_open = os.open

    def recording_open(path, flags, mode=0o777, *args, **kwargs):
        created.append((Path(path).name, mode))
        return real_open(path, flags, mode, *args, **kwargs)

    monkeypatch.setattr(tool.os, "open", recording_open)
    old = os.umask(0)
    try:
        tool.main()
    finally:
        os.umask(old)
    assert out.stat().st_mode & 0o777 == 0o600
    assert out.read_text(encoding="utf-8").startswith("window.CASEBOOK_SNAPSHOT=")
    assert created and all(mode == 0o600 for _, mode in created)
    assert [p.name for p in out.parent.iterdir()] == [out.name]
