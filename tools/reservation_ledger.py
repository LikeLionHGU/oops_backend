"""Local, atomic paid-run reservations. No keys, API calls or refunds.

Frozen snapshots describe estimates; this ledger decides whether a reservation
can actually be consumed once. A crash after claiming still spends the full
reservation. Files are private and must live under datasets.
"""
from contextlib import contextmanager
from decimal import Decimal
import os
from pathlib import Path
import sqlite3

from fixed_review_suite import digest, require


def amount(value):
    number=Decimal(str(value))
    require(number.is_finite() and number>=0, 'finite nonnegative reservation required')
    return number


@contextmanager
def transaction(path):
    path=Path(path)
    require(not path.is_symlink() and 'datasets' in path.resolve().parts, 'private ledger path required')
    path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    # Refuse symbolic links when creating/opening the ledger file.
    fd=os.open(path,os.O_CREAT|os.O_RDWR|getattr(os,'O_NOFOLLOW',0),0o600)
    os.close(fd);os.chmod(path,0o600)
    db=sqlite3.connect(path,timeout=5)
    try:
        db.execute('BEGIN IMMEDIATE')
        db.execute('CREATE TABLE IF NOT EXISTS budgets (id TEXT PRIMARY KEY, total TEXT NOT NULL, authorization_hash TEXT NOT NULL)')
        db.execute('CREATE TABLE IF NOT EXISTS reservations (artifact TEXT PRIMARY KEY, budget TEXT NOT NULL, cost TEXT NOT NULL, output TEXT NOT NULL, state TEXT NOT NULL)')
        yield db
        db.commit()
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()


def seed(path, authorization, entries):
    require(authorization.get('userAuthorized') is True and entries,
            'explicit approved budget and consumed-history entries required')
    bid=digest(authorization);total=amount(authorization['additionalBudgetUsd'])
    require(total>0 and len({e['artifactHash'] for e in entries})==len(entries), 'unique consumed entries required')
    require(sum(amount(e['reservedCostUsd']) for e in entries)<=total, 'history exceeds authorization')
    with transaction(path) as db:
        existing=db.execute('SELECT total, authorization_hash FROM budgets WHERE id=?',(bid,)).fetchone()
        if existing:
            require(existing==(str(total),bid),'budget changed')
            for entry in entries:
                row=db.execute('SELECT budget,cost,output,state FROM reservations WHERE artifact=?',
                               (entry['artifactHash'],)).fetchone()
                require(row==(bid,str(amount(entry['reservedCostUsd'])),entry['reportHash'],'LEGACY_CONSUMED'),
                        'consumed history changed or missing')
            return
        db.execute('INSERT INTO budgets VALUES (?,?,?)',(bid,str(total),bid))
        for entry in entries:
            db.execute('INSERT INTO reservations VALUES (?,?,?,?,?)',
                (entry['artifactHash'],bid,str(amount(entry['reservedCostUsd'])),entry['reportHash'],'LEGACY_CONSUMED'))


def claim(path, artifact, output):
    authorization=artifact.get('additionalFunding',artifact.get('authorization'))
    # Some funded follow-ups carry just the verified authorization plus its hash.
    if not isinstance(authorization,dict): authorization=artifact.get('authorization')
    require(isinstance(authorization,dict) and authorization.get('userAuthorized') is True,
            'approved reservation required')
    bid=digest(authorization);cost=amount(artifact['reservedCostUsd'])
    require(cost>0 and artifact['artifactHash']==digest({k:v for k,v in artifact.items() if k!='artifactHash'}),
            'unchanged positive reservation required')
    with transaction(path) as db:
        row=db.execute('SELECT total FROM budgets WHERE id=?',(bid,)).fetchone()
        require(row is not None,'budget must be initialized from verified history before paid execution')
        require(db.execute('SELECT 1 FROM reservations WHERE artifact=?',(artifact['artifactHash'],)).fetchone() is None,
                'reservation already consumed; a new output directory is not new authorization')
        spent=sum((amount(r[0]) for r in db.execute('SELECT cost FROM reservations WHERE budget=?',(bid,))),Decimal('0'))
        require(spent+cost<=amount(row[0]),'cumulative reservation exceeds authorization')
        db.execute('INSERT INTO reservations VALUES (?,?,?,?,?)',
                   (artifact['artifactHash'],bid,str(cost),str(Path(output).resolve()),'CLAIMED'))
        return dict(artifactHash=artifact['artifactHash'],budgetId=bid,reservedCostUsd=str(cost),
                    remainingBudgetUsd=str(amount(row[0])-spent-cost))


def balance(path, authorization):
    with transaction(path) as db:
        bid=digest(authorization)
        row=db.execute('SELECT total FROM budgets WHERE id=?',(bid,)).fetchone()
        require(row is not None,'unknown authorization')
        spent=sum((amount(r[0]) for r in db.execute('SELECT cost FROM reservations WHERE budget=?',(bid,))),Decimal('0'))
        return str(amount(row[0])-spent)


DEFAULT_PATH=Path(__file__).resolve().parents[1]/'datasets/controversy/evaluation/reservations.sqlite3'
