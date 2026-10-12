"""Offline anchor diagnostic. Existing selected quotation only, no new evidence."""
import copy
import json
from fixed_review_suite import require
from issue_scoped_review import hydrate_selection,validate_scope


def reanchor(base,scope,segment_id):
    payload=json.loads(base['messages'][1]['content']);validate_scope(scope,payload)
    selection=copy.deepcopy(scope['selectionOutput'])
    contexts=[p for p in selection['selections'] if p['segmentId']==segment_id and p['role']=='CONTEXT']
    require(len(contexts)==1,'existing selected CONTEXT quotation required')
    old=payload['candidates'][0]['anchorId']
    require(segment_id!=old,'different anchor required')
    for p in selection['selections']:
        if p['role']=='PRIMARY':p['role']='CONTEXT'
    contexts[0]['role']='PRIMARY'
    candidate=payload['candidates'][0];candidate['anchorId']=segment_id
    if not any(e['segmentId']==segment_id for e in candidate['proposedEvidence']):
        quote=next(r['text'] for r in payload['raw'] if r['id']==segment_id)
        pick=contexts[0]
        candidate['proposedEvidence'].append(dict(segmentId=segment_id,quote=quote[pick['start']:pick['end']]))
    selection['anchorId']=segment_id
    result=copy.deepcopy(base);result['messages'][1]['content']=json.dumps(payload,ensure_ascii=False)
    return result,hydrate_selection(selection,payload)
