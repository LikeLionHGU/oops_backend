"""Offline failure propagation and ownership-safe temporary-directory cleanup."""
from pathlib import Path
from types import SimpleNamespace
import sys

import pytest
from fastapi.testclient import TestClient
from app import main, media, ocr, stt


@pytest.mark.parametrize("endpoint", ["probe", "transcribe", "ocr"])
@pytest.mark.parametrize("file_upload", [True, False])
@pytest.mark.parametrize("fail", [True, False])
def test_endpoints_clean_owned_workdir_but_not_source_or_saved_frames(tmp_path, monkeypatch, endpoint, file_upload, fail):
    source = tmp_path / "original.mp4"
    source.write_bytes(b"source fixture")
    saved = tmp_path / "saved.jpg"
    saved.write_bytes(b"saved frame fixture")
    work = tmp_path / "owned-work"
    work.mkdir()
    (work / "temporary.mp3").write_bytes(b"temporary fixture")
    video = media.PreparedVideo(source, work, 10, "fixture")
    monkeypatch.setattr(main, "prepare", lambda *args: video)
    monkeypatch.setattr(main, "get_settings", lambda: SimpleNamespace(max_duration_sec=5400, default_interval_sec=4))

    def process(*args):
        if fail:
            raise media.MediaError("synthetic failure")
        return {"segments": [], "items": []}
    monkeypatch.setattr(main.stt, "transcribe", process)
    monkeypatch.setattr(main.ocr, "run", process)
    if endpoint == "probe" and fail:
        monkeypatch.setattr(main, "get_settings", lambda: (_ for _ in ()).throw(media.MediaError("synthetic failure")))
    request = {"filePath": str(source)} if file_upload else {"videoUrl": "https://youtu.be/fixture"}
    response = TestClient(main.app).post("/" + endpoint, json=request)
    assert response.status_code == (400 if fail else 200)
    assert not work.exists()
    assert source.read_bytes() == b"source fixture"
    assert saved.read_bytes() == b"saved frame fixture"


def test_prepare_cleans_before_it_can_return_a_video(tmp_path, monkeypatch):
    source = tmp_path / "source.mp4"
    source.touch()
    work = tmp_path / "owned-work"
    work.mkdir()
    monkeypatch.setattr(media, "ensure_ffmpeg", lambda: None)
    monkeypatch.setattr(media, "resolve_storage_path", lambda *args: source)
    monkeypatch.setattr(media, "_new_workdir", lambda: work)
    monkeypatch.setattr(media, "probe_duration", lambda *args: (_ for _ in ()).throw(RuntimeError("synthetic")))
    with pytest.raises(RuntimeError):
        media.prepare(None, str(source))
    assert not work.exists() and source.exists()


def test_download_failure_cleans_unreturned_workdir(tmp_path, monkeypatch):
    work = tmp_path / "owned-work"
    work.mkdir()
    class Downloader:
        def __init__(self, opts): pass
        def __enter__(self): raise RuntimeError("synthetic download failure")
        def __exit__(self, *args): pass
    monkeypatch.setitem(sys.modules, "yt_dlp", SimpleNamespace(YoutubeDL=Downloader))
    monkeypatch.setattr(media, "ensure_ffmpeg", lambda: None)
    monkeypatch.setattr(media, "_new_workdir", lambda: work)
    with pytest.raises(media.MediaError):
        media.prepare("https://youtu.be/fixture", None)
    assert not work.exists()


@pytest.mark.parametrize("mode", ["command_failure", "missing", "empty"])
def test_split_failure_does_not_return_a_partial_chunk_list(tmp_path, monkeypatch, mode):
    def split(command, **kwargs):
        target = Path(command[-1])
        if target.name == "audio_000.mp3":
            target.write_bytes(b"first valid fixture")
            return SimpleNamespace(returncode=0)
        if mode == "empty": target.touch()
        return SimpleNamespace(returncode=1 if mode == "command_failure" else 0)
    monkeypatch.setattr(stt.subprocess, "run", split)
    with pytest.raises(media.MediaError, match="2/2"):
        stt._split_audio(tmp_path / "input.mp3", tmp_path, 601)


def test_successful_split_preserves_all_offsets(tmp_path, monkeypatch):
    def split(command, **kwargs):
        Path(command[-1]).write_bytes(b"fixture")
        return SimpleNamespace(returncode=0)
    monkeypatch.setattr(stt.subprocess, "run", split)
    assert [offset for offset, path in stt._split_audio(tmp_path / "input.mp3", tmp_path, 1201)] == [0, 600000, 1200000]


def test_failed_chunk_prevents_any_transcription_api_call(tmp_path, monkeypatch):
    from unittest.mock import Mock
    import openai
    create = Mock()
    monkeypatch.setattr(openai, "OpenAI", lambda **kwargs: SimpleNamespace(
        audio=SimpleNamespace(transcriptions=SimpleNamespace(create=create))))
    monkeypatch.setattr(stt, "get_settings", lambda: SimpleNamespace(stt_model=None, whisper_model="whisper-1",
        openai_api_key="offline-fixture", openai_org_id="", openai_project_id=""))
    monkeypatch.setattr(stt, "extract_audio", lambda video: tmp_path / "input.mp3")
    def split(command, **kwargs):
        target = Path(command[-1])
        if target.name == "audio_000.mp3": target.write_bytes(b"valid fixture")
        return SimpleNamespace(returncode=0 if target.exists() else 1)
    monkeypatch.setattr(stt.subprocess, "run", split)
    with pytest.raises(media.MediaError):
        stt.transcribe(SimpleNamespace(workdir=tmp_path, duration_sec=601))
    create.assert_not_called()


@pytest.mark.parametrize("results, expected", [([None, None], "SUCCESS"), ([None, "fail"], "PARTIAL"),
                                               (["fail", "fail"], "FAILED"), ([], "FAILED")])
def test_ocr_processing_state_including_legitimate_no_text(monkeypatch, results, expected):
    class Engine:
        def ocr(self, path, cls=True):
            if results[int(path)] == "fail": raise RuntimeError("synthetic frame failure")
            return []  # Successful recognition, no text.
    monkeypatch.setattr(ocr, "_engine", lambda: Engine())
    monkeypatch.setattr(ocr, "_adjust_interval", lambda *args: 1)
    monkeypatch.setattr(ocr, "extract_frames", lambda *args: [(i * 1000, Path(str(i))) for i in range(len(results))])
    monkeypatch.setattr(ocr, "_frame_size", lambda *args: None)
    video = SimpleNamespace(duration_sec=10)
    if expected == "FAILED":
        with pytest.raises(ocr.OcrProcessingFailed): ocr.run(video, 1)
    else:
        result = ocr.run(video, 1)
        assert result["items"] == []
        assert result["processing"] == {"status": expected, "totalFrames": 2,
                                         "successfulFrames": results.count(None), "failedFrames": results.count("fail")}


def test_total_ocr_failure_is_http_503_and_cleans_workdir(tmp_path, monkeypatch):
    work = tmp_path / "owned-work"
    work.mkdir()
    monkeypatch.setattr(main, "prepare", lambda *args: media.PreparedVideo(tmp_path / "source", work, 10))
    monkeypatch.setattr(main.ocr, "run", lambda *args: (_ for _ in ()).throw(ocr.OcrProcessingFailed("synthetic OCR failure")))
    response = TestClient(main.app).post("/ocr", json={"filePath": "fixture"})
    assert response.status_code == 503
    assert not work.exists()
