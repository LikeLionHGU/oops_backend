"""PaddleOCR 로 화면에 박힌 자막/텍스트를 읽는다."""
from __future__ import annotations

import logging
import math
import shutil
import time
from importlib.util import find_spec
from functools import lru_cache
from pathlib import Path

from .config import get_settings
from .media import PreparedVideo, extract_frames

log = logging.getLogger(__name__)

# 신뢰도가 낮은 인식 결과는 노이즈라 버린다.
# 한글 자막은 외곽선·그림자 효과 때문에 신뢰도가 잘 안 올라가므로 기준을 낮게 잡고,
# 대신 글자 수 조건으로 노이즈를 거른다. 전사/OCR 원문을 문맥만으로 교정하지 않는다.
MIN_CONFIDENCE = 0.6
MIN_LENGTH = 2

# 짧은 영상에서 최소한 이만큼은 뽑는다. 2~3장으로는 자막을 놓치기 쉽다.
MIN_FRAMES = 8
# 아무리 짧아도 이보다 촘촘하게는 뜨지 않는다
MIN_INTERVAL_SEC = 0.5


class OcrUnavailable(RuntimeError):
    pass


class OcrProcessingFailed(RuntimeError):
    pass


@lru_cache
def _engine():
    try:
        from paddleocr import PaddleOCR
    except ImportError as e:
        raise OcrUnavailable("PaddleOCR 가 설치되지 않았습니다.") from e

    return PaddleOCR(use_angle_cls=True, lang=get_settings().ocr_lang, show_log=False,
                     rec_batch_num=get_settings().ocr_rec_batch_num)


def is_available() -> bool:
    """Dependency availability only; health must not allocate a persistent Paddle model."""
    try:
        return find_spec("paddleocr") is not None and find_spec("paddle") is not None
    except Exception:
        return False


def run(video: PreparedVideo, interval_sec: float, frame_dir: str | None = None) -> dict:
    """
    frame_dir 이 주어지면, 텍스트가 인식된 프레임만 그 폴더에 보관하고
    보관 경로를 함께 돌려준다. Spring 이 이 이미지를 프론트에 서빙한다.
    전체 프레임을 다 남기면 용량이 커지므로 필요한 것만 남긴다.

    영역별 원문·정규화 좌표와 이어지는 관측을 보존한다.
    반복 글자를 삭제하거나 같은 프레임의 영역을 합치지 않는다.
    """
    started = time.time()
    engine = _engine()

    interval_sec = _adjust_interval(video.duration_sec, interval_sec)
    frames = extract_frames(video, interval_sec)
    if not frames:
        raise OcrProcessingFailed("OCR 분석용 프레임을 추출하지 못했습니다.")
    extracted_at = time.time()

    keep_dir = Path(frame_dir) if frame_dir else None
    if keep_dir:
        keep_dir.mkdir(parents=True, exist_ok=True)

    # ---- 1단계: 프레임별로 줄 단위 인식 결과를 모은다 ----
    region_frames = []
    failed_frames = 0
    for time_ms, frame_path in frames:
        try:
            raw = engine.ocr(str(frame_path), cls=True)
        except Exception as e:
            log.warning("[ocr] 프레임 실패 %s: %s", frame_path.name, e)
            failed_frames += 1
            continue

        region_frames.append((time_ms, frame_path, _parse_regions(raw)))
        if len(region_frames) % 25 == 0:
            log.info("[ocr-progress] processed=%d/%d failed=%d elapsedSec=%.1f",
                     len(region_frames) + failed_frames, len(frames), failed_frames,
                     time.time() - started)

    if not region_frames:
        raise OcrProcessingFailed("모든 OCR 프레임의 인식에 실패했습니다. 글자 없음으로 판단할 수 없습니다.")

    # Do not delete repeated text or merge unrelated regions. Classification happens with STT in Spring.
    items = _region_items(region_frames, interval_sec, video.duration_sec, keep_dir)

    now = time.time()
    log.info("[ocr] frames=%d items=%d | 프레임추출 %.1f초, 인식 %.1f초 (프레임당 %.2f초)",
             len(frames), len(items),
             extracted_at - started, now - extracted_at,
             (now - extracted_at) / max(1, len(frames)))
    return {"items": items, "formatVersion": "regions-v1", "processing": {
        "status": "PARTIAL" if failed_frames else "SUCCESS", "totalFrames": len(frames),
        "successfulFrames": len(region_frames), "failedFrames": failed_frames}}


def _parse_regions(raw):
    """Preserve each OCR line and its full axis-aligned box; missing geometry stays unknown."""
    if not raw:
        return []
    page = raw[0] if isinstance(raw, list) and raw and isinstance(raw[0], list) else raw
    regions = []
    for item in page or []:
        try:
            text, confidence = item[1][0], float(item[1][1])
            if not isinstance(text, str) or not math.isfinite(confidence) or confidence < MIN_CONFIDENCE or len(text.strip()) < MIN_LENGTH:
                continue
        except (IndexError, TypeError, ValueError):
            continue
        box = None
        try:
            xs = [float(p[0]) for p in item[0]]
            ys = [float(p[1]) for p in item[0]]
            if len(xs) >= 4 and all(math.isfinite(v) for v in xs + ys) and max(xs) > min(xs) and max(ys) > min(ys):
                box = (min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys))
        except (IndexError, TypeError, ValueError):
            pass
        regions.append((text, confidence, box))
    return regions


