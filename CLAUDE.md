# OoPs — 작업 안내 (redesign-v3)

영상 공개 전 논란 가능 구간을 찾아 편집자에게 보여주는 서비스. 이 브랜치는 재설계 v3 작업 중이다.
**설계 기준은 `docs/redesign-v3.md`다. 코드를 바꾸기 전에 먼저 읽는다.**

## 구조
- `oops-backend/` — Spring Boot 4 (Java 17). 업로드, 작업 관리, 분석 엔진, 결과 저장.
- `oops-analysis/` — FastAPI. ffmpeg + Groq whisper-large-v3 전사.
- `oops-backend/src/main/resources/criteria/` — 21개 판단 기준 JSON(기본), 중단 판단 코드 초안, 옛 프롬프트(참고용).
- `oops-backend/src/main/java/com/example/oops/context/` — 새 맥락 확인(분석기 키 `context-review`).
  `criteria/`(CriteriaSource: JSON·Neo4j), `LineContextBuilder`(문맥), `ContextPrompts`, `ContextReviewEngine`(3단계·검증), `ContextCriteriaAnalyzer`.
- Neo4j 서버 배포 자료는 `neo4j-data-setup` 브랜치 `infra/neo4j`. `oops.criteria.source=neo4j`로 전환.

## 핵심 원칙
- 입력은 편집 전 원본 영상뿐 (≤120분, ≤4GB). 스크립트 입력·유튜브 링크·SRT·OCR 없음.
- 전사는 Groq만. 다른 유료 모델로 자동 대체하지 않는다.
- 맥락 확인(후보 탐색 → 독립 점검 → 상세 검토)과 사실대조는 서로 독립이고 병렬로 돈다.
- 문맥은 줄(구간 ID) 단위 + 시간 상·하한. 줄을 자르지 않는다. 확대는 한 번.
- 제외·중단·정보 부족은 내부 기록만. 사용자에게 "확인 필요" 카드를 보여주지 않는다.
- 오류(API·DB·기준 로드 실패)는 정보 부족이나 정상 통과로 처리하지 않는다.
- 기준은 `CriteriaSource`로만 읽는다. 지금은 JSON, 나중에 Neo4j로 교체.
- AI 출력의 구간 ID·인용·기준 버전은 서버가 반드시 검증한다.
- 원본 영상은 업로드 후 24시간 안에 삭제, 텍스트 기록은 보존.

## 하지 말 것
- `criteria/legacy-prompts-reference.json`의 프롬프트를 그대로 복사해 쓰기 (새 정책과 어긋남).
- 사례집(`ReviewCaseLibrary`, `ReviewGuidelineLibrary`) 위에 새 기능 얹기 — 걷어낼 대상이다.
- API 키를 커밋하기 (`application-secret.yml`, `oops-analysis/.env`는 git 제외).

## 빌드
- `cd oops-backend && ./gradlew test`
- `cd oops-analysis && pytest`
