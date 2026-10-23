"""No Paddle, remote video, paid API or user server needed. Real process lifecycle tests."""
import asyncio
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from app import ocr_jobs, ocr
from app.config import Settings


class Request:
    def __init__(self, disconnected=False): self.disconnected = disconnected
    async def is_disconnected(self): return self.disconnected


def configure(monkeypatch, tmp_path, timeout=2):
    monkeypatch.setattr(ocr_jobs, "get_settings", lambda: SimpleNamespace(
        work_dir=str(tmp_path), effective_ocr_timeout_sec=timeout))
    monkeypatch.setattr(ocr_jobs, "POLL_SECONDS", 0.01)


def spawn(monkeypatch, script):
    processes = []
    def start(directory):
        p = subprocess.Popen([sys.executable, "-c", script, str(directory)],
                             start_new_session=os.name == "posix")
        processes.append(p)
        return p
    monkeypatch.setattr(ocr_jobs, "_start", start)
    return processes


def test_success_preserves_response_and_reaps_process(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    processes = spawn(monkeypatch, "import pathlib,sys; pathlib.Path(sys.argv[1],'result.json').write_text("
                      "'{\"statusCode\":200,\"data\":{\"items\":[],\"formatVersion\":\"regions-v1\"}}')")
    assert asyncio.run(ocr_jobs.run({}, Request())) == {"items": [], "formatVersion": "regions-v1"}
    assert processes[0].poll() == 0
    assert list(tmp_path.iterdir()) == []


@pytest.mark.parametrize("result,status,detail", [
    ('{"statusCode":503,"detail":"OCR all frames failed"}', 503, "all frames"),
    ('{"statusCode":200,"data":{"items":null}}', 503, "OCR_WORKER_FAILED"),
    ('invalid JSON', 503, "OCR_WORKER_FAILED"),
    ('null', 503, "OCR_WORKER_FAILED"),
])
def test_bad_result_is_failure_not_successful_empty(monkeypatch, tmp_path, result, status, detail):
    configure(monkeypatch, tmp_path)
    spawn(monkeypatch, f"import pathlib,sys; pathlib.Path(sys.argv[1],'result.json').write_text({result!r})")
    with pytest.raises(HTTPException) as error:
        asyncio.run(ocr_jobs.run({}, Request()))
    assert error.value.status_code == status
    assert detail in error.value.detail
    assert list(tmp_path.iterdir()) == []


def test_deadline_kills_and_reaps_native_job_before_releasing_slot(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path, timeout=0.15)
    processes = spawn(monkeypatch, "import time; time.sleep(30)")
    with pytest.raises(HTTPException) as error:
        asyncio.run(ocr_jobs.run({}, Request()))
    assert error.value.status_code == 504 and "OCR_TIMEOUT" in error.value.detail
    assert processes[0].poll() is not None
    assert not ocr_jobs._admission.locked()
    assert list(tmp_path.iterdir()) == []


def test_disconnect_and_busy_do_not_start_second_worker(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    processes = spawn(monkeypatch, "import time; time.sleep(30)")
    async def scenario():
        request = Request()
        task = asyncio.create_task(ocr_jobs.run({}, request))
        while not processes: await asyncio.sleep(0.01)
        with pytest.raises(HTTPException) as busy:
            await ocr_jobs.run({}, Request())
        assert busy.value.status_code == 503 and "OCR_BUSY" in busy.value.detail
        request.disconnected = True
        with pytest.raises(HTTPException) as cancelled: await task
        assert cancelled.value.status_code == 499
    asyncio.run(scenario())
    assert len(processes) == 1 and processes[0].poll() is not None
    assert not ocr_jobs._admission.locked() and list(tmp_path.iterdir()) == []


def test_async_task_cancellation_still_reaps_job(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    processes = spawn(monkeypatch, "import time; time.sleep(30)")
    async def scenario():
        task = asyncio.create_task(ocr_jobs.run({}, Request()))
        while not processes: await asyncio.sleep(0.01)
        task.cancel()
        with pytest.raises(asyncio.CancelledError): await task
    asyncio.run(scenario())
    assert processes[0].poll() is not None
    assert not ocr_jobs._admission.locked() and list(tmp_path.iterdir()) == []


def test_preexisting_disconnect_spawns_nothing(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    monkeypatch.setattr(ocr_jobs, "_start", lambda *args: pytest.fail("must not start"))
    with pytest.raises(HTTPException) as error:
        asyncio.run(ocr_jobs.run({}, Request(True)))
    assert error.value.status_code == 499


def test_real_asgi_disconnect_reaps_worker_and_clears_busy(monkeypatch, tmp_path):
    from app.main import app
    configure(monkeypatch, tmp_path)
    processes = spawn(monkeypatch, "import time; time.sleep(30)")
    async def scenario():
        incoming = asyncio.Queue()
        await incoming.put({"type": "http.request", "body": b'{"filePath":"fixture"}', "more_body": False})
        sent = []
        async def send(message): sent.append(message)
        scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
                 "method": "POST", "scheme": "http", "path": "/ocr", "raw_path": b"/ocr",
                 "query_string": b"", "root_path": "", "headers": [(b"content-type", b"application/json")],
                 "client": ("127.0.0.1", 1), "server": ("test", 80)}
        task = asyncio.create_task(app(scope, incoming.get, send))
        while not processes: await asyncio.sleep(0.01)
        assert ocr_jobs.is_busy()
        await incoming.put({"type": "http.disconnect"})
        await asyncio.wait_for(task, timeout=2)
        assert any(m.get("status") == 499 for m in sent)
    asyncio.run(scenario())
    assert processes[0].poll() is not None and not ocr_jobs.is_busy()
    assert list(tmp_path.iterdir()) == []


def test_crashed_worker_is_failure_and_cleans_files(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    processes = spawn(monkeypatch, "import sys; sys.exit(2)")
    with pytest.raises(HTTPException) as error:
        asyncio.run(ocr_jobs.run({}, Request()))
    assert error.value.status_code == 503
    assert processes[0].poll() == 2 and list(tmp_path.iterdir()) == []


def test_job_deadlines_are_profile_specific_and_overrideable():
    assert Settings(_env_file=None, analysis_profile="default", ocr_timeout_sec=None).effective_ocr_timeout_sec == 540
    assert Settings(_env_file=None, analysis_profile="local", ocr_timeout_sec=None).effective_ocr_timeout_sec == 0
    assert Settings(_env_file=None, ocr_timeout_sec=300).effective_ocr_timeout_sec == 300


def test_local_unlimited_job_can_finish_and_still_reaps_worker(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path, timeout=0)
    processes = spawn(monkeypatch, "import pathlib,sys,time; time.sleep(.1); pathlib.Path(sys.argv[1],'result.json').write_text("
                      "'{\"statusCode\":200,\"data\":{\"items\":[]}}')")
    assert asyncio.run(ocr_jobs.run({}, Request())) == {"items": []}
    assert processes[0].poll() == 0 and not ocr_jobs.is_busy()
    assert list(tmp_path.iterdir()) == []


def test_unlimited_mode_rejects_deployment_and_still_handles_disconnect(monkeypatch, tmp_path):
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        Settings(_env_file=None, analysis_profile="default", ocr_timeout_sec=0)
    configure(monkeypatch, tmp_path, timeout=0)
    processes = spawn(monkeypatch, "import time; time.sleep(30)")
    async def scenario():
        request = Request()
        task = asyncio.create_task(ocr_jobs.run({}, request))
        await asyncio.sleep(.1)
        request.disconnected = True
        with pytest.raises(HTTPException) as error:
            await task
        assert error.value.status_code == 499
    asyncio.run(scenario())
    assert processes[0].poll() is not None and not ocr_jobs.is_busy()


def test_health_does_not_initialize_native_model(monkeypatch):
    monkeypatch.setattr(ocr, "_engine", lambda: pytest.fail("health must not initialize Paddle"))
    monkeypatch.setattr(ocr, "find_spec", lambda name: object())
    assert ocr.is_available()
    monkeypatch.setattr(ocr, "find_spec", lambda name: None)
    assert not ocr.is_available()


def test_native_constructor_uses_bounded_recognition_batch(monkeypatch):
    calls = []
    monkeypatch.setitem(sys.modules, "paddleocr", SimpleNamespace(PaddleOCR=lambda **kw: calls.append(kw) or object()))
    monkeypatch.setattr(ocr, "get_settings", lambda: SimpleNamespace(ocr_lang="korean", ocr_rec_batch_num=1))
    ocr._engine.cache_clear()
    try:
        assert ocr._engine() is ocr._engine()
        assert len(calls) == 1
        assert calls[0]["rec_batch_num"] == 1
        assert calls[0]["lang"] == "korean" and calls[0]["use_angle_cls"] is True
    finally:
        ocr._engine.cache_clear()


def test_real_worker_module_runs_upload_without_paddle_or_network(monkeypatch, tmp_path):
    import shutil
    if shutil.which("ffmpeg") is None:
        pytest.skip("ffmpeg required")
    storage = tmp_path / "storage"
    source_dir = storage / "videos" / "1"
    source_dir.mkdir(parents=True)
    source = source_dir / "source.mp4"
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                    "color=c=white:s=64x64:r=10:d=1", "-c:v", "mpeg4", str(source)],
                   check=True, capture_output=True, timeout=10)
    # Stub only the native recognizer; real worker entrypoint, ffmpeg, path guards,
    # response file, tracking and cleanup all execute in a fresh interpreter.
    stub = tmp_path / "stub"
    stub.mkdir()
    (stub / "paddleocr.py").write_text(
        "class PaddleOCR:\n"
        " def __init__(self, **kw):\n"
        "  import os\n"
        "  assert os.environ['OPENAI_API_KEY'] == '' and os.environ['GROQ_API_KEY'] == ''\n"
        "  assert kw['rec_batch_num'] == 1\n"
        " def ocr(self, path, cls=True):\n"
        "  return [[[[[0,0],[32,0],[32,16],[0,16]],('fixture text',0.9)]]]\n")
    monkeypatch.setenv("PYTHONPATH", str(stub) + os.pathsep + str(Path(__file__).resolve().parents[1]))
    monkeypatch.setenv("MEDIA_STORAGE_ROOT", str(storage))
    monkeypatch.setenv("OCR_REC_BATCH_NUM", "1")
    monkeypatch.setenv("OPENAI_API_KEY", "offline-test-not-real")
    monkeypatch.setenv("GROQ_API_KEY", "offline-test-not-real")
    work = tmp_path / "jobs"
    configure(monkeypatch, work, timeout=20)
    monkeypatch.chdir(tmp_path)  # uvicorn --app-dir must not require matching cwd.
    result = asyncio.run(ocr_jobs.run({"filePath": str(source), "intervalSec": 1}, Request()))
    assert result["processing"]["status"] == "SUCCESS"
    assert result["formatVersion"] == "regions-v1"
    assert result["items"][0]["text"] == "fixture text"
    assert result["items"][0]["startMs"] == 0
    assert source.exists() and list(work.iterdir()) == []


@pytest.mark.skipif(os.name != "posix", reason="POSIX session cleanup")
def test_deadline_kills_owned_descendant_process(monkeypatch, tmp_path):
    import time
    configure(monkeypatch, tmp_path / "jobs", timeout=0.4)
    pid_file = tmp_path / "descendant.pid"
    script = ("import pathlib,subprocess,sys,time; "
              "child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)']); "
              f"pathlib.Path({str(pid_file)!r}).write_text(str(child.pid)); "
              "time.sleep(30)")
    processes = spawn(monkeypatch, script)
    with pytest.raises(HTTPException) as error:
        asyncio.run(ocr_jobs.run({}, Request()))
    assert error.value.status_code == 504 and processes[0].poll() is not None
    child_pid = int(pid_file.read_text())
    # A killed orphan may briefly remain a zombie until reaped by the OS; never
    # count a live sleeping descendant as cleaned up.
    for _ in range(50):
        state = subprocess.run(["ps", "-p", str(child_pid), "-o", "stat="], capture_output=True, text=True).stdout.strip()
        if not state or state.startswith("Z"):
            break
        time.sleep(0.01)
    assert not state or state.startswith("Z")
