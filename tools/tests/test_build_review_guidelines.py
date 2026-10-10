import copy
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import build_review_guidelines as tool
from test_controversy_cards import fixture


class GuidelineCompilerTests(unittest.TestCase):
    def setUp(self):
        self.bundle = fixture()[0]
        self.bundle["collections"][0]["refreshOrDeleteBy"] = "2099-01-01T00:00:00+00:00"
        self.plan = {"schemaVersion":"review-guideline-plan-1","status":"WORKING_REFERENCE_NOT_VALIDATED",
                     "humanValidated":False,"version":"synthetic-v1","mechanisms":[
                         {"id":"mechanism","axis":"TARGET_TREATMENT","channels":["SPEECH","CAPTION"],
                          "sourceCaseIds":["synthetic-card"],"condition":"합성 연결 조건","normalContrast":"합성 정상 리뷰",
                          "requiredEvidence":"현재 원문 인용","missingContext":"대상 미확인"}]}

    def test_compilation_is_reference_not_approval_and_preserves_sources(self):
        before = copy.deepcopy(self.bundle)
        result = tool.compile_guidelines(self.bundle,self.plan,b"synthetic-source")
        self.assertEqual(before,self.bundle)
        self.assertFalse(result["humanValidated"])
        self.assertEqual("UNREVIEWED_DRAFT",result["sourceCases"][0]["status"])
        self.assertEqual("synthetic-card",result["guidelines"][0]["sourceCaseIds"][0])
        self.assertNotIn("reactions",result)

    def test_new_cards_require_explicit_mapping(self):
        card = copy.deepcopy(self.bundle["incidents"][0]["cards"][0]);card["id"]="new-card"
        self.bundle["incidents"][0]["cards"].append(card)
        with self.assertRaisesRegex(tool.PilotError,"NEW_CARDS_REQUIRE_EXPLICIT_MECHANISM_MAPPING"):
            tool.compile_guidelines(self.bundle,self.plan,b"source")
        self.plan["mechanisms"][0]["sourceCaseIds"].append("new-card")
        self.assertEqual(2,len(tool.compile_guidelines(self.bundle,self.plan,b"source")["sourceCases"]))

    def test_unsafe_approval_unknown_refs_and_overlong_rules_rejected(self):
        for field,value in (("sourceCaseIds",["absent"]),("condition","가"*351),("channels",["ANY"]),("axis","KEYWORD")):
            plan=copy.deepcopy(self.plan);plan["mechanisms"][0][field]=value
            with self.assertRaises(tool.PilotError):tool.compile_guidelines(self.bundle,plan,b"source")
        self.plan["humanValidated"]=True
        with self.assertRaises(tool.PilotError):tool.compile_guidelines(self.bundle,self.plan,b"source")

    def test_atomic_private_output_and_symlink_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"runtime.json"
            tool.write_private(path,{"version":"first"});tool.write_private(path,{"version":"second"})
            self.assertEqual(0o600,path.stat().st_mode & 0o777)
            self.assertEqual("second",tool.parse(path.read_bytes())["version"])
            link=Path(directory)/"link.json";link.symlink_to(path)
            with self.assertRaises(tool.PilotError):tool.write_private(link,{})

    def test_comparison_keeps_source_limits_without_raw_comments_or_ids(self):
        result = tool.compile_guidelines(self.bundle, self.plan, b"source")
        self.assertEqual("review-guidelines-2", result["schemaVersion"])
        reference = result["guidelines"][0]["referenceContexts"][0]
        self.assertEqual(["Synthetic known point"], reference["flow"])
        self.assertEqual(["Original unavailable"], reference["missingContext"])
        self.assertEqual(["Synthetic interpretation"], reference["criticismHypotheses"])
        payload = str(tool.prompt_rules(result, "SPEECH"))
        for private in ("synthetic-card", "synthetic-family", "sourceCaseId", "가상 반응", "https://"):
            self.assertNotIn(private, payload)

    def test_incident_counter_and_post_response_do_not_become_example_criticism(self):
        card = self.bundle["incidents"][0]["cards"][0]
        card["reactions"][0]["mapping"]["scope"] = "INCIDENT_ONLY"
        result = tool.compile_guidelines(self.bundle, self.plan, b"source")
        self.assertEqual([], result["guidelines"][0]["referenceContexts"][0]["criticismHypotheses"])

    def test_sequence_cannot_invent_source_segments(self):
        card = self.bundle["incidents"][0]["cards"][0]
        card["context"]["sequenceInterpretations"] = [{"text": "연결 해석", "segmentIds": ["absent"],
                                                      "status": "ASSISTANT_DRAFT_NOT_VIDEO_FACT"}]
        with self.assertRaisesRegex(tool.PilotError, "REFERENCE_FLOW_SOURCE_REQUIRED"):
            tool.compile_guidelines(self.bundle, self.plan, b"source")

    def test_dictionary_connection_preserves_all_content_reasons_not_background(self):
        import build_context_dictionary as dictionary_tool
        plan = {"schemaVersion": "context-dictionary-plan-1", "status": "ASSISTANT_DRAFT", "humanApproved": False,
                "cards": {"synthetic-card": {"topicTags": ["합성"], "mechanismIds": ["mechanism"], "documents": []}}}
        dictionary = dictionary_tool.compile_dictionary(self.bundle, plan, self.plan, b"source")
        units = dictionary["entries"][0]["audienceReception"]["pointReasonUnits"]
        for index in range(3):
            unit = copy.deepcopy(units[0]); unit["interpretation"]["reason"] = f"독립 비판 {index}"
            units.append(unit)
        result = tool.compile_guidelines(self.bundle, self.plan, b"source", dictionary)
        self.assertEqual("review-guidelines-4", result["schemaVersion"])
        self.assertNotIn("referenceContexts", result["guidelines"][0])
        ref = result["examples"][0]["context"]
        self.assertEqual(4, len(ref["criticismHypotheses"]))
        self.assertEqual([], ref["sourceInterpretations"])
        self.assertEqual(64, len(result["contextDictionarySha256"]))
        for private in ("counterReferenceOnly", "incidentOrOtherReactions", "rawExcerptLocation", "sourceCaseId"):
            self.assertNotIn(private, str(tool.prompt_rules(result, "SPEECH")))
        self.assertNotIn("독립 비판", str(tool.prompt_rules(result, "SPEECH")))
        self.assertEqual(["mechanism"], result["examples"][0]["mechanismIds"])
        dictionary["sourceBundleSha256"] = "0" * 64
        with self.assertRaisesRegex(tool.PilotError, "DICTIONARY_PROVENANCE_REQUIRED"):
            tool.compile_guidelines(self.bundle, self.plan, b"source", dictionary)

    def test_more_cases_do_not_expand_the_common_pattern_prompt(self):
        import build_context_dictionary as dictionary_tool
        import hashlib
        plan = {"schemaVersion": "context-dictionary-plan-1", "status": "ASSISTANT_DRAFT", "humanApproved": False,
                "cards": {"synthetic-card": {"topicTags": ["합성"], "mechanismIds": ["mechanism"], "documents": []}}}
        dictionary = dictionary_tool.compile_dictionary(self.bundle, plan, self.plan, b"source")
        first = tool.compile_guidelines(self.bundle, self.plan, b"source", dictionary)
        for i in range(100):
            cid = f"synthetic-extra-{i}"
            card = copy.deepcopy(self.bundle["incidents"][0]["cards"][0]); card["id"] = cid
            self.bundle["incidents"][0]["cards"].append(card)
            self.plan["mechanisms"][0]["sourceCaseIds"].append(cid)
            entry = copy.deepcopy(dictionary["entries"][0]); entry["id"] = cid
            dictionary["entries"].append(entry)
        dictionary["guidelinePlanSha256"] = hashlib.sha256(
            tool.json.dumps(self.plan, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        expanded = tool.compile_guidelines(self.bundle, self.plan, b"source", dictionary)
        self.assertEqual(101, len(expanded["examples"]))
        self.assertEqual(tool.prompt_rules(first, "SPEECH"), tool.prompt_rules(expanded, "SPEECH"))


if __name__=="__main__":unittest.main()
