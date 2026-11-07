"""Hand-made two-dimensional vectors only; no model runs."""
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from reason_vectors import attached_selections, chance_hit, retrieval_metrics, select_top


def unit(x, y):
    n = math.hypot(x, y)
    return [x / n, y / n]


DOCS = {"a#s1": unit(1, 0), "b#s1": unit(1, 0.1), "c#s1": unit(0, 1), "d#s1": unit(1, 0.1)}
FAMILY = {"a#s1": "a", "b#s1": "b", "c#s1": "c", "d#s1": "d"}
META = {"a#s1": {"familyId": "a", "mechanismIds": ["group"]}, "b#s1": {"familyId": "b", "mechanismIds": ["group"]},
        "c#s1": {"familyId": "c", "mechanismIds": ["sexual"]}, "d#s1": {"familyId": "d", "mechanismIds": ["solo"]}}


class SelectTests(unittest.TestCase):
    def test_same_family_documents_are_never_selected(self):
        out = select_top({"6001/stt-index-0": unit(1, 0)}, DOCS, FAMILY, {"6001/stt-index-0": "a"}, k=3)
        self.assertNotIn("a#s1", out["6001/stt-index-0"])
        self.assertEqual(len(out["6001/stt-index-0"]), 3)

    def test_ties_break_by_document_id(self):
        out = select_top({"q": unit(1, 0.1)}, DOCS, FAMILY, {"q": None}, k=2)
        self.assertEqual(out["q"], ["b#s1", "d#s1"])

    def test_null_or_invalid_query_reason_gives_empty_selection(self):
        out = select_top({}, DOCS, FAMILY, {"q": "a"}, k=3)
        self.assertEqual(out, {"q": []})


class ChanceTests(unittest.TestCase):
    def test_chance_hit_matches_hypergeometric(self):
        pool = [f"x{i}" for i in range(10)]
        self.assertAlmostEqual(chance_hit(pool, {"x0", "x1", "x2"}, k=3), 1 - 35 / 120)

    def test_small_pool_is_a_certain_hit_when_any_hit_exists(self):
        self.assertEqual(chance_hit(["x0", "x1"], {"x1"}, k=3), 1.0)
        self.assertEqual(chance_hit(["x0", "x1"], set(), k=3), 0.0)


class RetrievalTests(unittest.TestCase):
    def query_meta(self, **overrides):
        meta = {"6001/stt-index-0": {"familyId": "a", "mechanismIds": ["group"], "status": "OK"},
                "6002/stt-index-0": {"familyId": "d", "mechanismIds": ["solo"], "status": "OK"},
                "6003/stt-index-0": {"familyId": "c", "mechanismIds": ["group"], "status": "NULL"}}
        meta.update(overrides)
        return meta

    def test_single_family_mechanisms_and_null_reasons_leave_the_denominator(self):
        m = retrieval_metrics({"6001/stt-index-0": ["b#s1"], "6002/stt-index-0": ["a#s1"], "6003/stt-index-0": []},
                              self.query_meta(), META)
        self.assertEqual(m["denominator"], 1)
        self.assertEqual(m["hits"], 1)
        self.assertEqual(m["excluded"], {"NO_OTHER_FAMILY": 1, "NULL": 1, "INVALID": 0, "ERROR": 0})
        self.assertAlmostEqual(m["chanceExpectedHits"], 1.0)
        self.assertEqual(m["byMechanism"]["group"]["hits"], 1)

    def test_keyword_selection_of_non_casebook_example_is_not_a_hit(self):
        m = retrieval_metrics({"6001/stt-index-0": ["mimiminu-dev#audience"], "6002/stt-index-0": [], "6003/stt-index-0": []},
                              self.query_meta(), META)
        self.assertEqual(m["hits"], 0)

    def test_same_family_document_is_not_a_hit(self):
        m = retrieval_metrics({"6001/stt-index-0": ["a#s1"], "6002/stt-index-0": [], "6003/stt-index-0": []},
                              self.query_meta(), META)
        self.assertEqual(m["hits"], 0)

    def test_pool_meta_scores_non_casebook_picks_without_changing_the_denominator(self):
        pool = {**META, "mimi#x": {"familyId": "mimi", "mechanismIds": ["group"]}}
        m = retrieval_metrics({"6001/stt-index-0": ["mimi#x"], "6002/stt-index-0": ["mimi#x"], "6003/stt-index-0": []},
                              self.query_meta(), META, pool_meta=pool)
        self.assertEqual(m["denominator"], 1)
        self.assertEqual(m["hits"], 1)

    def test_error_status_is_excluded(self):
        m = retrieval_metrics({"6001/stt-index-0": ["b#s1"]},
                              self.query_meta(**{"6001/stt-index-0": {"familyId": "a", "mechanismIds": ["group"], "status": "ERROR"}}), META)
        self.assertEqual(m["excluded"]["ERROR"], 1)
        self.assertEqual(m["denominator"], 0)


class AttachedTests(unittest.TestCase):
    def test_attached_selections_use_repeat_zero_and_cut_to_k(self):
        rows = [{"videoId": 6001, "targetId": "stt-index-0", "repeat": 0, "discoveryReferenceIds": ["a", "b", "c", "d"]},
                {"videoId": 6001, "targetId": "stt-index-0", "repeat": 1, "discoveryReferenceIds": ["z"]},
                {"videoId": 6002, "targetId": "stt-index-0", "repeat": 0}]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "results.jsonl"
            path.write_text("\n".join(json.dumps(r) for r in rows))
            self.assertEqual(attached_selections(path), {"6001/stt-index-0": ["a", "b", "c"], "6002/stt-index-0": []})


if __name__ == "__main__":
    unittest.main()
