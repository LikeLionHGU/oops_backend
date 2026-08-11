"""OpenAI Whisper API 로 타임스탬프 붙은 대본을 만든다."""
from __future__ import annotations

import logging
import math
import subprocess
from pathlib import Path

from .config import get_settings
from .media import MediaError, PreparedVideo, extract_audio

log = logging.getLogger(__name__)

# Whisper API 업로드 상한이 25MB 라 긴 영상은 잘라서 보낸다
CHUNK_SEC = 600


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


def transcribe(video: PreparedVideo) -> dict:
    settings = get_settings()
    if not settings.openai_api_key:
        raise MediaError("OPENAI_API_KEY 가 설정되지 않았습니다.")

    from openai import OpenAI

    # 조직/프로젝트를 지정하면 그쪽 크레딧에서 차감된다. 비어 있으면 기본 조직.
    client = OpenAI(
        api_key=settings.openai_api_key,
        organization=settings.openai_org_id or None,
        project=settings.openai_project_id or None,
    )
    audio = extract_audio(video)

    segments: list[dict] = []
    language = None

    for offset_ms, chunk in _split_audio(audio, video.workdir, video.duration_sec):
        with open(chunk, "rb") as f:
            result = client.audio.transcriptions.create(
                model=settings.whisper_model,
                file=f,
                response_format="verbose_json",
                timestamp_granularities=["segment"],
            )

        language = language or getattr(result, "language", None)
        for seg in (getattr(result, "segments", None) or []):
            start = getattr(seg, "start", 0.0)
            end = getattr(seg, "end", 0.0)
            text = (getattr(seg, "text", "") or "").strip()
            if not text:
                continue
            segments.append({
                "startMs": offset_ms + int(start * 1000),
                "endMs": offset_ms + int(end * 1000),
                "text": text,
            })

    log.info("[stt] segments=%d language=%s", len(segments), language)
    return {"language": language or "ko", "segments": segments}
