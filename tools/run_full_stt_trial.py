"""Bounded whole-STT prototype, not runtime replay, STT, images or deployment."""
import copy
import json
from decimal import Decimal
from pathlib import Path
from fixed_review_suite import read,write_private,digest,require
from full_stt_contract import LIMIT,discovery_request,accept_discovery,candidate_base,selection_request,hydrate_selected
from bound_review_contract import prepare_attempt,receive,parse_response
from run_boundary_comparison import transport
from collect_youtube_comments import read_key
from experiment_costs import positive
from reservation_ledger import claim,DEFAULT_PATH
from stabilize_incident_reference import ROOT

AUTHORIZATION=dict(schemaVersion='fresh-experiment-budget-1',userAuthorized=True,additionalBudgetUsd='1.00',
    approvalId='2026-10-13-full-stt-user-1',approvalText='진행하자 1달러추가',
    scope='WHOLE_SAVED_STT_DISCOVERY_SELECTION_JUDGMENT_PROTOTYPE_NO_POOLING')
TRANSCRIPT_HASH='58efdd13606aeb40f1c81065601ee0e8cc27f8e9028d995d972046a77b800302'
BODY_BYTES=40000
VARIANT='SOURCE_FAITHFUL_CLOSED_SCOPE'


def source_inputs():
    from run_automatic_anchor_coupling import score
    from private_report_bundle import load
    p=ROOT/'automatic-primary-coupling-20261013';f=read(p/'frozen.json')
    s=score(f,load(p/'live-run/report-index.json'),read(p/'java-audit.json'))
    require(s==read(p/'score.json') and s['mechanicalContractGateMet'],'audited parent required')
    transcript=read(ROOT.parent.parent/'experiments/run-51.json')['transcript']
    require(digest(transcript)==TRANSCRIPT_HASH,'complete saved v1 transcript changed')
    return read(ROOT/'source-faithfulness-confirmation-20261013/frozen.json'),transcript


def freeze(parent,transcript,authorization):
    require(authorization==AUTHORIZATION and parent['config']['maxCompletionTokens']==3000,'exact new approval/config required')
    templates={j['case']:j for j in parent['jobs'] if j['repeat']==1}
    require(len(templates)==9,'all six normal controls required')
    mapping={};targets={}
    for name in ('F2','C','D'):
        job=templates[name];raw=json.loads(job['baseBody']['messages'][1]['content'])['raw']
        for r in raw:
            key=(r['startMs'],r['endMs'],r['text'])
            require(any(key==(t['startMs'],t['endMs'],t['text']) for t in transcript),'parent/source transcription differs')
            require(key not in mapping or mapping[key]==r['id'],'ambiguous saved IDs');mapping[key]=r['id']
        targets[name]=next(e['segmentId'] for e in job['fixedSelectedScope']['evidence'] if e['role']=='PRIMARY')
    rows=[dict(r,id=mapping.get((r['startMs'],r['endMs'],r['text']),f'full-stt-{i}'),type='SPEECH') for i,r in enumerate(transcript)]
    units=[dict(name='V1',repeat=i,baseBody=copy.deepcopy(templates['F2']['baseBody']),raw=copy.deepcopy(rows)) for i in (1,2,3)]
    for n,j in templates.items():
        if n in targets:continue
        units.append(dict(name=n,repeat=1,baseBody=copy.deepcopy(j['baseBody']),
            raw=copy.deepcopy(json.loads(j['baseBody']['messages'][1]['content'])['raw'])))
    for u in units:
        u['discoveryBody']=discovery_request(u['baseBody'],u['raw'])
        require(len(json.dumps(u['discoveryBody'],ensure_ascii=False).encode())<=BODY_BYTES,'discovery input envelope exceeded')
    config=copy.deepcopy(parent['config']);price=config['pricing'];cap=(Decimal(BODY_BYTES+2000)*positive(price['inputUsdPerMillion'])
        +Decimal(config['maxCompletionTokens'])*positive(price['outputUsdPerMillion']))/1000000
    calls=len(units)*(1+2*LIMIT);total=cap*calls
    require(total<=Decimal(authorization['additionalBudgetUsd']),'worst-case full trial exceeds new approval')
    result=dict(schemaVersion='whole-stt-prototype-trial-1',authorization=copy.deepcopy(authorization),
        authorizationHash=digest(authorization),sourceParentHash=digest(parent),transcriptHash=digest(transcript),
        units=units,developmentAnchorLocations=targets,config=config,bodyBytesCap=BODY_BYTES,
        maxCandidatesPerUnit=LIMIT,maxApiCalls=calls,perCallReservationUsd=str(cap),reservedCostUsd=str(total),
        remainingBudgetUsd=str(Decimal(authorization['additionalBudgetUsd'])-total),judgmentVariant=VARIANT,
        evaluationScope='WHOLE_SAVED_STT_KNOWN_CASE_PROTOTYPE_NOT_PRODUCTION_REPLAY',
        normalControlsRepetitions=1,videoRepetitions=3,runtimeChanged=False,fullPipelineReplayed=False,
        independentGoldApprovals=0,semanticQualityGateMet=None)
    result['artifactHash']=digest(result);return result


