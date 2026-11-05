"""One writer per dataset. The kernel drops the flock when the holder exits, so no stale guard remains."""
import contextlib
import fcntl
import os
from pathlib import Path

from review_context_pilot import require

LOCK_NAME = ".dataset-write.lock"


@contextlib.contextmanager
def dataset_write_lock(root):
    path = Path(root) / LOCK_NAME
    require(not path.is_symlink(), "DATASET_LOCK_SYMLINK_REFUSED")
    descriptor = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            require(False, "DATASET_WRITE_IN_PROGRESS")
        yield
    finally:
        os.close(descriptor)
