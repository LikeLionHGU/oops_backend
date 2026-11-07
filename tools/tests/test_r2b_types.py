import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from r2b_types import classify, far_direction, split_v2  # noqa: E402


def seg(i, start, end=None):
    return {"id": f"s{i}", "startMs": start, "endMs": end if end is not None else start + 1000, "text": "x"}


SEGMENTS = {s["id"]: s for s in [seg(1, 0), seg(2, 100_000), seg(3, 150_000), seg(4, 200_000), seg(5, 900_000),
                                 seg(6, 1_000_000), seg(7, 1_030_000), seg(8, 1_200_000)]}


def case(target, gold, a0, necessary=True):
    return {"targetSegmentId": target, "contextNecessary": necessary, "goldContextSegmentIds": gold,
            "a0ContextSegmentIds": a0, "exportStatus": "VALID"}


def test_not_context_necessary():
    assert classify(case("s2", [], ["s1"], necessary=False), SEGMENTS) == "NOT_CONTEXT_NECESSARY"


def test_near_when_all_gold_in_a0():
    assert classify(case("s7", ["s6"], ["s6"]), SEGMENTS) == "NEAR"


def test_preview_early_target_with_late_gold():
    assert classify(case("s2", ["s5"], []), SEGMENTS) == "PREVIEW"


def test_preview_needs_every_outside_gold_late():
    assert classify(case("s2", ["s5", "s3"], []), SEGMENTS) == "LONG_RANGE_REFERENCE"


def test_preview_needs_target_before_180s():
    assert classify(case("s4", ["s8"], []), SEGMENTS) == "LONG_RANGE_REFERENCE"


def test_page_boundary_within_60s_gap():
    assert classify(case("s6", ["s7"], []), SEGMENTS) == "A0_PAGE_BOUNDARY"


def test_page_boundary_uses_gap_between_segments():
    segments = {"t": {"id": "t", "startMs": 0, "endMs": 50_000}, "g": {"id": "g", "startMs": 109_000, "endMs": 110_000}}
    assert classify(case("t", ["g"], []), segments) == "A0_PAGE_BOUNDARY"


def test_long_range_past_reference():
    assert classify(case("s8", ["s4", "s7"], ["s7"]), SEGMENTS) == "LONG_RANGE_REFERENCE"


def test_invalid_case():
    assert classify({**case("s8", ["s4"], []), "exportStatus": "INVALID"}, SEGMENTS) == "INVALID"


def test_far_direction():
    assert far_direction(case("s8", ["s4"], []), SEGMENTS) == "BEFORE"
    assert far_direction(case("s4", ["s8"], []), SEGMENTS) == "AFTER"
    assert far_direction(case("s5", ["s1", "s8"], []), SEGMENTS) == "BOTH"


def test_split_punctuation():
    assert split_v2("그래? 맞아. 진짜!  ") == (["그래?", "맞아.", "진짜!"], "PUNCTUATION")


def test_split_ellipsis():
    assert split_v2("음… 그렇지") == (["음…", "그렇지"], "PUNCTUATION")


def test_no_split_without_whitespace_after_mark():
    assert split_v2("3.5배") == (["3.5배"], "UNSPLIT")


def test_split_length_chunks_earlier_longer():
    words = ["가나다라마바사아자차"] * 7
    text = " ".join(words)
    pieces, kind = split_v2(text)
    assert kind == "LENGTH"
    assert len(text) == 76 and len(pieces) == 3
    assert [len(p.split()) for p in pieces] == [3, 2, 2]


def test_split_length_capped_at_four():
    text = " ".join(["가나다라마바사아자차"] * 20)
    pieces, kind = split_v2(text)
    assert kind == "LENGTH" and [len(p.split()) for p in pieces] == [5, 5, 5, 5]


def test_unsplit_when_short_or_few_words():
    assert split_v2("짧은 문장 입니다 정말")[1] == "UNSPLIT"
    assert split_v2("가" * 40 + " " + "나" * 10)[1] == "UNSPLIT"