def prepare():return freeze(*source_inputs(),AUTHORIZATION)
def validate(frozen):
    expected=(prepare_catalog(frozen['availableBudgetUsd']) if frozen.get('followUp')=='CATALOG_SELECTION_1' else
        prepare_follow(frozen['availableBudgetUsd']) if frozen.get('followUp')=='EVIDENCE_BUDGET_1' else prepare())
    require(frozen==expected,'whole-STT plan changed')


def freeze_follow(source,available):
    available=positive(available);require(available<=Decimal(source['remainingBudgetUsd']),'no expansion/pooling')
    result=copy.deepcopy(source);result.pop('artifactHash')
    for u in result['units']:
        u['candidateLimit']=LIMIT if u['name']=='V1' else 1
        u['discoveryBody']=discovery_request(u['baseBody'],u['raw'],'EVIDENCE_BUDGET_1')
    cap_bytes=32000;p=source['config']['pricing']
    cap=(Decimal(cap_bytes+2000)*positive(p['inputUsdPerMillion'])+Decimal(3000)*positive(p['outputUsdPerMillion']))/1000000
    calls=sum(1+2*u['candidateLimit'] for u in result['units']);total=cap*calls
    require(total<=available and all(len(json.dumps(u['discoveryBody'],ensure_ascii=False).encode())<=cap_bytes for u in result['units']),
        'complete follow-up exceeds remaining approval/envelope')
    result.update(followUp='EVIDENCE_BUDGET_1',sourceTrialHash=digest(source),availableBudgetUsd=str(available),
        bodyBytesCap=cap_bytes,maxApiCalls=calls,perCallReservationUsd=str(cap),reservedCostUsd=str(total),remainingBudgetUsd=str(available-total))
    result['artifactHash']=digest(result);return result


def prepare_follow(available):
    p=ROOT/'whole-stt-prototype-20261013';source=read(p/'frozen.json')
    scored=score(source,load_report(p/'live-run/report-index.json'),[])
    require(scored==read(p/'score.json') and all(u['status']=='DISCOVERY_REJECTED' for u in scored['units'][:3]),
        'preserved discovery-contract failure required')
    return freeze_follow(source,available)


