# API 명세 ↔ 구현 대조표

> Creator Risk Manager v2.0 명세 기준 · 2026년 8월 11일

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
| §1-6 에러 코드 10종 | 전부 구현 + `INVALID_REQUEST` 추가 | 일치 (추가분 있음) |
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

### 응답 필드

명세에 적힌 필드명과 타입이 **그대로** 구현돼 있습니다.

```
VideoUploadResponse    videoId, jobId, filename, status, streamUrl
VideoStatusResponse    videoId, jobId, status, progress, stage, message
ProgressMessage        videoId, jobId, status, progress, stage, message (+errorCode)
AnalysisReportResponse videoId, jobId, status, summary{high,medium,low}, events[]
AnalysisRetryResponse  videoId, jobId, status
```

### Timeline Event (§6)

```
공통      id, startMs, endMs, type, severity, reason, frameUrl, occurrences
SPEECH    text, riskTypes[]
CAPTION   captionText  (speechText 는 현재 비어 있음)
```

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

### 3-2. `ProgressMessage`에 `errorCode` 추가

분석이 실패했을 때 프론트가 원인별로 다른 안내를 띄울 수 있게 넣었습니다.
명세에는 없지만 추가 필드라 기존 처리에는 영향이 없습니다.

### 3-3. `TimelineEventDto` 에 `occurrences` 추가

같은 논란이 영상에서 몇 번 반복됐는지 알려줍니다.
영상 내내 떠 있는 고정 자막처럼 여러 번 잡히는 건은 한 건으로 병합하고,
`startMs`~`endMs` 가 그 전체 구간을 뜻하게 했습니다.

`1` 이면 한 번만 등장한 것이라 무시해도 됩니다. 명세에 없는 추가 필드입니다.

### 3-4. 업로드에 `genre` 추가 (선택)

영상 유형을 지정하면 그에 맞는 분석기가 돌아갑니다.

```
ECONOMY_POLICY / INVESTMENT_FINANCE / INTERVIEW_PODCAST / GENERAL
```

**선택 필드입니다.** 안 보내면 대본을 보고 자동으로 판별하므로
기존 요청은 그대로 동작합니다. 나중에 UI 에 유형 선택을 넣으면 정확도가 올라갑니다.

### 3-5. `/report` 에 `adSuitability`, `genre` 추가

```json
{
  "genre": "INTERVIEW_PODCAST",
  "adSuitability": "LIMITED",
  "adSuitabilityNote": "광고가 일부만 붙거나 단가가 크게 떨어집니다.",
  "summary": { "high": 2, "medium": 3, "low": 5 },
  "events": [ ... ]
}
```

`adSuitability` 는 유튜브 노란 딱지 예측입니다.
`MONETIZED` / `LIMITED` / `DEMONETIZED` 세 값이며, 가장 심한 구간을 기준으로 합니다.
구간별 문제는 `events` 안에 `AD_LIMITED`, `AD_DEMONETIZED` 카테고리로 들어갑니다.

둘 다 명세에 없는 추가 필드라 안 써도 무방합니다.

### 3-6. 에러 코드 `INVALID_REQUEST` 추가

필수 값 누락이나 잘못된 파라미터에 쓰는 400 코드입니다.
명세 §1-6 목록에는 없었지만 유효성 검사 실패를 표현할 코드가 필요했습니다.

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

앞의 둘은 프론트가 안 써도 됩니다. 분석 결과가 이상할 때 원인을 찾는 용도입니다.

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
