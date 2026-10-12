"""Shared opt-in live gate for old runners; mocks never consume the ledger."""
from pathlib import Path

from fixed_review_suite import require
from reservation_ledger import claim_legacy, DEFAULT_PATH


def reserve_execution(artifact, output, cost, authorization, *, simulate, callback, default_transport,
                      key, ledger_path=None):
    directory=Path(output).resolve()
    require('datasets' in directory.parts, 'private output required')
    if directory.exists(): raise FileExistsError('new execution directory required')
    if simulate:
        require(callback is not default_transport and key is None and authorization is None,
                'mock requires explicit callback, no key or live approval')
        return None
    return claim_legacy(ledger_path or DEFAULT_PATH, artifact, authorization, cost, directory)