def _frame_size(path):
    # Optional metadata only: unavailable image reader must not discard raw OCR.
    try:
        from PIL import Image
        with Image.open(path) as image:
            return image.size
    except Exception:
        try:
            import cv2
            image = cv2.imread(str(path))
            return (image.shape[1], image.shape[0]) if image is not None else None
        except Exception:
            return None


def _iou(a, b):
    if a is None or b is None:
        return 0.0
    x = max(0, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0]))
    y = max(0, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
    intersection = x * y
    return intersection / (a[2] * a[3] + b[2] * b[3] - intersection) if intersection else 0.0


def _region_items(frames, interval_sec, duration_sec, keep_dir=None):
    items, previous, slots = [], [], {}
    step = max(1, int(interval_sec * 1000))
    duration = int(duration_sec * 1000) if duration_sec > 0 else None
    last_time = None
    for time_ms, path, regions in frames:
        size = _frame_size(path)
        saved_path = None
        if regions and keep_dir:
            target = keep_dir / f"{time_ms:09d}.jpg"
            try:
                shutil.copyfile(path, target)
                saved_path = str(target.resolve())
            except OSError as e:
                log.warning("[ocr] 프레임 보관 실패 %s: %s", target, e)
        current, used = [], set()
        for text, confidence, pixels in regions:
            box = None
            if pixels and size and size[0] > 0 and size[1] > 0:
                x, y, w, h = pixels
                if x >= 0 and y >= 0 and x + w <= size[0] and y + h <= size[1]:
                    box = (x / size[0], y / size[1], w / size[0], h / size[1])
            slot = tuple(round(v / 0.05) for v in (box[0] + box[2] / 2, box[1] + box[3] / 2)) if box else None
            if slot is not None:
                slots.setdefault(slot, set()).add(text)
            match = next((index for index in previous if index not in used
                          and last_time is not None and time_ms - last_time <= step * 1.5
                          and items[index]["text"] == text and _iou(items[index]["_box"], box) >= 0.6), None)
            end = min(time_ms + step, duration) if duration is not None else time_ms + step
            if end <= time_ms:
                continue
            if match is not None:
                item = items[match]
                item["endMs"] = end
                item["observations"] += 1
                item["confidence"] = min(item["confidence"], round(confidence, 3))
                used.add(match)
                current.append(match)
                continue
            item = {"startMs": time_ms, "endMs": end, "text": text, "confidence": round(confidence, 3),
                    "framePath": saved_path, "trackId": f"region-{len(items)}", "observations": 1,
                    "boxX": box[0] if box else None, "boxY": box[1] if box else None,
                    "boxWidth": box[2] if box else None, "boxHeight": box[3] if box else None,
                    "_box": box, "_slot": slot}
            items.append(item)
            current.append(len(items) - 1)
        previous, last_time = current, time_ms
    for item in items:
        item["slotTextChanges"] = max(0, len(slots.get(item.pop("_slot"), set())) - 1)
        item.pop("_box")
    return items


def _adjust_interval(duration_sec: float, requested: float) -> float:
    """
    영상이 길면 프레임 간격을 자동으로 늘린다.

    간격을 고정하면 프레임 수가 영상 길이에 비례해 늘어난다.
    4초 간격이면 60분 영상에 900장이고, 인식에만 7분 넘게 걸린다.
    분석 시간의 대부분이 여기서 나온다.

    자막은 보통 몇 초씩 유지되므로 간격이 벌어져도 대부분 잡힌다.
    놓치는 것보다 아예 끝나지 않는 게 더 나쁘다.
    """
    limit = get_settings().max_ocr_frames
    if duration_sec <= 0 or limit <= 0:
        return requested

    # 짧은 영상은 반대로 간격을 좁힌다.
    #
    # 8초짜리를 4초 간격으로 뜨면 2장뿐이다. 자막은 보통 2~3초마다 바뀌므로
    # 대부분을 놓친다. 화면에 자막이 큼직하게 박혀 있는데 0건으로 끝나는 일이 생긴다.
    # 짧은 영상은 어차피 프레임을 늘려도 비용이 얼마 안 든다.
    if duration_sec < requested * MIN_FRAMES:
        adjusted = max(MIN_INTERVAL_SEC, duration_sec / MIN_FRAMES)
        if adjusted < requested:
            log.info("[ocr] 영상이 짧아 프레임 간격을 %.1f초 → %.1f초 로 좁힙니다 "
                     "(%.0f초, 약 %d장)",
                     requested, adjusted, duration_sec, int(duration_sec / adjusted))
            return adjusted

    needed = duration_sec / max(1.0, requested)
    if needed <= limit:
        return requested

    adjusted = duration_sec / limit
    log.info("[ocr] 영상이 길어 프레임 간격을 %.1f초 → %.1f초 로 조정합니다 "
             "(%.0f분, 약 %d장)",
             requested, adjusted, duration_sec / 60, limit)
    return adjusted
