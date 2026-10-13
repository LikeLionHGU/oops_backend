"""One-time import of the four already consumed reservations; no new approval."""
from pathlib import Path
from fixed_review_suite import digest, read, require
from private_report_bundle import load
from reservation_ledger import seed, balance, DEFAULT_PATH

ROOT=Path(__file__).resolve().parents[1]/'datasets/controversy/evaluation/v1'
HISTORY=[
    ('scoped-input-live-20261012','frozen.json','run/report.json',
     'cd815eed89d839b1db4417956f0884899020e62582659e85da79edc11be55a1e',
     '1ebd67a04ed6b2c078701b531782a751e5a013e38fe1198cbcb92d3e7f1b978b'),
    ('scoped-full-regression-20261012','frozen-bundle.json','live-run/report-index.json',
     '97efafee5e76c3ddb11cb206b10a19f17dafa5e8072c1f2661b5359e21c037a1',
     '33b7abfacb2c702fdfa05bb5e669b8a6d0d5d624c1d25b2443d878d5551d2230'),
    ('context-reselection-20261012','frozen.json','live-run/report-index.json',
     '9e012daaa6a2d814ec80b7d1dfc89762fadd31312d67911ec1ea559efa135267',
     '9124949e52fc73accc216b5b16e2bd5edfa91c980fc7a9803fc0361c115c0e5a'),
    ('b-context-boundary-live-20261012','frozen.json','live-run/report-index.json',
     'e36af2bb522f50430d453203517ee39c7eaf9d9b8eca248661efbddde51a5003',
     '07e4db0d556c782477a466cff04016819c27a01a9afb8e532741391e9b29d4d8')]


def initialize(path=DEFAULT_PATH):
    authorization=None;entries=[]
    for directory,fp,rp,fh,rh in HISTORY:
        frozen=read(ROOT/directory/fp);report=load(ROOT/directory/rp)
        require(digest(frozen)==fh and digest(report)==rh
                and report['artifactHash']==frozen['artifactHash'] and report['runKind']=='LIVE'
                and report['unknownCostAttempts']==0
                and report['consumedReservationUsd']==frozen['reservedCostUsd'], 'verified consumed history required')
        if authorization is None: authorization=frozen['authorization']
        require(frozen['authorization']==authorization, 'do not pool separate authorizations')
        entries.append(dict(artifactHash=frozen['artifactHash'],reservedCostUsd=frozen['reservedCostUsd'],reportHash=rh))
    seed(path,authorization,entries)
    return balance(path,authorization)


if __name__=='__main__':
    print(dict(importedReservations=4,remainingBudgetUsd=initialize(),apiCalls=0))
