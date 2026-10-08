# oops-analysis

Spring 백엔드가 호출하는 Python 분석 서버.
STT(Whisper API)와 화면 자막 OCR(PaddleOCR)을 담당한다.

## 실행

```bash
python -m venv .venv && source .venv/bin/activate   # 윈도우: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env      # OPENAI_API_KEY 채우기
uvicorn app.main:app --reload --port 8000
```

**ffmpeg가 PATH에 있어야 한다.**
- Windows: `winget install Gyan.FFmpeg`
- Mac: `brew install ffmpeg`

PaddleOCR은 첫 실행 때 모델을 자동 다운로드한다 (수백 MB, 몇 분 걸림).
OCR 없이 STT만 먼저 테스트하려면 `pip install`에서 paddle 계열을 빼도 되고,
그 경우 `/ocr`은 503을 돌려주며 Spring 쪽에서 자동으로 건너뛴다.

> **윈도우에서 curl 쓸 때**
> PowerShell의 `curl`은 진짜 curl이 아니라 `Invoke-WebRequest` 별칭이라 보안 경고가 뜬다.
> `curl.exe` 로 쓰거나 `Invoke-RestMethod` 를 쓰면 된다.
> ```powershell
> curl.exe http://localhost:8000/health
> Invoke-RestMethod http://localhost:8000/health
> ```

## 긴 영상

### STT 모델 비교: 기본값은 유지

`STT_MODEL`이 비어 있으면 기존 `WHISPER_MODEL`(기본 `whisper-1`)을 사용한다.
선택 실험용으로 `STT_MODEL=gpt-4o-transcribe-diarize`를 지원한다. 모델 변경은 Python 프로세스에 설정을 적용해 다시 시작해야 반영된다. 자동 재전사나 유료 fallback은 없다.

- Whisper는 기존 구간 시간 요청을 유지한다. Diarize는 `diarized_json`과 `chunking_strategy=auto`를 요청한다.
- 구간 시간이 없거나 잘못된 응답은 실패 처리한다. 임의로 0초를 붙이거나 원문을 추측해 교정하지 않는다.
- Python 응답에 `model`, `timestampSource`, 선택적 `speaker`를 제공한다. 청크별 화자 ID는 별개이며 Spring 저장/판정 연결은 아직 구현하지 않았다.
- `gpt-transcribe` 등 정렬 미지원 모델을 이름만 바꿔 쓰지 않는다. 별도 시간 정렬 설계가 필요하다.
- Diarize의 방언 정확도 우위는 미검증이다. 실제 비교 전 사람 확인 대본·시간 기준이 필요하고, Spring 비용 추정도 해당 모델 요금에 맞춰 별도로 수정해야 한다.

요청 제약은 [OpenAI 음성 전사 공식 문서](https://developers.openai.com/api/docs/guides/speech-to-text#speaker-diarization)를 따른다. SDK 1.59.6의 실제 요청/응답 처리는 네트워크 없는 모의 HTTP 테스트로 검증했다. 실제 API 접근 가능성·품질을 검증한 것은 아니다.

타깃이 20~60분 롱폼인데 실제로는 70~80분짜리도 흔해서 **90분까지** 받는다.

> 긴 영상은 비용과 시간이 크게 는다.
> 60분이면 음성 인식만 약 500원, 대본이 300줄을 넘어 LLM 호출이 수십 번 나간다.
> 요청 한도가 낮은 계정에서는 중간에 끊길 수 있다.
> **시연은 3~5분짜리로 하는 것을 권한다.**

프레임 수는 영상 길이와 무관하게 **300장으로 묶여 있다.**
간격을 고정하면 60분 영상에 900장이 되어 인식에만 7분 넘게 걸린다.
길면 간격이 자동으로 늘어난다.

| 영상 길이 | 간격 | 프레임 |
|---|---|---|
| 5분 | 4초 | 75장 |
| 20분 | 4초 | 300장 |
| 40분 | 8초 | 300장 |
| 60분 | 12초 | 300장 |

자막은 보통 몇 초씩 유지되므로 간격이 벌어져도 대부분 잡힌다.
놓치는 것보다 아예 끝나지 않는 게 더 나쁘다.

상한은 `.env` 에서 조절한다 (`MAX_DURATION_SEC`, `MAX_OCR_FRAMES`).

## 엔드포인트

| Method | Path | 설명 |
|---|---|---|
| GET | `/health` | 헬스체크 + 기능별 사용 가능 여부 |
| POST | `/transcribe` | 음성 → 타임스탬프 대본 |
| POST | `/ocr` | 프레임 → 화면 자막 텍스트 |

두 엔드포인트 모두 요청 바디는 같다:

```json
{ "videoUrl": "https://youtube.com/watch?v=...", "filePath": null, "intervalSec": 2.0 }
```

`videoUrl`이면 yt-dlp로 받고, `filePath`면 로컬 파일을 쓴다.
`filePath`는 `MEDIA_STORAGE_ROOT/videos/` 아래 원본만 허용하고, OCR 프레임은 같은 저장소의 `frames/` 아래에만 쓴다. 로컬 기본값은 `../oops-backend/uploads`이며, 배포 시 Spring의 `oops.storage.location`과 같은 공유 경로로 `MEDIA_STORAGE_ROOT`를 설정한다. YouTube 등록은 HTTPS YouTube 도메인 URL만 허용한다.
