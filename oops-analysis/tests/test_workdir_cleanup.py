"""오래된 작업 폴더 자동 정리 (2026-10)"""
import os
import time

from app import media
from app.config import Settings


def test_removes_only_stale_workdirs(tmp_path, monkeypatch):
    monkeypatch.setattr(media, "get_settings", lambda: Settings(_env_file=None, work_dir=str(tmp_path)))
    old = tmp_path / "old"
    old.mkdir()
    (old / "audio.mp3").write_bytes(b"x")
    two_days_ago = time.time() - 48 * 3600
    os.utime(old, (two_days_ago, two_days_ago))
    fresh = tmp_path / "fresh"
    fresh.mkdir()

    assert media.cleanup_stale_workdirs() == 1
    assert not old.exists()
    assert fresh.exists()


def test_new_workdir_triggers_cleanup(tmp_path, monkeypatch):
    monkeypatch.setattr(media, "get_settings", lambda: Settings(_env_file=None, work_dir=str(tmp_path)))
    old = tmp_path / "old"
    old.mkdir()
    os.utime(old, (time.time() - 48 * 3600,) * 2)
    created = media._new_workdir()
    assert created.exists() and not old.exists()
