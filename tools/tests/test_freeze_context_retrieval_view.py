import copy
import hashlib
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from freeze_context_retrieval_view import freeze
from build_review_guidelines import prompt_rules
from test_reference_split_comparison import inputs as source_inputs


def inputs():
    values=source_inputs()
    for e in values[1]['examples']: e['context']['criticismHypotheses']=[]
    return values


class FrozenRetrievalTests(unittest.TestCase):
    def test_migration_is_pure_and_keeps_rules_and_judgment_context(self):
        _, base, _ = inputs(); original = copy.deepcopy(base)
        updated, meta = freeze(base)
        self.assertEqual(base, original)
        self.assertEqual(updated['schemaVersion'], 'review-guidelines-5')
        self.assertEqual(prompt_rules(base,'SPEECH'), prompt_rules(updated,'SPEECH'))
        for old, new in zip(base['examples'],updated['examples']):
            self.assertEqual(old['context'], new['context'])
            text=new['retrievalView']['text']
            self.assertEqual(new['retrievalView']['sha256'],hashlib.sha256(text.encode()).hexdigest())
        self.assertTrue(meta['legacyInterpretationsRetainedForCompatibility'])
        self.assertFalse(meta['sourceOnlyIndex'])
        self.assertFalse(meta['defaultRuntimeChanged'])

    def test_existing_view_unknown_mechanism_and_approved_source_refused(self):
        for mode in ('view','mechanism','approval','schema'):
            _, base, _ = inputs()
            if mode=='view': base['examples'][0]['retrievalView']=None
            if mode=='mechanism': base['examples'][0]['mechanismIds']=['missing']
            if mode=='approval': base['humanValidated']=True
            if mode=='schema': base['schemaVersion']='review-guidelines-5'
            with self.subTest(mode=mode),self.assertRaises(ValueError): freeze(base)

    def test_exact_legacy_join_and_duplicate_ids_refused(self):
        _, base, _ = inputs(); updated,_=freeze(base)
        e=base['examples'][0]
        self.assertEqual(updated['examples'][0]['retrievalView']['text'],
            ' '.join(e['context']['flow'])+' '+' '.join(e['context'].get('criticismHypotheses',[]))
            +'  '+base['guidelines'][0]['condition'])
        base['examples'].append(copy.deepcopy(e))
        with self.assertRaises(ValueError): freeze(base)
