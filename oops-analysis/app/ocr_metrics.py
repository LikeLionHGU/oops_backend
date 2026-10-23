"""Raw-free OCR phase timings. No model, file paths, URLs or provider credentials."""
from contextlib import contextmanager
import logging
import os
import time
import sys
try:
    import resource
except ImportError:  # Windows: timing remains available, memory is explicitly unknown.
    resource = None

log = logging.getLogger(__name__)
log.setLevel(logging.INFO)  # Paddle import may reset the root logger's level.


@contextmanager
def phase(stage):
    started = time.perf_counter()
    status = "SUCCESS"
    try:
        yield
    except BaseException:
        status = "FAILED"
        raise
    finally:
        usage = resource.getrusage(resource.RUSAGE_SELF) if resource else None
        peak_kib = (usage.ru_maxrss / 1024 if sys.platform == "darwin" else usage.ru_maxrss) if usage else None
        log.info("[ocr-timing] pid=%d phase=%s status=%s elapsedSec=%.3f processPeakRssKiB=%s",
                 os.getpid(), stage, status, time.perf_counter() - started, peak_kib)
