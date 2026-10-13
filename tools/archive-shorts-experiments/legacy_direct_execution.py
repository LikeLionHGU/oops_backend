"""Historical direct-call tools are plan/mock only until frozen budget migration."""


def execution_mode(execute, simulate, callback):
    if execute and not simulate:
        raise ValueError('LEGACY_DIRECT_EXECUTION_DISABLED_USE_GUARDED_FROZEN_RUNNER')
    if simulate and not execute:
        raise ValueError('MOCK_REQUIRES_EXPLICIT_EXECUTE')
    if execute:
        from replay_draft_cases import complete as disabled_transport
        if callback is disabled_transport:
            raise ValueError('MOCK_REQUIRES_EXPLICIT_CALLBACK')
    return 'MOCK_OFFLINE' if simulate else 'DRY_RUN'


def mark_report(report, mode):
    report.update(runKind=mode, apiCalls=0, consumedReservationUsd='0',
                  actualBilledCostUsd=None, simulatedCalls=len(report['calls']) if mode=='MOCK_OFFLINE' else 0)
