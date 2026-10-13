import copy
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_case_context_ablation import revise, prepare
from fixed_review_suite import digest
from test_reference_split_comparison import inputs
from run_reference_split_comparison import freeze


def fixture():
    source, base, candidate = inputs()
    for e in base['examples']: e['context']['criticismHypotheses'] = ['old draft']
    # Align the saved example contexts with the fixture archive, not new raw text.
    for j in source['jobs']:
        system = j['baseBody']['messages'][0]['content']
        prefix, tail = system.split('contextReference=', 1)
        ref, end = json.JSONDecoder().raw_decode(tail)
        for e in ref['referenceContexts']: e['context']['criticismHypotheses'] = ['old draft']
        j['baseBody']['messages'][0]['content'] = prefix + 'contextReference=' + json.dumps(ref, ensure_ascii=False) + tail[end:]
    from split_context_reference_draft import split
    frozen = freeze(source, base, split(base)[0], '.1')
    patch = dict(schemaVersion='case-explanation-patch-1', status='UNREVIEWED_DRAFT',
                 humanApproved=False, runtimeEligible=False, trainingUseAuthorized=False,
                 caseId='pisik-B', previousContextHash=digest(base['examples'][0]['context']),
                 criticismHypotheses=['assistant hypothesis, not comment ground truth'],
                 annotationBasis='ASSISTANT_HYPOTHESIS_NOT_REACTION_GOLD')
    return frozen, base, patch


class ExplanationAblationTests(unittest.TestCase):
    def test_noop_explanation_refused(self):
        _, base, patch = fixture()
        patch['criticismHypotheses'] = ['old draft']
        with self.assertRaises(ValueError): revise(base, patch)

    def test_only_one_explanation_changes_archive(self):
        _, base, patch = fixture(); original = copy.deepcopy(base)
        changed = revise(base, patch)
        self.assertEqual(base, original)
        restored = copy.deepcopy(changed)
        restored['version'] = base['version']
        restored['contextDictionarySha256'] = base['contextDictionarySha256']
        restored['examples'][0]['context']['criticismHypotheses'] = ['old draft']
        self.assertEqual(restored, base)

    def test_unapproved_patch_and_source_hash_required(self):
        for mode in ('approval', 'hash', 'extra', 'long'):
            _, base, patch = fixture()
            if mode == 'approval': patch['humanApproved'] = True
            if mode == 'hash': patch['previousContextHash'] = 'changed'
            if mode == 'extra': patch['flow'] = ['may not change']
            if mode == 'long': patch['criticismHypotheses'] = ['x' * 351]
            with self.subTest(mode=mode), self.assertRaises(ValueError): revise(base, patch)

    def test_preview_cannot_execute_and_preserves_rules_raw_and_scope(self):
        frozen, base, patch = fixture(); original = copy.deepcopy(frozen)
        _, preview = prepare(frozen, base, patch)
        self.assertEqual(frozen, original)
        self.assertFalse(preview['executionEnabled'])
        self.assertEqual(preview['apiCalls'], 0)
        self.assertEqual(preview['consumedReservationUsd'], '0')
        for i in range(0, len(preview['units']), 2):
            a, b = preview['units'][i:i+2]
            self.assertEqual(a['body']['messages'][1:], b['body']['messages'][1:])
            self.assertEqual(a['sourceScopeHash'], b['sourceScopeHash'])
            refs=[]
            for u in (a,b):
                prefix, tail = u['body']['messages'][0]['content'].split('contextReference=',1)
                ref, end = json.JSONDecoder().raw_decode(tail)
                refs.append((prefix, tail[end:], ref['reviewGuidelines']))
            self.assertEqual(refs[0], refs[1])

    def test_changed_frozen_or_archive_refused(self):
        for mode in ('source', 'archive'):
            frozen, base, patch = fixture()
            if mode == 'source': frozen['jobs'][0]['fixedSelectedScope']['issueSummary'] = 'changed'
            else: base['guidelines'][0]['condition'] = 'changed'
            with self.subTest(mode=mode), self.assertRaises(ValueError): prepare(frozen, base, patch)
