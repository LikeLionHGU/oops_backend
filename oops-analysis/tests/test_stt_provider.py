"""음성 인식 제공자(Groq / OpenAI) 선택과 대체 동작을 잠근다. (2026-10 고도화)"""
from pathlib import Path
from types import SimpleNamespace

import pytest

from app import stt
from app.config import Settings
from app.media import MediaError


def settings(**kw) -> Settings:
    return Settings(_env_file=None, **kw)


def test_groq_is_default_and_openai_is_fallback():
    backends = stt.select_backends(settings(groq_api_key="g", openai_api_key="o"))
    assert [b.name for b in backends] == ["groq", "openai"]
    assert backends[0].model == "whisper-large-v3"
    assert str(backends[0].client.base_url).startswith("https://api.groq.com/openai/v1")


def test_groq_without_key_uses_openai():
    backends = stt.select_backends(settings(openai_api_key="o"))
    assert [b.name for b in backends] == ["openai"]
    assert backends[0].model == "whisper-1"


def test_openai_provider_keeps_old_behaviour():
    backends = stt.select_backends(settings(stt_provider="openai", groq_api_key="g", openai_api_key="o"))
    assert [b.name for b in backends] == ["openai"]


def test_no_keys_means_unavailable():
    assert stt.select_backends(settings()) == []
    assert not stt.is_available(settings())
    assert stt.is_available(settings(groq_api_key="g"))


def test_segments_from_sdk_object_and_dict():
    obj = SimpleNamespace(segments=[SimpleNamespace(start=1.5, end=3.0, text=" 안녕하세요 "),
                                    SimpleNamespace(start=3.0, end=4.0, text="  ")])
    assert stt.to_segments(obj, 600_000) == [{"startMs": 601_500, "endMs": 603_000, "text": "안녕하세요"}]

    as_dict = {"segments": [{"start": 0.0, "end": 2.25, "text": "반갑습니다"}]}
    assert stt.to_segments(as_dict, 0) == [{"startMs": 0, "endMs": 2250, "text": "반갑습니다"}]


class FakeTranscriptions:
    def __init__(self, fail: bool, calls: list):
        self.fail, self.calls = fail, calls

    def create(self, file, **kwargs):
        self.calls.append(kwargs)
        if self.fail:
            raise RuntimeError("429 rate limit")
        return SimpleNamespace(language="korean",
                               segments=[SimpleNamespace(start=0.0, end=1.0, text="첫 문장")])


def fake_backend(name: str, fail: bool, calls: list) -> stt.SttBackend:
    client = SimpleNamespace(audio=SimpleNamespace(transcriptions=FakeTranscriptions(fail, calls)))
    return stt.SttBackend(name, f"{name}-model", client)


def _patch_pipeline(monkeypatch, tmp_path: Path, backends):
    audio = tmp_path / "audio.mp3"
    audio.write_bytes(b"x")
    monkeypatch.setattr(stt, "get_settings", lambda: settings(stt_language="ko"))
    monkeypatch.setattr(stt, "select_backends", lambda s: backends)
    monkeypatch.setattr(stt, "extract_audio", lambda video: audio)
    return SimpleNamespace(workdir=tmp_path, duration_sec=30.0)


def test_falls_back_to_openai_when_groq_fails(monkeypatch, tmp_path):
    calls: list = []
    video = _patch_pipeline(monkeypatch, tmp_path,
                            [fake_backend("groq", True, calls), fake_backend("openai", False, calls)])
    result = stt.transcribe(video)
    assert result["provider"] == "openai"
    assert result["segments"] == [{"startMs": 0, "endMs": 1000, "text": "첫 문장"}]
    # 두 제공자 모두 문장별 시각을 요청하고, 언어 힌트를 넘긴다
    assert all(c["response_format"] == "verbose_json" and c["timestamp_granularities"] == ["segment"]
               and c["language"] == "ko" for c in calls)
    assert [c["model"] for c in calls] == ["groq-model", "openai-model"]


def test_raises_when_every_provider_fails(monkeypatch, tmp_path):
    calls: list = []
    video = _patch_pipeline(monkeypatch, tmp_path, [fake_backend("groq", True, calls)])
    with pytest.raises(MediaError):
        stt.transcribe(video)
