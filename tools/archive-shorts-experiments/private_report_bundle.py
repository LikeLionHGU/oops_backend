"""Bounded report shards; preserves the logical report and provider responses."""
import copy
from pathlib import Path

from fixed_review_suite import digest,read,require,write_private


def save(directory,report,name):
    require(name in {'report','audit'},'explicit report bundle kind required')
    records=[]
    require(1<=len(report['calls'])<=100,'bounded report units required')
    for i,unit in enumerate(report['calls'],1):
        filename=f'{name}-unit-{i:03d}.json'
        write_private(directory/filename,unit)
        require((directory/filename).stat().st_size<=4*1024*1024,'single report unit exceeds reader limit')
        records.append(dict(file=filename,sha256=digest(unit)))
    metadata=copy.deepcopy({k:v for k,v in report.items() if k!='calls'})
    bundle=dict(schemaVersion='private-report-bundle-1',name=name,metadata=metadata,units=records,
                logicalReportHash=digest(report))
    bundle['bundleHash']=digest(bundle)
    write_private(directory/f'{name}-index.json',bundle)


def load(path):
    path=Path(path)
    bundle=read(path)
    if bundle.get('schemaVersion')!='private-report-bundle-1':return bundle
    require('datasets' in path.resolve().parts and bundle['name'] in {'report','audit'}
        and bundle['bundleHash']==digest({k:v for k,v in bundle.items() if k!='bundleHash'})
        and 1<=len(bundle['units'])<=100 and 'calls' not in bundle['metadata'],'valid bounded private bundle required')
    calls=[];total=0
    for i,item in enumerate(bundle['units'],1):
        require(item['file']==f'{bundle["name"]}-unit-{i:03d}.json','ordered local unit filename required')
        file=path.parent/item['file']
        require(not file.is_symlink(),'report shard symlink refused')
        total+=file.stat().st_size
        require(total<=32*1024*1024,'aggregate report exceeds bounded memory budget')
        unit=read(file)
        require(digest(unit)==item['sha256'],'report shard changed')
        calls.append(unit)
    result=dict(bundle['metadata'],calls=calls)
    require(digest(result)==bundle['logicalReportHash'],'logical report changed')
    return result
