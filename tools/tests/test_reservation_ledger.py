from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from pathlib import Path
import sys

import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fixed_review_suite import digest
from reservation_ledger import seed, claim, balance

AUTH=dict(userAuthorized=True,additionalBudgetUsd='1.00',authority='offline-test')


def artifact(name,cost):
    a=dict(authorization=AUTH,reservedCostUsd=cost,name=name)
    a['artifactHash']=digest(a)
    return a


def initialized(tmp_path,spent='0'):
    path=tmp_path/'datasets/reservations.sqlite3'
    seed(path,AUTH,[dict(artifactHash='old-fixture',reservedCostUsd=spent,reportHash='old-report')])
    return path


def test_one_snapshot_cannot_be_replayed_in_a_new_directory(tmp_path):
    path=initialized(tmp_path);a=artifact('one','.2')
    claim(path,a,tmp_path/'first')
    with pytest.raises(ValueError,match='already consumed'):claim(path,a,tmp_path/'second')
    assert Decimal(balance(path,AUTH))==Decimal('.8')


def test_concurrent_reservations_are_atomic(tmp_path):
    path=initialized(tmp_path)
    def attempt(name):
        try:claim(path,artifact(name,'.6'),tmp_path/name);return True
        except ValueError:return False
    with ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(attempt,['one','two']))
    assert sum(results)==1
    assert Decimal(balance(path,AUTH))==Decimal('.4')


def test_initial_history_is_imported_once_and_not_refunded(tmp_path):
    path=initialized(tmp_path,'.5824575')
    claim(path,artifact('new','.1'),tmp_path/'new')
    seed(path,AUTH,[dict(artifactHash='old-fixture',reservedCostUsd='.5824575',reportHash='old-report')])
    assert Decimal(balance(path,AUTH))==Decimal('.3175425')
    with pytest.raises(ValueError,match='history changed'):
        seed(path,AUTH,[dict(artifactHash='old-fixture',reservedCostUsd='.1',reportHash='old-report')])


def test_claim_blocks_unknown_budget_tampered_snapshot_and_exhaustion(tmp_path):
    path=initialized(tmp_path,'.9')
    with pytest.raises(ValueError,match='exceeds'):claim(path,artifact('too-much','.2'),tmp_path/'run')
    a=artifact('tampered','.01');a['reservedCostUsd']='.001'
    with pytest.raises(ValueError,match='unchanged'):claim(path,a,tmp_path/'run')
    a=artifact('unknown','.01');a['authorization']=dict(AUTH,authority='other');a['artifactHash']=digest({k:v for k,v in a.items() if k!='artifactHash'})
    with pytest.raises(ValueError,match='initialized'):claim(path,a,tmp_path/'run')
    assert Decimal(balance(path,AUTH))==Decimal('.1')


def test_failure_after_claim_cannot_reset_the_reservation(tmp_path):
    path=initialized(tmp_path);a=artifact('crashed','.3')
    claim(path,a,tmp_path/'missing-output')
    assert not (tmp_path/'missing-output').exists()
    with pytest.raises(ValueError):claim(path,a,tmp_path/'retry')
    assert Decimal(balance(path,AUTH))==Decimal('.7')


def test_shared_runner_rejects_repeat_before_loading_key_or_callback(tmp_path,monkeypatch):
    from run_issue_scoped_comparison import execute
    from test_run_context_boundary_comparison import frozen_fixture
    from test_scoped_input_study import mock_scoped
    import run_context_boundary_comparison
    f=frozen_fixture();path=tmp_path/'datasets/reservations.sqlite3'
    f['authorization']=AUTH;f['authorizationHash']=digest(AUTH)
    f['artifactHash']=digest({k:v for k,v in f.items() if k!='artifactHash'})
    seed(path,f['authorization'],[dict(artifactHash='fixture-history',reservedCostUsd='0',reportHash='fixture')])
    monkeypatch.setattr(run_context_boundary_comparison,'from_source',lambda:f)
    execute(f,tmp_path/'datasets/first','offline-fixture',mock_scoped,ledger_path=path)
    monkeypatch.setattr('run_issue_scoped_comparison.read_key',lambda *a,**kw:pytest.fail('key loaded'))
    with pytest.raises(ValueError,match='already consumed'):
        execute(f,tmp_path/'datasets/second',None,lambda *a,**kw:pytest.fail('callback'),ledger_path=path)
    assert not (tmp_path/'datasets/second').exists()
