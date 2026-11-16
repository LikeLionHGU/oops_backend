"""OCR 프레임 간격 자동 조정."""
from app.ocr import _adjust_interval


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
