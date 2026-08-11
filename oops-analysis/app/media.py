"""영상 준비(다운로드) + ffmpeg 로 오디오/프레임 뽑아내기."""
from __future__ import annotations

import json
import logging
import shutil
import subprocess
import uuid
from dataclasses import dataclass
from pathlib import Path

from .config import get_settings

log = logging.getLogger(__name__)


class MediaError(RuntimeError):
    pass


def ensure_ffmpeg() -> None:
    if shutil.which("ffmpeg") is None:
        raise MediaError("ffmpeg 를 찾을 수 없습니다. PATH 에 설치해 주세요.")


@dataclass
class PreparedVideo:
    path: Path
    workdir: Path
    duration_sec: float
    title: str | None = None

    def cleanup(self) -> None:
        shutil.rmtree(self.workdir, ignore_errors=True)


def _new_workdir() -> Path:
    base = Path(get_settings().work_dir) / uuid.uuid4().hex
    base.mkdir(parents=True, exist_ok=True)
    return base


def probe_duration(path: Path) -> float:
    result = subprocess.run(
        ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", str(path)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        return 0.0
    try:
        return float(json.loads(result.stdout)["format"]["duration"])
    except (KeyError, ValueError, json.JSONDecodeError):
        return 0.0


def prepare(video_url: str | None, file_path: str | None) -> PreparedVideo:
    """유튜브 링크면 받아오고, 로컬 파일이면 그대로 쓴다."""
    ensure_ffmpeg()
    workdir = _new_workdir()

    if file_path:
        src = Path(file_path)
        if not src.exists():
            raise MediaError(f"파일이 없습니다: {file_path}")
        return PreparedVideo(path=src, workdir=workdir,
                             duration_sec=probe_duration(src), title=src.name)

    if not video_url:
        raise MediaError("videoUrl 또는 filePath 중 하나는 필요합니다.")

    from yt_dlp import YoutubeDL

    out_tmpl = str(workdir / "source.%(ext)s")
    opts = {
        "format": "bestvideo[height<=720]+bestaudio/best[height<=720]/best",
        "outtmpl": out_tmpl,
        "quiet": True,
        "no_warnings": True,
        "merge_output_format": "mp4",
    }
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(video_url, download=True)
        downloaded = Path(ydl.prepare_filename(info))

    if not downloaded.exists():
        candidates = list(workdir.glob("source.*"))
        if not candidates:
            raise MediaError("영상 다운로드에 실패했습니다.")
        downloaded = candidates[0]

    return PreparedVideo(
        path=downloaded,
        workdir=workdir,
        duration_sec=float(info.get("duration") or probe_duration(downloaded)),
        title=info.get("title"),
    )


def extract_audio(video: PreparedVideo) -> Path:
    """Whisper 가 받아들이는 16kHz mono mp3 로 변환. 파일 크기도 크게 줄어든다."""
    target = video.workdir / "audio.mp3"
    cmd = [
        "ffmpeg", "-y", "-i", str(video.path),
        "-vn", "-ac", "1", "-ar", "16000", "-b:a", "64k",
        str(target),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0 or not target.exists():
        raise MediaError(f"오디오 추출 실패: {result.stderr[-500:]}")
    return target


def extract_frames(video: PreparedVideo, interval_sec: float) -> list[tuple[int, Path]]:
    """interval_sec 간격으로 프레임을 뽑아 (타임코드ms, 파일경로) 목록을 돌려준다."""
    frame_dir = video.workdir / "frames"
    frame_dir.mkdir(exist_ok=True)

    cmd = [
        "ffmpeg", "-y", "-i", str(video.path),
        # 자막 글자가 작으면 OCR 이 깨진다. 원본이 작아도 업스케일해서 인식률을 올린다.
        "-vf", f"fps=1/{interval_sec},scale=1920:-2:flags=lanczos",
        "-q:v", "2",
        str(frame_dir / "frame_%05d.jpg"),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise MediaError(f"프레임 추출 실패: {result.stderr[-500:]}")

    frames = sorted(frame_dir.glob("frame_*.jpg"))
    # ffmpeg 의 fps 필터는 n번째 프레임이 (n-0.5)*interval 지점에 해당한다
    return [(int(idx * interval_sec * 1000), path) for idx, path in enumerate(frames)]
