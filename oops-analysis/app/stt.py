"""Timestamp-preserving STT adapters. Groq Whisper Large v3 is the default; no paid fallback."""
from __future__ import annotations

import logging
import math
import time
import subprocess
from pathlib import Path

from .config import get_settings
from .media import MediaError, PreparedVideo, extract_audio, run_command

log = logging.getLogger(__name__)

# 긴 영상의 안정적인 업로드와 타임스탬프 보존을 위해 10분 단위로 처리한다.
CHUNK_SEC = 600
GROQ_STT_MODELS = {"whisper-large-v3"}
GROQ_BASE_URL = "https://api.groq.com/openai/v1"


def _request_options(model: str) -> dict:
    if model == "whisper-1" or model in GROQ_STT_MODELS:
        return {"model": model, "response_format": "verbose_json",
                "timestamp_granularities": ["segment"]}
    if model == "gpt-4o-transcribe-diarize":
        # SDK 1.59.6 has no typed diarization fields. extra_body overrides the
        # wire format while its json parser preserves additional response fields.
        return {"model": model, "response_format": "json",
                "extra_body": {"response_format": "diarized_json", "chunking_strategy": "auto"}}
    raise MediaError("구간 시간 정보를 보존하는 STT 모델만 사용할 수 있습니다.")


def _client_options(model: str, settings) -> dict:
    if model in GROQ_STT_MODELS:
        if not settings.groq_api_key:
            raise MediaError("GROQ_API_KEY 가 설정되지 않았습니다.")
        return {"api_key": settings.groq_api_key, "base_url": GROQ_BASE_URL}
    if not settings.openai_api_key:
        raise MediaError("OPENAI_API_KEY 가 설정되지 않았습니다.")
    return {
        "api_key": settings.openai_api_key,
        "organization": settings.openai_org_id or None,
        "project": settings.openai_project_id or None,
    }


def _field(value, name: str, default=None):
    return value.get(name, default) if isinstance(value, dict) else getattr(value, name, default)


def _normalize_segments(result, offset_ms: int, chunk_index: int, diarized: bool) -> list[dict]:
    raw = _field(result, "segments")
    if not isinstance(raw, (list, tuple)):
        raise MediaError("STT 응답에 구간 시간 정보가 없습니다.")
    segments = []
    for seg in raw:
        text = _field(seg, "text", "")
        if not isinstance(text, str):
            raise MediaError("STT 구간 텍스트 형식이 올바르지 않습니다.")
        if not text.strip():
            continue
        start, end = _field(seg, "start"), _field(seg, "end")
        if (isinstance(start, bool) or isinstance(end, bool)
                or not isinstance(start, (int, float)) or not isinstance(end, (int, float))
                or not math.isfinite(start) or not math.isfinite(end) or start < 0 or end <= start):
            raise MediaError("STT 구간 시간 정보가 올바르지 않습니다.")
        item = {"startMs": offset_ms + int(start * 1000),
                "endMs": offset_ms + int(end * 1000), "text": text.strip()}
        if item["endMs"] <= item["startMs"]:
            raise MediaError("STT 구간 길이가 밀리초 해상도보다 짧습니다.")
        speaker = _field(seg, "speaker")
        if diarized and isinstance(speaker, str) and speaker:
            # Speaker A in independently uploaded chunks is not a global identity.
            item["speaker"] = f"chunk-{chunk_index}:{speaker}"
        segments.append(item)
    if not segments and (_field(result, "text", "") or "").strip():
        raise MediaError("STT 텍스트에 대응하는 유효 구간이 없습니다.")
    return sorted(segments, key=lambda s: (s["startMs"], s["endMs"]))


def _split_audio(audio: Path, workdir: Path, duration_sec: float) -> list[tuple[int, Path]]:
    if duration_sec <= CHUNK_SEC:
        return [(0, audio)]

    chunks: list[tuple[int, Path]] = []
    count = math.ceil(duration_sec / CHUNK_SEC)
    for i in range(count):
        start = i * CHUNK_SEC
        target = workdir / f"audio_{i:03d}.mp3"
        result = run_command(
            ["ffmpeg", "-y", "-i", str(audio), "-ss", str(start),
             "-t", str(CHUNK_SEC), "-c", "copy", str(target)],
            timeout=60, label="음성 분할",
        )
        if result.returncode != 0 or not target.is_file() or target.stat().st_size == 0:
            # Split every chunk before any paid request. Never certify an incomplete transcript.
            raise MediaError(f"음성 분할에 실패했습니다 (구간 {i + 1}/{count}). 전사를 시작하지 않았습니다.")
        chunks.append((start * 1000, target))
    return chunks


def transcribe(video: PreparedVideo) -> dict:
    settings = get_settings()
    model = settings.stt_model or settings.whisper_model
    options = _request_options(model)
    from openai import OpenAI

    started = time.time()
    client = OpenAI(**_client_options(model, settings))
    audio = extract_audio(video)

    segments: list[dict] = []
    language = None

    for chunk_index, (offset_ms, chunk) in enumerate(_split_audio(audio, video.workdir, video.duration_sec)):
        with open(chunk, "rb") as f:
            result = client.audio.transcriptions.create(
                file=f,
                **options,
            )

        language = language or getattr(result, "language", None)
        segments.extend(_normalize_segments(result, offset_ms, chunk_index, model == "gpt-4o-transcribe-diarize"))

    log.info("[stt] model=%s segments=%d language=%s | 소요 %.1f초", model, len(segments), language,
             time.time() - started)
    return {"language": language or "ko", "segments": segments, "model": model,
            "timestampSource": "diarized_segment" if model == "gpt-4o-transcribe-diarize"
            else "groq_segment" if model in GROQ_STT_MODELS else "whisper_segment"}
