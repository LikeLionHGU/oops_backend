# API 명세 ↔ 구현 대조표

> 명세 v2.1 기준 · 2026년 8월 18일
> 실제 영상으로 동작 확인 완료

**§1~§8, §11 은 명세와 일치합니다.** 다른 곳은 §3 에 이유와 함께 정리했습니다.

---

## 1. 공통 규칙 (§1)

### 응답 껍데기

성공이든 실패든 세 필드가 항상 있습니다.

```json
{ "success": true,  "data": { }, "error": null }
{ "success": false, "data": null,
  "error": { "code": "VIDEO_NOT_FOUND", "message": "영상을 찾을 수 없습니다.",
             "details": { "traceId": "6adf4c7c2f8a4b42" } } }
```

**최상위 `message` 는 없습니다.** 실패 문구는 `error.message` 에 있습니다.
프론트는 `error.code` 로 분기하고 화면 문구는 직접 정하세요.
`message` 는 개발 확인용입니다.

### 식별자와 시각

| 항목 | 형식 | 예 |
|---|---|---|
| 모든 id | **문자열** | `"123"`, `"128"` |
| 날짜·시각 | ISO-8601 **UTC** | `"2026-08-18T08:11:09Z"` |
| 시간 구간 | 밀리초 정수 | `startMs: 3000` |

접두어(`video-`)는 붙이지 않습니다. 숫자를 문자열로 준 것이라
받은 값을 그대로 경로에 넣으면 됩니다.

DB 는 숫자 그대로 두고 응답에서만 문자열로 바꿉니다.
키 타입까지 바꾸면 인덱스와 조인이 느려지는데, 필요한 건 "파싱 안 해도 되는 값"이지
키 타입이 아니라고 판단했습니다.

### Enum

```
AnalysisStatus  PENDING | PROCESSING | COMPLETED | FAILED | CANCELLED
AnalysisStage   UPLOAD | STT | TEXT_RISK | SCENE_DETECTION | OCR | MULTIMODAL | FINALIZING | COMPLETED
ReviewStatus    NOT_STARTED | IN_REVIEW | COMPLETED
ReviewAction    CONFIRMED | EDITED | HOLD | NOT_USEFUL
EventType       SPEECH | CAPTION
CandidateType   SPEECH_REVIEW | FACT_CHECK
Severity        LOW | MEDIUM | HIGH        (내부값. optional)
```

### 에러 코드

| HTTP | code |
|---|---|
| 400 | `INVALID_REQUEST` |
| 404 | `VIDEO_NOT_FOUND` · `FRAME_NOT_FOUND` · `EVENT_NOT_FOUND` |
| 409 | `ANALYSIS_IN_PROGRESS` · `ANALYSIS_NOT_COMPLETED` · `INVALID_ANALYSIS_STATE` · `REVIEW_INCOMPLETE` |
| 410 | `VIDEO_SOURCE_PURGED` — 보관 기간이 지나 원본을 지웠습니다 |
| 413 | `MAX_UPLOAD_SIZE_EXCEEDED` |
| 415 | `UNSUPPORTED_VIDEO_FORMAT` |
| 416 | `RANGE_NOT_SATISFIABLE` |
| 422 | `MAX_VIDEO_DURATION_EXCEEDED` |
| 503 | `WORKER_UNAVAILABLE` |
| 500 | `ANALYSIS_FAILED` · `INTERNAL_SERVER_ERROR` |

---

## 2. 엔드포인트

| 명세 | Method | Path | 상태 |
|---|---|---|---|
| §2 영상 업로드 | POST | `/api/v1/videos` | 201 |
| §3-1 분석 상태 | GET | `/api/v1/videos/{id}/status` | 200 |
| §3-2 진행률 | WS | `/ws` → `/topic/videos/{id}/progress` | STOMP |
| §4 검수 이력 | GET | `/api/v1/videos/history` | 200 |
| §5 검수 리포트 | GET | `/api/v1/videos/{id}/report` | 200 |
| §6 검수 결정 저장 | PUT | `/api/v1/videos/{id}/review-actions/{eventId}` | 200 |
| §6 검수 완료 | POST | `/api/v1/videos/{id}/review-completion` | 200 |
| §7 분석 재시도 | POST | `/api/v1/videos/{id}/analysis/retry` | 202 |
| §7 분석 취소 | POST | `/api/v1/videos/{id}/analysis/cancel` | 200 |
| §8 영상 스트리밍 | GET | `/api/v1/videos/{id}/stream` | 200 / 206 / 416 |
| §8 프레임 이미지 | GET | `/api/v1/videos/{id}/frames/{frameId}` | 200 |

