# OOPS — 영상 논란 탐지 서비스

영상을 올리면 **몇 분 몇 초에 어떤 논란거리가 있는지** 타임라인으로 돌려줍니다.

유튜버가 영상을 공개하기 전에 검수하는 용도입니다.
사람이 처음부터 끝까지 봐도 놓치는 걸, AI가 훑어서 시각과 근거를 함께 짚어줍니다.

---

## 구성

```
oops/
├── oops-backend/    Spring Boot 4 · Java 17    (8080)
├── oops-analysis/   FastAPI · Python 3.11      (8000)
└── docs/                  인수인계 문서
```

서버는 **한 대면 됩니다.** 음성 인식과 화면 문자 인식이 파이썬 생태계라 프로세스만 나눴고,
같은 머신에서 통신합니다. Python 서버는 외부에 노출하지 않습니다.

---

## 빠른 시작

### 1. 분석 서버

```powershell
cd oops-analysis
copy .env.example .env      # OPENAI_API_KEY 채우기
.\run.ps1 -Setup
```

사전 준비: `ffmpeg`, `Python 3.11`

```powershell
winget install -e --id Gyan.FFmpeg
winget install -e --id Python.Python.3.11
```

### 2. 백엔드

IntelliJ 에서 `oops-backend` 를 열고 `OopsApplication` 실행.
**Run → Edit Configurations → Environment variables** 에 키 등록이 필요합니다.

```
OPENAI_API_KEY=sk-proj-...
```

DB는 설치할 필요가 없습니다. H2 파일 모드라 켜면 알아서 만들어집니다.

### 3. 확인

```powershell
cd oops-backend
.\scripts\smoke-test.ps1 -File "C:\경로\영상.mp4"
.\scripts\smoke-test.ps1 -Url  "https://www.youtube.com/watch?v=..."
.\scripts\smoke-test.ps1 -VideoId 1     # 재분석 없이 결과만 다시 보기
```

유튜브 분석이 안 되면 yt-dlp 부터 올려 보세요. 유튜브가 수시로 막습니다.

```powershell
cd oops-analysis; .\.venv\Scripts\activate; pip install --upgrade yt-dlp
```

| URL | 용도 |
|---|---|
| `/swagger-ui.html` | API 문서 · 바로 호출 가능 |
| `/report-test.html` | 타임라인 + 영상 재생 + 캡처 이미지 |
| `/ws-test.html` | WebSocket 진행률 |
| `/h2-console` | DB 조회 |

---

## 어떻게 분석하나

```
영상
 ├─ 음성 → 타임스탬프 대본        (Whisper)
 └─ 화면 → 자막 텍스트 + 캡처      (PaddleOCR)
        ↓
   영상 유형 판별                  경제·정책 / 투자·금융 / 인터뷰·팟캐스트 / 일반
        ↓
   유형에 맞는 분석기가 각자 훑기
     [공통]   발언 · 금지어 / 발언 · 맥락(LLM)
              화면 · 금지어 / 화면 · 맥락(LLM)
              시의성 검토(뉴스 검색 + LLM)
              노란딱지 예측(유튜브 가이드라인 + LLM)
     [경제·투자] 사실 검증(뉴스 대조 + LLM)
        ↓
   같은 논란 병합 · 교차검증 가산 · 우선순위
        ↓
   시간대별 논란 목록
```

### 유형마다 논란이 되는 지점이 다릅니다

경제 해설물은 **틀린 숫자 하나**가 곧 논란이 되고,
인터뷰는 **발언이 공개 시점의 이슈와 맞물릴 때** 논란이 됩니다.
같은 잣대로 보면 둘 다 놓칩니다.

| 유형 | 중점 |
|---|---|
| 경제·정책·데이터 해설 | 사실 검증 — 수치·정책·인과 주장을 기사와 대조 |
| 투자·주식·금융 | 사실 검증 + 단정적 전망 |
| 인터뷰·팟캐스트 | 시의성 — 발언과 인물을 최근 이슈와 대조 |
| 일반 | 공통 분석만 |

유형은 대본을 보고 자동으로 판별하고, 업로드할 때 직접 지정할 수도 있습니다.

### 차별점 세 가지

**노란 딱지 예측** — 크리에이터에게는 논란보다 이쪽이 더 직접적인 손해입니다.
유튜브 광고주 친화 가이드라인 14개 주제로 판정해서,
올리기 전에 "이 부분 때문에 광고가 제한될 수 있다"를 알려줍니다.
어떻게 고치면 되는지도 함께 제시합니다.


**시의성 검토** — "재선거" 같은 표현은 그 자체로 무해합니다.
그런데 마침 그 사안이 진행 중인 시점이면 같은 영상이라도 반응이 완전히 달라집니다.
LLM은 학습 시점 이후 뉴스를 모르므로, 최근 기사를 검색해 함께 판단하게 했습니다.

**사실 검증** — 경제·투자 영상에서 검증 가능한 주장만 뽑아 기사와 대조합니다.
틀렸는지, 오해를 부르는지, 근거가 없는지를 가릅니다.
LLM은 통계를 정확히 외우지 못하므로 여기서도 검색을 끼웠습니다.

