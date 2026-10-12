import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from diagnose_candidate_output import diagnose


class DiagnosticsTests(unittest.TestCase):
    def test_missing_target_is_wire_specific_and_rejected_wire_is_not_lost(self):
        from diagnose_candidate_output import report_diagnostics
        a = {'decision':'REVIEW_REQUIRED','target':'place','evidence':[{'segmentId':'a','quote':'text','role':'PRIMARY'}]}
        raw = [{'id':'a','text':'text'}]
        self.assertEqual([],diagnose(a,raw)['defects'])
        self.assertEqual(['WIRE_SINGLE_TARGET_REQUIRED'],diagnose(a,raw,wire=True)['defects'])
        report = {'cases':[{'name':'B','payload':{'raw':raw}}], 'calls':[{
            'case':'B','arm':'CANDIDATE','repeat':1,'outputAdapter':'single-target-evidence-wire-1',
            'wireOutput':{'verifications':[{'candidateId':'c','assessment':a}]}}]}
        result = report_diagnostics(report)
        self.assertEqual('wireOutput',result['results'][0]['outputSource'])
        self.assertEqual(['WIRE_SINGLE_TARGET_REQUIRED'],result['results'][0]['defects'])
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


class SemanticPacketTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import tempfile
        from test_prepare_evidence_selection_trial import setup
        from prepare_evidence_selection_trial import prepare
        from compare_recorded_candidates import execute,audit_export
        from compare_review_prompts import digest
        from test_compare_recorded_candidates import response
        cls.frozen = prepare(*setup())
        with tempfile.TemporaryDirectory() as tmp:
            cls.report = execute(cls.frozen,Path(tmp)/'datasets'/'run',None,response, simulate=True)
        cls.audit = {'sourceReportSha256':digest(audit_export(cls.report)),
                     'results':[{'case':c['case'],'arm':c['arm'],'repeat':c['repeat'],
                                 'accepted':False,'failureCode':'TEST_STUB_ONLY'} for c in cls.report['calls']]}

    def test_keeps_all_cases_responses_and_blank_semantic_labels(self):
        from diagnose_candidate_output import semantic_packet
        original = copy.deepcopy((self.frozen,self.report,self.audit))
        packet = semantic_packet(self.frozen,self.report,self.audit)
        self.assertEqual(original,(self.frozen,self.report,self.audit))
        self.assertEqual(42,len(packet['worksheet']))
        self.assertEqual(7,len(packet['cases']))
        self.assertTrue(all(r['review']['semanticDecision'] is None for r in packet['worksheet']))
        self.assertEqual(0,packet['independentGoldApprovals'])
        self.assertFalse(packet['apiExecutionEnabled'])
        self.assertTrue(any(r['responseSource']=='wireOutput' for r in packet['worksheet']))

    def test_stale_source_missing_response_and_duplicate_audit_fail_closed(self):
        from diagnose_candidate_output import semantic_packet
        for mode in ('frozen','audit','call','duplicate','adapter'):
            frozen,report,audit = copy.deepcopy((self.frozen,self.report,self.audit))
            if mode == 'frozen': frozen['cases'][0]['name'] = 'changed'
            if mode == 'audit': audit['sourceReportSha256'] = 'wrong'
            if mode == 'call': report['calls'].pop()
            if mode == 'duplicate': audit['results'][-1] = copy.deepcopy(audit['results'][0])
            if mode == 'adapter': report['calls'][0]['outputAdapter'] = 'changed'
            with self.subTest(mode=mode),self.assertRaises(ValueError): semantic_packet(frozen,report,audit)

    def notes(self):
        from diagnose_candidate_output import semantic_packet
        from fixed_review_suite import digest
        packet = semantic_packet(self.frozen,self.report,self.audit)
        cases = {c['name']:c['payload'] for c in packet['cases']}
        reviews = {}
        for name in ('B','C'):
            row = cases[name]['raw'][0]
            reviews[name] = {'normalExplanation':'normal','criticismExplanation':'criticism',
                             'semanticDecision':None,'evaluationLinkQuotes':[{'segmentId':row['id'],'quote':row['text']}],
                             'targetGroundingQuotes':[]}
        notes = {'schemaVersion':'assistant-semantic-notes-1','sourcePacketHash':digest(packet),
                 'reviewerType':'ASSISTANT','scope':['B','C'],'caseReviews':reviews,
                 'entries':[{'case':w['case'],'arm':w['arm'],'repeat':w['repeat'],'review':{'note':'observation'}}
                            for w in packet['worksheet'] if w['case'] in {'B','C'}]}
        return packet,notes

    def test_attaches_twelve_observations_without_editing_responses_or_other_reviews(self):
        from diagnose_candidate_output import attach_observations
        packet,notes = self.notes()
        original = copy.deepcopy(packet)
        result = attach_observations(packet,notes)
        self.assertEqual(packet,original)
        self.assertEqual(result['assistantObservedResponses'],12)
        for before,after in zip(packet['worksheet'],result['worksheet']):
            self.assertEqual(before['savedAssessment'],after['savedAssessment'])
            self.assertEqual(before['contractAudit'],after['contractAudit'])
            if before['case'] not in {'B','C'}: self.assertEqual(before,after)
        self.assertEqual(result['independentGoldApprovals'],0)
        self.assertIsNone(result['qualityGateMet'])

    def test_cannot_attach_fabricated_quotes_assign_gold_or_rewrite_assessments(self):
        from diagnose_candidate_output import attach_observations
        for mode in ('quote','decision','rewrite','duplicate','hash','reviewer'):
            packet,notes = self.notes()
            if mode=='quote': notes['caseReviews']['B']['evaluationLinkQuotes'][0]['quote']='not in raw'
            if mode=='decision': notes['caseReviews']['B']['semanticDecision']='REVIEW_REQUIRED'
            if mode=='rewrite': notes['entries'][0]['review']['savedAssessment']={}
            if mode=='duplicate': notes['entries'][-1]=copy.deepcopy(notes['entries'][0])
            if mode=='hash': notes['sourcePacketHash']='wrong'
            if mode=='reviewer': notes['reviewerType']='HUMAN'
            with self.subTest(mode=mode),self.assertRaises(ValueError): attach_observations(packet,notes)
