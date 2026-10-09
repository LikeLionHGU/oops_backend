"""Bounded, OCR-independent scene samples. No model calls or saved image artifacts."""
from __future__ import annotations

import base64
import math
import re
import subprocess

from .media import MediaError, PreparedVideo

MAX_FRAME_BYTES = 512_000


def sample(video: PreparedVideo, timestamps_ms: list[int]) -> list[dict]:
    if (not math.isfinite(video.duration_sec) or video.duration_sec <= 0
            or not 1 <= len(timestamps_ms) <= 3
            or len(set(timestamps_ms)) != len(timestamps_ms)
            or any(type(t) is not int or t < 0 or t >= video.duration_sec * 1000 for t in timestamps_ms)):
        raise MediaError("장면 요청 시간 또는 영상 길이가 올바르지 않습니다.")
    frames = []
    for timestamp in sorted(timestamps_ms):
        try:
            result = subprocess.run([
                "ffmpeg", "-hide_banner", "-loglevel", "info", "-copyts",
                "-ss", str(timestamp / 1000), "-i", str(video.path),
                "-vf", "showinfo,scale=768:768:force_original_aspect_ratio=decrease",
                "-frames:v", "1", "-an", "-q:v", "5", "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1",
            ], capture_output=True, timeout=20)
        except (subprocess.TimeoutExpired, OSError) as exc:
            raise MediaError("장면 추출 시간 초과 또는 실행 실패") from exc
        pts = re.search(r"\bpts_time:([\d.]+)", result.stderr.decode("utf-8", errors="replace"))
        if (result.returncode or not pts or not result.stdout.startswith(b"\xff\xd8")
                or len(result.stdout) > MAX_FRAME_BYTES):
            raise MediaError("유효한 장면 이미지와 원본 시간 정보를 추출하지 못했습니다.")
        actual = round(float(pts.group(1)) * 1000)
        if actual < 0 or actual >= video.duration_sec * 1000 or abs(actual - timestamp) > 1000:
            raise MediaError("장면 추출 시간이 요청 범위를 벗어났습니다.")
        frames.append({"frameId": f"scene-{timestamp}", "requestedMs": timestamp,
                       "timestampMs": actual, "jpegBase64": base64.b64encode(result.stdout).decode("ascii")})
    return frames
