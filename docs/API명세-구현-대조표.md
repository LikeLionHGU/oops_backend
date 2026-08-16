# API 명세 ↔ 구현 대조표

> 명세 v3 기준 · 2026년 8월 16일

**결론: 프론트엔드가 사용하는 모든 항목이 명세와 일치합니다.**
차이가 나는 곳은 §11~13(백엔드 ↔ Python 내부 통신)뿐이고, 프론트에는 영향이 없습니다.

---

## 1. 공통 규칙

| 명세 | 구현 | 상태 |
|---|---|---|
| §1-1 Base URL `/api/v1` | 동일 | 일치 |
| §1-3 성공 `{success, message, data}` | 동일 | 일치 |
| §1-4 에러 `{success, message, error:{code, traceId}}` | 동일 | 일치 |
| §1-5 HTTP 상태 코드 | 200/201/202/400/404/409/413/415/416/500/503 | 일치 |
| §1-6 에러 코드 13종 | 전부 구현 | 일치 |
| §1-7 시간 단위 ms 정수 | `startMs`, `endMs` 모두 `long` | 일치 |
| §1-8 상태값 | `PENDING / PROCESSING / COMPLETED / FAILED` | 일치 |
| §1-9 단계값 | `UPLOAD / STT / TEXT_RISK / SCENE_DETECTION / OCR / MULTIMODAL / FINALIZING / COMPLETED` | 일치 |
| §1-10 심각도 | `LOW / MEDIUM / HIGH` | 일치 |

---

## 2. 엔드포인트

| 명세 | Method | Path | 상태 |
|---|---|---|---|
| §2-1 영상 업로드 | POST | `/api/v1/videos` | 일치 (201) |
| §3-1 분석 상태 조회 | GET | `/api/v1/videos/{id}/status` | 일치 |
| §4 WebSocket | WS | `/ws` → `/topic/videos/{id}/progress` | 일치 |
| §5-1 분석 결과 | GET | `/api/v1/videos/{id}/report` | 일치 |
| §7 영상 스트리밍 | GET | `/api/v1/videos/{id}/stream` | 일치 (Range 206) |
| §8 프레임 이미지 | GET | `/api/v1/videos/{id}/frames/{frameId}` | 일치 |
| §9-1 분석 재시도 | POST | `/api/v1/videos/{id}/analysis/retry` | 일치 (202) |
| §3-2 검수 이력 | GET | `/api/v1/videos` | 일치 |
| §9-2 검수 액션 저장 | POST | `/api/v1/videos/{id}/review-actions` | 일치 (200) |

### 응답 필드

명세에 적힌 필드명과 타입이 **그대로** 구현돼 있습니다.

```
VideoUploadResponse    videoId, jobId, filename, status, streamUrl
VideoStatusResponse    videoId, jobId, status, progress, stage, message (+errorCode)
ProgressMessage        videoId, jobId, status, progress, stage, message (+errorCode)
AnalysisReportResponse videoId, jobId, status, summary{high,medium,low}, events[]
                       (+coverage[], warnings[])
AnalysisRetryResponse  videoId, jobId, status
```

### Timeline Event (§6)

```
공통      id, startMs, endMs, type, severity, reason, frameUrl,
          candidateType, occurrences, references[]
SPEECH    text, riskTypes[]
CAPTION   captionText  (speechText 는 현재 비어 있음)
```

`type` 은 **어디서 나온 말인가**(발언/화면), `candidateType` 은 **왜 확인하는가** 입니다.

```
SPEECH_REVIEW        발언에서 다시 볼 표현·주장·대상
SCREEN_TEXT_REVIEW   화면 글자에서 다시 볼 표현·주장·대상
FACT_ENTITY          인물·회사·날짜·숫자를 외부 자료와 대조
CONTEXT_REFERENCE    지금 시점의 사건·배경 참고
VISUAL_REFERENCE     예약값 — 현재 안 나옵니다
CAPTION_CONSISTENCY  예약값 — 분석기가 꺼져 있어 안 나옵니다
```

`references[]` 가 붙는 건 `FACT_ENTITY` 와 `CONTEXT_REFERENCE` 뿐입니다.

> **CAPTION 의 `speechText` 는 항상 비어 있습니다.**
> 발언과 자막을 대조하던 분석기를 껐기 때문입니다.
> 자막이 발언과 다른 건 원래 정상이고(예능 자막, 요약 자막),
> OCR 오인식까지 겹쳐 오탐이 대부분이었습니다.
> 지금은 발언과 화면을 각각 독립적으로 분석합니다.