---

## API 한눈에

Base URL `/api/v1` · 문서는 `/swagger-ui.html`

| Method | Path | 설명 |
|---|---|---|
| POST | `/videos` | 영상 업로드 (multipart). **업로드 즉시 분석 시작** |
| POST | `/videos` | 유튜브 URL 등록 (JSON `{url}`) |
| GET | `/videos/{id}/status` | 진행률 폴링 |
| GET | `/videos/{id}/report` | Timeline Report |
| POST | `/videos/{id}/analysis/retry` | 재분석 |
| GET | `/videos/{id}/stream` | 영상 재생 (HTTP Range) |
| GET | `/videos/{id}/frames/{frameId}` | 화면 캡처 이미지 |
| GET | `/videos` | 목록 (관리용) |
| DELETE | `/videos/{id}` | 영상·결과·파일 삭제 |

WebSocket `/ws` → `/topic/videos/{videoId}/progress` 구독

`/report` 응답에는 논란 목록(`events`)과 함께
영상 유형(`genre`)과 유튜브 광고 적합성(`adSuitability`)이 들어갑니다.

## 데이터 관리

- **DB**: H2 파일 모드. 별도 설치가 필요 없고 재시작해도 남습니다
- **스키마**: 엔티티에서 자동 생성 (`ddl-auto: update`)
- **삭제**: `DELETE /videos/{id}` 가 DB 행과 디스크 파일을 함께 지웁니다
- **자동 정리**: `oops.storage.retention-days` 를 켜면 매일 새벽 4시에
  그만큼 지난 영상을 정리합니다. 로컬 기본값은 꺼짐(0), 배포 서버는 7 권장

> 영상 하나에 원본 수십 MB 와 프레임 이미지가 쌓입니다.
> 배포할 때 자동 정리를 켜지 않으면 디스크가 조용히 찹니다.

## 상태

| 항목 | 상태 |
|---|---|
| 업로드 → 분석 → 결과 | 완료 (실제 영상 검증) |
| 유튜브 링크 분석 | 완료 |
| API 명세 일치 | 완료 (`docs/API명세-구현-대조표.md`) |
| WebSocket 진행률 | 완료 |
| 영상 재생 · 구간 이동 | 완료 |
| 화면 캡처 이미지 | 완료 |
| Swagger 문서 | 완료 |
| 영상 유형 판별 | 완료 |
| 사실 검증 (경제·투자) | 완료 |
| 노란 딱지 예측 | 완료 |
| 시의성 검토 | 완료 |
| 영상 삭제 · 저장소 자동 정리 | 완료 |
| **배포** | **미착수** — 현재 로컬 전용 |
| DB 마이그레이션 도구 | 미도입 — 배포 전 Flyway 권장 |
| MySQL 전환 | 설정만 있음, 미검증 |
| 유튜브 댓글 분석 | 미구현 (스텁만) |
| 포즈 · 제스처 판별 | 미구현 (스텁만) |

---

## 문서

| 파일 | 대상 |
|---|---|
| [docs/개발자-인수인계.md](docs/개발자-인수인계.md) | **백엔드 개발자. 먼저 읽으세요** |
| [docs/API명세-구현-대조표.md](docs/API명세-구현-대조표.md) | 프론트엔드 |
| [docs/사용중인-프롬프트-전문.md](docs/사용중인-프롬프트-전문.md) | 탐지 품질 담당 |
| [docs/프로젝트-현황-정리.md](docs/프로젝트-현황-정리.md) | 비개발자 · 발표 준비 |
| [oops-backend/README.md](oops-backend/README.md) | 백엔드 상세 |
| [oops-analysis/README.md](oops-analysis/README.md) | 분석 서버 상세 |

인수인계 문서의 **"알아둘 함정들"** 장은 꼭 읽어주세요.
Spring Boot 4의 Jackson 3 전환, Java HttpClient의 HTTP/2 문제 등
모르면 하루씩 날리는 것들을 정리해 뒀습니다.

---

## 주의

**API 키를 커밋하지 마세요.** `.env` 와 `.idea/` 는 `.gitignore` 에 있습니다.
키는 각자 로컬에서만 관리하고, 배포 시에는 서버 환경변수로 넣습니다.

**비용이 발생합니다.** 영상 1분당 약 100원(Whisper + LLM)입니다.
개발 중에는 1~2분짜리 짧은 영상을 쓰세요.

**OpenAI 요청 한도에 걸리면 결과가 조용히 비어 나옵니다.**
분석기가 여러 번 호출하므로 영상 하나에 수십 건이 나갑니다.
계정 등급이 낮으면 금방 막힙니다. 결과가 갑자기 비면
콘솔에서 `rate_limit_exceeded` 부터 찾아보세요.
프롬프트 문제로 착각하기 쉽습니다.

**분석 시간의 대부분은 OCR 입니다.**
`oops.analysis-server.ocr-interval-sec` 로 조절합니다.
파이프라인이 끝나면 콘솔에 단계별 소요 시간이 한 줄로 찍힙니다.
