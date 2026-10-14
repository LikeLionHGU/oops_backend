"""Validate authored criticism interpretations without inventing comments or source evidence."""
import copy
from review_context_pilot import require, text

BASES = {"USER_REVIEWED_DEVELOPMENT_EXPECTATION", "SOURCE_INTERPRETATION", "AUDIENCE_REACTION_INTERPRETATION"}


def validate(hypotheses, card):
    require(isinstance(hypotheses, list) and len(hypotheses) <= 16, "CRITICISM_HYPOTHESES_LIMIT")
    source_ids = {s['id'] for s in card['evidence']['sources']}
    segment_ids = {s['id'] for s in card['evidence']['segments']}
    reactions = {r['id']: r for r in card['reactions']}
    ids, result = set(), []
    for h in hypotheses:
        require(isinstance(h, dict) and set(h) == {
            'id', 'text', 'basis', 'status', 'evidenceRefs', 'sourceRefs', 'supportingReactionIds',
            'requiredCurrentEvidence', 'normalContrast', 'missingContext'}, "CRITICISM_HYPOTHESIS_FIELDS")
        require(text(h['id'], 100) and h['id'] not in ids and text(h['text'], 350)
                and h['basis'] in BASES and h['status'] == 'DRAFT', "CRITICISM_HYPOTHESIS_IDENTITY")
        ids.add(h['id'])
        for name, allowed in [('evidenceRefs', segment_ids), ('sourceRefs', source_ids), ('supportingReactionIds', set(reactions))]:
            require(isinstance(h[name], list) and all(isinstance(x, str) for x in h[name])
                    and len(h[name]) == len(set(h[name])) and set(h[name]) <= allowed, "CRITICISM_REFERENCE_INVALID:" + name)
        if h['basis'] == 'SOURCE_INTERPRETATION':
            require(bool(h['sourceRefs']), "CRITICISM_SOURCE_REQUIRED")
        if h['basis'] == 'AUDIENCE_REACTION_INTERPRETATION':
            require(bool(h['supportingReactionIds']), "CRITICISM_REACTION_REQUIRED")
        require(all(reactions[r]['stage'] == 'CONTENT' and reactions[r]['role'] == 'CRITICISM_SUPPORT'
                    and reactions[r]['mapping']['scope'] in {'EXACT_POINT', 'SCENE_OR_FLOW'}
                    for r in h['supportingReactionIds']), "CRITICISM_REACTION_SCOPE")
        for key, minimum in [('requiredCurrentEvidence', 1), ('normalContrast', 1), ('missingContext', 0)]:
            require(isinstance(h[key], list) and minimum <= len(h[key]) <= 4
                    and all(text(t, 200) for t in h[key]), "CRITICISM_BOUNDARY_REQUIRED:" + key)
        # Label the provenance in the model-facing text; no popularity/ground-truth claim.
        prefix = '개발 검토 가설' if h['basis'] == 'USER_REVIEWED_DEVELOPMENT_EXPECTATION' else '출처 해석 가설'
        compact = prefix + ': ' + h['text'] + ' 적용 근거: ' + '; '.join(h['requiredCurrentEvidence'])
        compact += ' 정상 대조: ' + '; '.join(h['normalContrast'])
        if h['missingContext']:
            compact += ' 미확인: ' + '; '.join(h['missingContext'])
        require(text(compact, 350), "CRITICISM_REFERENCE_TOO_LONG")
        result.append({**copy.deepcopy(h), 'compactReferenceText': compact})
    return result