해당 타입에 없는 필드는 JSON에서 아예 빠집니다.
`events`는 **우선순위 내림차순**으로 정렬돼 나갑니다.

---

## 3. 명세와 다른 부분

### 3-1. Timeline Event DTO를 하나로 합침

명세 §15는 `SpeechTimelineEventDto` / `CaptionTimelineEventDto`를 나누자고 했지만,
자바에서 타입별 클래스를 나누면 컨트롤러 반환 타입이 복잡해집니다.

`TimelineEventDto` 하나로 두고 해당 없는 필드를 `null`로 뒀습니다.
**JSON 결과물은 명세와 완전히 동일**합니다.

### 3-2. `errorCode` — STOMP·REST 양쪽에 있습니다

분석이 실패했을 때 프론트가 원인별로 다른 안내를 띄울 수 있게 넣었습니다.
`ProgressMessage`(STOMP)와 `VideoStatusResponse`(REST) **둘 다** 같은 값을 줍니다.
WebSocket이 끊겨 폴링으로 넘어가도 화면 분기가 달라지지 않습니다.

성공 중에는 필드가 아예 빠집니다.

### 3-3. `TimelineEventDto` 에 `occurrences` 추가

같은 논란이 영상에서 몇 번 반복됐는지 알려줍니다.
영상 내내 떠 있는 고정 자막처럼 여러 번 잡히는 건은 한 건으로 병합하고,
`startMs`~`endMs` 가 그 전체 구간을 뜻하게 했습니다.

`1` 이면 한 번만 등장한 것이라 무시해도 됩니다. 명세에 없는 추가 필드입니다.

### 3-4. `TimelineEventDto` 에 `references` 추가

**AI 가 판단 근거로 실제로 본 기사입니다.** 프론트에서 링크로 걸어주세요.

```json
"references": [
  {
    "title": "OO그룹 창립 20주년...2020년 설립 후 성장세",
    "provider": "한국경제",
    "url": "https://...",
    "publishedAt": "Mon, 03 Aug 2026 09:12:00 GMT",
    "relevantContext": "기사에는 2020년 설립으로 나옵니다",
    "snippet": "2020년 설립된 OO그룹은..."
  }
]
```

`snippet` 은 기사에 있는 문장 그대로이고, `relevantContext` 는 **그래서 뭐가 확인됐는지** 입니다.
링크를 열기 전에 볼 수 있게 카드에 같이 보여주면 좋습니다.

**없으면 필드 자체가 빠집니다.** 빈 배열이 아니라 `undefined` 로 오므로
`event.references?.map(...)` 처럼 처리하면 됩니다.

붙는 카드는 `이름·수치 확인`(FACT_ERROR / MISINFORMATION / UNVERIFIED_CLAIM)과
`맥락 참고`(TIMING_SENSITIVE) 두 종류입니다. 나머지는 외부 검색을 하지 않아 근거가 없습니다.
카드당 최대 4건입니다.

이 필드가 필요한 이유는 이 도구가 **오탐을 낸다**는 전제 때문입니다.
"기사에는 2020년으로 나옵니다" 라는 문장만 주면 사용자는 AI 말을 믿을 수밖에 없습니다.
원문을 열어봐야 무관한 기사와 대조한 오탐인지 판단할 수 있습니다.
가능하면 **눈에 띄게** 노출해 주세요.

### 3-5. 업로드에 `genre` 추가 (선택)

영상 유형을 지정하면 그에 맞는 분석기가 돌아갑니다.

```
TALK_PODCAST / GENERAL
```

**선택 필드입니다.** 안 보내면 대본을 보고 자동으로 판별하므로
기존 요청은 그대로 동작합니다. 나중에 UI 에 유형 선택을 넣으면 정확도가 올라갑니다.

### 3-6. `/report` 에 `adSuitability`, `genre` 추가

```json
{
  "genre": "TALK_PODCAST",
  "adSuitability": "MONETIZED",
  "summary": { "high": 2, "medium": 3, "low": 5 },
  "events": [ ... ]
}
```

`adSuitability` 는 유튜브 노란 딱지 예측입니다.
**현재 해당 분석기는 기본 비활성이라 항상 `MONETIZED` 로 나옵니다.**
`MONETIZED` / `LIMITED` / `DEMONETIZED` 세 값이며, 가장 심한 구간을 기준으로 합니다.
구간별 문제는 `events` 안에 `AD_LIMITED`, `AD_DEMONETIZED` 카테고리로 들어갑니다.

