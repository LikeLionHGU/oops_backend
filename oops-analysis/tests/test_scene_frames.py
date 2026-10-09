import subprocess
from pathlib import Path
from types import SimpleNamespace
import pytest
from app.media import MediaError, PreparedVideo
from app import scene_frames


def video():
    return PreparedVideo(Path("source.mp4"), Path("work"), 10.0)


@pytest.mark.parametrize("times", [[], [0, 1, 2, 3], [-1], [10_000], [0, 0], [True], [1.5]])
def test_invalid_times_rejected_before_ffmpeg(times, monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("must not execute")
    monkeypatch.setattr(scene_frames.subprocess, "run", forbidden)
    with pytest.raises(MediaError):
        scene_frames.sample(video(), times)


def test_preserves_actual_pts_and_inline_jpeg_without_ocr(monkeypatch):
    calls = []
    def run(cmd, **kwargs):
        calls.append((cmd, kwargs))
        return SimpleNamespace(returncode=0, stdout=b"\xff\xd8jpeg", stderr=b"showinfo pts_time:1.04")
    monkeypatch.setattr(scene_frames.subprocess, "run", run)
    frames = scene_frames.sample(video(), [1000])
    assert frames[0]["timestampMs"] == 1040
    assert frames[0]["requestedMs"] == 1000
    assert frames[0]["frameId"] == "scene-1000"
    assert calls[0][1]["timeout"] == 20
    assert "-copyts" in calls[0][0]
    assert "framePath" not in frames[0]


@pytest.mark.parametrize("stdout,stderr", [(b"bad", b"pts_time:1"), (b"\xff\xd8jpeg", b""),
                                           (b"\xff\xd8jpeg", b"pts_time:8"),
                                           (b"\xff\xd8" + b"x" * 512_000, b"pts_time:1")])
def test_bad_image_missing_pts_and_wrong_time_fail(stdout, stderr, monkeypatch):
    monkeypatch.setattr(scene_frames.subprocess, "run", lambda *a, **k: SimpleNamespace(returncode=0, stdout=stdout, stderr=stderr))
    with pytest.raises(MediaError):
        scene_frames.sample(video(), [1000])


def test_timeout_is_controlled_failure(monkeypatch):
    def run(*a, **k):
        raise subprocess.TimeoutExpired("ffmpeg", 20)
    monkeypatch.setattr(scene_frames.subprocess, "run", run)
    with pytest.raises(MediaError):
        scene_frames.sample(video(), [0])


def test_real_ffmpeg_sample_pts(tmp_path):
    import shutil
    if not shutil.which("ffmpeg"):
        pytest.skip("ffmpeg unavailable")
    path = tmp_path / "fixture.mp4"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=blue:s=320x240:r=25:d=3",
                    "-c:v", "mpeg4", str(path)], check=True, capture_output=True, timeout=20)
    frames = scene_frames.sample(PreparedVideo(path, tmp_path, 3.0), [0, 750, 2750])
    assert [f["requestedMs"] for f in frames] == [0, 750, 2750]
    assert all(abs(f["timestampMs"] - f["requestedMs"]) < 50 for f in frames)


def test_source_path_boundary_including_symlink(tmp_path, monkeypatch):
    from app import media
    root = tmp_path / "storage"
    videos = root / "videos" / "1"
    videos.mkdir(parents=True)
    source = videos / "original.mp4"
    source.touch()
    outside = tmp_path / "outside.mp4"
    outside.touch()
    monkeypatch.setattr(media, "get_settings", lambda: SimpleNamespace(media_storage_root=str(root)))
    assert media.resolve_storage_path(str(source), "source") == source
    (videos / "link.mp4").symlink_to(outside)
    for path in [outside, videos / "link.mp4", root / "frames" / "1.jpg"]:
        with pytest.raises(MediaError):
            media.resolve_storage_path(str(path), "source")


def test_scene_request_rejects_coerced_and_unbounded_timestamps():
    from app.main import SceneRequest
    from pydantic import ValidationError
    for times in [[], [1, 2, 3, 4], [True], ["1"], [1.2]]:
        with pytest.raises(ValidationError):
            SceneRequest(filePath="videos/1/original.mp4", timestampsMs=times)


def test_scene_endpoint_cleans_workdir_on_failure(monkeypatch):
    from app import main
    from fastapi import HTTPException
    cleaned = []
    fake = SimpleNamespace(duration_sec=5, cleanup=lambda: cleaned.append(True))
    monkeypatch.setattr(main, "prepare", lambda url, path: fake)
    monkeypatch.setattr(main, "_guard_duration", lambda duration: None)
    def fail(*args):
        raise MediaError("frame failed")
    monkeypatch.setattr(main.scene_frames, "sample", fail)
    with pytest.raises(HTTPException) as exc:
        main.scene(main.SceneRequest(filePath="videos/1/original.mp4", timestampsMs=[0]))
    assert exc.value.status_code == 400
    assert cleaned == [True]
