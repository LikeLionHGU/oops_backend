"""Whisper 로 타임스탬프 붙은 대본을 만든다.

2026-10 고도화: 기본 제공자를 Groq Whisper(large-v3) 로 바꿨다.
OpenAI whisper-1 은 2027-02-26 에 API 에서 사라지고, OpenAI 가 권하는 gpt-transcribe 는
문장별 시각(segment)을 주지 않는다. 우리는 그 시각으로 카드 위치·발언/자막 맞추기를 하므로
같은 Whisper 를 OpenAI 와 같은 형식으로 제공하는 Groq 로 옮겼다.

  STT_PROVIDER=groq   (기본) GROQ_API_KEY 로 Groq 호출
  STT_PROVIDER=openai OPENAI_API_KEY 로 whisper-1 호출 (예전 방식)

groq 로 두었는데 키가 없거나 조각 하나가 실패하면, OpenAI 키가 있을 때 그 조각만 OpenAI 로 다시 보낸다.
whisper-1 이 살아 있는 동안에는 이게 안전망이 된다.
"""
from __future__ import annotations

import logging
import math
import time
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .config import Settings, get_settings
from .media import MediaError, PreparedVideo, extract_audio

log = logging.getLogger(__name__)

# 업로드 상한이 25MB(OpenAI·Groq 무료 단계 공통)라 긴 영상은 잘라서 보낸다.
# 16kHz mono 64kbps 로 뽑으니 10분이면 약 5MB 다.
CHUNK_SEC = 600


@dataclass(frozen=True)
class SttBackend:
    """음성 인식을 보낼 곳 하나. client 는 openai.OpenAI (Groq 도 같은 클라이언트에 주소만 다르다)."""
    name: str
    model: str
    client: Any


def _openai_backend(settings: Settings) -> SttBackend | None:
    if not settings.openai_api_key:
        return None
    from openai import OpenAI
    # 조직/프로젝트를 지정하면 그쪽 크레딧에서 차감된다. 비어 있으면 기본 조직.
    client = OpenAI(
        api_key=settings.openai_api_key,
        organization=settings.openai_org_id or None,
        project=settings.openai_project_id or None,
    )
    return SttBackend("openai", settings.whisper_model, client)


def _groq_backend(settings: Settings) -> SttBackend | None:
    if not settings.groq_api_key:
        return None
    from openai import OpenAI
    client = OpenAI(api_key=settings.groq_api_key, base_url=settings.groq_base_url)
    return SttBackend("groq", settings.groq_whisper_model, client)


def select_backends(settings: Settings) -> list[SttBackend]:
    """쓸 순서대로. 첫 번째가 주 제공자, 두 번째가 실패했을 때의 대체."""
    provider = (settings.stt_provider or "groq").strip().lower()
    groq = _groq_backend(settings)
    openai = _openai_backend(settings)

    if provider == "openai":
        ordered = [openai]
    else:
        if groq is None and openai is not None:
            log.warning("[stt] STT_PROVIDER=groq 인데 GROQ_API_KEY 가 없어 OpenAI(%s) 로 돌립니다.",
                        settings.whisper_model)
        ordered = [groq, openai]
    return [b for b in ordered if b is not None]


def is_available(settings: Settings | None = None) -> bool:
    settings = settings or get_settings()
    return bool(settings.groq_api_key or settings.openai_api_key)


def _split_audio(audio: Path, workdir: Path, duration_sec: float) -> list[tuple[int, Path]]:
    if duration_sec <= CHUNK_SEC:
        return [(0, audio)]

    chunks: list[tuple[int, Path]] = []
    count = math.ceil(duration_sec / CHUNK_SEC)
    for i in range(count):
        start = i * CHUNK_SEC
        target = workdir / f"audio_{i:03d}.mp3"
        subprocess.run(
            ["ffmpeg", "-y", "-i", str(audio), "-ss", str(start),
             "-t", str(CHUNK_SEC), "-c", "copy", str(target)],
            capture_output=True, text=True,
        )
        if target.exists():
            chunks.append((start * 1000, target))
    return chunks


def _transcribe_chunk(backend: SttBackend, chunk: Path, language: str) -> Any:
    kwargs: dict[str, Any] = {
        "model": backend.model,
        "response_format": "verbose_json",
        "timestamp_granularities": ["segment"],
    }
    if language:
        kwargs["language"] = language
    with open(chunk, "rb") as f:
        return backend.client.audio.transcriptions.create(file=f, **kwargs)


def _field(obj: Any, name: str, default: Any = None) -> Any:
    """SDK 객체든 dict 든 같은 방법으로 꺼낸다. (Groq 응답을 dict 로 받는 경우 대비)"""
    if isinstance(obj, dict):
        return obj.get(name, default)
    return getattr(obj, name, default)


def to_segments(result: Any, offset_ms: int) -> list[dict]:
    segments: list[dict] = []
    for seg in (_field(result, "segments") or []):
        text = (_field(seg, "text", "") or "").strip()
        if not text:
            continue
        start = float(_field(seg, "start", 0.0) or 0.0)
        end = float(_field(seg, "end", start) or start)
        segments.append({
            "startMs": offset_ms + int(start * 1000),
            "endMs": offset_ms + int(end * 1000),
            "text": text,
        })
    return segments


def transcribe(video: PreparedVideo) -> dict:
    settings = get_settings()
    backends = select_backends(settings)
    if not backends:
        raise MediaError("음성 인식 키가 없습니다. .env 에 GROQ_API_KEY(또는 OPENAI_API_KEY)를 넣어 주세요.")

    started = time.time()
    audio = extract_audio(video)

    segments: list[dict] = []
    language = None
    used: dict[str, int] = {}

    for offset_ms, chunk in _split_audio(audio, video.workdir, video.duration_sec):
        result = None
        last_error: Exception | None = None
        for backend in backends:
            try:
                result = _transcribe_chunk(backend, chunk, settings.stt_language)
                used[backend.name] = used.get(backend.name, 0) + 1
                break
            except Exception as e:   # 한도 초과·일시 장애 등. 다음 제공자로 넘긴다
                last_error = e
                log.warning("[stt] %s(%s) 실패 — %s", backend.name, backend.model, e)
        if result is None:
            raise MediaError(f"음성 인식에 실패했습니다: {last_error}")

        language = language or _field(result, "language")
        segments.extend(to_segments(result, offset_ms))

    provider = "+".join(used) if used else backends[0].name
    log.info("[stt] provider=%s segments=%d language=%s | 소요 %.1f초", provider, len(segments), language,
             time.time() - started)
    return {"language": language or "ko", "segments": segments, "provider": provider}
