"""incidents 마이그레이션 왕복/동치 회귀 테스트.

두 층으로 나눈다:
1. 합성 fixture 왕복 — datasets 없이 항상 실행. 변환기 로직(무손실 역조직) 자체를 고정한다.
2. 실제 datasets runtime 바이트 동치 — datasets 가 있을 때만(skipUnless). CI/다른 머신에서
   datasets 는 Git 제외라 없을 수 있으므로 가드한다. 기존 테스트 관행(fixture 독립)을 지킨다.
"""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import incidents_migration as tool

DATASET_ROOT = ROOT.parent / "datasets/controversy"
HAS_DATASET = (DATASET_ROOT / "drafts/cards.json").exists()


def _synthetic():
    """사건 1개의 레거시 조각(cards_incident, gplan, dplan, catalog) 합성."""
    cards_incident = {
        "familyId": "demo-topic",
        "split": "DEVELOPMENT",
        "cards": [
            {"id": "demo-A", "familyId": "demo-topic", "status": "UNREVIEWED_DRAFT",
             "knownPoint": {"text": "데모 논란 지점", "basis": "SYNTHETIC"}, "reactions": []},
            {"id": "demo-B", "familyId": "demo-topic", "status": "UNREVIEWED_DRAFT",
             "knownPoint": {"text": "두 번째 지점", "basis": "SYNTHETIC"}, "reactions": []},
        ],
    }
    gplan = {"schemaVersion": "x", "mechanisms": [
        {"id": "mech-one", "sourceCaseIds": ["demo-A", "demo-B"], "condition": "조건"},
        {"id": "mech-two", "sourceCaseIds": ["demo-B"], "condition": "조건2"},
    ]}
    dplan = {"schemaVersion": "x", "cards": {
        # demo-A 는 선택 키 sourceOriginGroups 있음, demo-B 는 없음 — 무손실 핵심 케이스.
        "demo-A": {"topicTags": ["t1"], "mechanismIds": ["mech-one"],
                   "sourceOriginGroups": {"g": "x"}, "documents": []},
        "demo-B": {"topicTags": ["t2"], "mechanismIds": ["mech-one", "mech-two"], "documents": []},
    }}
    catalog = {"incidents": [
        {"familyId": "demo-topic", "caseIds": ["demo-A", "demo-B"], "collectionId": "c1",
         "evidenceLevel": "REPORTED_CONTEXT_ONLY", "selectedReactions": 0},
    ]}
    return cards_incident, gplan, dplan, catalog


class SyntheticRoundTripTests(unittest.TestCase):
    def test_incident_roundtrip_is_lossless(self):
        cards_incident, gplan, dplan, catalog = _synthetic()
        incident = tool.to_incident("demo-topic", cards_incident, gplan, dplan, catalog)
        r_cards, r_gplan, r_dplan, r_cat = tool.from_incident(incident)

        # 1. cards incident 통째 보존
        self.assertEqual(r_cards, cards_incident)
        # 2. dplan 카드 — 선택 키 유무까지 무손실(demo-B 에 sourceOriginGroups 를 추가하지 않음)
        self.assertEqual(r_dplan, {cid: dplan["cards"][cid] for cid in ("demo-A", "demo-B")})
        self.assertNotIn("sourceOriginGroups", r_dplan["demo-B"])
        # 3. gplan 역매핑 — 사건 내부 mechanism->caseId 보존, 복수 메커니즘 포함
        self.assertEqual(r_gplan, {"mech-one": ["demo-A", "demo-B"], "mech-two": ["demo-B"]})
        # 4. catalog 항목 통째 보존
        self.assertEqual(r_cat, catalog["incidents"][0])

    def test_mechanism_ids_is_array_with_multiple(self):
        cards_incident, gplan, dplan, catalog = _synthetic()
        incident = tool.to_incident("demo-topic", cards_incident, gplan, dplan, catalog)
        by_id = {s["id"]: s for s in incident["statements"]}
        self.assertEqual(by_id["demo-B"]["mechanismIds"], ["mech-one", "mech-two"])
        self.assertEqual(by_id["demo-A"]["mechanismIds"], ["mech-one"])


