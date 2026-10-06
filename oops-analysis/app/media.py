"""영상 준비(다운로드) + ffmpeg 로 오디오/프레임 뽑아내기."""
from __future__ import annotations

import json
import logging
import shutil
import subprocess
import uuid
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from .config import get_settings

log = logging.getLogger(__name__)


class MediaError(RuntimeError):
    pass


YOUTUBE_HOSTS = {
    "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
    "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com",
}


def validate_video_url(value: str) -> str:
    if len(value) > 2048:
        raise MediaError("영상 URL이 너무 깁니다.")
    try:
        parsed = urlsplit(value)
        host = parsed.hostname
        port = parsed.port
    except ValueError as e:
        raise MediaError("YouTube 영상 URL이 올바르지 않습니다.") from e

    if (parsed.scheme.lower() != "https"
            or host is None
            or host.lower() not in YOUTUBE_HOSTS
            or parsed.username is not None
            or parsed.password is not None
            or port not in (None, 443)
            or not parsed.path.strip("/")):
        raise MediaError("YouTube 영상 URL만 사용할 수 있습니다.")
    return value


def resolve_storage_path(value: str, category: str) -> Path:
    """Spring 저장소 중 원본 videos/ 또는 OCR 결과 frames/ 하위 경로만 허용한다."""
    root = Path(get_settings().media_storage_root).expanduser().resolve()
    candidate = Path(value).expanduser().resolve()
    try:
        relative = candidate.relative_to(root)
    except ValueError as e:
        raise MediaError("요청한 파일 경로가 허용된 저장소 밖에 있습니다.") from e

    expected = {"source": "videos", "frames": "frames"}.get(category)
    if expected is None or not relative.parts or relative.parts[0] != expected:
        raise MediaError("요청한 경로 유형이 허용되지 않습니다.")
    return candidate


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
    if file_path:
        src = resolve_storage_path(file_path, "source")
        if not src.is_file():
            raise MediaError("요청한 영상 파일을 찾을 수 없습니다.")
        workdir = _new_workdir()
        return PreparedVideo(path=src, workdir=workdir,
                             duration_sec=probe_duration(src), title=src.name)

    if not video_url:
        raise MediaError("videoUrl 또는 filePath 중 하나는 필요합니다.")
    video_url = validate_video_url(video_url)

    workdir = _new_workdir()

    from yt_dlp import YoutubeDL

    out_tmpl = str(workdir / "source.%(ext)s")
    opts = {
        "format": "bestvideo[height<=720]+bestaudio/best[height<=720]/best",
        "outtmpl": out_tmpl,
        "quiet": True,
        "no_warnings": True,
        "merge_output_format": "mp4",
        # 유튜브가 봇으로 보고 막는 경우가 있어 클라이언트를 바꿔 시도한다
        "extractor_args": {"youtube": {"player_client": ["android", "web"]}},
        "retries": 3,
    }
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(video_url, download=True)
            downloaded = Path(ydl.prepare_filename(info))
    except Exception as e:
        # 유튜브 차단, 연령 제한, 비공개 영상 등. 원인을 그대로 올려보내야 진단이 된다.
        raise MediaError(
            f"영상을 받아오지 못했습니다: {e}\n"
            "yt-dlp 가 오래됐을 수 있습니다. pip install --upgrade yt-dlp 로 올려보세요."
        ) from e

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
        # 1920 은 인식률이 좋지만 느리다. 1600 이 속도와 정확도의 타협점이다.
        "-vf", f"fps=1/{interval_sec},scale=1600:-2:flags=lanczos",
        "-q:v", "2",
        str(frame_dir / "frame_%05d.jpg"),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise MediaError(f"프레임 추출 실패: {result.stderr[-500:]}")

    frames = sorted(frame_dir.glob("frame_*.jpg"))
    # ffmpeg 의 fps 필터는 n번째 프레임이 (n-0.5)*interval 지점에 해당한다
    return [(int(idx * interval_sec * 1000), path) for idx, path in enumerate(frames)]
