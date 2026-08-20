"""PaddleOCR 로 화면에 박힌 자막/텍스트를 읽는다."""
from __future__ import annotations

import logging
import re
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

# 전체 프레임 중 이 비율 이상에서 같은 자리에 글자가 있으면
# 고정 텍스트(로고·배너)로 본다. 자막은 몇 초씩만 떠 있으므로 절반을 넘지 않는다.
WATERMARK_RATIO = 0.5

# 위치를 뭉갤 격자 크기(px). 로고가 몇 픽셀 흔들려도 같은 칸으로 잡히게 한다.
POSITION_GRID = 80

# 같은 자리에 나온 글자들이 이만큼 비슷하면 고정 텍스트로 본다.
# 자막은 내용이 매번 바뀌므로 이 값을 넘지 않는다.
WATERMARK_SIMILARITY = 0.45

# 유사도 비교에 쓸 최대 표본 수. 프레임이 많아도 계산이 폭증하지 않게 한다.
WATERMARK_SAMPLE = 30

# 짧은 영상에서 최소한 이만큼은 뽑는다. 2~3장으로는 자막을 놓치기 쉽다.
MIN_FRAMES = 8
# 아무리 짧아도 이보다 촘촘하게는 뜨지 않는다
MIN_INTERVAL_SEC = 0.5


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


def _parse(raw) -> list[tuple[str, float, int, int]]:
    """
    PaddleOCR 출력을 (텍스트, 신뢰도, 중심x, 중심y) 로 바꾼다.

    위치를 함께 뽑는 이유:
    채널 로고나 고정 배너는 매 프레임 같은 자리에 나온다.
    그런데 OCR 이 그때그때 다르게 읽어서(POGUES, PO6UFS, 피F LOGUES)
    글자만으로는 같은 것인 줄 모른다. 위치로 보면 확실하다.

    출력 포맷이 버전마다 달라서 방어적으로 파싱한다.
    """
    lines: list[tuple[str, float, int, int]] = []
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

        cx, cy = 0, 0
        try:
            box = item[0]
            xs = [float(pt[0]) for pt in box]
            ys = [float(pt[1]) for pt in box]
            cx, cy = int(sum(xs) / len(xs)), int(sum(ys) / len(ys))
        except (IndexError, TypeError, ValueError):
            pass   # 위치를 못 얻으면 0,0 으로 두고 글자로만 판단한다

        lines.append((text, confidence, cx, cy))
    return lines


def run(video: PreparedVideo, interval_sec: float, frame_dir: str | None = None) -> dict:
    """
    frame_dir 이 주어지면, 텍스트가 인식된 프레임만 그 폴더에 보관하고
    보관 경로를 함께 돌려준다. Spring 이 이 이미지를 프론트에 서빙한다.
    전체 프레임을 다 남기면 용량이 커지므로 필요한 것만 남긴다.

    2단계로 처리한다.
      1) 모든 프레임을 인식해 줄 단위로 모은다
      2) 거의 모든 프레임에 나오는 줄은 워터마크로 보고 버린다
    채널 로고나 고정 배너가 자막에 섞여 들어가는 것을 막기 위해서다.
    """
    started = time.time()

    # 단계마다 로그를 남긴다.
    #
    # 예전에는 이 함수가 도는 동안 아무 기록도 안 남겼다.
    # 그래서 중간에 프로세스가 죽으면 자바 쪽에 "응답을 못 받았다" 만 남고,
    # 모델 로딩에서 죽었는지 프레임 추출에서 죽었는지 인식에서 죽었는지
    # 알 방법이 없었다. 원인을 찾는 데 며칠이 걸렸다.
    log.info("[ocr] 시작 — 영상 %.0f초, 요청 간격 %.1f초", video.duration_sec, interval_sec)

    engine = _engine()
    log.info("[ocr] 엔진 준비 완료 (%.1f초)", time.time() - started)

    interval_sec = _adjust_interval(video.duration_sec, interval_sec)
    frames = extract_frames(video, interval_sec)
    extracted_at = time.time()
    log.info("[ocr] 프레임 %d장 추출 완료 (%.1f초)", len(frames), extracted_at - started)

    keep_dir = Path(frame_dir) if frame_dir else None
    if keep_dir:
        keep_dir.mkdir(parents=True, exist_ok=True)

    # ---- 1단계: 프레임별로 줄 단위 인식 결과를 모은다 ----
    per_frame: list[tuple[int, Path, list[tuple[str, float]]]] = []
    for i, (time_ms, frame_path) in enumerate(frames, start=1):
        # 10장마다 한 줄. 여기서 로그가 끊기면 그 프레임에서 죽은 것이다.
        if i == 1 or i % 10 == 0 or i == len(frames):
            log.info("[ocr] 인식 중 %d/%d", i, len(frames))
        try:
            raw = engine.ocr(str(frame_path), cls=True)
        except Exception as e:
            log.warning("[ocr] 프레임 실패 %s: %s", frame_path.name, e)
            continue

        parsed = [
            (text.strip(), conf, cx, cy) for text, conf, cx, cy in _parse(raw)
            if conf >= MIN_CONFIDENCE
            and len(text.strip()) >= MIN_LENGTH
            and not any(p in text for p in IGNORE_PATTERNS)
        ]
        if parsed:
            per_frame.append((time_ms, frame_path, parsed))

    watermarks = _find_watermarks(per_frame)
    if watermarks:
        log.info("[ocr] 고정 위치 %d곳 제외 (로고·배너로 판단)", len(watermarks))

    # ---- 2단계: 워터마크를 뺀 나머지로 자막을 만든다 ----
    items: list[dict] = []
    previous_text = None

    for time_ms, frame_path, parsed in per_frame:
        kept = [(t, c) for t, c, cx, cy in parsed
                if _position_key(cx, cy) not in watermarks]
        if not kept:
            continue

        merged = " ".join(text for text, _ in kept)
        confidence = sum(conf for _, conf in kept) / len(kept)

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


