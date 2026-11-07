"""Toy vectors and a fake reranker only; no model downloads."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from far_retrieval_eval import candidates, case_metrics, evaluate_case, rerank, split_pieces, top_exposure

SEGS = [{"id": f"s{i}", "startMs": i * 10_000, "endMs": i * 10_000 + 5_000, "text": f"t{i}"} for i in range(7)]
TARGET = SEGS[3]


class SplitTests(unittest.TestCase):
    def test_splits_after_sentence_marks_followed_by_space(self):
        self.assertEqual(split_pieces("가요? 나다. 라!  마"), ["가요?", "나다.", "라!", "마"])

    def test_single_piece_means_unsplit(self):
        self.assertEqual(split_pieces("끝까지 한 문장"), ["끝까지 한 문장"])
        self.assertEqual(split_pieces("3.5점 이었다"), ["3.5점 이었다"])


class CandidateTests(unittest.TestCase):
    # per-piece cosine scores over documents s0..s6 (s3 is the target and is never returned)
    A = {"s0": .9, "s1": .1, "s2": .8, "s4": .7, "s5": .2, "s6": .1}
    B = {"s0": .1, "s1": .95, "s2": .2, "s4": .1, "s5": .6, "s6": .05}

    def test_single_query_ranks_by_score_then_distance_then_order(self):
        tie = {"s0": .5, "s1": .5, "s2": .5, "s4": .5, "s5": .5, "s6": .5}
        self.assertEqual(candidates("Q0_RAW", [tie], SEGS, TARGET, 4), ["s2", "s4", "s1", "s5"])

    def test_max_rrf_topn_and_union(self):
        self.assertEqual(candidates("MAX", [self.A, self.B], SEGS, TARGET, 3), ["s1", "s0", "s2"])
        self.assertEqual(candidates("RRF", [self.A, self.B], SEGS, TARGET, 2), ["s2", "s1"])
        self.assertEqual(candidates("TOPN_EQUAL", [self.A, self.B], SEGS, TARGET, 3), ["s0", "s1", "s2"])
        self.assertEqual(candidates("UNION_A3", [self.A, self.B], SEGS, TARGET, 3), ["s0", "s1", "s2"])
        self.assertEqual(candidates("UNION_A3", [self.A, self.B], SEGS, TARGET, 4), ["s0", "s1", "s2", "s5"])

    def test_one_piece_makes_every_method_equal_q0(self):
        q0 = candidates("Q0_RAW", [self.A], SEGS, TARGET, 4)
        for method in ("MAX", "RRF", "TOPN_EQUAL", "UNION_A3"):
            self.assertEqual(candidates(method, [self.A], SEGS, TARGET, 4), q0, method)

    def test_past_only_scope_drops_later_segments(self):
        self.assertEqual(candidates("Q0_RAW", [self.A], SEGS, TARGET, 5, past_only=True), ["s0", "s2", "s1"])


class RerankAndMetricTests(unittest.TestCase):
    def test_rerank_uses_max_logit_over_pieces(self):
        logits = {("p1", "t0"): 1.0, ("p2", "t0"): 0.0, ("p1", "t2"): 0.0, ("p2", "t2"): 3.0}
        scorer = lambda pairs: [logits[p] for p in pairs]
        self.assertEqual(rerank(["s0", "s2"], ["p1", "p2"], SEGS, TARGET, scorer), ["s2", "s0"])

    def test_case_metrics_with_and_without_a0_outside_gold(self):
        m = case_metrics(["s0", "s5"], ["s5", "s1"], a0_ids=["s0"])
        self.assertEqual(m, {"recall": 0.5, "recallOutsideA0": 1.0, "allContext": False,
                             "ranks": {"s0": None, "s5": 1}})
        self.assertIsNone(case_metrics(["s0"], ["s0"], a0_ids=["s0"])["recallOutsideA0"])


class TopExposureTests(unittest.TestCase):
    def test_share_of_gold_in_each_cut(self):
        ranked = [f"d{i}" for i in range(30)]
        self.assertEqual(top_exposure(["d0", "d7", "d15", "x"], ranked), {"5": 0.25, "10": 0.5, "20": 0.75})


class EvaluateCaseSplitTests(unittest.TestCase):
    def test_custom_split_and_top_exposure_at_largest_budget(self):
        segs = [{"id": f"s{i}", "startMs": i * 10_000, "endMs": i * 10_000 + 5_000, "text": f"t{i}"} for i in range(4)]
        segs[1]["text"] = "one two"
        vec = {s["id"]: [1.0, 0.0] for s in segs}
        embeddings = {"documentVectors": vec, "queryVectors": vec}
        case = {"videoId": 1, "target": "s1", "type": "NEAR", "gold": ["s0"], "a0ContextSegmentIds": ["s0"]}
        seen = []
        encode = lambda texts: seen.append(list(texts)) or [[1.0, 0.0] for _ in texts]
        scorer = lambda pairs: [0.0 for _ in pairs]
        result = evaluate_case(case, segs, embeddings, encode, scorer, split=lambda t: t.split())
        self.assertEqual(result["pieces"], ["one", "two"])
        self.assertEqual(seen[0], ["one", "two"])
        cell = result["scopes"]["FULL_VIDEO"]["methods"]["MAX"]["atK"]["100"]
        self.assertEqual(cell["topExposure"], {"a2": {"5": 1.0, "10": 1.0, "20": 1.0},
                                               "a3": {"5": 1.0, "10": 1.0, "20": 1.0}})
        self.assertNotIn("topExposure", result["scopes"]["FULL_VIDEO"]["methods"]["MAX"]["atK"]["20"])


if __name__ == "__main__":
    unittest.main()
