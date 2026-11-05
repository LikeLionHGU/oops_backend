"""One writer per dataset; locks must not outlive the process that took them."""
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import dataset_write_lock as tool
from review_context_pilot import PilotError

TOOLS = Path(__file__).resolve().parents[1]


def test_second_writer_is_refused_while_first_holds_the_lock(tmp_path):
    with tool.dataset_write_lock(tmp_path):
        with pytest.raises(PilotError, match="DATASET_WRITE_IN_PROGRESS"):
            with tool.dataset_write_lock(tmp_path):
                pass
    with tool.dataset_write_lock(tmp_path):
        pass


def test_leftover_lock_file_without_holder_does_not_block(tmp_path):
    (tmp_path / tool.LOCK_NAME).write_text("left by an earlier run")
    with tool.dataset_write_lock(tmp_path):
        pass


def test_killed_holder_releases_the_lock(tmp_path):
    code = ("import sys,time;sys.path.insert(0,sys.argv[1]);import dataset_write_lock as t\n"
            "with t.dataset_write_lock(sys.argv[2]):\n print('held',flush=True);time.sleep(60)")
    child = subprocess.Popen([sys.executable, "-c", code, str(TOOLS), str(tmp_path)], stdout=subprocess.PIPE, text=True)
    try:
        assert child.stdout.readline().strip() == "held"
        with pytest.raises(PilotError, match="DATASET_WRITE_IN_PROGRESS"):
            with tool.dataset_write_lock(tmp_path):
                pass
        os.kill(child.pid, signal.SIGKILL)
        child.wait(timeout=10)
    finally:
        if child.poll() is None:
            child.kill()
    with tool.dataset_write_lock(tmp_path):
        pass


def test_symlinked_lock_is_refused(tmp_path):
    target = tmp_path / "elsewhere"
    target.write_text("")
    (tmp_path / tool.LOCK_NAME).symlink_to(target)
    with pytest.raises(PilotError, match="DATASET_LOCK_SYMLINK_REFUSED"):
        with tool.dataset_write_lock(tmp_path):
            pass


def test_dataset_write_cli_refuses_while_another_writer_holds_the_lock(tmp_path):
    with tool.dataset_write_lock(tmp_path):
        started = time.monotonic()
        result = subprocess.run([sys.executable, str(TOOLS / "build_incident_dataset.py"), "--dataset-root",
                                 str(tmp_path), "--write"], capture_output=True, text=True, timeout=60)
    assert result.returncode == 2 and "DATASET_WRITE_IN_PROGRESS" in result.stderr
    assert time.monotonic() - started < 30
