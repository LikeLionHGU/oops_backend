import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from decimal import Decimal
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from test_prepare_target_wire_trial import inputs
from test_compare_recorded_candidates import response
from prepare_target_wire_trial import prepare as source_prepare
from prepare_evidence_selection_trial import prepare
from compare_recorded_candidates import execute
from fixed_review_suite import digest


def setup():
    args = inputs()
    source = source_prepare(*args)
    prior = dict(artifactHash=source['artifactHash'],reservedCostUsd='0.1829466',
                 status='COMPLETED_PENDING_JAVA_AUDIT',calls=[{}]*42)
    return source,prior,args[3]


class BudgetTests(unittest.TestCase):
    def test_debits_prior_reservation_and_preserves_inputs(self):
        args = setup()
        original = copy.deepcopy(args)
        artifact = prepare(*args)
        self.assertEqual(args,original)
        self.assertEqual(Decimal(artifact['remainingBudgetUsd']),Decimal('0.3170534'))
        self.assertLessEqual(Decimal(artifact['reservedTotalCostUsd']),Decimal('0.3170534'))
        for a,b in zip(artifact['requests'][::2],artifact['requests'][1::2]):
            self.assertEqual(a['body']['messages'][1],b['body']['messages'][1])
        with tempfile.TemporaryDirectory() as tmp:
            result = execute(artifact,Path(tmp)/'datasets'/'run','fake',response)
        self.assertEqual(len(result['calls']),42)
        self.assertEqual(result['status'],'COMPLETED_PENDING_JAVA_AUDIT')
        # Legacy fake responses are measured as rejected, never silently repaired.
        self.assertTrue(all(c['status'] == 'PYTHON_WIRE_REJECTED' for c in result['calls'] if c['arm'] == 'CANDIDATE'))

    def test_invalid_ledger_fails_before_transport(self):
        for mode in ('reset','source','prior'):
            args = list(setup())
            if mode == 'source': args[0]['cases'][0]['name'] = 'changed'
            if mode == 'prior': args[1]['reservedCostUsd'] = '0'
            if mode != 'reset':
                with self.assertRaises(ValueError): prepare(*args)
                continue
            artifact = prepare(*args)
            artifact['remainingBudgetUsd'] = '.5'
            artifact['artifactHash'] = digest({k:v for k,v in artifact.items() if k != 'artifactHash'})
            with tempfile.TemporaryDirectory() as tmp, self.assertRaises(ValueError):
                execute(artifact,Path(tmp)/'datasets'/'run','fake',lambda *args:self.fail('paid call'))
