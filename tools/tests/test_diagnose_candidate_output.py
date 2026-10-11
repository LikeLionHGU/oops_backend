import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from diagnose_candidate_output import diagnose


class DiagnosticsTests(unittest.TestCase):
    def test_coexisting_budget_and_wrong_row_not_confused_with_other_type(self):
        a = {'targetType':'OTHER','evidence':[{'segmentId':'a','quote':'여기는','role':'TARGET'}]*9}
        original = copy.deepcopy(a)
        result = diagnose(a,[{'id':'a','text':'메뉴가 의미가 없어'},{'id':'b','text':'여기는'}])
        self.assertEqual(['EVIDENCE_OBJECT_BUDGET_EXCEEDED','QUOTE_NOT_IN_OWN_RAW_ROW'],result['defects'])
        self.assertEqual(list(range(9)),result['quoteMismatches'])
        self.assertEqual(original,a)

    def test_interpreted_label_requires_contextual_relation(self):
        a = {'target':'해당 장소의 메뉴','targetMention':'메뉴','targetRelation':'EXPLICIT',
             'evidence':[{'segmentId':'a','quote':'메뉴','role':'TARGET'}]}
        raw = [{'id':'a','text':'메뉴가 의미가 없어'}]
        self.assertEqual(['RESOLVED_TARGET_REQUIRES_CONTEXTUAL_RELATION'],diagnose(a,raw)['defects'])
        a['targetRelation'] = 'CONTEXTUAL'
        self.assertEqual([],diagnose(a,raw)['defects'])
        # An empty supplemental defect list is NOT a full Java contract approval.

    def test_other_type_and_identical_explicit_label_are_not_defects(self):
        a = {'target':'메뉴','targetMention':'메뉴','targetRelation':'EXPLICIT','targetType':'OTHER',
             'evidence':[{'segmentId':'a','quote':'메뉴','role':'TARGET'}]}
        self.assertEqual([],diagnose(a,[{'id':'a','text':'메뉴'}])['defects'])
