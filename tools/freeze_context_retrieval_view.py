"""Isolated v4->v5 migration preserving the old lexical index, not new evidence.

Frozen legacy text still contains historical interpretations. It is compatibility
data only, never included in the model payload. No runtime overwrite or API calls.
"""
import argparse
import copy
import hashlib
from pathlib import Path

from fixed_review_suite import digest, read, require, write_private


def freeze(base):
    require(base.get('schemaVersion') == 'review-guidelines-4'
            and base.get('status') == 'WORKING_REFERENCE_NOT_VALIDATED'
            and base.get('humanValidated') is False, 'unvalidated v4 source required')
    require(base.get('examples') and len({e['id'] for e in base['examples']}) == len(base['examples']),
            'nonempty unique examples required')
    result = copy.deepcopy(base)
    conditions = {r['id']: r['condition'] for r in base['guidelines']}
    for example in result['examples']:
        require('retrievalView' not in example, 'refuse existing retrieval version')
        require(example['mechanismIds'] and all(m in conditions for m in example['mechanismIds']),
                'known mechanisms required')
        require(all(isinstance(example['context'].get(k), list)
                    and all(isinstance(s, str) for s in example['context'][k])
                    for k in ('flow', 'criticismHypotheses')), 'explicit lexical fields required')
        # Exact Java legacy concatenation including its leading separator.
        text = (' '.join(example['context']['flow']) + ' '
                + ' '.join(example['context']['criticismHypotheses']) + ' '
                + ''.join(' ' + conditions[m] for m in example['mechanismIds']))
        require(0 < len(text.strip()) and len(text) <= 8000, 'bounded retrieval text required')
        example['retrievalView'] = dict(version='frozen-legacy-lexical-1',
            basis='FROZEN_LEGACY_NOT_SOURCE_EVIDENCE', text=text,
            sha256=hashlib.sha256(text.encode('utf-8')).hexdigest())
    result['schemaVersion'] = 'review-guidelines-5'
    result['version'] = '2026-10-12-frozen-retrieval-view-1'
    provenance = dict(schemaVersion='frozen-retrieval-provenance-1', baseHash=digest(base),
        migratedHash=digest(result), retrievalIndexHash=digest([
            dict(id=e['id'], retrievalView=e['retrievalView']) for e in result['examples']]),
        legacyInterpretationsRetainedForCompatibility=True, sourceOnlyIndex=False,
        defaultRuntimeChanged=False, apiCalls=0, detectionImprovementMeasured=False,
        humanApproved=False, trainingUseAuthorized=False, qualityGateMet=None)
    return result, provenance


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base', required=True); p.add_argument('--output-dir', required=True)
    args = p.parse_args()
    result, meta = freeze(read(args.base))
    directory = Path(args.output_dir).resolve()
    require('datasets' in directory.parts and not directory.exists(), 'new private directory required')
    directory.mkdir(parents=True, mode=0o700)
    write_private(directory/'frozen-runtime.json', result)
    write_private(directory/'provenance.json', meta)


if __name__ == '__main__': main()
