from __future__ import annotations

import logging

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from . import ocr, stt
from .config import get_settings
from .media import MediaError, prepare, PreparedVideo

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

app = FastAPI(title="oops-analysis", version="0.1.0")


class MediaRequest(BaseModel):
    videoUrl: str | None = None
    filePath: str | None = None
    intervalSec: float | None = None
    # OCR 에서 텍스트가 잡힌 프레임을 보관할 폴더. Spring 이 지정한다.
    frameDir: str | None = None


@app.get("/health")
def health() -> dict:
    settings = get_settings()
    return {
        "status": "UP",
        "sttAvailable": bool(settings.openai_api_key),
        "ocrAvailable": ocr.is_available(),
    }


@app.post("/probe")
def probe(request: MediaRequest) -> dict:
    """길이만 재고 끝낸다.

    업로드 직후에 90분을 넘는지 판정하려고 만들었다.
    분석을 다 돌린 뒤에 "너무 깁니다" 라고 하면 이미 STT 비용이 나간 뒤다.
    무거운 작업은 하지 않으므로 로컬 파일이면 1초 안에 끝난다.
    """
    video = None
    try:
        video = prepare(request.videoUrl, request.filePath)
        return {
            "durationSec": int(video.duration_sec),
            "title": video.title,
            "maxDurationSec": get_settings().max_duration_sec,
            "withinLimit": video.duration_sec <= get_settings().max_duration_sec,
        }
    except MediaError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:
        log.exception("길이 확인 실패")
        raise HTTPException(status_code=500, detail=str(e)) from e
    finally:
        _cleanup(video)


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
        _cleanup(video)


@app.post("/ocr")
def run_ocr(request: MediaRequest) -> dict:
    video = None
    try:
        video = prepare(request.videoUrl, request.filePath)
        _guard_duration(video.duration_sec)
        interval = request.intervalSec or get_settings().default_interval_sec
        return ocr.run(video, interval, request.frameDir)
    except ocr.OcrUnavailable as e:
        # Spring 쪽에서 503 을 보고 OCR 단계를 조용히 건너뛴다
        raise HTTPException(status_code=503, detail=str(e)) from e
    except MediaError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:
        log.exception("OCR 실패")
        raise HTTPException(status_code=500, detail=str(e)) from e
    finally:
        _cleanup(video)


def _cleanup(video: PreparedVideo | None) -> None:
    """작업 폴더를 지운다.

    예전에는 `not request.filePath` 로 감싸서 업로드 파일일 때는 아예 안 지웠다.
    유튜브는 workdir 안에 원본을 내려받으니 지우면 안 될 것처럼 보이지만,
    로컬 파일도 workdir 는 따로 만든다(uuid 폴더 하나). 원본은 그 안에 없다.
    그래서 업로드 경로에서도 지우는 것이 맞다.

    안 지우면 분석 1회마다 이만큼 남는다.
      audio.mp3 + 분할 조각        60분 영상에 8MB 안팎
      frames/ JPEG 최대 300장      50~150MB
    채점처럼 여러 편을 연달아 돌리면 디스크가 찬다.
    workdir 를 청소하는 코드는 스프링 쪽에도 없다 — 여기가 유일하다.

    /ocr 은 글자가 있는 프레임만 frameDir(스프링 쪽 uploads/frames)로 **복사**한다.
    finally 는 return 값이 만들어진 뒤에 도므로 캡처 이미지는 그대로 남는다.
    """
    if video:
        video.cleanup()


def _guard_duration(duration_sec: float) -> None:
    limit = get_settings().max_duration_sec
    if duration_sec > limit:
        raise MediaError(
            "영상이 너무 깁니다 (%d분). 최대 %d분까지 지원합니다. "
            "더 긴 영상을 다루려면 .env 의 MAX_DURATION_SEC 을 올리세요."
            % (duration_sec / 60, limit / 60)
        )
