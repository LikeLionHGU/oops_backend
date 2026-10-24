import pytest
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_youtube_script import parse_script


def test_duplicate_clock_and_equal_timestamps_preserve_all_rows():
    result = parse_script("0:022초[음악]\n0:077초첫 말\n0:077초[웃음]\n챕터 2: 설명\n1:00:071시간 7초뒤의 말")
    assert len(result["segments"]) == 3
    assert result["segments"][1]["text"] == "첫 말\n[웃음]"
    assert result["segments"][1]["endMs"] == 3607000
    assert result["segments"][-1]["endMs"] == result["segments"][-1]["startMs"]
    assert result["segments"][-1]["timePrecision"] == "POINT_ONLY_END_UNKNOWN"
    assert len(result["chapters"]) == 1
    assert sum(len(s["sourceRows"]) for s in result["segments"]) == 4


def test_plain_timestamps_and_zero_second_korean_prefix():
    result = parse_script("1:001분원문\n1:00:001시간다음 원문\n1:01:001시간 1분마지막")
    assert [s["text"] for s in result["segments"]] == ["원문", "다음 원문", "마지막"]


def test_transcription_errors_units_and_speaker_ambiguity_are_not_corrected():
    text = "몸무게 93km 매일 8kg 갑니다 네 왜요?"
    result = parse_script("0:07 " + text)
    assert result["segments"][0]["text"] == text
    assert "speaker" not in result["segments"][0]


@pytest.mark.parametrize("text", ["0:022초", "0:612초문장", "0:022초첫 말\n0:011초역행", "0:025초불일치", "제목만", ""])
def test_unrecognized_or_conflicting_rows_fail_without_silent_drop(text):
    with pytest.raises(ValueError):
        parse_script(text)
