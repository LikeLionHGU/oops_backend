"""화면 글자에서 채널 로고를 걸러내는 로직.

이 기능은 세 번 고쳐서야 맞았다.

  1차 — 글자가 정확히 같은지만 봤다.
        OCR 이 로고를 프레임마다 다르게 읽어서(POGUES → PO6UFS → FOGUES) 실패.
  2차 — 같은 자리에 계속 나오는지만 봤다.
        자막도 하단 같은 자리에 계속 나와서 자막까지 지워버렸다.
  3차 — 자리 + 글자 유사도를 같이 본다. 통과.

세 번 헤맨 로직이라 회귀 테스트를 붙인다.
PaddleOCR 없이도 돌아야 하므로 파싱된 결과만 넣는다.
"""
from __future__ import annotations

import pytest

from app.ocr import _find_watermarks, _position_key, _similarity, _adjust_interval


def frame(idx: int, items):
    """(프레임번호, 시각ms, [(글자, 신뢰도, x, y), ...])"""
    return (idx, idx * 4000, items)


class TestFindWatermarks:

    def test_로고는_자리도_같고_글자도_비슷하다(self):
        # 오른쪽 위에 채널 로고가 계속 떠 있고, OCR 이 매번 조금씩 다르게 읽는다
        logo_reads = ["POGUES", "PO6UFS", "FOGUES", "POGUE5", "P0GUES", "POGUES"]
        frames = [frame(i, [(text, 0.9, 900, 80)]) for i, text in enumerate(logo_reads)]

        assert _position_key(900, 80) in _find_watermarks(frames)

    def test_자막은_자리가_같아도_지우지_않는다(self):
        # 하단 자막은 자리는 고정이지만 내용이 매번 완전히 바뀐다.
        # 2차 시도에서 이걸 로고로 보고 다 지워버렸다.
        subtitles = [
            "오늘은 여기 와봤습니다",
            "생각보다 사람이 많네요",
            "맛은 어떨지 궁금한데요",
            "한번 먹어보겠습니다",
            "생각보다 괜찮은데요",
            "다음에 또 오고 싶네요",
        ]
        frames = [frame(i, [(text, 0.9, 640, 650)]) for i, text in enumerate(subtitles)]

        assert _position_key(640, 650) not in _find_watermarks(frames)

    def test_첫_프레임이_심하게_깨져도_로고로_잡는다(self):
        # 실제로 겪은 사례: 로고가 첫 프레임에서 "동행마스" 로 읽혔다.
        # 첫 글자를 기준으로 삼으면 나머지가 전부 다르다고 나와서 놓친다.
        reads = ["동행마스", "POGUES", "POGUES", "PO6UES", "POGUES", "POGUE5"]
        frames = [frame(i, [(text, 0.9, 900, 80)]) for i, text in enumerate(reads)]

        assert _position_key(900, 80) in _find_watermarks(frames)

    def test_잠깐_나오는_글자는_로고가_아니다(self):
        # 간판이나 메뉴판은 그 장면에만 나온다
        frames = [frame(i, []) for i in range(8)]
        frames[2] = frame(2, [("영업중", 0.9, 500, 300)])
        frames[3] = frame(3, [("영업중", 0.9, 500, 300)])

        assert _position_key(500, 300) not in _find_watermarks(frames)

    def test_프레임이_너무_적으면_판단하지_않는다(self):
        # 3장으로 "계속 나온다" 를 말할 수 없다. 섣불리 지우면 자막이 날아간다.
        frames = [frame(i, [("POGUES", 0.9, 900, 80)]) for i in range(3)]

        assert _find_watermarks(frames) == set()

    def test_빈_입력(self):
        assert _find_watermarks([]) == set()


class TestSimilarity:

    def test_같은_글자는_1에_가깝다(self):
        assert _similarity("POGUES", "POGUES") == pytest.approx(1.0)

    def test_오인식된_같은_로고는_높게_나온다(self):
        assert _similarity("POGUES", "PO6UFS") >= 0.45

    def test_다른_자막은_낮게_나온다(self):
        assert _similarity("오늘은 여기 와봤습니다", "다음에 또 오고 싶네요") < 0.45


class TestAdjustInterval:

    def test_보통_길이는_요청한_간격을_그대로_쓴다(self):
        assert _adjust_interval(120, 4.0) == 4.0

    def test_아주_짧은_영상은_간격을_좁힌다(self):
        # 8초를 4초 간격으로 뜨면 2장뿐이다.
        # 자막이 큼직하게 박혀 있는데 0건으로 끝나는 일이 실제로 있었다.
        interval = _adjust_interval(8, 4.0)
        assert interval < 4.0
        assert 8 / interval >= 6

    def test_짧아도_지나치게_촘촘해지지는_않는다(self):
        assert _adjust_interval(1, 4.0) >= 0.5

    def test_긴_영상은_간격을_늘린다(self):
        # 60분 영상을 4초 간격으로 뜨면 900장이다. 감당이 안 된다.
        assert _adjust_interval(3600, 4.0) > 4.0

    def test_프레임_수를_상한_안으로_묶는다(self):
        for duration in (600, 1800, 3600, 5400):
            interval = _adjust_interval(duration, 4.0)
            assert duration / interval <= 320, f"{duration}초에서 프레임이 너무 많다"