---

## 3. 응답 필드

아래는 **실제 코드에서 뽑은 것**입니다. `null` 필드는 JSON 에서 아예 빠집니다.

### 업로드 (§2)

```json
{ "videoId": "123", "jobId": "job_8fc391", "filename": "sample.mp4",
  "durationMs": 8000, "status": "PENDING",
  "streamUrl": "/api/v1/videos/123/stream" }
```

`durationMs` 는 업로드 직후 잰 값입니다. 못 쟀으면 `null` 입니다.
90분을 넘으면 `422 MAX_VIDEO_DURATION_EXCEEDED` 로 거절하고 저장한 파일도 지웁니다.

### 분석 상태 (§3)

```json
{ "videoId": "123", "jobId": "job_8fc391", "filename": "sample.mp4",
  "durationMs": 8000, "status": "PROCESSING", "stage": "OCR", "progress": 64,
  "message": "화면 속 텍스트를 확인하고 있습니다.",
  "startedAt": "2026-08-18T08:10:00Z", "updatedAt": "2026-08-18T08:11:15Z",
  "completedAt": null, "failure": null }
```

**STOMP 메시지도 같은 구조입니다.** WebSocket 이 끊겨 폴링으로 넘어가도
프론트가 같은 코드를 쓸 수 있어야 하기 때문입니다.

실패하면 `failure` 가 채워집니다.

```json
"failure": { "code": "ANALYSIS_FAILED", "message": "..." }
```

### 검수 이력 (§4)

```
GET /api/v1/videos/history?status=ALL&page=0&size=20
```

```json
{ "items": [
    { "videoId": "123", "filename": "sample.mp4",
      "uploadedAt": "2026-08-18T08:10:00Z",
      "analysisStatus": "COMPLETED", "reviewStatus": "IN_REVIEW",
      "eventCount": 3, "editedCount": 1, "reviewedAt": null,
      "sourceType": "UPLOAD",
      "streamUrl": "/api/v1/videos/123/stream", "embedUrl": null }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
```

**`analysisStatus` 와 `reviewStatus` 는 다른 값입니다.**
분석이 끝나도 사람이 아직 안 봤을 수 있습니다.

`status` 필터는 `ALL` / `COMPLETED` / `FAILED` 입니다.
`FAILED` 에는 취소한 것도 포함됩니다.

### 검수 리포트 (§5)

```json
{ "videoId": "123", "jobId": "job_8fc391", "filename": "sample.mp4",
  "generatedAt": "2026-08-18T08:11:09Z", "durationMs": 8000,
  "sourceType": "UPLOAD",
  "streamUrl": "/api/v1/videos/123/stream",
  "sourceUrl": null, "embedUrl": null, "youtubeVideoId": null,
  "reviewStatus": "IN_REVIEW", "status": "COMPLETED",

  "summary":       { "total": 3, "speechReview": 2, "factCheck": 1 },
  "reviewSummary": { "decided": 1, "remaining": 2,
                     "confirmed": 1, "edited": 0, "hold": 0, "notUseful": 0 },
  "coverage":      { "speechAnalyzed": true, "screenTextAnalyzed": true,
                     "sceneAnalyzed": false },
  "warnings": [],
  "events": [ ],
  "genre": "TALK_PODCAST" }
```

분석이 안 끝났으면 `409 ANALYSIS_NOT_COMPLETED` 입니다.

`genre` 는 내부 확장 필드입니다. 안 써도 됩니다.

### Timeline Event (§5 · v2.1 §10)

```json
{ "id": "128", "startMs": 3000, "endMs": 8000,
  "type": "SPEECH", "candidateType": "SPEECH_REVIEW",
  "title": "'~노' — 배경 확인 필요",
  "reason": "문장 끝의 '~노' 어미입니다. 경상도 사투리로도 쓰이지만...",
  "frameUrl": "/api/v1/videos/123/frames/45",
  "references": [], "reviewAction": "CONFIRMED",
  "occurrences": 5,

  "text": "정치판에 내 얘기가 왜 나오노?",
  "contextBefore": "아 진짜로?",
  "contextAfter": "그러게 말이에요" }
```

