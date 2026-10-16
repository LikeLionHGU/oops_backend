# oops-analysis

2026-10-16 · Spring이 호출하는 Python FastAPI 분석 서버다. 기본 전사는 Groq `whisper-large-v3`이며 OCR·선택 프레임 추출은 로컬에서 수행한다.

## 설치·실행

`ffmpeg`·`ffprobe`가 PATH에 있어야 한다. 설치 예시는 Python 3.11 환경을 기준으로 한다. 기존 환경이 있으면 해당 환경을 활성화한다.

```bash
cd oops-analysis
python -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
```

STT만 설치하려면 `requirements-core.txt`를 사용하고, OCR은 `requirements-ocr.txt`로 추가한다. PaddleOCR은 첫 실행에 모델 다운로드가 필요할 수 있다.

최초에만 `.env.example`을 `.env`로 복사한다. 기존 파일은 덮어쓰지 않고 수정한다.

```env
GROQ_API_KEY=발급받은_Groq_키
STT_MODEL=whisper-large-v3
MEDIA_STORAGE_ROOT=../oops-backend/uploads
```

```bash
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Windows는 `.venv\Scripts\Activate.ps1`로 환경을 활성화한 뒤 같은 Python 명령을 사용한다. HTTP 요청은 PowerShell 별칭인 `curl` 대신 `curl.exe` 또는 `Invoke-RestMethod`를 사용한다.

`.env`는 실행 디렉터리를 기준으로 읽는다. 설정·키 변경은 프로세스 재시작 후 반영되며, 이미 켠 다른 터미널의 프로세스에 `export`가 소급 전달되지는 않는다.

## STT 모델과 동일 기능 계약

| 선택 | 키 | 시간 응답 |
|---|---|---|
| 기본 `whisper-large-v3` · Groq | `GROQ_API_KEY` | `verbose_json`, 구간 시작·끝 |
| `STT_MODEL=whisper-1` · OpenAI | `OPENAI_API_KEY` | 같은 구간 시간 요청 |
| `STT_MODEL=gpt-4o-transcribe-diarize` · 선택 실험 | `OPENAI_API_KEY` | `diarized_json`, 구간·화자 |

선택 순서는 `STT_MODEL` → `WHISPER_MODEL` → 코드 기본 `whisper-large-v3`다. 환경변수나 기존 `.env`에 모델을 명시하면 그 값이 기본값보다 우선한다. 지원하지 않는 모델명이나 키 누락은 실패하며 다른 제공자로 자동 전환하지 않는다.

Groq와 OpenAI Whisper는 동일한 16kHz mono·64kbps MP3 변환, 600초 분할, `timestamp_granularities=["segment"]` 요청, 원본 기준 밀리초 변환을 사용한다. 단어별 시간은 요청하지 않는다. 모델에 따라 전사 문구·구간 경계는 달라질 수 있다.

응답에는 `segments[].startMs/endMs/text`, `model`, `timestampSource`가 포함된다. Groq의 시간 출처는 `groq_segment`, OpenAI Whisper는 `whisper_segment`다. 유효한 구간 시간 없이 텍스트만 오면 성공 대본으로 처리하지 않는다. 전사 내용을 문맥으로 조용히 교정하지 않는다.

Diarize의 청크별 화자 ID는 전역 인물 ID가 아니다. Python 화자 응답을 Spring의 화자별 판정에 연결하는 기능은 후속이다.

## 엔드포인트

| 메서드·경로 | 기능 |
|---|---|
| GET `/health` | 서버 상태·선택 STT의 키 설정·OCR 사용 가능 여부 |
| POST `/probe` | 길이·제한 확인 |
| POST `/transcribe` | 전사와 구간 시간 |
| POST `/ocr` | 화면 글자·영역·프레임 |
| POST `/scene-frames` | 지정 시각의 선택 프레임 |

`sttAvailable=true`는 선택 모델의 키가 설정됐다는 의미이며 외부 API 인증·과금·전사 품질 검증은 아니다. OCR 미설치·처리 실패는 `/ocr`에서 503으로 전달한다.

probe/transcribe/ocr 요청 예시:

```json
{
  "videoUrl": null,
  "filePath": "/실제_공유저장소/videos/1/original.mp4",
  "intervalSec": 4.0
}
```

로컬 원본은 `MEDIA_STORAGE_ROOT/videos/` 아래만 허용한다. OCR의 `frameDir`은 같은 저장소 `frames/` 하위여야 한다. `videoUrl`을 사용하면 HTTPS YouTube URL을 전달하며 yt-dlp 다운로드 가능 여부에 영향을 받는다.

장면 요청은 업로드 원본의 `filePath`와 최대 3개의 정수 `timestampsMs`를 사용한다.

## 길이·비용·관측 한계

기본 `MAX_DURATION_SEC=5400`은 90분이다. `MAX_OCR_FRAMES=300`을 넘으면 프레임 간격이 늘어나므로 짧게 표시된 자막을 놓칠 수 있다. OCR 글자가 없다고 자막이 없다고 확정하지 않는다.

Groq STT의 현재 설정 단가 추정은 분당 $0.00185다. 60분은 약 $0.111이며 환율 1,400원 가정 시 약 155원이다. 텍스트 판단 비용·실제 청구액은 별도다. Spring 비용 추정 단가는 Spring 설정에서 관리한다.

[Groq 후속 점검](../docs/Groq-전사-후속-점검.md)에서 v4·v6 전사가 실제 완료됐다. 핵심 질문과 일부 누락 대화는 개선됐으나 줄임말 오류·전체 정확도·실제 음성 정렬 오차는 남은 평가 과제다. 후속 후보 엔진의 실패를 전사 성공과 혼동하지 않는다.

전체 실행 순서는 [루트 README](../README.md), 서버 구조는 [아키텍처](../docs/서버-아키텍처.md)를 따른다.
