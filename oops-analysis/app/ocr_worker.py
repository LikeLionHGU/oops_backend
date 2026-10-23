"""One OCR job per OS process. No API keys or transcript calls are needed here."""
from __future__ import annotations

import json
import logging
import sys
from pathlib import Path

from . import ocr
from .config import get_settings
from .ocr_metrics import phase
from .media import MediaError, prepare, resolve_storage_path, valid_duration

log = logging.getLogger(__name__)


def perform(payload: dict) -> dict:
    video = None
    try:
        with phase("prepare_media"):
            video = prepare(payload.get("videoUrl"), payload.get("filePath"))
        duration = valid_duration(video.duration_sec)
        settings = get_settings()
        limit = settings.effective_max_duration_sec
        if limit and duration > limit:
            raise MediaError("영상 길이가 분석 서버의 허용 범위를 초과했습니다.")
        frame_dir = payload.get("frameDir")
        if frame_dir:
            frame_dir = str(resolve_storage_path(frame_dir, "frames"))
        return ocr.run(video, payload.get("intervalSec") or settings.default_interval_sec, frame_dir)
    finally:
        if video:
            video.cleanup()


def main(job_dir: Path) -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        with phase("worker_total"):
            data = perform(json.loads((job_dir / "request.json").read_text()))
        result = {"statusCode": 200, "data": data}
    except MediaError as exc:
        result = {"statusCode": 400, "detail": str(exc)}
    except (ocr.OcrUnavailable, ocr.OcrProcessingFailed) as exc:
        result = {"statusCode": 503, "detail": str(exc)}
    except Exception:
        log.exception("[ocr-worker] job failed")
        result = {"statusCode": 500, "detail": "OCR 작업 중 내부 오류가 발생했습니다."}
    (job_dir / "result.json").write_text(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main(Path(sys.argv[1]))
