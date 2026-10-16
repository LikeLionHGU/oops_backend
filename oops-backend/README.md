# oops-backend

2026-10-16 · `minwook` 코드 기준. Spring Boot 4·Java 17 기반 API·분석 오케스트레이터다.

Spring은 영상·작업·리포트를 저장하고 Python에 전사·OCR·프레임 추출을 요청한다. 전사는 Python이 Groq `whisper-large-v3`로 처리하고, 후보 탐색·검증은 Spring이 OpenAI `gpt-6-luna`로 요청한다. Python 콜백 구조는 사용하지 않는다.

## 실행

`oops-backend`를 작업 디렉터리로 실행한다. Python 서버를 별도 터미널에서 먼저 켜며 [Python README](../oops-analysis/README.md)를 따른다.

macOS zsh:

```zsh
read -s "OPENAI_API_KEY?OpenAI API 키 입력: "
export OPENAI_API_KEY
echo
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
bash ./gradlew bootRun --args='--spring.profiles.active=local --server.address=127.0.0.1'
```

Java 경로는 설치 환경에 맞춘다. IntelliJ 실행 시에도 같은 환경변수·프로필을 지정한다. `application-secret.yml.example`을 최초에만 복사해 키를 입력하는 방식도 지원한다. Python `.env`나 루트 `.env`를 Spring이 자동으로 읽는 것은 아니다.

Windows PowerShell:

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local --server.address=127.0.0.1"
```

실행 터미널에 `OPENAI_API_KEY`를 설정하거나 secret YAML을 먼저 준비한다. PowerShell에서 HTTP 요청은 `curl.exe`를 사용한다.

기본 로컬 DB는 H2 **파일 모드** `./data/oops`다. 기본 파일 저장소는 `./uploads`이며 Python의 `MEDIA_STORAGE_ROOT`와 동일한 실제 디렉터리여야 한다. 설정 파일은 [application.yml](src/main/resources/application.yml), [local 프로필](src/main/resources/application-local.yml)이다.

## 현재 분석 경로

업로드·YouTube 등록 → 분석 접수 → Python 길이 확인·STT·OCR → 별도 표현 등장 목록 → 맥락 사전과 후보 탐색 → 후보별 원문 검증 → 근거 기반 병합 → 결과·완료 상태 저장.

- 기본 후보 엔진 개정은 `2026-10-14-target-contract-diagnostics-37`이다.
- 기본 맥락 사전은 외부 파일의 v7 작업 참고다. 사람 검수 완료·학습 완료 데이터라는 뜻은 아니다.
- v7의 로컬 개념 조합 검색은 기본 ON이다. [구조화 검색 보완](../docs/맥락사전-구조화검색-보완.md)은 기존 문자 검색을 보완하며 외부 호출이나 최종 판정을 추가하지 않는다. `STRUCTURED_CONTEXT_SEARCH_ENABLED=false`로 이전 검색을 재현한다.
- 자동 검색 키 분류와 원문 관계 추가 기능은 기본 OFF다.
- 탐색 응답 전용 strict JSON Schema는 Luna 호환·32회 비교 후 기본 ON이다. `STRUCTURED_DISCOVERY_OUTPUT_ENABLED=false`로 기존 JSON 모드를 선택할 수 있다. [실제 출력 계약 비교](../docs/탐색-출력계약-JSON-스키마-비교.md)를 따른다. 형식 준수와 판단 정확도는 별개다.
  후속 ID 범위 계약은 요청마다 primary/raw ID를 분리한다. 보조 문맥 인용은 유지하며 검토·대표 ID로만 쓸 수 없다. 실호출 32회 비교에서 정상 초안은 양쪽 12/12 완전 무경고, 범위 오류도 양쪽 0건이었다. 호환은 확인했지만 감소 효과·범용 정확도 향상은 입증하지 못했다.
- 선택적 프레임 검증 코드는 기본 활성화돼 있지만 전체 영상·음성의 분위기나 포즈를 분석하는 기능은 아니다.
- 일반 분석은 사실 대조·최근 뉴스 검색을 실행하지 않는다. 기존 검색 경로를 시험할 때만 `local,external-review` 프로필을 선택한다.
- 욕설·논쟁 표현 등장은 별도 저장/API 트랙이다. 적중 자체가 위험 카드를 만들지는 않는다.

## 주요 API

기본 경로는 `/api/v1`이다. 구체적인 DTO·권한·옵션은 [API 대조표](../docs/API명세-구현-대조표.md)와 Swagger `/swagger-ui.html`을 함께 확인한다.

| 메서드·경로 | 기능 |
|---|---|
| POST `/videos` · multipart | 파일 업로드 후 분석 시작 |
| POST `/videos` · JSON | YouTube URL 등록 후 분석 시작 |
| GET `/videos/{id}/status` | 분석 상태·진행률 |
| GET `/videos/{id}/report` | 리포트·분석 범위·경고·검수 상태 |
| GET `/videos/{id}/transcript` | 저장된 STT 원문과 시간 |
| GET `/videos/{id}/screen-texts` | 저장된 OCR 원문 |
| GET `/videos/history` | 검수 이력·페이지 |
| POST `/videos/{id}/analysis/retry` | 실패·취소 분석만 재시도 |
| POST `/videos/{id}/analysis/cancel` | 진행 중 분석 취소 |
| PUT `/videos/{id}/review-actions/{eventId}` | 후보별 검수 결정 |
| POST `/videos/{id}/review-completion` | 검수 완료 |
| GET `/videos/{id}/stream` | 업로드 원본 재생 |
| GET `/videos/{id}/frames/{frameId}` | 저장 프레임 |
| DELETE `/videos/{id}` | 영상·분석 결과 삭제 |

WebSocket은 `/ws` → `/topic/videos/{videoId}/progress`다. `GET /health`에서 Python·저장소 상태를 확인한다. YouTube 등록은 로컬 스트림 대신 프론트의 YouTube 재생 처리가 필요하다.

응답은 `success`, `data`, `error` 구조이고 null 필드는 생략될 수 있다. ID는 문자열, 시간 구간은 밀리초 정수다. 오류 추적 값은 `error.details.traceId`와 `X-Request-Id`를 확인한다. 예전 최상위 `message`·숫자 ID·severity 중심 리포트 예시는 현재 계약으로 사용하지 않는다.

## 설정과 사전 반영

| 설정 | 용도 |
|---|---|
| `OPENAI_API_KEY` | Spring 텍스트·선택 이미지 판단 |
| `OPENAI_MODEL` / `OPENAI_REASONING_EFFORT` | 기본 `gpt-6-luna` / `none` |
| `ANALYSIS_SERVER_URL` | 기본 `http://localhost:8000` |
| `REVIEW_GUIDELINES_LOCATION` | 기본 `file:../datasets/controversy/guidelines/runtime.json` |
| `REVIEW_GUIDELINES_MAX_CASES` | 기본 3, 관련성·크기 예산에 따라 더 적게 선택 가능 |
| `CONTEXT_MECHANISM_ROUTING_ENABLED` | 추가 검색 키 분류, 기본 false |
| `CANDIDATE_SOURCE_RELATIONS_ENABLED` | 원문 관계 전달 실험, 기본 false |
| `FINAL_REVIEW_BASIS_ENABLED` | 텍스트 검증의 최종 근거 분리, 기본 false. [계약·검증 상태](../docs/최종-판단-근거-분리.md) |
| `GROQ_STT_USD_PER_MINUTE` | 비용 추정용 기본 $0.00185. Groq 키 설정이 아님 |