def freeze_catalog(source,report,available,normal_payloads):
    available=positive(available);require(available<=Decimal(source['remainingBudgetUsd']),'no expansion/pooling')
    require(report['artifactHash']==source['artifactHash'] and report['status']=='COMPLETED','associated complete source required')
    result=copy.deepcopy(source);result.pop('artifactHash');units=[]
    for u,spec in zip(report['units'],source['units'],strict=True):
        if u['name']=='V1':
            candidates=[c['payload'] for c in u['candidates'] if c['payload']['candidates'][0]['anchorId']==source['developmentAnchorLocations']['C']]
            require(len(candidates)==1,'one recorded C candidate per repeat required')
            units.append(dict(name=f'C-{u["repeat"]}',repeat=1,baseBody=copy.deepcopy(spec['baseBody']),raw=copy.deepcopy(spec['raw']),
                fixedPayloads=copy.deepcopy(candidates)))
        elif u['name'] in {'Control-Review','Control-SingleMenu'}:
            require(not u['candidates'] and not u['discovery']['unreviewedIds'],'observed normal source required')
            payload=copy.deepcopy(normal_payloads[u['name']]);require(payload['raw']==spec['raw'],'normal source changed')
            units.append(dict(name=u['name'],repeat=1,baseBody=copy.deepcopy(spec['baseBody']),raw=copy.deepcopy(spec['raw']),fixedPayloads=[payload]))
    require(len(units)==5,'three recorded C and two owner normals required')
    total=Decimal(source['perCallReservationUsd'])*10;require(total<=available,'focused selector repair exceeds remaining approval')
    result.update(units=units,followUp='CATALOG_SELECTION_1',selectionVariant='CATALOG',sourceTrialHash=digest(source),sourceReportHash=digest(report),
        availableBudgetUsd=str(available),maxApiCalls=10,reservedCostUsd=str(total),remainingBudgetUsd=str(available-total),
        videoRepetitions=0,recordedCRepetitions=3,evaluationScope='FIXED_DISCOVERED_C_AND_OWNER_NORMAL_CATALOG_SELECTION_ONLY')
    result['artifactHash']=digest(result);return result


def prepare_catalog(available):
    p=ROOT/'whole-stt-evidence-budget-20261013';f=read(p/'frozen.json');r=load_report(p/'live-run/report-index.json')
    s=score(f,r,[read(p/'java-audit-1.json')]);require(s==read(p/'score.json'),'complete audited selector-failure source required')
    parent=read(ROOT/'source-faithfulness-confirmation-20261013/frozen.json')
    normals={j['case']:json.loads(j['baseBody']['messages'][1]['content']) for j in parent['jobs']
        if j['case'] in {'Control-Review','Control-SingleMenu'} and j['repeat']==1}
    return freeze_catalog(f,r,available,normals)


def save_report(directory,report):
    index=dict(schemaVersion='whole-stt-report-index-1',metadata={k:v for k,v in report.items() if k not in {'units','attempts'}},
        reportHash=digest(report),attempts=[],units=[])
    for key,prefix in (('attempts','result'),('units','unit')):
        for i,row in enumerate(report[key],1):
            filename=f'{prefix}-{i:03d}.json';path=directory/filename
            if path.exists():require(read(path)==row,'persisted attempt/unit changed')
            else:write_private(path,row)
            index[key].append(dict(file=filename,sha256=digest(row)))
    write_private(directory/'report-index.json',index)


def load_report(path):
    path=Path(path);index=read(path);require(index['schemaVersion']=='whole-stt-report-index-1'
        and 'datasets' in path.resolve().parts and not path.is_symlink(),'private full-STT index required')
    report=copy.deepcopy(index['metadata'])
    total=0
    for key,prefix,limit in (('attempts','result',117),('units','unit',9)):
        require(len(index[key])<=limit,'bounded shards required');report[key]=[]
        for i,item in enumerate(index[key],1):
            require(item['file']==f'{prefix}-{i:03d}.json' and not (path.parent/item['file']).is_symlink(),'local ordered shards required')
            total+=(path.parent/item['file']).stat().st_size;require(total<=32*1024*1024,'aggregate report exceeds bounded memory')
            row=read(path.parent/item['file']);require(digest(row)==item['sha256'],'shard changed');report[key].append(row)
    require(digest(report)==index['reportHash'],'logical report changed');return report


def audit_exports(report):
    from compare_recorded_candidates import audit_export
    cases=[];calls=[]
    for u in report['units']:
        for c in u.get('candidates',[]):
            name=c['auditName'];cases.append(dict(name=name,payload=copy.deepcopy(c['payload'])))
            call=dict(case=name,arm='AUTO',repeat=u['repeat'])
            if 'receipt' in c:call['output']=copy.deepcopy(c['receipt']['compatiblePacket']['compatibleAssessment'])
            else:call['error']='CANDIDATE_CONTRACT_REJECTED'
            calls.append(call)
    return [audit_export(dict(cases=cases[i:i+8],calls=calls[i:i+8])) for i in range(0,len(cases),8)]


