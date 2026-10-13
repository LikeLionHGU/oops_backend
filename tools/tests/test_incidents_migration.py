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


if __name__ == "__main__":
    unittest.main()
