# VideoGuard — 영상 논란 탐지 서비스

영상을 올리면 **몇 분 몇 초에 어떤 논란거리가 있는지** 타임라인으로 돌려줍니다.

유튜버가 영상을 공개하기 전에 검수하는 용도입니다.
사람이 처음부터 끝까지 봐도 놓치는 걸, AI가 훑어서 시각과 근거를 함께 짚어줍니다.

---

## 구성

```
videoguard/
├── videoguard-backend/    Spring Boot 4 · Java 17    (8080)
├── videoguard-analysis/   FastAPI · Python 3.11      (8000)
└── docs/                  인수인계 문서
```

서버는 **한 대면 됩니다.** 음성 인식과 화면 문자 인식이 파이썬 생태계라 프로세스만 나눴고,
같은 머신에서 통신합니다. Python 서버는 외부에 노출하지 않습니다.

---

## 빠른 시작

### 1. 분석 서버

```powershell
cd videoguard-analysis
copy .env.example .env      # OPENAI_API_KEY 채우기
.\run.ps1 -Setup
```

사전 준비: `ffmpeg`, `Python 3.11`

```powershell
winget install -e --id Gyan.FFmpeg
winget install -e --id Python.Python.3.11
```

### 2. 백엔드

IntelliJ 에서 `videoguard-backend` 를 열고 `VideoguardApplication` 실행.
**Run → Edit Configurations → Environment variables** 에 키 등록이 필요합니다.

```
OPENAI_API_KEY=sk-proj-...
```

DB는 설치할 필요가 없습니다. H2 파일 모드라 켜면 알아서 만들어집니다.

### 3. 확인

```powershell
cd videoguard-backend
.\scripts\smoke-test.ps1 -File "C:\경로\영상.mp4"
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
   분석기 6종이 각자 훑기
     발언 · 금지어 / 발언 · 맥락(LLM)
     화면 · 금지어 / 화면 · 맥락(LLM)
     발언 ↔ 화면 대조(LLM)
     시의성 검토(뉴스 검색 + LLM)
        ↓
   중복 정리 · 교차검증 가산 · 우선순위
        ↓
   시간대별 논란 목록
```

**시의성 검토가 이 서비스의 차별점입니다.**
"재선거" 같은 표현은 그 자체로 무해하지만, 마침 그 사안이 진행 중인 시점이면
같은 영상이라도 반응이 완전히 달라집니다.
LLM은 학습 시점 이후 뉴스를 모르므로, 최근 기사를 검색해서 함께 판단하게 했습니다.

---

## 상태

| 항목 | 상태 |
|---|---|
| 업로드 → 분석 → 결과 | 완료 (실제 영상 검증) |
| API 명세 일치 | 완료 (`docs/API명세-구현-대조표.md`) |
| WebSocket 진행률 | 완료 |
| 영상 재생 · 구간 이동 | 완료 |
| 화면 캡처 이미지 | 완료 |
| Swagger 문서 | 완료 |
| **배포** | **미착수** — 현재 로컬 전용 |
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
| [videoguard-backend/README.md](videoguard-backend/README.md) | 백엔드 상세 |
| [videoguard-analysis/README.md](videoguard-analysis/README.md) | 분석 서버 상세 |

인수인계 문서의 **"알아둘 함정들"** 장은 꼭 읽어주세요.
Spring Boot 4의 Jackson 3 전환, Java HttpClient의 HTTP/2 문제 등
모르면 하루씩 날리는 것들을 정리해 뒀습니다.

---

## 주의

**API 키를 커밋하지 마세요.** `.env` 와 `.idea/` 는 `.gitignore` 에 있습니다.
키는 각자 로컬에서만 관리하고, 배포 시에는 서버 환경변수로 넣습니다.

**비용이 발생합니다.** 영상 1분당 약 100원(Whisper + LLM)입니다.
개발 중에는 1~2분짜리 짧은 영상을 쓰세요.