def execute(frozen,directory,*,simulate=False,call=transport,env_file=None,ledger_path=DEFAULT_PATH):
    validate(frozen);directory=Path(directory).resolve()
    require('datasets' in directory.parts and not directory.exists(),'new private run required')
    require(not simulate or call is not transport,'explicit offline callback required')
    reservation=None;key=None
    if not simulate:
        reservation=claim(ledger_path,frozen,directory);key=read_key(env_file,key_name='OPENAI_API_KEY')
    directory.mkdir(parents=True,mode=0o700);write_private(directory/'frozen.json',frozen)
    if reservation:write_private(directory/'reservation.json',reservation)
    attempts=[];units=[];stopped=False
    def invoke(stage,body):
        require(len(attempts)<frozen['maxApiCalls'] and len(json.dumps(body,ensure_ascii=False).encode())<=frozen['bodyBytesCap'],
            'dynamic request envelope/call cap exceeded')
        a=dict(stage=stage,body=copy.deepcopy(body),requestHash=digest(body));attempts.append(a)
        write_private(directory/f'attempt-{len(attempts):03d}.json',a)
        try:
            response=call(key,body,timeout=90);a['providerResponse']=response
            usage=response.get('usage',{});require(all(type(usage.get(k)) is int and usage[k]>=0 for k in ('prompt_tokens','completion_tokens')),'usage unavailable')
            p=frozen['config']['pricing'];cost=(Decimal(usage['prompt_tokens'])*positive(p['inputUsdPerMillion'])
                +Decimal(usage['completion_tokens'])*positive(p['outputUsdPerMillion']))/1000000
            a.update(usage=copy.deepcopy(usage),estimatedCostUsd=str(cost))
            require(usage['prompt_tokens']<=frozen['bodyBytesCap']+2000 and usage['completion_tokens']<=3000
                and cost<=Decimal(frozen['perCallReservationUsd']),'usage exceeds reservation')
            a['rawOutput']=parse_response(response,body['model'])
        except Exception as error:
            a['error']=type(error).__name__
            raise
        finally:write_private(directory/f'result-{len(attempts):03d}.json',a)
        return a
    for i,u in enumerate(frozen['units'],1):
        result=dict(name=u['name'],repeat=u['repeat'],candidates=[],status='PARTIAL');units.append(result)
        try:
            if 'fixedPayloads' in u:
                parsed=dict(payloads=copy.deepcopy(u['fixedPayloads']),unreviewedIds=[],uncertainSegments=[],truncated=False)
                result['discoverySource']='PRESERVED_CANDIDATE_NOT_NEW_DISCOVERY'
            else:
                disc=invoke('DISCOVER',u['discoveryBody']);result['discoveryAttempt']=len(attempts)-1
                try:
                    parsed=accept_discovery(disc['rawOutput'],u['raw'])
                    require(len(parsed['payloads'])<=u.get('candidateLimit',LIMIT),'unit candidate reservation exceeded')
                except (ValueError,KeyError,TypeError):result['status']='DISCOVERY_REJECTED';continue
            result['discovery']=parsed
            for k,payload in enumerate(parsed['payloads'],1):
                c=dict(payload=payload,auditName=f'Unit-{i}-Candidate-{k}',status='PARTIAL');result['candidates'].append(c)
                selector=frozen.get('selectionVariant','OFFSETS')
                base=candidate_base(u['baseBody'],payload);sel=invoke('SELECT',selection_request(base,selector));c['selectionAttempt']=len(attempts)-1
                try:scope=hydrate_selected(sel['rawOutput'],payload,selector)
                except (ValueError,KeyError,TypeError):c['status']='SELECTION_REJECTED';continue
                c['scope']=scope;ticket=prepare_attempt(base,scope,f'{frozen["artifactHash"]}-{i}-{k}',judgment_variant=VARIANT)
                c['ticket']=ticket;jud=invoke('JUDGE',ticket['requestBody']);c['judgmentAttempt']=len(attempts)-1
                try:c['receipt']=receive(ticket,jud['body'],jud['providerResponse']);c['status']='CONTRACT_ONLY'
                except (ValueError,KeyError,TypeError):c['status']='JUDGMENT_REJECTED'
            result['status']='COMPLETED'
        except Exception as error:
            result.update(status='FAILED_STOP',error=type(error).__name__);stopped=True
        finally:write_private(directory/f'unit-{i:03d}.json',result)
        print(dict(unit=i,name=u['name'],repeat=u['repeat'],candidates=len(result['candidates']),status=result['status']),flush=True)
        if stopped:break
    report=dict(schemaVersion='whole-stt-prototype-report-1',artifactHash=frozen['artifactHash'],authorizationHash=frozen['authorizationHash'],
        runKind='MOCK_OFFLINE' if simulate else 'LIVE',status='PARTIAL' if stopped else 'COMPLETED',units=units,attempts=attempts,
        apiCalls=0 if simulate else len(attempts),simulatedCalls=len(attempts) if simulate else 0,
        consumedReservationUsd='0' if simulate else frozen['reservedCostUsd'],
        estimatedUsageCostUsd=str(sum(Decimal(a.get('estimatedCostUsd','0')) for a in attempts)) if not simulate else None,
        unknownCostAttempts=sum('estimatedCostUsd' not in a for a in attempts),actualBilledCostUsd=None,
        runtimeChanged=False,semanticQualityGateMet=None,fullPipelineReplayed=False)
    save_report(directory,report)
    for i,e in enumerate(audit_exports(report),1):write_private(directory/f'java-export-{i}.json',e)
    return report