둘 다 명세에 없는 추가 필드라 안 써도 무방합니다.

### 3-7. 에러 코드 `INVALID_REQUEST` 추가

필수 값 누락이나 잘못된 파라미터에 쓰는 400 코드입니다.
명세 §1-6 목록에는 없었지만 유효성 검사 실패를 표현할 코드가 필요했습니다.

---

## 3-8. `coverage[]` 와 `warnings[]` (명세 §5-1 · §19-5)

**후보 0건과 분석 실패를 구분하기 위한 필드입니다.**

```json
"coverage": [
  { "analyzer": "STT",               "status": "SUCCESS" },
  { "analyzer": "OCR",               "status": "SUCCESS" },
  { "analyzer": "SPEECH_REVIEW",     "status": "SUCCESS" },
  { "analyzer": "SCREEN_TEXT_REVIEW","status": "SUCCESS" },
  { "analyzer": "FACT_ENTITY",       "status": "FAILED",
    "message": "AI 요청 한도 초과로 이 단계를 수행하지 못했습니다." },
  { "analyzer": "CONTEXT_REFERENCE", "status": "SUCCESS" },
  { "analyzer": "VISUAL",            "status": "NOT_ENABLED" }
],
"warnings": [
  { "stage": "FACT_ENTITY",
    "code": "FACT_ENTITY_UNAVAILABLE",
    "message": "이름·수치 확인 — AI 요청 한도 초과로 이 단계를 수행하지 못했습니다." }
]
```

`status` 는 `SUCCESS` / `FAILED` / `SKIPPED` / `NOT_ENABLED` 입니다.
후보가 0건이어도 끝까지 돌았으면 `SUCCESS` 입니다.

`warnings[]` 는 그중 `FAILED` 와 `SKIPPED` 만 추린 것이고, **없으면 필드가 빠집니다.**

**값이 있으면 결과 위에 눈에 띄게 띄워 주세요.**
이게 없으면 "확인할 지점 0곳" 이 두 가지를 동시에 뜻하게 됩니다.

```
검수했더니 괜찮다
검수를 못 했다
```

후자를 전자로 읽고 영상을 올리면 이 도구는 없느니만 못합니다.

한 단계를 분석기 두 개가 나눠 맡는 경우 **나쁜 쪽**을 보고합니다.
룰 기반은 성공했는데 AI 검토가 한도에 걸렸다면 `FAILED` 로 나갑니다.

---

## 3-9. 검수 액션 `POST /api/v1/videos/{id}/review-actions` (명세 §9-2)

```json
{ "eventId": 12, "action": "EDITED", "note": "자막 표현 수정" }
```

`action` 은 `CONFIRMED` / `EDITED` / `HOLD` / `NOT_USEFUL`.
같은 `eventId` 를 다시 보내면 마지막 값으로 덮습니다.
다른 영상의 후보를 보내면 `EVENT_NOT_FOUND`(404) 입니다.

**새로고침 복구용으로 조회도 추가했습니다.** 명세에는 없지만
저장만 하고 못 읽으면 저장하는 의미가 없습니다.

```
GET /api/v1/videos/{id}/review-actions
```

주의: **재분석하면 검수 액션이 지워집니다.** 후보 id 가 새로 발급되므로
옛 액션은 엉뚱한 후보를 가리키게 됩니다.

---

## 3-10. 업로드 시 영상 길이 검증 (명세 §2-1)

90분을 넘으면 `400 MAX_VIDEO_DURATION_EXCEEDED` 로 거절하고 저장한 파일도 지웁니다.

```json
{ "success": false,
  "message": "영상이 120분입니다. 최대 90분까지 분석할 수 있습니다.",
  "error": { "code": "MAX_VIDEO_DURATION_EXCEEDED", "traceId": "..." } }
```

파이썬 서버에 `POST /probe` 를 추가해 길이만 재고 옵니다. 1초 안에 끝납니다.

두 가지 예외가 있습니다.

- **분석 서버가 꺼져 있으면 그냥 통과시킵니다.** 길이를 못 쟀다는 이유로
  업로드를 막으면 더 나쁩니다. 이 경우 분석 단계에서 다시 걸립니다
- **유튜브 링크 등록은 검사하지 않습니다.** 길이를 재려면 영상을 통째로
  받아야 해서 등록 응답이 몇 분씩 걸립니다. 분석 단계에서 걸립니다

