import io
from types import SimpleNamespace

import httpx
import pytest
from openai import OpenAI
from app import stt
from app.media import MediaError


def test_whisper_keeps_existing_timestamp_request():
    assert stt._request_options("whisper-1") == {
        "model": "whisper-1", "response_format": "verbose_json", "timestamp_granularities": ["segment"]}


@pytest.mark.parametrize("model", ["gpt-transcribe", "gpt-4o-transcribe", "unknown"])
def test_timestamp_less_models_are_rejected_instead_of_fabricating_times(model):
    with pytest.raises(MediaError):
        stt._request_options(model)


def test_pinned_sdk_diarization_wire_contract_and_response_parsing_without_network():
    def handler(request):
        body = request.read().decode()
        assert 'name="response_format"\r\n\r\ndiarized_json' in body
        assert 'name="chunking_strategy"\r\n\r\nauto' in body
        assert "timestamp_granularities" not in body
        assert 'name="prompt"' not in body
        return httpx.Response(200, json={"text": "방언 원문", "segments": [
            {"start": 1.25, "end": 2.5, "text": "방언 원문", "speaker": "A"}]})
    with OpenAI(api_key="offline-test-key", http_client=httpx.Client(transport=httpx.MockTransport(handler))) as client:
        result = client.audio.transcriptions.create(file=("clip.wav", io.BytesIO(b"fake")),
                                                   **stt._request_options("gpt-4o-transcribe-diarize"))
    assert stt._normalize_segments(result, 600000, 1, True) == [
        {"startMs": 601250, "endMs": 602500, "text": "방언 원문", "speaker": "chunk-1:A"}]


def test_whisper_object_response_preserves_text_and_offset():
    result = SimpleNamespace(text="원문", segments=[SimpleNamespace(start=0.5, end=1.5, text=" 원문 ")])
    assert stt._normalize_segments(result, 1000, 0, False) == [
        {"startMs": 1500, "endMs": 2500, "text": "원문"}]


@pytest.mark.parametrize("start,end", [(None, 1), (0, None), (-1, 1), (1, 1), (2, 1),
                                        (float("nan"), 1), (0, float("inf")), (True, 1), (0, 0.0001)])
def test_invalid_times_are_not_silently_changed_to_zero(start, end):
    with pytest.raises(MediaError):
        stt._normalize_segments({"segments": [{"start": start, "end": end, "text": "원문"}]}, 0, 0, False)


def test_text_without_segments_is_not_a_successful_timestamped_transcript():
    with pytest.raises(MediaError):
        stt._normalize_segments({"text": "원문만 있음"}, 0, 0, False)


def test_silent_audio_may_return_empty_segments():
    assert stt._normalize_segments({"text": "", "segments": []}, 0, 0, False) == []


def test_overlapping_speakers_are_preserved_and_chunk_identities_are_not_merged():
    result = {"segments": [{"start": 1, "end": 3, "text": "첫 발언", "speaker": "A"},
                           {"start": 2, "end": 4, "text": "겹친 발언", "speaker": "B"}]}
    a = stt._normalize_segments(result, 0, 0, True)
    b = stt._normalize_segments(result, 600000, 1, True)
    assert a[0]["endMs"] > a[1]["startMs"]
    assert a[0]["speaker"] != b[0]["speaker"]
