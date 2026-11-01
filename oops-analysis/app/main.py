from __future__ import annotations

import logging

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field, StrictInt

from . import stt
from .config import get_settings
from .media import MediaError, prepare, valid_duration

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

app = FastAPI(title="oops-analysis", version="0.1.0")


class MediaRequest(BaseModel):
    videoUrl: str | None = None
    filePath: str | None = None
    # redesign-v3: 화면 글자(OCR)는 쓰지 않는다. 옛 백엔드가 보내도 무시한다.
    intervalSec: float | None = Field(default=None, gt=0, allow_inf_nan=False)
    frameDir: str | None = None


@app.get("/health")
def health() -> dict:
    settings = get_settings()
    model = settings.stt_model or settings.whisper_model
    stt_key = settings.groq_api_key if model in stt.GROQ_STT_MODELS else settings.openai_api_key
    return {
        "status": "UP",
        "sttAvailable": bool(stt_key),
        "ocrAvailable": False,   # redesign-v3: OCR 제거
        "analysisProfile": settings.analysis_profile,
    }


@app.post("/probe")
def probe(request: MediaRequest) -> dict:
    """길이만 재고 끝낸다.

    업로드 직후 설정된 길이 상한(기본 120분)을 검사한다. local 모드는 상한이 없다.
    분석을 다 돌린 뒤에 "너무 깁니다" 라고 하면 이미 STT 비용이 나간 뒤다.
    무거운 작업은 하지 않으므로 로컬 파일이면 1초 안에 끝난다.
    """
    video = None
    try:
        video = prepare(request.videoUrl, request.filePath)
        valid_duration(video.duration_sec)
        return {
            "durationSec": int(video.duration_sec),
            "title": video.title,
            "maxDurationSec": get_settings().effective_max_duration_sec,
            "withinLimit": get_settings().effective_max_duration_sec == 0
                or video.duration_sec <= get_settings().effective_max_duration_sec,
        }
    except MediaError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:
        log.exception("길이 확인 실패")
        raise HTTPException(status_code=500, detail=str(e)) from e
    finally:
        if video:
            video.cleanup()


@app.post("/transcribe")
def transcribe(request: MediaRequest) -> dict:
    video = None
    try:
        video = prepare(request.videoUrl, request.filePath)
        _guard_duration(video.duration_sec)
        result = stt.transcribe(video)
        result["title"] = video.title
        result["durationSec"] = int(video.duration_sec)
        return result
    except MediaError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:
        log.exception("전사 실패")
        raise HTTPException(status_code=500, detail=str(e)) from e
    finally:
        if video:
            video.cleanup()


def _guard_duration(duration_sec: float) -> None:
    duration_sec = valid_duration(duration_sec)
    limit = get_settings().effective_max_duration_sec
    if limit > 0 and duration_sec > limit:
        raise MediaError(
            "영상이 너무 깁니다 (%d분). 최대 %d분까지 지원합니다. "
            "더 긴 영상을 다루려면 .env 의 MAX_DURATION_SEC 을 올리세요."
            % (duration_sec / 60, limit / 60)
        )