---

## 3-11. 서버 재시작 복구 (명세 §18-3 P1-2)

분석은 메모리 위 스레드에서 돕니다. 서버가 죽으면 스레드는 사라지는데
DB 의 job 은 `PROCESSING` 인 채로 남습니다. 그러면 그 영상은:

```
GET /report      → 영원히 409 ANALYSIS_NOT_COMPLETED
진행률           → 그 자리에서 멈춘 채 끝나지 않음
재시도           → 409 ANALYSIS_IN_PROGRESS (이미 도는 줄 알고 막음)
삭제             → 409 (같은 이유)
```

**손쓸 방법이 없어집니다.** 시연 중에 서버를 껐다 켜면 바로 겪습니다.

이제 서버가 뜰 때 끊긴 job 을 찾아 `FAILED` 로 정리합니다.

```
[recovery] 서버 재시작으로 중단된 분석 2건을 정리했습니다.
```

`status: FAILED`, `errorCode: ANALYSIS_FAILED`,
`message: "서버가 재시작되어 분석이 중단되었습니다. 다시 시도해 주세요."` 로 나가므로
프론트는 재시도 버튼을 띄우면 됩니다.

**이어서 돌리지는 않습니다.** 어디까지 했는지 모르는 상태로 재개하면
중복 저장이나 반쯤 분석된 결과가 나옵니다.

---

## 3-12. 검수 품질 지표 `GET /api/v1/metrics` (명세 §18-4)

```json
{
  "totalCandidates": 47,
  "reviewedCandidates": 31,
  "actions": { "CONFIRMED": 12, "EDITED": 9, "HOLD": 4, "NOT_USEFUL": 6 },
  "acceptanceRate": 0.806,
  "editingActionRate": 0.29,
  "falsePositiveRate": 0.194,
  "byCandidateType": [
    { "candidateType": "FACT_ENTITY", "total": 12, "reviewed": 8,
      "notUseful": 4, "falsePositiveRate": 0.5 }
  ]
}
```

`byCandidateType` 은 **오탐이 많은 순서**입니다. 어느 분석기를 먼저 손봐야 할지 여기서 드러납니다.

아직 아무도 처리하지 않았으면 비율은 `null` 입니다.
`0.0` 으로 주면 "측정했더니 0%" 로 읽히는데 실제로는 "아직 안 봤다" 입니다.

팀 내부 확인용이라 프론트 화면에는 안 써도 됩니다.

---

## 3-13. 아직 안 한 것

| 명세 | 상태 |
|---|---|
| §11 callback 구조 전환 | MVP 는 직접 호출. 명세도 후속 전환안으로 분리 |
| §17 `storageKey` 공유 저장소 | 분산 배포 시 필요. 지금은 서버 한 대 |
| §19-6 실제 영상 기반 회귀 세트 | 단위 테스트만 있음. 영상 fixture 는 없음 |

`CAPTION_CONSISTENCY`(발언↔자막 비교)는 명세 §18-1에서 MVP 제외로 확정돼
지금처럼 분석기를 꺼둔 상태가 맞습니다.

---

## 3-14. 테스트

```
oops-backend    ./gradlew test
oops-analysis   pip install -r requirements-dev.txt && pytest
```

붙여둔 건 **실제로 틀렸던 로직**입니다. 새로 만든 기능보다 이미 고친 것을 지킵니다.

| 대상 | 왜 |
|---|---|
| `CandidateType` 매핑 | 카테고리를 추가하고 여기 안 넣으면 전부 발언 검토로 떨어짐 |
| `AnalyzerStatus.worseOf` | 뒤집히면 "절반만 봤는데 다 봤다" 고 보고함 |
| `CommunitySlangRules` | 못 잡으면 만든 이유가 없고, 과하면 사투리마다 경고 |
| `VagueReasonFilter` | 오탐 잡으려다 진짜 탐지를 죽인 적이 있음 |
| `FindingFusionService` | 같은 카드가 7장, 11장씩 쌓였던 문제 |
| `ReviewReference` | 상한·중복·길이 초과 |
| `ocr._find_watermarks` | **세 번 고쳐서 맞은 로직** — 로고와 자막 구분 |

아직 없는 것: 컨트롤러 통합 테스트, 실제 영상 fixture 기반 품질 회귀(§19-6).

---

## 4. 구현하지 않은 부분

