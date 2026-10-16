import io
from types import SimpleNamespace

import httpx
import pytest
from openai import OpenAI
from app import stt
from app.config import Settings
from app.media import MediaError


def test_whisper_keeps_existing_timestamp_request():
    assert stt._request_options("whisper-1") == {
        "model": "whisper-1", "response_format": "verbose_json", "timestamp_granularities": ["segment"]}


def test_groq_whisper_large_v3_requests_segment_timestamps():
    assert stt._request_options("whisper-large-v3") == {
        "model": "whisper-large-v3", "response_format": "verbose_json",
        "timestamp_granularities": ["segment"]}


def test_groq_whisper_large_v3_is_the_default_stt_model(monkeypatch):
    monkeypatch.delenv("WHISPER_MODEL", raising=False)
    monkeypatch.delenv("STT_MODEL", raising=False)
    assert Settings(_env_file=None).whisper_model == "whisper-large-v3"


def test_groq_whisper_uses_groq_key_and_openai_compatible_endpoint():
    settings = SimpleNamespace(groq_api_key="offline-test-key")
    assert stt._client_options("whisper-large-v3", settings) == {
        "api_key": "offline-test-key", "base_url": "https://api.groq.com/openai/v1"}


def test_groq_whisper_sends_timestamped_transcription_request_without_network():
    def handler(request):
        assert request.url.host == "api.groq.com"
        assert request.url.path == "/openai/v1/audio/transcriptions"
        body = request.read().decode()
        assert 'name="model"\r\n\r\nwhisper-large-v3' in body
        assert 'name="response_format"\r\n\r\nverbose_json' in body
        assert 'name="timestamp_granularities[]"\r\n\r\nsegment' in body
        return httpx.Response(200, json={"text": "확인된 발언", "segments": [
            {"start": 0.5, "end": 1.5, "text": " 확인된 발언"}]})

    client_options = stt._client_options("whisper-large-v3", SimpleNamespace(groq_api_key="offline-test-key"))
    with OpenAI(**client_options, http_client=httpx.Client(transport=httpx.MockTransport(handler))) as client:
        result = client.audio.transcriptions.create(file=("clip.wav", io.BytesIO(b"fake")),
                                                    **stt._request_options("whisper-large-v3"))
    assert stt._normalize_segments(result, 0, 0, False) == [
        {"startMs": 500, "endMs": 1500, "text": "확인된 발언"}]


def test_groq_whisper_requires_groq_key_without_openai_fallback():
    settings = SimpleNamespace(groq_api_key="")
    with pytest.raises(MediaError, match="GROQ_API_KEY"):
        stt._client_options("whisper-large-v3", settings)


def test_openai_whisper_keeps_openai_client_configuration():
    settings = SimpleNamespace(openai_api_key="openai-test-key", openai_org_id="org-test",
                               openai_project_id="project-test")
    assert stt._client_options("whisper-1", settings) == {
        "api_key": "openai-test-key", "organization": "org-test", "project": "project-test"}


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