화면 글자에서 나온 사실 확인 후보는 이렇게 옵니다.

```json
{ "id": "204", "startMs": 61000, "endMs": 65000,
  "type": "CAPTION", "candidateType": "FACT_CHECK",
  "title": "'앨범 발매 연도' — 사실 확인 필요",
  "reason": "화면에는 2023년으로 적혀 있는데 기사에는 2024년으로 나옵니다.",
  "frameUrl": "/api/v1/videos/123/frames/88",
  "references": [ ... ], "reviewAction": null, "occurrences": 1,

  "captionText": "2023년 정규 2집 발매" }
```

**`type` 과 `candidateType` 은 다른 질문에 답합니다.**

```
type          어디서 나왔나   SPEECH = 발언 / CAPTION = 화면 글자
candidateType 왜 확인하나     SPEECH_REVIEW = 다시 읽어볼 표현
                             FACT_CHECK    = 외부 자료와 대조가 필요
```

네 조합을 다 처리해야 하지만, **지금 실제로 나오는 것은 위 두 개뿐입니다.**
사실 확인(`entity-check`)이 꺼져 있어 `FACT_CHECK` 는 0건입니다.

| `type` | `candidateType` | 어떤 카드인가 |
|---|---|---|
| `SPEECH` | `SPEECH_REVIEW` | 출연자 발언 중 다시 읽어볼 표현 |
| `CAPTION` | `SPEECH_REVIEW` | **편집 자막 중 다시 읽어볼 표현** ← "발언" 이라 쓰면 안 됩니다 |
| `SPEECH` | `FACT_CHECK` | 출연자가 말한 이름·연도·숫자 (지금 0건) |
| `CAPTION` | `FACT_CHECK` | 화면 자막에 박힌 이름·연도·숫자 (지금 0건) |

`CAPTION` × `SPEECH_REVIEW` 가 **지금 가장 흔한 조합**입니다.
예전 문서는 이 조합을 "안 나옴" 으로 적고 `FACT_CHECK` 를 나오는 것으로
적어뒀는데, 정확히 뒤집혀 있었습니다.

**화면 문구는 `candidateType` 이 아니라 `type` 으로 정하세요.**
`SPEECH_REVIEW` 라고 무조건 "발언" 이라 쓰면 안 됩니다.
`type` 이 `CAPTION` 이면 그건 편집자가 쓴 화면 글자입니다.

| 필드 | 설명 |
|---|---|
| `title` | 카드 제목. 목록에서 훑어볼 수 있게 대상을 포함합니다 |
| `references` | **항상 배열.** 자료가 없으면 `[]` |
| `reviewAction` | 아직 결정 안 했으면 `null` |
| `occurrences` | 같은 후보가 몇 번 나왔는지. `2` 이상이면 `startMs~endMs` 가 전체 구간 |

**`severity` 와 `riskTypes` 는 더 이상 내려가지 않습니다.**
이 도구는 위험도를 매기지 않습니다. 점수가 화면에 보이는 순간
"AI 가 0.8 이라고 했으니 문제다" 가 되고, 그건 판정입니다.
두 값 모두 서버 내부에는 남아 있어 정렬·병합·품질 지표에 계속 쓰입니다.

**`FACT_CHECK` 카드에는 항상 `references` 가 붙습니다.**
사실 확인 후보는 실제로 자료를 검색해 대조한 분석기만 만들 수 있게 막아뒀습니다.
근거 없는 사실 지적이 나가지 않습니다.

**`contextBefore` / `contextAfter` 는 SPEECH 에만** 붙습니다.
직전·직후 대본 줄입니다. 카드만 보면 발언이 앞뒤 없이 뚝 떨어져 있어서
제작자가 흐름을 판단하기 어렵기 때문입니다. 없으면 `null` 입니다.

**모든 구간은 영상 길이 안에 있습니다.** OCR 이 프레임 간격만큼 `endMs` 를 잡아
영상 밖으로 나가던 것을 저장 전에 잘라냅니다.

### 참고 자료

```json
{ "title": "OO그룹 창립 20주년...2020년 설립 후 성장세",
  "provider": "한국경제", "url": "https://...",
  "publishedAt": "Mon, 03 Aug 2026 09:12:00 GMT",
  "relevantContext": "기사에는 2020년 설립으로 나옵니다",
  "snippet": "2020년 설립된 OO그룹은...",
  "sourceType": "DIRECT_QUOTE_SOURCE", "sourceLabel": "인터뷰·직접 인용" }
```

