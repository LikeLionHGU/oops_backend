# OoPs?! — 공개 전 영상 검수 도우미 (redesign-v3)

편집 전 원본 영상의 발언에서 편집자가 다시 볼 지점을 찾아, 원문·시간·근거와 함께 보여주는 서비스다.
**개발 기준은 [docs/redesign-v3.md](docs/redesign-v3.md)** 이다.

## 분석 흐름

```
원본 영상 업로드 (≤120분, ≤4GB)
  → Groq whisper-large-v3 전사 (대체 모델 없음)
  ├─ 맥락 확인 (context-review): 21개 기준 · 후보 탐색 → 독립 점검 → 상세 검토
  └─ 사실대조 (fact-check): 주장 추출 → Perplexity → 충분성 평가 → (필요 시) Serper 1회 → 판정
  → 서버 검증 (구간 ID·인용·기준 버전·최신 실행) → 결과 카드 → 편집자 검수
```

- 두 흐름은 서로 독립이고 동시에 돈다. 각 흐름 안의 AI 호출도 병렬이다.
- 제외·중단·정보 부족은 내부 기록에만 남고 사용자에게 보여주지 않는다.
- 원본 영상은 업로드 후 24시간 안에 지우고, 전사·결과·검수 기록(텍스트)은 남긴다.

## 서버 구성

| 구성 | 담당 |
|---|---|
| Spring Boot 4 / Java 17 · 8080 | 업로드, 작업 상태, 분석 순서, AI 요청, 근거 검증, DB·리포트 |
| Python FastAPI · 8000 | 영상 준비, Groq STT |
| Groq Whisper Large v3 | 음성 → 시작·끝 시간이 있는 대본 |
| OpenAI GPT-6 Luna | 맥락 확인 3단계, 사실 주장 추출·판정 |
| Perplexity · Serper | 사실대조 검색 (1차 · 보조) |
| Neo4j (선택) | 판단 기준 21개 본문. 기본은 저장소 JSON (`oops.criteria.source`) |

## 로컬 실행

Java 17, Python 3.11+, `ffmpeg`·`ffprobe`가 필요하다.

### 1. Python 분석 서버

```bash
cd oops-analysis
python -m venv .venv && source .venv/bin/activate
python -m pip install -r requirements.txt
cp .env.example .env   # GROQ_API_KEY 입력
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

### 2. Spring 백엔드

환경변수 또는 `application-secret.yml`(git 제외)에 키를 넣는다.

| 변수 | 용도 |
|---|---|
| `OPENAI_API_KEY` | 맥락 확인·사실대조 판단 |
| `PERPLEXITY_API_KEY`, `SERPER_API_KEY` | 사실대조 검색 (없으면 사실대조는 FAILED 로 알림) |
| `OOPS_CRITERIA_SOURCE` | `json`(기본) 또는 `neo4j` |
| `NEO4J_PASSWORD` | neo4j 사용 시. 서버 `infra/neo4j/.env` 의 `OOPS_DB_PASSWORD` |

```bash
cd oops-backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

### 3. 업로드·조회

```bash
curl -X POST http://127.0.0.1:8080/api/v1/videos -F "file=@/절대경로/영상.mp4"
curl http://127.0.0.1:8080/api/v1/videos/VIDEO_ID/status
curl http://127.0.0.1:8080/api/v1/videos/VIDEO_ID/report
```

업로드하면 전사·분석 API 비용이 발생한다.

## 테스트

```bash
cd oops-backend && ./gradlew test
cd oops-analysis && pytest
```