실제 Groq 키는 **Python 서버**에 설정한다. `datasets/**`는 Git 제외이므로 배포 서버에도 사전·입력 파일을 따로 제공해야 한다. 사전은 시작 시 로드하며 빌드만으로 실행 중인 서버가 갱신되지 않는다. [파일 관리와 재빌드 절차](../docs/데이터셋-파일-관리.md)를 따른다.

로컬 프로필은 진단을 활성화한다. 실제 요청 trace는 별도 토큰·보존 제한을 사용하는 선택 기능이다. 공개 환경 설정은 [보안·운영 정책](../docs/보안-운영정책.md)을 확인한다.

## 현재 확인된 한계

[Groq 후속 점검](../docs/Groq-전사-후속-점검.md)에서 전사 복원 이후에도 사례 회수 누락, 후보 쟁점 이동, `targetRelation` 불일치, 필수 `truncated` 누락이 남았다. FAILED/PARTIAL·미판정을 경고 0개라는 이유로 정상 처리하지 않는다.

원본은 기본 설정에서 24시간 뒤 정리 대상이 될 수 있다. `source-retention-hours`·프로필 설정과 실제 파일 존재를 확인한다. 기존 완료 리포트는 모델 교체로 자동 갱신되지 않으며 완료 영상의 새 비교는 재업로드로 진행한다.

최신 우선순위는 [개선 계획](../docs/AI-분석-개선-계획.md), 서버 확장 과제는 [아키텍처](../docs/서버-아키텍처.md)에 정리한다.
