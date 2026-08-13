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
| GET | `/videos` | 영상 목록 (최근 100건) | 200 |
| DELETE | `/videos/{id}` | 영상 + 분석 결과 + 파일 삭제 | 200 |
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
| `entity-check` | `EntityCheckAnalyzer` | OpenAI | **이름·날짜·수치 확인** | SPEECH |
| `context-check` | `ContextCheckAnalyzer` | OpenAI | **언급된 주제의 최근 배경** | SPEECH/CAPTION |
| `speech-review` | `SpeechReviewAnalyzer` | OpenAI | 발언 중 확인할 지점 | SPEECH |
| `screen-text-review` | `ScreenTextReviewAnalyzer` | OpenAI | 화면 자막 중 확인할 지점 | CAPTION |
| `subtitle` | `SubtitleAnalyzer` | 없음 | 대본 금지어·개인정보 (안전망) | SPEECH |
| `screen-text` | `ScreenTextAnalyzer` | 없음 | 화면 자막 금지어 (안전망) | CAPTION |
| `caption-mismatch` | `CaptionMismatchAnalyzer` | OpenAI | 발언↔자막 비교 — **기본 비활성** | CAPTION |
| `monetization` | `MonetizationRiskAnalyzer` | OpenAI | 노란딱지 예측 — **기본 비활성** | SPEECH/CAPTION |
| `comment`, `pose` | — | — | **범위에서 제외** | — |

`entity-check` 와 `context-check` 가 P0 핵심입니다.

### 발언과 자막은 따로 봅니다

`subtitle`, `speech-review`, `entity-check` 는 STT 대본만 봅니다.
`screen-text`, `screen-text-review` 는 OCR 자막만 봅니다.
서로 참조하지 않습니다.

각자 찾은 것을 마지막 병합 단계에서 합칩니다.
같은 지점이 양쪽에서 나오면 한 건으로 묶고 `crossModal` 로 표시합니다.

### 발언↔자막 비교를 왜 껐나

OCR 은 화면의 모든 글자를 읽습니다.
편집 자막과 간판, 메뉴판, 채널 로고를 구분하지 못합니다.

그래서 "발언은 '할머니의 살을 뜯는 거 같다' 인데 자막은 'POGUES' 다" 같은
결과가 대부분이었습니다. 확인할 가치가 없습니다.

자막이 발언과 다른 것 자체는 알릴 이유가 없고,
**자막이나 발언의 내용에 확인할 것이 있을 때만** 알리는 것이 맞습니다.
화면 텍스트에서 편집 자막만 골라낼 수 있게 되면 다시 켭니다.

### 같은 지적은 한 번만

같은 것을 두고 분석기마다 다른 유형으로 보고합니다.
"패스트푸드" 를 한쪽은 비하로, 다른 쪽은 일반화로 잡는 식입니다.
사용자에게는 같은 지적이므로 합칩니다.

병합 조건은 셋입니다.

1. **대상이 같으면** 유형이 달라도 한 건 (`target` 비교)
2. 문장이 사실상 같으면 한 건 (고정 자막이 프레임마다 잡히는 경우)
3. 같은 유형이고 시간이 3초 이내면 한 건

병합된 건은 등장 시각을 함께 보여줍니다.
`(등장: 00:26, 00:35, 00:44)` 처럼 나가므로 어디를 봐야 할지 바로 압니다.

발언과 화면을 각각 **룰 + LLM 두 겹**으로 본다.
룰은 키가 없어도 도는 안전망이고, 열거할 수 없는 유형은 LLM이 맡는다.

### 판정하지 않는다

모든 프롬프트에 같은 원칙을 넣었다.

제작자는 영상을 수십 번 봤고, 알면서 넣은 장면도 있다.
"이 발언은 부적절합니다", "논란 가능성 85%" 같은 출력은
제작자의 가치판단과 충돌하고 "나도 알아, 그래서 넣은 건데" 로 끝난다.

그래서 출력을 이렇게 바꿨다.

```
전: 논란 위험 85% — 이 표현은 문제가 있습니다
후: 확인 필요 — 특정 세대를 하나로 묶는 표현입니다.
    의도한 범위가 맞는지 확인해 보세요. 최종 판단은 제작자가 하시면 됩니다.
```

### 노란 딱지 예측 (기본 비활성)

유튜브 광고주 친화 가이드라인 14개 주제로 판정하는 기능이 구현돼 있지만
`enabled-analyzers` 에서 빼 두었다.

광고 정책 문제라서 지금 풀려는 편집·정확성 문제와 성격이 다르다.
별도 기능으로 분리하는 것이 맞다고 보고 보류했다.
필요하면 yml 에서 주석 한 줄만 풀면 된다.

### 영상 유형

`TALK_PODCAST` 와 `GENERAL` 두 가지다.
토크·인터뷰일 때 배경 확인 범위를 넓게(주제 8개까지) 잡는다.

경제·정책·투자 유형은 뺐다. 대본 기반이라 대본만 검토해도 대부분 해결되고,
영상 단위로 볼 이유가 약했다.

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

## 데이터 관리

### 스키마

`ddl-auto: update` 로 엔티티에서 자동 생성한다. 마이그레이션 도구는 아직 없다.
엔티티를 계속 고치는 중이라 손으로 SQL 을 관리하면 속도가 떨어져서 미뤘다.
**배포할 때는 Flyway 를 붙이는 것을 권한다.** `update` 는 컬럼 삭제나 타입 변경을 하지 않는다.

### 삭제

`DELETE /api/v1/videos/{id}` 는 DB 행과 디스크 파일을 함께 지운다.

JPA cascade 대신 `VideoDeletionService` 가 순서를 직접 관리한다.
`risk_finding` 과 `screen_text` 가 `video_frame` 을 참조하므로 프레임보다 먼저 지워야 한다.
순서를 틀리면 외래키 제약에 걸린다.

분석이 진행 중이면 409 로 거절한다. 백그라운드 작업이 사라진 데이터를 건드리면 깨진다.

### 자동 정리

영상 하나에 원본 수십 MB 와 프레임 이미지가 쌓인다. 지우지 않으면 디스크가 조용히 찬다.

```yaml
oops:
  storage:
    retention-days: 0     # 0 이면 정리하지 않음
```

`0` 보다 크면 매일 새벽 4시에 그만큼 지난 영상을 지운다.
분석이 끝난 것만 대상이며 진행 중인 것은 건드리지 않는다.

로컬은 `0` 으로 두었다. 개발 중에 테스트 영상이 사라지면 곤란하다.
**배포 서버에서는 7 정도로 켜는 것을 권한다.**

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
