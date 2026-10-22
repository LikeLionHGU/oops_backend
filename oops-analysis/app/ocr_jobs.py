"""Bounded OCR subprocess: disconnect/deadline/cancellation kills native inference too."""
from __future__ import annotations

import asyncio
import json
import logging
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time

from fastapi import HTTPException, Request

from .config import get_settings

log = logging.getLogger(__name__)
_admission = threading.Lock()  # Per ASGI worker process; deploy with one worker for one OCR slot.
POLL_SECONDS = 0.2


def is_busy() -> bool:
    return _admission.locked()


def _start(job_dir: Path) -> subprocess.Popen:
    env = dict(os.environ, WORK_DIR=str(job_dir))
    project_root = str(Path(__file__).resolve().parents[1])
    env["PYTHONPATH"] = project_root + os.pathsep + env.get("PYTHONPATH", "")
    # OCR does not need the paid-provider credentials inherited by the parent server.
    for key in ("OPENAI_API_KEY", "GROQ_API_KEY"):
        env[key] = ""  # Also overrides .env loading in the child.
    return subprocess.Popen([sys.executable, "-m", "app.ocr_worker", str(job_dir)],
                            env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                            start_new_session=os.name == "posix")


def _stop(proc: subprocess.Popen) -> None:
    """Kill the owned session (including ffmpeg/download children) before removing its files."""
    if os.name == "posix":
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    elif proc.poll() is None:
        proc.kill()
    proc.wait(timeout=5)


async def run(payload: dict, request: Request) -> dict:
    if not _admission.acquire(blocking=False):
        raise HTTPException(status_code=503, detail="OCR_BUSY: 다른 OCR 작업이 진행 중입니다.")
    proc = None
    started = time.monotonic()
    try:
        settings = get_settings()
        base = Path(settings.work_dir).resolve()
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="ocr-job-", dir=base) as directory:
            job_dir = Path(directory)
            (job_dir / "request.json").write_text(json.dumps(payload, ensure_ascii=False))
            try:
                if await request.is_disconnected():
                    raise HTTPException(status_code=499, detail="OCR_CANCELLED: 요청 연결이 종료됐습니다.")
                proc = _start(job_dir)
                log.info("[ocr-job] started pid=%s timeoutSec=%s", proc.pid, settings.effective_ocr_timeout_sec)
                while True:
                    if await request.is_disconnected():
                        log.warning("[ocr-job] disconnected pid=%s", proc.pid)
                        raise HTTPException(status_code=499, detail="OCR_CANCELLED: 요청 연결이 종료됐습니다.")
                    if time.monotonic() - started >= settings.effective_ocr_timeout_sec:
                        log.warning("[ocr-job] deadline pid=%s", proc.pid)
                        raise HTTPException(status_code=504, detail="OCR_TIMEOUT: OCR 작업 제한 시간을 초과했습니다.")
                    if proc.poll() is not None:
                        break
                    await asyncio.sleep(POLL_SECONDS)
                result_path = job_dir / "result.json"
                if proc.returncode != 0 or not result_path.is_file():
                    raise HTTPException(status_code=503, detail="OCR_WORKER_FAILED: OCR 작업 프로세스가 종료됐습니다.")
                result = json.loads(result_path.read_text())
                if not isinstance(result, dict):
                    raise ValueError("Invalid OCR worker envelope")
                status = result.get("statusCode")
                if status != 200:
                    if status not in (400, 500, 503):
                        raise ValueError("Invalid OCR worker error status")
                    raise HTTPException(status_code=status, detail=result.get("detail", "OCR 작업 실패"))
                data = result["data"]
                if not isinstance(data, dict) or not isinstance(data.get("items"), list):
                    raise ValueError("Invalid OCR worker response")
                return data
            finally:
                # Synchronous, bounded reaping is deliberate: task cancellation must not
                # release admission or delete files while native inference still runs.
                if proc is not None:
                    _stop(proc)
                    log.info("[ocr-job] reaped pid=%s elapsedSec=%.1f", proc.pid, time.monotonic() - started)
    except (OSError, ValueError, KeyError):
        log.exception("[ocr-job] execution failed")
        raise HTTPException(status_code=503, detail="OCR_WORKER_FAILED: OCR 작업 응답을 확인하지 못했습니다.")
    finally:
        _admission.release()
