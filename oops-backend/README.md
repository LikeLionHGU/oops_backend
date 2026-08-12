# oops-backend

AI로 영상의 논란 소지를 잡아주는 서비스 — 백엔드

영상을 넣으면 **몇 분 몇 초에 어떤 논란이 있는지** Timeline Report로 돌려준다.
외부 API는 팀 API 명세(Creator Risk Manager v2.0)를 따른다.

## 시스템 구성

```
[프론트]
   │  REST /api/v1  +  STOMP WebSocket /ws
[oops-backend]  Spring Boot 4 / Java 17 / JPA
   │                    └─ OpenAI Chat API (발언 리스크·자막 비교 판정)
   │  HTTP
[oops-analysis] FastAPI / Python
   ├─ yt-dlp + ffmpeg   (영상 확보, 오디오·프레임 추출)
   ├─ OpenAI Whisper    (STT → 타임스탬프 대본)
   └─ PaddleOCR         (화면 자막 인식 + 프레임 보관)
```

명세 §11은 Python이 분석 전부를 하고 Spring에 콜백하는 구조지만,
현재 구현은 **Spring이 오케스트레이터**이고 Python은 STT/OCR 전용이다.
프론트가 보는 `/api/v1/**` 계약은 명세와 100% 동일하므로 프론트 개발에는 영향이 없다.

## 실행

```bash
# 1) Python 분석 서버 (별도 터미널)
cd ../oops-analysis && .\run.ps1

# 2) 백엔드
$env:OPENAI_API_KEY="sk-..."
./gradlew bootRun          # 또는 IntelliJ 에서 실행 + 환경변수 등록
```

기본 프로파일이 `local`이라 DB 설치 없이 뜬다(H2 인메모리).
공용 MySQL은 `--spring.profiles.active=dev`.

```powershell
.\scripts\smoke-test.ps1 -File "C:\경로\영상.mp4"
```

> **윈도우에서 curl 쓸 때**
> PowerShell의 `curl`은 `Invoke-WebRequest` 별칭이라 보안 경고가 뜬다.
> `curl.exe` 또는 `Invoke-RestMethod` 를 쓸 것.

## API

Base URL: `/api/v1`

| Method | Path | 설명 | Status |
|---|---|---|---|
| POST | `/videos` | 영상 업로드 (multipart `file`). **업로드 즉시 분석 시작** | 201 |
| POST | `/videos` | 유튜브 URL 등록 (JSON `{url}`) — 명세 외 확장 | 201 |
| GET | `/videos/{id}/status` | 분석 상태 | 200 |
| GET | `/videos/{id}/report` | Timeline Report | 200 |
| POST | `/videos/{id}/analysis/retry` | 분석 재시도 (새 jobId 발급) | 202 |
| GET | `/videos/{id}/stream` | 영상 재생 (HTTP Range) | 200 / 206 |
| GET | `/videos/{id}/frames/{frameId}` | 프레임 이미지 | 200 |
| GET | `/videos/{id}/transcript` | STT 대본 원문 (디버깅) | 200 |
| GET | `/videos/{id}/screen-texts` | OCR 화면 자막 원문 (디버깅) | 200 |

WebSocket: `/ws` (STOMP, SockJS 폴백) → `/topic/videos/{videoId}/progress` 구독

### 응답 형식

성공:

```json
{ "success": true, "message": "요청 성공", "data": { } }
```

에러:

```json
{
  "success": false,
  "message": "지원하지 않는 영상 형식입니다.",
  "error": { "code": "UNSUPPORTED_VIDEO_FORMAT", "traceId": "6adf4c7c2f8a4b42" }
}
```

`traceId`는 서버 로그에서 같은 요청을 찾는 데 쓴다. 에러 문의할 때 이 값을 알려주면 된다.

### 에러 코드

