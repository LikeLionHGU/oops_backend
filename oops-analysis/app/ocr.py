"""PaddleOCR 로 화면에 박힌 자막/텍스트를 읽는다."""
from __future__ import annotations

import logging
import shutil
import time
from functools import lru_cache
from pathlib import Path

from .config import get_settings
from .media import PreparedVideo, extract_frames

log = logging.getLogger(__name__)

# 신뢰도가 낮은 인식 결과는 노이즈라 버린다.
# 한글 자막은 외곽선·그림자 효과 때문에 신뢰도가 잘 안 올라가므로 기준을 낮게 잡고,
# 대신 글자 수 조건으로 노이즈를 거른다. 조금 깨진 텍스트는 LLM 이 문맥으로 복원한다.
MIN_CONFIDENCE = 0.6
MIN_LENGTH = 2

# 유튜브 UI 나 채널 워터마크처럼 매번 잡히는 무의미한 텍스트
IGNORE_PATTERNS = ("구독", "좋아요", "알림설정", "http", "www.")


class OcrUnavailable(RuntimeError):
    pass


@lru_cache
def _engine():
    try:
        from paddleocr import PaddleOCR
    except ImportError as e:
        raise OcrUnavailable("PaddleOCR 가 설치되지 않았습니다.") from e

    return PaddleOCR(use_angle_cls=True, lang=get_settings().ocr_lang, show_log=False)


def is_available() -> bool:
    try:
        _engine()
        return True
    except Exception:
        return False


def _parse(raw) -> list[tuple[str, float]]:
    """PaddleOCR 출력 포맷이 버전마다 달라서 방어적으로 파싱한다."""
    lines: list[tuple[str, float]] = []
    if not raw:
        return lines

    page = raw[0] if isinstance(raw, list) and raw and isinstance(raw[0], list) else raw
    if not page:
        return lines

    for item in page:
        try:
            text, confidence = item[1][0], float(item[1][1])
        except (IndexError, TypeError, ValueError):
            continue
        lines.append((text, confidence))
    return lines


def run(video: PreparedVideo, interval_sec: float, frame_dir: str | None = None) -> dict:
    """
    frame_dir 이 주어지면, 텍스트가 인식된 프레임만 그 폴더에 보관하고
    보관 경로를 함께 돌려준다. Spring 이 이 이미지를 프론트에 서빙한다.
    전체 프레임을 다 남기면 용량이 커지므로 필요한 것만 남긴다.
    """
    started = time.time()
    engine = _engine()
    frames = extract_frames(video, interval_sec)
    extracted_at = time.time()

    keep_dir = Path(frame_dir) if frame_dir else None
    if keep_dir:
        keep_dir.mkdir(parents=True, exist_ok=True)

    items: list[dict] = []
    previous_text = None

    for time_ms, frame_path in frames:
        try:
            raw = engine.ocr(str(frame_path), cls=True)
        except Exception as e:  # 프레임 하나가 깨져도 전체를 멈추지 않는다
            log.warning("[ocr] 프레임 실패 %s: %s", frame_path.name, e)
            continue

        parsed = [
            (text.strip(), conf) for text, conf in _parse(raw)
            if conf >= MIN_CONFIDENCE
            and len(text.strip()) >= MIN_LENGTH
            and not any(p in text for p in IGNORE_PATTERNS)
        ]
        if not parsed:
            continue

        merged = " ".join(text for text, _ in parsed)
        confidence = sum(conf for _, conf in parsed) / len(parsed)

        # 같은 자막이 여러 프레임에 걸쳐 있으면 앞 항목의 끝시간만 늘린다
        if merged == previous_text and items:
            items[-1]["endMs"] = time_ms + int(interval_sec * 1000)
            continue

        saved_path = None
        if keep_dir:
            target = keep_dir / f"{time_ms:09d}.jpg"
            try:
                shutil.copyfile(frame_path, target)
                saved_path = str(target.resolve())
            except OSError as e:
                log.warning("[ocr] 프레임 보관 실패 %s: %s", target, e)

        items.append({
            "startMs": time_ms,
            "endMs": time_ms + int(interval_sec * 1000),
            "text": merged,
            "confidence": round(confidence, 3),
            "framePath": saved_path,
        })
        previous_text = merged

    now = time.time()
    log.info("[ocr] frames=%d items=%d | 프레임추출 %.1f초, 인식 %.1f초 (프레임당 %.2f초)",
             len(frames), len(items),
             extracted_at - started, now - extracted_at,
             (now - extracted_at) / max(1, len(frames)))
    return {"items": items}
