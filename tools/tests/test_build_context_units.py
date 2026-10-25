"""Synthetic context units only; no real incident labels or network calls."""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_context_units import compile_preview


class ContextUnitTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        raw = json.dumps({"tasks": [{"spec": {"id": "source"}, "input": json.dumps({"raw": [
            {"id": "s1", "text": "평가 표현", "startMs": 0, "endMs": 1000},
            {"id": "s2", "text": "후속 상황", "startMs": 1000, "endMs": 2000}]})}]}).encode()
        (self.root / "source.json").write_bytes(raw)
        self.unit = {"schemaVersion": "context-reference-unit-1", "id": "incident#unit", "parentCaseId": "old",
                     "familyId": "incident", "status": "DRAFT", "humanVerified": False, "trainingUseAuthorized": False,
                     "routingKeys": ["graphic-body-metaphor"], "retrievalText": "검색용 고정 문구",
                     "interpretation": "작성한 해석", "boundary": "원음 검수 전",
                     "source": {"kind": "SAVED_VERIFICATION_PLAN", "path": "source.json",
                                "sha256": hashlib.sha256(raw).hexdigest(), "taskId": "source"},
                     "utteranceIds": ["s1", "s2"], "roles": {"situationIds": ["s2"], "evaluationIds": ["s1"]},
                     "normalContrasts": [{"kind": "SYNTHETIC_DEVELOPMENT_CONTROL", "text": "합성 정상 대조"}]}
        context = {"coverage": "REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT", "flow": ["기존 사건 요약"],
                   "criticismHypotheses": [], "missingContext": ["출처 검수 전"], "sourceInterpretations": []}
        self.base = {"schemaVersion": "review-guidelines-4", "examples": [
            {"id": "old", "familyId": "incident", "mechanismIds": ["broad"], "channels": ["SPEECH"], "context": context},
            {"id": "retained", "familyId": "other", "mechanismIds": ["broad"], "channels": ["SPEECH"], "context": context}],
            "guidelines": [{"id": "broad", "condition": "기존 판단 조건", "sourceCaseIds": ["old", "retained"]}]}
        self.catalog = {"schemaVersion": "context-routing-keys-1", "keys": [{"id": "graphic-body-metaphor"}]}

    def compile(self, unit=None):
        return compile_preview(self.base, [unit or self.unit], self.catalog, self.root)

    def standalone(self, decision="PASS"):
        approval_doc = {"schemaVersion": "development-human-review-1", "reviews": [
            {"eventId": "approved-unit", "anchorIds": ["s1"], "decision": decision,
             "scope": "합성 테스트 범위", "userReply": "승인", "sourceSha256": "a" * 64,
             "videoId": 1, "segmentIds": ["s1", "s2"]}]}
        approval_raw = json.dumps(approval_doc).encode()
        (self.root / "approval.json").write_bytes(approval_raw)
        source = {"schemaVersion": "context-transcript-source-1", "familyId": "development-family",
                  "sourceGroupId": "synthetic-video-group",
                  "origin": {"sha256": "a" * 64, "videoId": 1},
                  "audioVerified": False, "developmentReview": {
                      "decision": decision, "basis": "USER_APPROVED_DEVELOPMENT_BOUNDARY", "scope": "제공 문맥 승인",
                      "approvalRecord": {"path": "approval.json",
                                         "sha256": hashlib.sha256(approval_raw).hexdigest(),
                                         "eventIds": ["approved-unit"]}},
                  "segments": [{"id": "s1", "text": "평가 표현"}, {"id": "s2", "text": "후속 상황"}]}
        raw = json.dumps(source).encode()
        (self.root / "source.json").write_bytes(raw)
        unit = copy.deepcopy(self.unit)
        unit.update(schemaVersion="context-reference-unit-3", familyId="development-family",
                    referencePurpose="DEVELOPMENT_BOUNDARY_NOT_PUBLIC_CONTROVERSY",
                    developmentDecision=decision, mechanismIds=["broad"])
        unit.pop("parentCaseId")
        unit["source"] = {"kind": "STORED_TRANSCRIPT_SNAPSHOT", "path": "source.json",
                          "sha256": hashlib.sha256(raw).hexdigest()}
        self.base["guidelines"][0]["channels"] = ["SPEECH"]
        return unit

    def test_standalone_rejects_missing_or_out_of_scope_approval_record(self):
        unit = self.standalone()
        source = json.loads((self.root / "source.json").read_bytes())
        source["developmentReview"].pop("approvalRecord")
        raw = json.dumps(source).encode()
        (self.root / "source.json").write_bytes(raw)
        unit["source"]["sha256"] = hashlib.sha256(raw).hexdigest()
        with self.assertRaisesRegex(ValueError, "UNIT_APPROVAL_RECORD_REQUIRED"):
            self.compile(unit)

        unit = self.standalone()
        source = json.loads((self.root / "source.json").read_bytes())
        source["developmentReview"]["approvalRecord"]["eventIds"] = ["not-approved"]
        raw = json.dumps(source).encode()
        (self.root / "source.json").write_bytes(raw)
        unit["source"]["sha256"] = hashlib.sha256(raw).hexdigest()
        with self.assertRaisesRegex(ValueError, "UNIQUE_APPROVAL_REQUIRED"):
            self.compile(unit)

    def test_standalone_normal_boundary_does_not_invent_or_replace_an_incident(self):
        unit = self.standalone()
        out, manifest = self.compile(unit)
        example = out["examples"][0]
        self.assertEqual(manifest["replacedExamples"], [])
        self.assertEqual(len(out["examples"]), 3)
        self.assertEqual(example["mechanismIds"], ["broad"])
        self.assertEqual(example["context"]["criticismHypotheses"], [])
        self.assertIn("사용자 승인 개발 정상 경계", example["context"]["flow"][-1])
        self.assertTrue(all(s.startswith("합성 개발 대조") for s in example["context"]["normalContrasts"]))
        self.assertFalse(unit["humanVerified"])
        self.assertFalse(unit["trainingUseAuthorized"])

    def test_v2_transcript_boundary_still_requires_decision_and_scope_consistency(self):
        unit = self.standalone()
        unit["schemaVersion"] = "context-reference-unit-2"
        source = json.loads((self.root / "source.json").read_bytes())
        source["developmentReview"]["decision"] = "REVIEW_REQUIRED"
        raw = json.dumps(source).encode()
        (self.root / "source.json").write_bytes(raw)
        unit["source"]["sha256"] = hashlib.sha256(raw).hexdigest()
        with self.assertRaisesRegex(ValueError, "UNIT_DEVELOPMENT_APPROVAL_REQUIRED"):
            self.compile(unit)

    def ocr_unit(self):
        unit = self.standalone()
        unit["schemaVersion"] = "context-reference-unit-2"
        (self.root / "frame.jpg").write_bytes(b"synthetic frame fixture")
        rows = [{"text": "그림 생성 조건", "startMs": 10, "endMs": 20, "frameUrl": "/synthetic/frame"},
                {"text": "대학생 외모로 표현", "startMs": 10, "endMs": 20, "frameUrl": "/synthetic/frame"}]
        snapshot = json.dumps({"data": rows}).encode()
        (self.root / "ocr.json").write_bytes(snapshot)
        source = {"schemaVersion": "context-ocr-frame-source-1", "familyId": unit["familyId"],
                  "userLabelApproved": False, "developmentReview": {"decision": "PASS",
                  "basis": "DEVELOPER_FRAME_REVIEW_NOT_USER_LABEL", "scope": "프레임 검토 초안"},
                  "frame": {"path": "frame.jpg", "sha256": hashlib.sha256(b"synthetic frame fixture").hexdigest(),
                            "url": "/synthetic/frame"},
                  "ocrSnapshot": {"path": "ocr.json", "sha256": hashlib.sha256(snapshot).hexdigest()},
                  "segments": [{"id": "s" + str(i + 1), "sourceIndex": i,
                                **{k: row[k] for k in ("text", "startMs", "endMs")}} for i, row in enumerate(rows)]}
        raw = json.dumps(source).encode()
        (self.root / "source.json").write_bytes(raw)
        unit["source"].update(kind="STORED_OCR_FRAME_SNAPSHOT", sha256=hashlib.sha256(raw).hexdigest())
        self.base["guidelines"][0]["channels"] = ["SPEECH", "CAPTION"]
        return unit, source

    def test_ocr_boundary_keeps_draft_provenance_and_caption_only_channel(self):
        unit, _ = self.ocr_unit()
        out, _ = self.compile(unit)
        example = out["examples"][0]
        self.assertEqual(example["channels"], ["CAPTION"])
        self.assertIn("사용자 정답 미승인", example["context"]["flow"][-1])
        self.assertNotIn("사용자 승인", json.dumps(example, ensure_ascii=False))
        self.assertEqual(example["context"]["criticismHypotheses"], [])

    def test_ocr_boundary_rejects_raw_correction_label_promotion_and_wrong_frame(self):
        for change in ("raw", "approval", "frame", "index"):
            unit, source = self.ocr_unit()
            if change == "raw": source["segments"][0]["text"] = "추측 교정 원문"
            if change == "approval": source["userLabelApproved"] = True
            if change == "frame": source["frame"]["url"] = "/other/frame"
            if change == "index": source["segments"][0]["sourceIndex"] = -1
            raw = json.dumps(source).encode()
            (self.root / "source.json").write_bytes(raw)
            unit["source"]["sha256"] = hashlib.sha256(raw).hexdigest()
            with self.subTest(change=change), self.assertRaises(ValueError): self.compile(unit)

    def test_ocr_boundary_rejects_changed_artifact_and_wrong_channel(self):
        unit, _ = self.ocr_unit()
        (self.root / "frame.jpg").write_bytes(b"changed")
        with self.assertRaises(ValueError): self.compile(unit)
        unit, _ = self.ocr_unit()
        self.base["guidelines"][0]["channels"] = ["SPEECH"]
        with self.assertRaises(ValueError): self.compile(unit)

    def test_standalone_review_boundary_keeps_current_input_requirement(self):
        out, _ = self.compile(self.standalone("REVIEW_REQUIRED"))
        self.assertIn("사용자 승인 개발 검토 경계", out["examples"][0]["context"]["criticismHypotheses"][0])
        self.assertIn("공개 논란 사건이 아닌", out["examples"][0]["context"]["missingContext"][0])

    def test_user_script_provenance_is_not_presented_as_verified_stt(self):
        unit = self.standalone()
        before, _ = self.compile(unit)
        source = json.loads((self.root / "source.json").read_bytes())
        source["sourceKind"] = "USER_SUPPLIED_SCRIPT_NOT_AUDIO_VERIFIED"
        raw = json.dumps(source).encode()
        (self.root / "source.json").write_bytes(raw)
        unit["source"]["sha256"] = hashlib.sha256(raw).hexdigest()
        after, _ = self.compile(unit)
        self.assertIn("발췌 STT", before["examples"][0]["context"]["missingContext"][-1])
        self.assertIn("사용자 제공 자막 스크립트", after["examples"][0]["context"]["missingContext"][-1])
        self.assertEqual(before["examples"][0]["context"]["flow"], after["examples"][0]["context"]["flow"])
        self.assertNotIn("발췌 STT", after["examples"][0]["context"]["missingContext"][-1])

    def test_standalone_rejects_unknown_pattern_or_approval_mismatch(self):
        for field, value in [("mechanismIds", ["unknown"]), ("developmentDecision", "REVIEW_REQUIRED"),
                             ("referencePurpose", "PUBLIC_CONTROVERSY"), ("parentCaseId", "old"),
                             ("familyId", "incident")]:
            unit = self.standalone()
            unit[field] = value
            with self.assertRaises(ValueError):
                self.compile(unit)

    def test_order_preserves_raw_then_later_context_not_role_order(self):
        out, manifest = self.compile()
        self.assertEqual(out["examples"][0]["context"]["flow"], ["평가 원문: 평가 표현", "상황 원문: 후속 상황"])
        self.assertEqual(out["examples"][0]["context"]["normalContrasts"], ["합성 개발 대조(실제 관측 아님): 합성 정상 대조"])
        self.assertEqual(manifest["sourceLinks"][0]["utterances"][0]["startMs"], 0)
        self.assertFalse(manifest["activeRuntimeChanged"])

    def test_original_inputs_remain_identical_and_unconverted_examples_stay_visible(self):
        before = copy.deepcopy(self.base)
        out, manifest = self.compile()
        self.assertEqual(self.base, before)
        self.assertEqual(manifest["retainedLegacyCount"], 1)
        self.assertEqual(out["examples"][1]["routingKeys"], [])
        self.assertEqual(out["examples"][1]["context"]["flow"], ["기존 사건 요약"])

    def test_judgment_edits_do_not_change_authored_retrieval_text(self):
        first, _ = self.compile()
        self.unit["interpretation"] = "수정한 판단 해석"
        self.base["guidelines"][0]["condition"] = "다른 기준 설명"
        second, _ = self.compile()
        self.assertEqual(first["examples"][0]["retrievalView"], second["examples"][0]["retrievalView"])

    def test_unknown_route_duplicate_and_missing_source_are_rejected(self):
        for change in ({"routingKeys": ["unknown"]}, {"utteranceIds": ["s1", "unknown"]},
                       {"utteranceIds": ["s1", "s1"]}, {"utteranceIds": ["s2", "s1"]},
                       {"roles": {"situationIds": [], "evaluationIds": []}}):
            unit = {**copy.deepcopy(self.unit), **change}
            with self.assertRaises(ValueError):
                self.compile(unit)
        with self.assertRaises(ValueError):
            compile_preview(self.base, [self.unit, self.unit], self.catalog, self.root)

    def test_source_mutation_and_control_promotion_are_rejected(self):
        unit = copy.deepcopy(self.unit)
        unit["normalContrasts"][0]["kind"] = "OBSERVED_APPROVED_VIDEO"
        with self.assertRaises(ValueError): self.compile(unit)
        (self.root / "source.json").write_text("{}")
        with self.assertRaises(ValueError): self.compile()

    def test_approved_observed_pass_does_not_require_invented_synthetic_dialogue(self):
        unit = self.standalone("PASS")
        unit["normalContrasts"] = []
        out, _ = self.compile(unit)
        example = out["examples"][0]
        self.assertEqual(example["context"]["normalContrasts"], [])
        self.assertEqual(example["context"]["criticismHypotheses"], [])
        self.assertIn("정상 경계", example["context"]["flow"][-1])
        unit = self.standalone("REVIEW_REQUIRED")
        unit["normalContrasts"] = []
        with self.assertRaisesRegex(ValueError, "UNIT_CONTROL_PROVENANCE_REQUIRED"):
            self.compile(unit)

    def test_linked_approved_control_is_verbatim_and_not_a_model_label(self):
        doc = {"status": "USER_APPROVED_DEVELOPMENT_BOUNDARIES", "trainingUseAuthorized": False,
               "normalDrafts": [{"id": "normal", "humanApproved": True, "expectedDecision": "PASS",
                                 "sourceKind": "SYNTHETIC_DEVELOPMENT_CONTROL",
                                 "raw": [{"text": "정상 독려"}, {"text": "이어진 설명"}]}]}
        def save():
            raw = json.dumps(doc).encode()
            (self.root / "control.json").write_bytes(raw)
            self.unit["normalContrasts"][0]["approvalSource"]["sha256"] = hashlib.sha256(raw).hexdigest()
        control = self.unit["normalContrasts"][0]
        control.update(text="정상 독려 이어진 설명", approvalSource={"path": "control.json", "sha256": "", "caseId": "normal"})
        save()
        runtime, _ = self.compile()
        self.assertNotIn("approvalSource", json.dumps(runtime))
        self.assertIn("정상 독려 이어진 설명", runtime["examples"][0]["context"]["normalContrasts"][0])
        control["text"] = "교정한 대사"
        with self.assertRaisesRegex(ValueError, "CONTROL_TEXT_MISMATCH"): self.compile()
        control["text"] = "정상 독려 이어진 설명"
        doc["normalDrafts"][0]["humanApproved"] = False
        save()
        with self.assertRaisesRegex(ValueError, "CONTROL_APPROVAL_REQUIRED"): self.compile()
        doc["normalDrafts"][0]["humanApproved"] = True
        save()
        (self.root / "control.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "CONTROL_SOURCE_HASH_MISMATCH"): self.compile()

    def test_null_source_times_are_not_replaced_with_synthetic_timing(self):
        incident = {"incidentId": "incident", "statements": [{"id": "old", "legacyCard": {"evidence": {
            "segments": [{"id": "s1", "text": "평가 표현", "startMs": None, "endMs": None},
                         {"id": "s2", "text": "후속 상황", "startMs": None, "endMs": None}]}}}]}
        raw = json.dumps(incident).encode()
        (self.root / "source.json").write_bytes(raw)
        self.unit["source"].update(kind="CANONICAL_STATEMENT", sha256=hashlib.sha256(raw).hexdigest())
        _, manifest = self.compile()
        self.assertIsNone(manifest["sourceLinks"][0]["utterances"][0]["startMs"])

    def profile(self):
        return {"schemaVersion": "context-relation-profile-1", "unitId": self.unit["id"],
                "status": "DRAFT", "humanVerified": False, "trainingUseAuthorized": False,
                "sourceSha256": self.unit["source"]["sha256"], "utteranceIds": self.unit["utteranceIds"][:],
                "situation": "작성한 상황 검색 설명", "target": "", "evaluation": "작성한 평가 검색 설명",
                "linkage": "작성한 연결 검색 설명"}

    def test_opt_in_profiles_change_only_search_metadata_not_quotes_or_approval(self):
        before = copy.deepcopy((self.base, self.unit))
        old, _ = self.compile()
        profile = self.profile()
        new, _ = compile_preview(self.base, [self.unit], self.catalog, self.root, [profile])
        self.assertEqual(old["schemaVersion"], "review-guidelines-6")
        self.assertEqual(new["schemaVersion"], "review-guidelines-7")
        self.assertEqual((self.base, self.unit), before)
        self.assertEqual(old["examples"][0]["context"], new["examples"][0]["context"])
        self.assertNotIn("relationProfile", new["examples"][1])
        self.assertEqual(new["examples"][0]["relationProfile"]["target"], "")
        profile["evaluation"] = "다른 검색용 설명"
        changed, _ = compile_preview(self.base, [self.unit], self.catalog, self.root, [profile])
        self.assertNotEqual(new["sourceBundleSha256"], changed["sourceBundleSha256"])
        self.assertEqual(new["examples"][0]["context"], changed["examples"][0]["context"])

    def test_profiles_reject_changed_source_unknown_unit_duplicates_and_promotion(self):
        for change in ({"sourceSha256": "a" * 64}, {"utteranceIds": ["s2", "s1"]},
                       {"unitId": "unknown"}, {"humanVerified": True}, {"status": "APPROVED"},
                       {"trainingUseAuthorized": True}, {"evaluation": ""}, {"target": " "},
                       {"linkage": "x" * 351}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                compile_preview(self.base, [self.unit], self.catalog, self.root, [{**self.profile(), **change}])
        with self.assertRaises(ValueError):
            compile_preview(self.base, [self.unit], self.catalog, self.root, [self.profile(), self.profile()])


if __name__ == "__main__": unittest.main()