`snippet` 은 기사에 있는 문장 그대로이고, `relevantContext` 는 **그래서 뭐가 확인됐는지**입니다.
링크를 열기 전에 볼 수 있게 같이 보여주면 좋습니다.

`sourceType` 은 원출처에 얼마나 가까운지입니다.
`PRIMARY_SOURCE` / `OFFICIAL_SOURCE` / `DIRECT_QUOTE_SOURCE` / `REPUTABLE_MEDIA` / `SECONDARY_SOURCE`.
**진실성 순위가 아니라 표시 순서입니다.** `sourceLabel` 을 뱃지에 그대로 쓰면 됩니다.

붙는 건 `FACT_CHECK` 카드뿐입니다.

이 필드가 있는 이유는 이 도구가 **오탐을 낸다**는 전제 때문입니다.
"기사에는 2020년으로 나옵니다" 라는 문장만 주면 사용자는 AI 말을 믿을 수밖에 없습니다.
가능하면 **눈에 띄게** 노출해 주세요.

### 검수 결정 (§6)

```
PUT /api/v1/videos/{videoId}/review-actions/{eventId}
{ "action": "CONFIRMED", "note": null }
```

```json
{ "videoId": "123", "eventId": "128", "action": "CONFIRMED",
  "note": null, "updatedAt": "2026-08-18T08:11:41Z" }
```

같은 요청을 반복해도 결과가 같습니다. 더블클릭해도 안전합니다.
첫 결정을 저장하면 `reviewStatus` 가 `IN_REVIEW` 가 됩니다.
다른 영상의 후보를 보내면 `404 EVENT_NOT_FOUND` 입니다.

```
POST /api/v1/videos/{videoId}/review-completion
```

```json
{ "videoId": "123", "reviewStatus": "COMPLETED",
  "reviewedAt": "2026-08-18T08:12:00Z",
  "summary": { "total": 3, "confirmed": 2, "edited": 1, "hold": 0, "notUseful": 0 } }
```

남은 후보가 있으면 `409 REVIEW_INCOMPLETE` 입니다.
"다 봤다" 는 기록은 실제로 다 봤을 때만 남아야 하기 때문입니다.

> **재분석하면 검수 결정이 지워집니다.** 후보 id 가 새로 발급되므로
> 옛 결정은 엉뚱한 후보를 가리키게 됩니다. `reviewStatus` 도 초기화됩니다.

### 재시도 · 취소 (§7)

```json
{ "videoId": "123", "jobId": "job_c7b2d1", "status": "PENDING" }
```

| | 허용 상태 | 아니면 |
|---|---|---|
| 재시도 | `FAILED` · `CANCELLED` | `409 INVALID_ANALYSIS_STATE` |
| 취소 | `PENDING` · `PROCESSING` | `409 INVALID_ANALYSIS_STATE` |

**취소는 도는 스레드를 죽이지 않습니다.** 상태만 바꿉니다.
강제로 끊으면 반쯤 저장된 결과가 남기 때문입니다.

---

## 4. `coverage` 와 `warnings` — 꼭 읽어주세요

**후보 0건과 "그 단계를 못 돌았다" 를 구분하기 위한 필드입니다.**

```json
"coverage": { "speechAnalyzed": true, "screenTextAnalyzed": false, "sceneAnalyzed": false },
"warnings": [
  { "stage": "FACT_ENTITY", "code": "FACT_ENTITY_UNAVAILABLE",
    "message": "이름·수치 확인 — AI 요청 한도 초과로 이 단계를 수행하지 못했습니다." }
]
```

**`warnings` 가 비어 있지 않으면 결과 위에 눈에 띄게 띄워 주세요.**

이게 없으면 "확인할 지점 0곳" 이 두 가지를 동시에 뜻하게 됩니다.

```
검수했더니 괜찮다
검수를 못 했다
```

후자를 전자로 읽고 영상을 올리면 이 도구는 없느니만 못합니다.

실제로 AI 요청 한도에 걸려 분석기 3개가 통째로 못 돈 적이 있는데,
그때 화면에는 "확인할 지점 1곳" 만 떠 있었습니다.

`sceneAnalyzed` 는 항상 `false` 입니다. 화면 자료 분석은 아직 없습니다.