`VIDEO_NOT_FOUND` `FRAME_NOT_FOUND` `UNSUPPORTED_VIDEO_FORMAT` `MAX_UPLOAD_SIZE_EXCEEDED`
`ANALYSIS_IN_PROGRESS` `ANALYSIS_NOT_COMPLETED` `INVALID_ANALYSIS_STATE`
`WORKER_UNAVAILABLE` `ANALYSIS_FAILED` `INVALID_REQUEST` `INTERNAL_SERVER_ERROR`

### `/report` 응답

```json
{
  "success": true,
  "message": "분석 결과 조회 성공",
  "data": {
    "videoId": 123,
    "jobId": "job_8fc391",
    "status": "COMPLETED",
    "summary": { "high": 2, "medium": 3, "low": 5 },
    "events": [
      {
        "id": 1,
        "startMs": 42300, "endMs": 46100,
        "type": "SPEECH", "severity": "HIGH",
        "text": "문제가 될 수 있는 발언",
        "riskTypes": ["GENERALIZATION"],
        "reason": "특정 집단에 대한 일반화 표현입니다."
      },
      {
        "id": 2,
        "startMs": 73100, "endMs": 76000,
        "type": "CAPTION", "severity": "MEDIUM",
        "speechText": "원래 발언",
        "captionText": "화면에 나온 자막",
        "reason": "실제 발언보다 자극적으로 표현되었습니다.",
        "frameUrl": "/api/v1/videos/123/frames/2"
      }
    ]
  }
}
```

`events`는 **우선순위 내림차순**이다. 프론트는 위에서부터 그리면 된다.
`type`이 discriminator이고, 해당 타입에 없는 필드는 JSON에서 아예 빠진다
(`default-property-inclusion: non_null`).

## 유튜브 링크 분석

```powershell
.\scripts\smoke-test.ps1 -Url "https://www.youtube.com/watch?v=..."
```

또는 `POST /api/v1/videos` 에 `{"url": "..."}` 를 JSON 으로 보낸다.
응답 형태는 파일 업로드와 같다.

**주의할 점**

- `GET /stream` 이 동작하지 않는다. 로컬에 파일이 남지 않기 때문이다.
  프론트에서는 유튜브 임베드를 쓰거나 파일 업로드를 안내한다.
- yt-dlp 가 오래되면 유튜브가 막는다. 다운로드 실패 시 먼저 이것부터 시도할 것.
  ```powershell
  cd ..\oops-analysis
  .\.venv\Scripts\activate
  pip install --upgrade yt-dlp
  ```
- 연령 제한·비공개·지역 제한 영상은 받을 수 없다.
- 분석 서버가 영상을 임시 폴더에 받았다가 끝나면 지운다. 디스크 여유가 필요하다.

## 분석 흐름

| stage | progress | 하는 일 |
|---|---|---|
| `STT` | 15 | Whisper로 타임스탬프 대본 생성 |
| `OCR` | 35 | 2초 간격 프레임에서 화면 자막 인식 + 프레임 보관 |
| `TEXT_RISK` / `OCR` / `MULTIMODAL` | 45~80 | 활성 분석기 실행 |
| `MULTIMODAL` | 85 | 후보 병합 + 우선순위 |
| `FINALIZING` | 92 | 리포트 집계 |
| `COMPLETED` | 100 | 완료 |

단계가 바뀔 때마다 `/topic/videos/{id}/progress`로 `ProgressMessage`가 나간다.

### 분석기

| key | 클래스 | 필요한 키 | 하는 일 | 이벤트 타입 |
|---|---|---|---|---|
| `subtitle` | `SubtitleAnalyzer` | 없음 | 대본 욕설·혐오·개인정보 룰 탐지 | SPEECH |
| `speech-risk` | `SpeechRiskAnalyzer` | OpenAI | 조롱·비하·일반화·민감주제 문맥 판정 | SPEECH |
| `screen-text` | `ScreenTextAnalyzer` | 없음 | 화면 자막 룰 탐지 | CAPTION |
| `screen-text-risk` | `ScreenTextRiskAnalyzer` | OpenAI | 화면 자막 LLM 판정 (OCR 깨짐 보정 포함) | CAPTION |
| `caption-mismatch` | `CaptionMismatchAnalyzer` | OpenAI | 발언 vs 자막 대조 — **기본 비활성** | CAPTION |
| `fact-check` | `FactCheckAnalyzer` | OpenAI | **주장이 사실인지** (경제·투자 영상만) | SPEECH |
| `timeliness` | `TimelinessAnalyzer` | OpenAI | **지금 시점에 다뤄도 되는 주제인지** | SPEECH/CAPTION |
| `comment` | `CommentAnalyzer` | — | **미구현** (스텁) | — |
| `pose` | `PoseAnalyzer` | — | **미구현** (스텁) | — |