@unittest.skipUnless(HAS_DATASET, "datasets 가 Git 제외라 없음 — 로컬에서만 실행")
class RealDatasetEquivalenceTests(unittest.TestCase):
    def test_pisik_roundtrip(self):
        results, _ = tool.roundtrip_check(DATASET_ROOT, "pisik-yeongyang")
        self.assertTrue(all(results.values()), results)

    def test_full_runtime_byte_equivalence(self):
        checks = tool.runtime_equivalence(DATASET_ROOT)
        self.assertTrue(all(checks.values()), checks)
        self.assertTrue(checks["runtime_byte_equivalent_except_ts"])

    def test_single_input_build_is_equivalent_and_circular_free(self):
        """4단계: incidents 단일 입력 빌드가 기존 runtime 과 동치 + 순환 입력 미경유."""
        import build_incident_dataset as build
        # incidents 파일이 아직 없으면 먼저 생성(로컬 전용).
        if not (DATASET_ROOT / "incidents").is_dir():
            tool.emit_incidents(DATASET_ROOT)
        runtime, _ = build.generate(DATASET_ROOT)
        saved = tool.load(DATASET_ROOT, "guidelines/runtime.json")
        self.assertEqual(build._strip_ts(runtime), tool._strip_ts(saved))
        # 순환 입력(cards/catalog)을 '코드에서 참조하지 않음'을 고정.
        # docstring/주석은 결함 설명으로 그 이름을 '언급'하므로 AST 로 떼어내고 코드만 검사한다.
        import ast
        tree = ast.parse((ROOT / "build_incident_dataset.py").read_text(encoding="utf-8"))
        # 모듈 docstring 제거
        if tree.body and isinstance(tree.body[0], ast.Expr) and isinstance(tree.body[0].value, ast.Constant):
            tree.body = tree.body[1:]
        code_only = ast.unparse(tree)
        for forbidden in ("drafts/cards.json", "validate_catalog", "validate_private", "build_bundle"):
            self.assertNotIn(forbidden, code_only, f"순환 입력 참조 발견: {forbidden}")

    def test_article_only_incident_passes_full_build(self):
        """5단계(결함 B 제거): reactions 0 · collectionId null 사건이 전 체인을 통과한다.

        실제 카드 구조를 복제해 reactions 만 비운다(완전 구조 + 댓글 0). 새 빌드 경로는 catalog
        UNKNOWN_COLLECTION 을 안 타므로 기사-only 사건이 성립해야 한다.
        """
        import copy
        from build_context_dictionary import compile_dictionary
        from build_review_guidelines import compile_guidelines
        from review_dataset_catalog import inside
        from review_context_pilot import read_bytes

        cards = tool.load(DATASET_ROOT, "drafts/cards.json")
        gplan = tool.load(DATASET_ROOT, "guidelines/plan.json")
        dplan = tool.load(DATASET_ROOT, "research/context-dictionary-plan.json")
        cat = tool.load(DATASET_ROOT, "catalog.json")
        incidents = [tool.to_incident(ci["familyId"], ci, gplan, dplan, cat) for ci in cards["incidents"]]

        pisik = next(i for i in cards["incidents"] if i["familyId"] == "pisik-yeongyang")
        base = copy.deepcopy(next(c for c in pisik["cards"] if c["id"] == "pisik-C"))
        base["id"] = "dummy-X"
        base["familyId"] = "dummy-article-only-20260101"
        base["reactions"] = []
        base["controversy"]["reasonHypotheses"] = []
        base["controversy"]["reasonSupportReactionIds"] = []

        dummy = {"schemaVersion": "incident-2", "incidentId": "dummy-article-only-20260101",
                 "split": "DEVELOPMENT",
                 "catalogMeta": {"familyId": "dummy-article-only-20260101", "caseIds": ["dummy-X"],
                                 "collectionId": None, "evidenceLevel": "REPORTED_CONTEXT_ONLY",
                                 "selectedReactions": 0},
                 "statements": [{"id": "dummy-X", "mechanismIds": ["local-life-devaluation"],
                                 "topicTags": ["데모"],
                                 "legacyDictionaryCard": {"topicTags": ["데모"],
                                                          "mechanismIds": ["local-life-devaluation"],
                                                          "documents": []},
                                 "legacyCard": base}]}

        rc, rg, rd, rcat = tool.reassemble(DATASET_ROOT, incidents + [dummy])
        # reassemble 이 새 사건을 cards/dplan/gplan 에 반영했는지
        self.assertTrue(any(c["id"] == "dummy-X" for i in rc["incidents"] for c in i["cards"]))
        self.assertIn("dummy-X", rd["cards"])
        m = next(x for x in rg["mechanisms"] if x["id"] == "local-life-devaluation")
        self.assertIn("dummy-X", m["sourceCaseIds"])
        # collectionId null 보존
        new_cat = next(i for i in rcat["incidents"] if i["familyId"] == "dummy-article-only-20260101")
        self.assertIsNone(new_cat["collectionId"])

        snaps = {}
        for cfg in rd["cards"].values():
            for d in cfg.get("documents", []):
                snaps[d["snapshot"]] = read_bytes(inside(DATASET_ROOT, d["snapshot"]))
        import json as _json
        sb = (_json.dumps(rc, ensure_ascii=False, indent=2) + "\n").encode()
        dictionary = compile_dictionary(rc, rd, rg, sb, snaps)
        runtime = compile_guidelines(rc, rg, sb, dictionary)  # 통과하면 결함 B 제거 성립
        self.assertGreaterEqual(len(runtime["guidelines"]), 1)

    def test_add_incident_assemble_passes_full_build(self):
        """add_incident.assemble 이 만든 기사-only 사건이 전 체인(validate_bundle+reference_context)을 통과한다."""
        import add_incident as add
        from build_context_dictionary import compile_dictionary
        from build_review_guidelines import compile_guidelines
        from review_dataset_catalog import inside
        from review_context_pilot import read_bytes
        import json as _json

        spec = {"incidentId": "unittest-demo-20260101",
                "statements": [{"caseId": "unittest-demo-20260101#s1",
                                "knownPoint": "테스트 논란 지점",
                                "mechanismIds": ["local-life-devaluation"],
                                "topicTags": ["지역", "생활"]}]}
        incident = add.assemble(spec, DATASET_ROOT)
        self.assertEqual(incident["catalogMeta"]["collectionId"], None)
        self.assertEqual(incident["catalogMeta"]["selectedReactions"], 0)

        cards = tool.load(DATASET_ROOT, "drafts/cards.json")
        gplan = tool.load(DATASET_ROOT, "guidelines/plan.json")
        dplan = tool.load(DATASET_ROOT, "research/context-dictionary-plan.json")
        cat = tool.load(DATASET_ROOT, "catalog.json")
        incidents = [tool.to_incident(ci["familyId"], ci, gplan, dplan, cat) for ci in cards["incidents"]]
        rc, rg, rd, rcat = tool.reassemble(DATASET_ROOT, incidents + [incident])
        snaps = {}
        for cfg in rd["cards"].values():
            for d in cfg.get("documents", []):
                snaps[d["snapshot"]] = read_bytes(inside(DATASET_ROOT, d["snapshot"]))
        sb = (_json.dumps(rc, ensure_ascii=False, indent=2) + "\n").encode()
        dictionary = compile_dictionary(rc, rd, rg, sb, snaps)
        runtime = compile_guidelines(rc, rg, sb, dictionary)
        self.assertTrue(any("unittest-demo-20260101#s1" in m["sourceCaseIds"] for m in rg["mechanisms"]))
        self.assertGreaterEqual(len(runtime["guidelines"]), 1)

    def test_add_incident_rejects_unknown_mechanism(self):
        import add_incident as add
        from review_context_pilot import PilotError
        spec = {"incidentId": "unittest-bad-20260101",
                "statements": [{"caseId": "x", "knownPoint": "y",
                                "mechanismIds": ["no-such-mechanism"], "topicTags": ["t"]}]}
        with self.assertRaises(PilotError):
            add.assemble(spec, DATASET_ROOT)


if __name__ == "__main__":
    unittest.main()