**`screenTextAnalyzed` 는 화면 글자를 어느 분석기든 봤으면 `true` 입니다.**
지금은 화면 자막 표현 검토(`screen-text-review`)가 켜져 있어서 그쪽으로 `true`
가 됩니다. 이름·수치 확인은 꺼져 있습니다. 둘 중 하나라도 성공하면 `true` 입니다.

**단계별 상세를 주는 별도 엔드포인트는 없습니다.** 리포트의 `warnings[]` 로만
나갑니다. 지금 `NOT_ENABLED` 로 나가는 단계는 `FACT_ENTITY`(사실 확인)와
`VISUAL`(화면 자료 분석) 둘입니다.

> **`NOT_ENABLED` 는 `warnings[]` 에 안 들어갑니다.** 경고는 `FAILED`·`SKIPPED`
> 일 때만 붙습니다. 그래서 사실 확인이 꺼져 있다는 사실은 응답만 봐서는
> 알 수 없고, `summary.factCheck: 0` 이 "대조했는데 어긋난 게 없다" 로 읽힙니다.
> 화면에서 사실 확인 항목을 아예 감추는 편이 안전합니다.

---

## 5. CORS (§11)

허용 Origin 은 `application.yml` 의 `oops.cors.allowed-origins` 에 있습니다.
**REST 와 WebSocket 이 같은 목록을 씁니다.**

```yaml
oops:
  cors:
    allowed-origins:
      - http://localhost:5173
      - http://localhost:3000
      - http://localhost:8080
      - https://oops-im-so-sorry.vercel.app
      - https://oops-im-sorry.online            # 커스텀 도메인
      - https://oops-im-so-sorry-*.vercel.app   # 브랜치·PR 미리보기
```

`allowedOriginPatterns` 로 받으므로 `*` 를 쓸 수 있습니다.
Vercel 은 브랜치마다 미리보기 주소를 새로 만들기 때문에,
그때마다 설정을 고치고 서버를 다시 띄우는 건 현실적이지 않습니다.

**요청 헤더는 `*` 로 열어뒀습니다.** 예전에는 `Content-Type`, `Accept`, `Range`
만 허용했는데, 프론트가 그 밖의 헤더를 하나라도 붙이면 Preflight 가 막힙니다.
axios 같은 래퍼가 조용히 헤더를 더하는 경우가 있어 원인을 찾기 어렵습니다.
막아서 얻는 이득이 거의 없고, 정작 중요한 건 Origin 제한입니다.

서버가 뜰 때 허용 목록이 로그에 찍힙니다. 여기 없으면 그 Origin 은 막힙니다.

```
[cors] 허용 Origin: [http://localhost:5173, ..., https://oops-im-so-sorry.vercel.app]
```

`PUT /review-actions` 는 JSON 이라 Preflight `OPTIONS` 가 먼저 날아옵니다.
Spring 이 처리하며, 결과를 하루 캐시합니다.

### CORS 로 보이지만 아닌 경우

배포된 프론트(https)가 로컬 백엔드를 부를 때는 **CORS 가 아니라 다른 문제**입니다.

| 증상 | 원인 | 해결 |
|---|---|---|
| `Mixed Content` 차단 | https 페이지에서 http API 호출 | 백엔드도 https 로 서비스 |
| 요청이 아예 안 나감 | `localhost` 는 **보는 사람의 PC** 를 가리킨다 | 백엔드를 공개 주소로 배포 |
| WebSocket 만 실패 | https 페이지에서 `ws://` 사용 | `wss://` 필요 |

CORS 설정을 아무리 고쳐도 이 셋은 안 풀립니다.
브라우저 콘솔의 에러 문구를 그대로 보고 구분하세요.

영상 재생을 위해 `Content-Range`, `Accept-Ranges`, `Content-Length` 를 노출합니다.
이게 없으면 브라우저가 Range 응답을 못 읽습니다.

---

## 6. 명세와 다른 부분

### `GET /videos` 를 없앴습니다

`GET /videos/history` 가 대체합니다. 옛 목록 API 는 제거했습니다.

### `POST /videos` (유튜브 URL) 이 남아 있습니다

명세 §0 에서 MVP 제외로 잡혔지만 지우지 않았습니다.
프론트가 안 부르면 그만이고, 저희 테스트에 유용합니다.
로컬에 파일이 없어 `/stream` 은 동작하지 않습니다.

