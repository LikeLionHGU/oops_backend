"""Historical watermark-filter fixture, isolated from the production OCR path."""
import re

WATERMARK_RATIO = 0.5
POSITION_GRID = 80
WATERMARK_SIMILARITY = 0.45
WATERMARK_SAMPLE = 30


def _normalize(text):
    return re.sub(r"[^가-힣a-zA-Z0-9]", "", text).lower()


def _position_key(cx, cy):
    return (cx // POSITION_GRID, cy // POSITION_GRID)


def _similarity(a, b):
    x, y = _normalize(a), _normalize(b)
    if not x or not y:
        return 0.0
    counts = {}
    for ch in x:
        counts[ch] = counts.get(ch, 0) + 1
    common = 0
    for ch in y:
        if counts.get(ch, 0) > 0:
            counts[ch] -= 1
            common += 1
    return common / min(len(x), len(y))


def _find_watermarks(per_frame):
    if len(per_frame) < 4:
        return set()
    texts_at = {}
    for _, _, parsed in per_frame:
        seen = {}
        for text, _conf, cx, cy in parsed:
            seen.setdefault(_position_key(cx, cy), text)
        for key, text in seen.items():
            texts_at.setdefault(key, []).append(text)
    threshold = max(3, int(len(per_frame) * WATERMARK_RATIO))
    watermarks = set()
    for key, texts in texts_at.items():
        if len(texts) < threshold:
            continue
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