발언과 화면을 각각 **룰 + LLM 두 겹**으로 본다.
룰은 키가 없어도 도는 안전망이고, 열거할 수 없는 유형은 LLM이 맡는다.

### 왜 발언과 자막을 대조하지 않나

`caption-mismatch` 는 만들었지만 껐다.

자막이 발언과 다른 것은 원래 정상이다. 예능 자막, 요약 자막, 효과음 표기는
발언을 그대로 옮기지 않는다. 여기에 OCR 오인식까지 겹쳐서
("부모를 놀라게 울우 아이의 종이로가려진") 오탐이 대부분이었다.

지금은 **발언과 화면을 각각 독립적으로 분석**하고,
같은 시간대에 같은 유형이 잡히면 병합 단계에서 한 건으로 합친다.
양쪽에서 확인된 건은 근거가 강하므로 점수를 올린다.

### 영상 유형별로 다르게 본다

경제 해설물은 **틀린 숫자 하나**가 곧 논란이 되고,
인터뷰는 **발언이 공개 시점의 이슈와 맞물릴 때** 논란이 된다.
같은 잣대로 보면 둘 다 놓친다.

그래서 분석 전에 영상 유형을 정하고, 유형에 맞는 분석기만 돌린다.

| 유형 | 무엇을 중점적으로 보나 |
|---|---|
| `ECONOMY_POLICY` | 사실 검증. 수치·정책·인과 주장을 기사와 대조 |
| `INVESTMENT_FINANCE` | 사실 검증 + 단정적 전망 |
| `INTERVIEW_PODCAST` | 시의성. 발언과 인물을 최근 이슈와 대조 (주제를 8개까지 확대) |
| `GENERAL` | 공통 분석만 |

유형은 업로드할 때 `genre` 로 지정할 수 있고, 비워두면 대본을 보고 자동 판별한다
(`GenreDetector`). 애매하면 `GENERAL` 로 떨어지므로 억지 분류는 하지 않는다.

### 사실 검증 (`FactCheckAnalyzer`)

경제·정책·투자 영상에서만 돌아간다. 브이로그에 팩트체크는 의미가 없다.

LLM 은 통계를 정확히 외우지 못하고 학습 시점 이후 수치는 아예 모른다.
그래서 검색을 끼워 세 단계로 나눴다.

1. 대본에서 **검증 가능한 주장**만 뽑는다 (의견·전망은 제외)
2. 각 주장을 뉴스에서 찾아본다
3. 기사와 대조해 판정한다

판정 결과는 네 가지다.

| 카테고리 | 뜻 |
|---|---|
| `FACT_ERROR` | 기사와 명백히 어긋남 |
| `MISINFORMATION` | 틀리진 않았지만 맥락을 빼 오해를 부름 |
| `UNVERIFIED_CLAIM` | 근거를 찾을 수 없음 |
| `OVERCONFIDENT_FORECAST` | 불확실한 미래를 확정처럼 말함 |

확인된 주장(`OK`)은 보고하지 않는다. 문제가 있는 것만 올린다.

### 시의성 검토 (`TimelinessAnalyzer`)

"재선거" 같은 표현은 그 자체로는 문제가 없다. 그런데 마침 재선거가 진행 중인 시점이라면
같은 영상이라도 반응이 완전히 달라진다. LLM은 학습 시점 이후의 뉴스를 모르므로
혼자서는 이 판단을 할 수 없다. 그래서 세 단계로 나눴다.