### `GET /metrics` 를 추가했습니다

검수 품질 지표입니다. `NOT_USEFUL` 이 어느 유형에서 나오는지 봅니다.
팀 내부용이라 프론트는 안 써도 됩니다.

### 디버깅용 엔드포인트

```
GET /api/v1/videos/{id}/transcript      음성 인식 원문
GET /api/v1/videos/{id}/screen-texts    화면 글자 원문
GET /api/v1/videos/{id}/review-actions  저장된 결정 목록
DELETE /api/v1/videos/{id}              영상·결과·파일 삭제
```

분석 결과가 이상할 때 원인을 찾는 용도입니다.

---

## 7. 아직 안 된 것

| 항목 | 상태 |
|---|---|
| v2.1 §10-3·10-4 화면 텍스트 사실 주장 → `type=CAPTION` + `FACT_CHECK` | 구현됨 · 기본 비활성 (`entity-check` 꺼짐) |
| v2.1 §10-5 `ScreenTextReviewAnalyzer` 비활성화 | 아직 켜져 있음 (일부러 켜뒀습니다) |
| §9 Range 200·206·416 계약 테스트 | 동작하지만 테스트 없음 |
| 인증 | 없음. 주소가 공개되면 누구나 업로드 가능 |
| 배포 | 완료. 커스텀 도메인 + Vercel 프론트 |

`CAPTION_CONSISTENCY`(발언↔자막 비교)는 MVP 제외로 확정돼
분석기를 꺼둔 상태가 맞습니다.

---

## 8. 프론트 개발 시 참고

**진행률은 두 경로가 같은 값을 줍니다.** WebSocket 이 끊겨도 `GET /status` 폴링으로 대체됩니다.
`jobId` 가 현재 작업과 다른 메시지는 무시하세요.

**업로드하면 분석이 바로 시작됩니다.** 별도의 분석 시작 API 는 없습니다.

**카드 클릭 시 구간 이동**

```jsx
<video src={report.streamUrl} ref={videoRef} controls />
videoRef.current.currentTime = event.startMs / 1000;
```

`streamUrl` 과 `frameUrl` 은 응답에서 온 값을 그대로 쓰세요. 직접 조합하지 마세요.
S3 로 옮겨도 계약은 유지되지만 경로 규칙은 바뀔 수 있습니다.

### 재생기는 `sourceType` 으로 갈라야 합니다

```
sourceType = UPLOAD   → <video src={streamUrl}>
sourceType = YOUTUBE  → <iframe src={embedUrl}>
```

**유튜브 영상을 `<video>` 에 넣으면 오류 없이 검은 화면만 나옵니다.**

유튜브로 등록한 영상은 **서버에 파일이 없습니다.** 분석할 때 임시로 받아
쓰고 끝나면 지웁니다. 그래서 `streamUrl` 이 `null` 이고, 대신 `sourceUrl`
(원본 링크)과 `embedUrl`(삽입용 주소)이 옵니다.

`sourceUrl` 을 `<video src>` 에 넣고 싶어지지만 그게 검은 화면의 원인입니다.
`https://www.youtube.com/watch?v=...` 는 영상 파일이 아니라 **HTML 페이지**라
`<video>` 가 재생할 수 없습니다. 에러 이벤트도 안 뜨는 경우가 많아
"서버가 유튜브에 접속을 못 하나" 로 오해하기 쉽습니다. 서버는 관여하지 않습니다.

```json
{ "sourceType": "YOUTUBE",
  "streamUrl": null,
  "sourceUrl": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "embedUrl":  "https://www.youtube.com/embed/dQw4w9WgXcQ?enablejsapi=1&rel=0" }
```

| 필드 | 쓰임 |
|---|---|
| `streamUrl` | 업로드 영상 재생. 유튜브면 `null` |
| `embedUrl` | 유튜브 iframe 재생. 업로드면 `null` |
| `youtubeVideoId` | `YT.Player({ videoId })` 에 넣을 값. 업로드면 `null` |
| `sourceUrl` | "원본 보기" 링크. **재생용이 아님** |

### 구간 이동 — 커스텀 재생바로는 iframe 을 못 움직입니다

iframe 안은 다른 도메인이라 `currentTime` 을 읽지도 쓰지도 못합니다.
`<video>` 기준으로 만든 진행바·10초 버튼은 유튜브 영상에서 아무 일도 하지 않습니다.

