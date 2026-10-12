"""Isolated B/C reference split. Never overwrites runtime or promotes labels.

This changes rule wording as well as ownership; it is NOT a pure routing ablation.
"""
import argparse
import copy
from pathlib import Path

from fixed_review_suite import digest, read, require, write_private


def split(base):
    require(base.get('schemaVersion') == 'review-guidelines-4'
            and base.get('status') == 'WORKING_REFERENCE_NOT_VALIDATED'
            and base.get('humanValidated') is False, 'unvalidated working archive required')
    result = copy.deepcopy(base)
    parents = [r for r in result['guidelines'] if r['id'] == 'local-life-devaluation']
    require(len(parents) == 1, 'single mixed parent required')
    parent = parents[0]
    require(set(parent['sourceCaseIds']) == {'pisik-B', 'pisik-C'}
            and len(parent['sourceCaseIds']) == 2, 'split ownership must be reviewed for new sources')
    child_id = 'offering-choice-devaluation'
    require(not any(r['id'] == child_id for r in result['guidelines']), 'already split')
    child = copy.deepcopy(parent)
    parent['sourceCaseIds'] = ['pisik-B']
    parent['condition'] = ('시설·선택지 부재→원하는 것을 못 누림→대체 생활을 낮추는 비교·비유의 연결을 검토한다. '
          '부재·욕구·대체만으로 폄하하지 않는다. 실제 낮추는 평가와 대상 연결이 필요하다.')
    child['id'] = child_id
    child['sourceCaseIds'] = ['pisik-C']
    child['condition'] = ('메뉴 평가→제공·선택 자체를 무가치하게 취급하는 연결을 검토한다. '
                          '단조로움·가격·취향 불만이나 단일 메뉴 안내만으로 경고하지 않는다. '
                          '제공자 모욕·청취 여부를 원문 없이 추가하지 않는다.')
    result['guidelines'].insert(result['guidelines'].index(parent) + 1, child)
    examples = [e for e in result['examples'] if e['id'] in {'pisik-B', 'pisik-C'}]
    require(len(examples) == 2 and {e['id'] for e in examples} == {'pisik-B', 'pisik-C'},
            'both source examples required')
    for example in examples:
        require(example['mechanismIds'] == ['local-life-devaluation'], 'unexpected mechanism ownership')
        if example['id'] == 'pisik-C': example['mechanismIds'] = [child_id]
    result['version'] = base['version'] + '-bc-split-draft-1'
    result['contextDictionarySha256'] = digest({'base': base['contextDictionarySha256'],
                                               'guidelines': result['guidelines']})
    provenance = {'schemaVersion': 'context-pattern-split-draft-1',
        'baseHash': digest(base), 'candidateHash': digest(result),
        'ownership': {'local-life-devaluation': ['pisik-B'], child_id: ['pisik-C']},
        'ruleWordingChanged': True, 'examplesTextChanged': False,
        'defaultRuntimeChanged': False, 'humanApproved': False,
        'apiCalls': 0, 'detectionImprovementMeasured': False, 'qualityGateMet': None}
    return result, provenance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--output-dir', required=True)
    args = parser.parse_args()
    result, provenance = split(read(args.base))
    directory = Path(args.output_dir).resolve()
    require('datasets' in directory.parts and not directory.exists(), 'new private directory required')
    directory.mkdir(parents=True, mode=0o700)
    write_private(directory / 'split-runtime.json', result)
    write_private(directory / 'provenance.json', provenance)


if __name__ == '__main__': main()
