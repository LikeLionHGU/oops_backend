from pathlib import Path
from app import ocr


def raw(text, confidence=0.9, box=None):
    return [box or [[10, 10], [110, 10], [110, 30], [10, 30]], (text, confidence)]


def test_parser_preserves_separate_regions_and_unmodified_text():
    result = ocr._parse_regions([[raw("  발언 자막  "), raw("메뉴 9000원")]])
    assert len(result) == 2
    assert result[0][0] == "  발언 자막  "
    assert result[0][2] == (10, 10, 100, 20)


def test_bad_geometry_does_not_discard_text_and_invalid_confidence_is_rejected():
    assert ocr._parse_regions([[[None, ("원문", 0.9)]]])[0][2] is None
    assert ocr._parse_regions([[raw("원문", float("nan")), raw("원문", 0.3)]]) == []


def test_contiguous_identical_regions_track_independently(monkeypatch):
    monkeypatch.setattr(ocr, "_frame_size", lambda path: (200, 100))
    frames = [(i * 1000, Path("frame.jpg"), [("자막", 0.9, (10, 70, 100, 20)),
                                           ("간판", 0.8, (120, 10, 60, 20))]) for i in range(3)]
    items = ocr._region_items(frames, 1, 3)
    assert len(items) == 2
    assert {item["text"] for item in items} == {"자막", "간판"}
    assert all(item["observations"] == 3 and item["endMs"] == 3000 for item in items)
    assert items[0]["boxY"] == 0.7


def test_empty_frame_or_gap_or_different_location_breaks_track(monkeypatch):
    monkeypatch.setattr(ocr, "_frame_size", lambda path: (200, 100))
    line = ("같은 원문", 0.9, (10, 10, 60, 20))
    frames = [(0, Path("f"), [line]), (1000, Path("f"), []), (2000, Path("f"), [line]),
              (5000, Path("f"), [line]), (6000, Path("f"), [("같은 원문", 0.9, (120, 10, 60, 20))])]
    items = ocr._region_items(frames, 1, 8)
    assert len(items) == 4
    assert items[0]["endMs"] == 1000


def test_missing_dimensions_preserves_unknown_regions_without_false_tracking(monkeypatch):
    monkeypatch.setattr(ocr, "_frame_size", lambda path: None)
    frames = [(i * 1000, Path("f"), [("원문", 0.9, (10, 10, 100, 20))]) for i in range(2)]
    items = ocr._region_items(frames, 1, 2)
    assert len(items) == 2
    assert all(item["boxX"] is None for item in items)


def test_ui_and_repeated_text_are_preserved_and_changed_slot_is_counted(monkeypatch):
    monkeypatch.setattr(ocr, "_frame_size", lambda path: (200, 100))
    frames = [(i * 1000, Path("f"), [(text, 0.9, (10, 70, 100, 20))])
              for i, text in enumerate(["구독", "설명 자막", "강조 문구", "다른 표현"])]
    items = ocr._region_items(frames, 1, 4)
    assert len(items) == 4
    assert items[0]["text"] == "구독"
    assert all(item["slotTextChanges"] == 3 for item in items)


def test_same_text_twice_in_frame_cannot_merge_into_one_track(monkeypatch):
    monkeypatch.setattr(ocr, "_frame_size", lambda path: (200, 100))
    line = ("원문", 0.9, (10, 10, 100, 20))
    items = ocr._region_items([(0, Path("f"), [line, line]), (1000, Path("f"), [line, line])], 1, 2)
    assert len(items) == 2
    assert all(item["observations"] == 2 for item in items)


def test_run_returns_region_contract_without_real_ocr_or_media_calls(monkeypatch):
    from app.media import PreparedVideo
    class Engine:
        def ocr(self, path, cls=True):
            return [[raw("발언 자막"), raw("식당 간판", box=[[120, 10], [180, 10], [180, 30], [120, 30]])]]
    monkeypatch.setattr(ocr, "_engine", lambda: Engine())
    monkeypatch.setattr(ocr, "extract_frames", lambda video, interval: [(0, Path("f")), (1000, Path("f"))])
    monkeypatch.setattr(ocr, "_adjust_interval", lambda duration, requested: requested)
    monkeypatch.setattr(ocr, "_frame_size", lambda path: (200, 100))
    response = ocr.run(PreparedVideo(Path("video"), Path("work"), 2), 1)
    assert response["formatVersion"] == "regions-v1"
    assert len(response["items"]) == 2
    assert {item["text"] for item in response["items"]} == {"발언 자막", "식당 간판"}
    assert all(item["observations"] == 2 for item in response["items"])
