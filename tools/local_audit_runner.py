"""Runs the offline Gradle retrieval audits that both intake tools depend on."""
import os
import subprocess
from pathlib import Path

from review_context_pilot import PilotError, parse, require

BACKEND = Path(__file__).resolve().parents[1] / "oops-backend"
ENV_KEYS = {"PATH", "JAVA_HOME", "HOME", "USER", "TMPDIR", "LANG", "GRADLE_USER_HOME"}
TIMEOUT_ENV = "OOPS_AUDIT_TIMEOUT_SECONDS"
# A cold Gradle cache compiles all test classes before the audit itself starts.
DEFAULT_TIMEOUT_SECONDS = 600


def timeout_seconds():
    raw = os.environ.get(TIMEOUT_ENV)
    if raw is None:
        return DEFAULT_TIMEOUT_SECONDS
    require(raw.isdigit() and 0 < int(raw) <= 3600, "AUDIT_TIMEOUT_INVALID")
    return int(raw)


def run_gradle_audit(task, properties, marker, unavailable, failed, backend=BACKEND):
    env = {k: v for k, v in os.environ.items() if k in ENV_KEYS}
    command = ["bash", "./gradlew", "--offline", "--console=plain", "-q", task]
    command += [f"-P{key}={value}" for key, value in properties.items()]
    try:
        process = subprocess.run(command, cwd=backend, env=env, text=True, capture_output=True,
                                 timeout=timeout_seconds())
    except (OSError, subprocess.TimeoutExpired):
        raise PilotError(unavailable) from None
    rows = [line.removeprefix(marker) for line in process.stdout.splitlines() if line.startswith(marker)]
    require(process.returncode == 0 and len(rows) == 1, failed)
    return parse(rows[0])