def score(frozen,report,audits):
    from compare_review_prompts import digest as audit_digest
    validate(frozen);require(report['artifactHash']==frozen['artifactHash'] and report['authorizationHash']==frozen['authorizationHash']
        and report['status']=='COMPLETED' and len(report['units'])==len(frozen['units']),'complete bound trial required')
    exports=audit_exports(report);require(len(audits)==len(exports),'all Java batches required')
    indexed={}
    for e,a in zip(exports,audits,strict=True):
        require(a['sourceReportSha256']==audit_digest(e) and len(a['results'])==len(e['calls']),'associated complete Java audit required')
        for row in a['results']:
            require(row['case'] not in indexed,'duplicate audit');indexed[row['case']]=row
    attempts=report['attempts'];used=[];out=[]
    def check(index,stage,body):
        require(type(index) is int and 0<=index<len(attempts),'attempt index invalid');a=attempts[index];used.append(index)
        require(a['stage']==stage and a['body']==body and a['requestHash']==digest(body)
            and len(json.dumps(body,ensure_ascii=False).encode())<=frozen['bodyBytesCap'],'original request changed')
        require(parse_response(a['providerResponse'],body['model'])==a['rawOutput'],'original response changed')
        u=a['providerResponse']['usage'];p=frozen['config']['pricing'];amount=(Decimal(u['prompt_tokens'])*positive(p['inputUsdPerMillion'])
            +Decimal(u['completion_tokens'])*positive(p['outputUsdPerMillion']))/1000000
        require(u==a['usage'] and all(type(u[k]) is int and u[k]>=0 for k in ('prompt_tokens','completion_tokens'))
            and u['prompt_tokens']<=frozen['bodyBytesCap']+2000 and u['completion_tokens']<=3000
            and amount==Decimal(a['estimatedCostUsd']) and amount<=Decimal(frozen['perCallReservationUsd']),'usage/cost changed')
        return a
    for i,(u,spec) in enumerate(zip(report['units'],frozen['units'],strict=True),1):
        require((u['name'],u['repeat'])==(spec['name'],spec['repeat']),'unit changed')
        if 'fixedPayloads' in spec:
            require(u.get('discoverySource')=='PRESERVED_CANDIDATE_NOT_NEW_DISCOVERY' and 'discoveryAttempt' not in u,'no fake discovery allowed')
            parsed=dict(payloads=copy.deepcopy(spec['fixedPayloads']),unreviewedIds=[],uncertainSegments=[],truncated=False)
        else:
            disc=check(u['discoveryAttempt'],'DISCOVER',spec['discoveryBody'])
            try:
                parsed=accept_discovery(disc['rawOutput'],spec['raw'])
                require(len(parsed['payloads'])<=spec.get('candidateLimit',LIMIT),'unit candidate reservation exceeded')
            except (ValueError,KeyError,TypeError):
                require(u['status']=='DISCOVERY_REJECTED' and not u['candidates'],'discovery rejection changed')
                out.append(dict(name=u['name'],repeat=u['repeat'],status=u['status']));continue
        require(parsed==u['discovery'] and len(parsed['payloads'])==len(u['candidates']),'discovery changed')
        observed=[]
        for k,(c,payload) in enumerate(zip(u['candidates'],parsed['payloads'],strict=True),1):
            require(c['payload']==payload and c['auditName']==f'Unit-{i}-Candidate-{k}','candidate changed')
            selector=frozen.get('selectionVariant','OFFSETS')
            base=candidate_base(spec['baseBody'],payload);sel=check(c['selectionAttempt'],'SELECT',selection_request(base,selector));a=indexed[c['auditName']]
            decision=None
            try:scope=hydrate_selected(sel['rawOutput'],payload,selector)
            except (ValueError,KeyError,TypeError):require(c['status']=='SELECTION_REJECTED' and not a['accepted'] and 'receipt' not in c,'selection rejection changed')
            else:
                ticket=prepare_attempt(base,scope,f'{frozen["artifactHash"]}-{i}-{k}',judgment_variant=VARIANT)
                require(c['scope']==scope and c['ticket']==ticket,'scope/ticket changed')
                jud=check(c['judgmentAttempt'],'JUDGE',ticket['requestBody'])
                try:receipt=receive(ticket,jud['body'],jud['providerResponse'])
                except (ValueError,KeyError,TypeError):require(c['status']=='JUDGMENT_REJECTED' and not a['accepted'] and 'receipt' not in c,'judgment rejection changed')
                else:
                    require(receipt==c['receipt'] and c['status']=='CONTRACT_ONLY'
                        and a.get('decision')==receipt['modelOutput']['assessment']['decision'],'receipt/Java decision changed')
                    decision=receipt['modelOutput']['assessment']['decision']
            observed.append(dict(anchorId=payload['candidates'][0]['anchorId'],status=c['status'],javaAccepted=a['accepted'],decision=decision))
        require(u['status']=='COMPLETED','unit completion changed')
        out.append(dict(name=u['name'],repeat=u['repeat'],status=u['status'],unreviewedIds=parsed['unreviewedIds'],
            uncertainSegments=parsed['uncertainSegments'],truncated=parsed['truncated'],candidates=observed,
            anchorLocationStages={name:next((c for c in observed if c['anchorId']==sid),None)
                for name,sid in frozen['developmentAnchorLocations'].items()} if u['name']=='V1' else {},semanticIssueMatched=None))
    require(used==list(range(len(attempts))) and len(attempts)<=frozen['maxApiCalls'],'extra/reused/reordered calls')
    live=report['runKind']=='LIVE';require(report['runKind'] in {'LIVE','MOCK_OFFLINE'} and report['unknownCostAttempts']==0
        and report['apiCalls']==(len(attempts) if live else 0) and report['simulatedCalls']==(0 if live else len(attempts))
        and report['consumedReservationUsd']==(frozen['reservedCostUsd'] if live else '0'),'accounting changed')
    cost=sum(Decimal(a['estimatedCostUsd']) for a in attempts)
    require(report['estimatedUsageCostUsd']==(str(cost) if live else None),'total cost changed')
    return dict(schemaVersion='whole-stt-prototype-score-1',units=out,javaAccepted=sum(a['accepted'] for a in indexed.values()),
        javaTotal=len(indexed),runKind=report['runKind'],semanticQualityGateMet=None,independentGoldApprovals=0,
        estimatedUsageCostUsd=report['estimatedUsageCostUsd'],actualBilledCostUsd=None,runtimeUseAuthorized=False,
        fullPipelineReplayed=False,normalControlsRepetitions=1,evaluationScope=frozen['evaluationScope'])