1. 대본·자막에서 시사성 있는 주제를 뽑는다 (LLM)
2. 그 주제로 최근 뉴스를 검색한다 (네이버, 최신순)
3. **오늘 날짜**와 기사 목록을 함께 주고 위험한지 판단하게 한다 (LLM)

주제는 최대 5개까지만 검색해서 비용과 시간을 제한한다.

뉴스 소스는 `NewsSearchClient` 구현체 중 사용 가능한 것을 자동으로 고른다.

| 구현체 | 키 | 비고 |
|---|---|---|
| `NaverNewsSearchClient` | 필요 | 키가 있으면 우선. 국내 매체 커버리지가 넓다 |
| `GoogleNewsRssSearchClient` | **불필요** | 기본값. 공개 RSS 라 비용 0 |

**추가 키 없이 바로 동작한다.** 네이버 키는 정확도를 올리고 싶을 때만 넣으면 된다.

`application.yml`의 `oops.analysis.enabled-analyzers`에서 켜고 끈다.
새 분석기는 `ContentAnalyzer` 구현체를 `@Component`로 만들고 key만 추가하면 되며,
파이프라인 코드는 건드리지 않는다.

### 다중 후보 병합 (`FindingFusionService`)

음성과 화면을 따로 분석하면 같은 장면이 여러 번 잡힌다.

1. **클러스터링** — 카테고리가 같고 시간이 3초 이내면 한 묶음
2. **대표 선정** — 확신도 최고 1건만 남김 (`mergedCount`에 원래 개수 기록)
3. **교차 검증** — 발언·화면 양쪽에서 잡힌 묶음은 점수 ×1.25, 화면 캡처를 대표에 붙여줌
4. **우선순위** — `확신도(600) + 카테고리 중요도(100) + 교차검증(150) + 중복보고(60)`

그래서 `events[0]`이 "가장 먼저 확인해야 할 논란"이 된다.

## 저장소 규칙

절대경로 대신 `storageKey`를 쓴다. 로컬에서는 `oops.storage.location` 기준 상대 경로다.

```
videos/{videoId}/original.mp4     원본 영상
frames/{videoId}/{timeMs}.jpg     OCR 에 쓰인 화면 캡처
```

나중에 S3로 바꿀 때 `StorageService`만 교체하면 된다.

## 환경변수

| 변수 | 필요한 곳 | 없으면 |
|---|---|---|
| `OPENAI_API_KEY` | Spring, Python | LLM 분석기 4종 스킵, 룰만 동작 |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | Spring | 구글 뉴스 RSS 로 자동 폴백 (선택 사항) |
| `OPENAI_ORG_ID` | Spring, Python | 계정 기본 조직으로 과금 |

네이버 키는 **선택 사항**이다. 없으면 구글 뉴스 RSS 를 쓴다.
정확도를 올리고 싶으면 [developers.naver.com](https://developers.naver.com/apps/#/register)에서
애플리케이션 등록 → **검색 API** 선택으로 무료 발급받으면 된다.

분석 서버 없이 파이프라인만 보려면 `oops.use-dummy-transcript: true`.

> **코드를 고쳤으면 반드시 재시작할 것**
> Python(`ocr.py`, `media.py`)과 Spring은 별개 프로세스다.
> 한쪽만 재시작하면 결과가 이전과 똑같이 나와서 수정이 반영 안 된 걸 눈치채기 어렵다.

## 명세 대비 아직 안 한 것

- **§11 Python 콜백 구조** — 현재는 Spring 오케스트레이션. 프론트 계약에는 영향 없음
- **§12 Internal API 보안** — internal 엔드포인트를 안 만들었으므로 해당 없음
- **§13 Idempotency** — 재시도 시 새 jobId 발급과 진행 중 409는 구현됨. 콜백 중복 방어는 콜백 도입 시 필요
- Swagger/OpenAPI 자동 문서 — `build.gradle`에 주석 처리해 둠
- `CommentAnalyzer`, `PoseAnalyzer`