def _normalize(text: str) -> str:
    """OCR 이 매번 조금씩 다르게 읽으므로 비교용으로 다듬는다."""
    return re.sub(r"[^가-힣a-zA-Z0-9]", "", text).lower()


def _position_key(cx: int, cy: int) -> tuple[int, int]:
    """위치를 격자로 뭉갠다. 로고가 몇 픽셀씩 흔들려도 같은 칸으로 본다."""
    return (cx // POSITION_GRID, cy // POSITION_GRID)


def _similarity(a: str, b: str) -> float:
    """겹치는 글자 비율. OCR 오인식이 섞여도 견디도록 글자 단위로 본다."""
    x, y = _normalize(a), _normalize(b)
    if not x or not y:
        return 0.0
    counts: dict[str, int] = {}
    for ch in x:
        counts[ch] = counts.get(ch, 0) + 1
    common = 0
    for ch in y:
        if counts.get(ch, 0) > 0:
            counts[ch] -= 1
            common += 1
    return common / min(len(x), len(y))


def _find_watermarks(per_frame) -> set[tuple[int, int]]:
    """
    채널 로고나 고정 배너가 있는 자리를 찾는다.

    두 조건을 모두 만족해야 한다.
      1) 거의 모든 프레임에서 그 자리에 글자가 있다
      2) 그 글자들이 매번 사실상 같은 내용이다

    위치만 보면 안 되는 이유:
    자막도 하단 같은 자리에 계속 나온다. 위치만으로는 로고와 구분되지 않는다.

    글자만 보면 안 되는 이유:
    OCR 이 로고를 프레임마다 다르게 읽는다.
    POGUES 가 PO6UFS, 피F LOGUES, FOGUES 로 나오는 식이다.

    그래서 "같은 자리에 + 비슷한 글자가 계속" 일 때만 로고로 본다.
    자막은 자리는 같아도 내용이 매번 바뀌므로 걸러지지 않는다.
    """
    if len(per_frame) < 4:
        return set()   # 프레임이 너무 적으면 판단할 수 없다

    texts_at: dict[tuple[int, int], list[str]] = {}
    for _, _, parsed in per_frame:
        seen: dict[tuple[int, int], str] = {}
        for text, _conf, cx, cy in parsed:
            seen.setdefault(_position_key(cx, cy), text)
        for key, text in seen.items():
            texts_at.setdefault(key, []).append(text)

    threshold = max(3, int(len(per_frame) * WATERMARK_RATIO))
    watermarks: set[tuple[int, int]] = set()

    for key, texts in texts_at.items():
        if len(texts) < threshold:
            continue

        # 가장 대표적인 글자를 기준으로 삼는다.
        # 첫 글자를 그냥 쓰면, 그게 유독 심하게 깨진 경우 전부 다르다고 나온다.
        # 실제로 로고가 첫 프레임에서 "동행마스" 로 읽힌 사례가 있었다.
        sample = texts[:WATERMARK_SAMPLE]
        best = 0.0
        for base in sample:
            others = [t for t in sample if t is not base]
            if not others:
                continue
            avg = sum(_similarity(base, other) for other in others) / len(others)
            best = max(best, avg)

        if best >= WATERMARK_SIMILARITY:
            watermarks.add(key)

    return watermarks
