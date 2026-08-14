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

타깃이 20~60분 롱폼이라 **60분까지** 받는다.

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