**공식 IFrame Player API 를 쓰세요.** `postMessage` 로 직접 명령을 보내는 방법도
있지만, 플레이어가 준비되기 전에 보내면 조용히 무시됩니다.
준비됐는지 알 방법이 없어서 "눌러도 아무 일도 안 일어남" 이 됩니다.
공식 API 는 `onReady` 를 주기 때문에 그 시점을 알 수 있습니다.

```html
<script src="https://www.youtube.com/iframe_api"></script>
```

```js
let yt, ytReady = false, pending = null;

window.onYouTubeIframeAPIReady = () => {
  yt = new YT.Player('ytPlayer', {          // <div id="ytPlayer"></div>
    videoId: report.youtubeVideoId,          // 주소가 아니라 id 입니다
    playerVars: { rel: 0, playsinline: 1 },
    events: {
      onReady: () => {
        ytReady = true;
        if (pending != null) { yt.seekTo(pending, true); yt.playVideo(); pending = null; }
      },
      onError: (e) => {
        // 101 / 150 = 영상 주인이 삽입 재생을 막아둠
        if (e.data === 101 || e.data === 150) embedBlocked = true;
      }
    }
  });
};

function seekTo(startMs) {
  const sec = startMs / 1000;

  if (sourceType !== 'YOUTUBE') { player.currentTime = sec; player.play(); return; }

  // 삽입이 막힌 영상은 새 탭에서 그 시각으로 연다
  if (embedBlocked) {
    window.open(`https://www.youtube.com/watch?v=${id}&t=${Math.floor(sec)}`, '_blank');
    return;
  }
  // 아직 준비 중이면 기억해 뒀다가 onReady 에서 처리한다.
  // 여기서 버리면 사용자에게는 "눌렀는데 아무 반응 없음" 이 된다.
  if (!ytReady) { pending = sec; return; }

  yt.seekTo(sec, true);
  yt.playVideo();
}
```

**`YT.Player` 는 지정한 요소를 iframe 으로 바꿔 끼웁니다.**
`<iframe>` 이 아니라 **빈 `<div>`** 를 넘기세요.
보이기/숨기기는 바깥 wrapper 로 하고, 영상을 바꿀 때는
`destroy()` 후 그 자리에 `<div>` 를 다시 만들어야 합니다.

**삽입 재생이 막힌 영상이 있습니다.** 영상 주인이 설정한 것이라 우리가 풀 수 없습니다.
그때도 시각을 누르면 유튜브에서 그 지점으로 열리게 해두세요.
타임코드만 보여주고 이동을 막으면 이 도구의 핵심이 빠집니다.

동작하는 예시가 `/report-test.html` 에 있습니다 (`loadYouTubeApi`, `mountPlayer`, `seekTo`).
그대로 가져다 쓸 수 있습니다.

### `streamUrl` 이 `null` 인 두 경우

| 상황 | 재생 방법 | `/stream` 호출 시 |
|---|---|---|
| 유튜브 링크로 등록 | `embedUrl` 로 iframe | `404 VIDEO_NOT_FOUND` |
| 보관 기간이 지나 원본 삭제 | **없음** | `410 VIDEO_SOURCE_PURGED` |

원본은 **분석 완료 24시간 뒤에 자동으로 삭제**됩니다.
지워지는 건 영상 파일뿐이고 **리포트·대본·검토 후보·참고 자료·검수 이력은
그대로 남습니다.** 화면 캡처(`frameUrl`)도 남아서 카드는 정상적으로 보입니다.

그래서 `410` 은 오류 화면이 아니라 안내 문구로 처리하세요.
"영상을 찾을 수 없습니다" 가 아니라
"보관 기간이 지나 원본은 삭제되었습니다. 검수 결과는 그대로 확인할 수 있습니다" 입니다.
카드의 시각을 눌러도 이동할 곳이 없으므로 재생 컨트롤 자체를 감추는 편이 낫습니다.

검수 이력(`/videos/history`)에도 같은 필드가 나갑니다.

**동작 예시 페이지**가 있습니다. 카드 UI, 검수 버튼, 경고 배너가 다 들어 있어
구현 참고용으로 보시면 됩니다.

```
http://localhost:8080/report-test.html
http://localhost:8080/ws-test.html
http://localhost:8080/swagger-ui.html
```