### §11 Spring ↔ Python 내부 API

**명세:** Python 워커가 분석 전부를 수행하고 Spring에 콜백으로 결과를 밀어준다.

**구현:** Spring이 오케스트레이터이고 Python은 STT/OCR만 담당한다.
리스크 판정, 후보 병합, 우선순위 계산은 전부 Spring(Java)에 있다.

**왜:** 분석 로직이 이미 Java로 작성돼 있었고, 콜백 구조로 뒤집으려면
LLM 분석기 4개와 병합 로직을 Python으로 재작성해야 합니다.
프론트가 보는 계약에는 영향이 없어서 유지했습니다.

바꾸려면 Python 워커에 분석 로직을 옮기고 콜백 엔드포인트 2개를 추가하면 됩니다.

### §12 Internal API 보안

내부 API를 만들지 않았으므로 해당 없습니다.
Python 서버는 `127.0.0.1`에만 바인딩해 외부에서 접근할 수 없게 합니다.

### §13 Idempotency

| 항목 | 상태 |
|---|---|
| 재시도 시 새 `jobId` 발급 | 구현 |
| 분석 중 재시도 시 409 `ANALYSIS_IN_PROGRESS` | 구현 |
| 중복 콜백 방어 | 해당 없음 (콜백 구조가 아님) |

---

## 5. 명세에 없지만 추가한 것

| Method | Path | 용도 |
|---|---|---|
| POST | `/api/v1/videos` (JSON `{url}`) | 유튜브 링크 등록. 나중에 댓글 분석에 원본 URL이 필요합니다 |
| GET | `/api/v1/videos/{id}/transcript` | 음성 인식 결과 원문 (디버깅) |
| GET | `/api/v1/videos/{id}/screen-texts` | 화면 자막 인식 결과 원문 (디버깅) |
| DELETE | `/api/v1/videos/{id}` | 영상·결과·파일 삭제 |

앞의 둘은 프론트가 안 써도 됩니다. 분석 결과가 이상할 때 원인을 찾는 용도입니다.

### 검수 이력 `GET /api/v1/videos` (명세 §3-2)

```json
{
  "videoId": 123,
  "filename": "sample.mp4",
  "uploadedAt": "2026-08-16T12:30:00Z",
  "status": "COMPLETED",
  "progress": 100,
  "eventCount": 3,
  "streamUrl": "/api/v1/videos/123/stream"
}
```

- `uploadedAt` 은 **UTC** 입니다. 서버 로컬 시간대에 의존하지 않게 고정했습니다
- `streamUrl` 은 원본이 서버에 없으면 `null` (유튜브 링크로 등록한 경우)
- `filename` 이 없는 유튜브 항목은 제목이나 주소로 채웁니다. 빈칸으로 두지 않습니다
- 관리용 확장 필드(`sourceType`, `sourceUrl`, `title`, `genre`)가 함께 나갑니다. 무시하면 됩니다

유튜브 링크로 등록한 영상은 로컬에 파일이 없어 **`/stream`이 동작하지 않습니다.**
그 경우 프론트에서 유튜브 임베드를 쓰거나, 파일 업로드만 사용하면 됩니다.

---

## 6. 문서

`http://localhost:8080/swagger-ui.html` 에서 실제 API를 확인하고 바로 호출해볼 수 있습니다.
명세 §16의 "Swagger/OpenAPI = Source of Truth"에 해당합니다.

코드에서 자동 생성되므로 구현이 바뀌면 문서도 같이 바뀝니다.

---

## 7. 프론트 개발 시 참고

**진행률은 두 경로 모두 동일한 값을 줍니다.**
WebSocket이 끊겨도 `GET /status` 폴링으로 대체할 수 있습니다.

**업로드하면 분석이 바로 시작됩니다.** 별도의 분석 시작 API는 없습니다.

**타임라인 카드 클릭 시 구간 이동**은 이렇게 하면 됩니다.

```jsx
<video src={`/api/v1/videos/${videoId}/stream`} ref={videoRef} controls />

// 카드 클릭
videoRef.current.currentTime = event.startMs / 1000;
```

`/stream`이 HTTP Range를 지원하므로 브라우저가 알아서 해당 구간만 받아옵니다.

**동작 예시 페이지**가 있습니다. 참고용으로 보세요.

- `http://localhost:8080/report-test.html` — 타임라인 + 영상 + 캡처 이미지
- `http://localhost:8080/ws-test.html` — WebSocket 진행률
