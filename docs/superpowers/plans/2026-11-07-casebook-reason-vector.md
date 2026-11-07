# 사례집 비판 이유 벡터 검색 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사례집 비판 이유를 bge-m3 벡터로 저장하고, 후보 발견 단계에 벡터로 찾은 참고 사례를 붙여 참고 없음·키워드·벡터 세 조건의 찾기와 판정을 비교한다.

**Architecture:** 실험은 단계별 오프라인 파이프라인이다. Python이 평가 입력과 비판 이유(유료 AI 호출은 사용자가 실행)를 만들고 로컬 bge-m3로 대상마다 참고 사례 ID를 미리 고른다. Java는 그 ID를 발견 요청에 그대로 렌더링하는 고정 선택 경로만 추가하고, 기존 실험 러너(`ContextExperimentTrial`)가 세 조건을 실행한다. 운영 경로와 기본값은 바꾸지 않는다.

**Tech Stack:** Java 17 / Spring Boot / JUnit 5 + AssertJ + Mockito, Python 3 `unittest`, `sentence-transformers` bge-m3(CPU), OpenAI chat completions(`tools/ai_labels.openai_model`).

**Spec:** [docs/superpowers/specs/2026-11-07-casebook-reason-vector-design.md](../specs/2026-11-07-casebook-reason-vector-design.md)

## Global Constraints

- 유료 호출(사례집 비판 이유 초안, 대상 비판 이유, 세 조건 서비스 실행)은 사용자가 직접 실행한다. 도구는 `--max-calls` 또는 기존 `maxHttpAttempts`/`capUsd` 상한을 반드시 가진다.
- 임베딩은 로컬 `BAAI/bge-m3`, `tools/embed_segments.py`의 `bge_m3_encoder(revision)`과 R1에서 쓴 고정 revision을 그대로 쓴다. 새 모델을 받지 않는다(디스크 여유 약 11GB).
- 산출물은 write-once(`open("x")` 후 `chmod 0o444`), 실험 데이터는 gitignore 경로 `datasets/controversy/experiments/casebook-vector-20261107/`에 둔다.
- 규칙(지시문 2개의 SHA-256, 모델, k=3, 입력 목록, 조건, 반복 3회, 비용 상한)은 첫 유료 호출 전에 `docs/experiments/retrieval-rules/casebook-vector-v1.json`으로 동결한다. 결과를 보고 규칙·이유 문장을 고쳐 같은 사례로 다시 재지 않는다.
- `application.yml`, `datasets/controversy/incidents/`, `guidelines/runtime.json`은 읽기만 한다.
- 참고 사례 상한 3개, 예시 크기 상한 3000 code point, 전체 6000 code point(운영 설정과 같음).
- 사례집 비판 이유 초안 모델 `gpt-6.1-sol`, 대상 비판 이유 모델은 서비스 모델 `gpt-6-luna`.
- 실험에서 "발언 묶음"은 대상 하나와 그 A0 맥락이다(`ContextExperimentRunner`가 대상마다 따로 실행). 그래서 비판 이유도 대상마다 0~1개다.
- 찾기 분모에서 `harmful-glorification`, `privacy-exposure`(사건 1개) 유형 발언과 대상 비판 이유가 null인 대상은 빼고 따로 센다.

## Review Focus

1. 기사 인용을 하나도 뽑을 수 없는 사례집 발언 → 입력에서 빠지고 사유와 함께 목록에 남아야 한다(작업 4 테스트).
2. 고정 선택 ID가 runtime에 없거나, SPEECH 채널이 아니거나, 제외된 family → 조용히 무시하지 않고 예외(작업 1 테스트).
3. 계획 대상인데 벡터 선택 파일에 항목이 없음 → `prepare`가 실패해야 한다(작업 3 테스트).
4. 대상 비판 이유 모델 출력이 깨진 JSON이거나 300자 초과 → 그 대상은 `INVALID`로 기록, 선택은 빈 목록(작업 6·7 테스트).
5. 같은 사건 누설: 사례집 대상에 같은 family 문서가 선택되면 안 되고, 키워드 조건도 같은 family를 제외해야 한다(작업 3·7 테스트).

---

### Task 1: 고정 ID 참고 렌더링 `selectFixed`

**Files:**
- Modify: `oops-backend/src/main/java/com/example/oops/analyzer/ReviewGuidelineLibrary.java`
- Test: `oops-backend/src/test/java/com/example/oops/analyzer/ReviewGuidelineLibraryTest.java`

**Interfaces:**
- Produces: `Selection selectFixed(TimelineEventType channel, String requestKey, List<String> exampleIds)` (package-private). 결과 `trace().retrievalMethod()` = `"FIXED_PRECOMPUTED_SELECTION"`, `trace().exampleIds()` = 실제 렌더링된 ID(순서 유지).

- [ ] **Step 1: 실패 테스트 작성** — 기존 테스트의 v7 archive 픽스처로:
  - `selectFixedRendersGivenExamplesInOrder`: 두 ID를 거꾸로 넘기면 `trace().exampleIds()`가 그 순서, `prompt()`에 두 예시의 `context` 텍스트가 모두 있고 `"reviewGuidelines"`도 있다.
  - `selectFixedWithEmptyListKeepsBasePatternsOnly`: `exampleIds()`가 비고 prompt는 `select`의 base-only와 같은 patterns를 담는다.
  - `selectFixedRejectsUnknownOrExcludedOrWrongChannelIds`: 없는 ID, `excludedFamilies`에 든 family, CAPTION 전용 예시 → `IllegalArgumentException`.
  - `selectFixedSkipsExamplesOverTheExampleBudget`: 예시 상한 256으로 만든 라이브러리에서 큰 예시는 빠지고 `trace().budgetSkipped()`가 1.
  - `selectFixedCapsAtMaxExamples`: maxExamples 3에 4개를 넘기면 앞 3개만.
- [ ] **Step 2: 실행해 실패 확인** — `cd oops-backend && ./gradlew test --tests '*ReviewGuidelineLibraryTest' -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` → 컴파일 실패(`selectFixed` 없음).
- [ ] **Step 3: 구현** — 기존 `select`의 payload·예산 검사(`exampleBudget`, `budget`)와 `SelectionTrace` 생성을 재사용한다. 라이브러리가 split archive가 아니거나 `selector == null`이면 `select`와 같은 `LEGACY_OR_BASE_ONLY` 경로를 탄다.
- [ ] **Step 4: 실행해 통과 확인** — 같은 명령, PASS.
- [ ] **Step 5: 커밋** — `feat: render a fixed list of casebook references`

### Task 2: 발견 단계 참고 주입점 `DiscoveryReferences`

**Files:**
- Create: `oops-backend/src/main/java/com/example/oops/analyzer/DiscoveryReferences.java`
- Modify: `oops-backend/src/main/java/com/example/oops/analyzer/CandidateReviewEngine.java` (`RunServices` 정의 128행, 발견 루프 `stats.reference(...)` 290행, `stats` 클래스 1204행 근처)
- Test: `oops-backend/src/test/java/com/example/oops/analyzer/CandidateReviewEngineTest.java`

**Interfaces:**
- Consumes: 작업 1의 `selectFixed`.
- Produces:
  - `@FunctionalInterface interface DiscoveryReferences { List<String> exampleIds(List<ReviewInput.Segment> raw); }`
  - `record RunServices(VisualContextReviewer visual, ReviewCaseLibrary caseLibrary, ReviewGuidelineLibrary guidelines, ReviewRequestTraceStore.Session requestTrace, ContextMechanismRouter router, DiscoveryReferences discoveryReferences)` + 기존 5인자 생성자(`discoveryReferences = null`) 유지.
  - `discoveryReferences != null`이고 `guidelines != null`이면 발견 요청의 `guidelinePrompt`는 `guidelines.selectFixed(SPEECH, discoveryRequestKey, discoveryReferences.exampleIds(raw))`의 prompt이고 trace는 `contextSelections`에 들어간다. null이면 기존 동작 그대로. 검증 단계(`candidateReference`)는 바꾸지 않는다.

- [ ] **Step 1: 실패 테스트 작성**
  - `discoveryReferencesReplaceLexicalSelectionInDiscoveryOnly`: 모델 mock이 발견 입력을 캡처. 고정 ID 하나를 돌려주는 `DiscoveryReferences`로 실행하면 발견 system/input에 그 예시 context가 있고, `diagnostics().candidatePipeline().contextSelections()`의 `discovery-1` trace가 `FIXED_PRECOMPUTED_SELECTION`과 그 ID를 가진다.
  - `nullDiscoveryReferencesKeepTheExistingPath`: 5인자 `RunServices`로 실행한 발견 입력이 변경 전과 같다(기존 테스트 기대값 재사용).
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*CandidateReviewEngineTest'` → 컴파일 실패.
- [ ] **Step 3: 구현** — `stats`에 `String fixedReference(ReviewGuidelineLibrary library, List<String> ids, String requestKey)`를 `reference(...)` 옆에 두고 trace 기록 방식을 같게 한다.
- [ ] **Step 4: 전체 백엔드 테스트 통과 확인** — `./gradlew test` (기존 1425개 + 신규) PASS.
- [ ] **Step 5: 커밋** — `feat: let experiments supply discovery references`

### Task 3: 실험 러너·트라이얼에 참고 조건 추가

**Files:**
- Modify: `oops-backend/src/main/java/com/example/oops/analyzer/ContextExperimentRunner.java`
- Modify: `oops-backend/src/test/java/com/example/oops/analyzer/ContextExperimentTrial.java`
- Test: `oops-backend/src/test/java/com/example/oops/analyzer/ContextExperimentRunnerTest.java`, `ContextExperimentTrialTest.java`

**Interfaces:**
- Consumes: 작업 2의 `RunServices` 6인자, `DiscoveryReferences`.
- Produces:
  - `TargetRecord`에 마지막 컴포넌트 `List<String> discoveryReferenceIds` 추가: `contextSelections` 중 `requestKey`가 `discovery-`로 시작하는 trace의 `exampleIds`를 순서대로 이어 중복 제거.
  - `ContextExperimentRunner.run(AnalysisContext, String conditionId, ContextSelector, List<String> targetIds, int repeat, ReviewGuidelineLibrary guidelines, DiscoveryReferences references)`; 기존 5인자 `run`은 `this.guidelines, null`로 위임.
  - `ContextExperimentTrial.Spec`에 마지막 컴포넌트 `DiscoveryReferenceSpec discoveryReferences`, `record DiscoveryReferenceSpec(String mode, String selectionsPath, String familiesPath)`. `mode`: `NONE`(또는 spec에 없음, 기존 `baseOnly`), `KEYWORD`, `VECTOR`.
  - `KEYWORD`: 대상 영상마다 `new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(runtime), true, "classpath:frozen.json", 6000, 3, 3000, family, true, true)`; `family`는 `familiesPath`(`{"6001": "pisik-yeongyang-20240511", ...}`)의 값, 없으면 `""`. 로드 후 state가 `READY_WORKING_REFERENCE`가 아니면 prepare 실패(만료 runtime 포함).
  - `VECTOR`: `selectionsPath` 형식 `{"format": "casebook-vector-selections-v1", "selections": {"<videoId>/<targetId>": ["exampleId", ...]}}`, 러너에 `raw -> selections.get(key)`를 넘기고 라이브러리는 `baseOnly`가 아닌 maxExamples 3 라이브러리(family 제외 없음, 제외는 선택 파일이 이미 함).
  - `selectionsPath`, `familiesPath`는 기존 `resolve(base, path, hashes)`로 `inputHashes`에 들어간다.

- [ ] **Step 1: 실패 테스트 작성**
  - Runner `recordsDiscoveryReferenceIds`: 고정 ID `DiscoveryReferences`로 실행한 기록의 `discoveryReferenceIds`가 그 ID.
  - Trial `vectorModeRequiresASelectionForEveryPlannedTarget`: 선택 파일에 대상 하나가 없으면 `prepare`가 `IllegalStateException`/check 실패.
  - Trial `keywordModeExcludesTheTargetFamily`: familiesPath로 지정한 family의 예시가 `select` 결과에 나오지 않는다(픽스처 runtime, 같은 키워드를 가진 예시 두 family).
  - Trial `missingDiscoveryReferencesMeansNone`: 기존 spec JSON(필드 없음)이 그대로 prepare된다.
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*ContextExperiment*'`.
- [ ] **Step 3: 구현** — `main`의 대상 루프에서 모드별 라이브러리와 references를 골라 6인자 `run` 호출. `prepare` 출력 줄에 `refs=<mode>` 추가.
- [ ] **Step 4: 전체 테스트 통과 확인** — `./gradlew test` PASS.
- [ ] **Step 5: 커밋** — `feat: run experiments with none, keyword or vector discovery references`

### Task 4: 사례집 평가 입력 만들기

**Files:**
- Create: `tools/casebook_reasons.py` (`items` 하위 명령)
- Test: `tools/tests/test_casebook_reasons.py`

**Interfaces:**
- Produces:
  - `extract_quotes(text: str) -> list[str]`: `‘…’`, `'…'`, `“…”`, `"…"` 안의 2자 이상 문자열, 등장 순서, 중복 제거.
  - `build_items(incidents: list[dict], runtime: dict) -> list[dict]`: 발언마다 `{"statementId", "familyId", "mechanismIds", "inRuntime", "sourceText", "quotes"}`. `sourceText` = `legacyCard.knownPoint.text` + 줄바꿈 + `legacyCard.controversy.boundaries` 이어붙임. `quotes`는 `knownPoint.text`에서만 뽑는다. `inRuntime` = runtime `examples[].id`에 있음.
  - CLI `python3 tools/casebook_reasons.py items RUNTIME.json INCIDENTS_DIR NEW_OUT_DIR` → `casebook-items-v1.json`(items + `excluded: [{"statementId", "reason": "NO_QUOTE"}]`), `transcripts/<videoId>.json`(`{"segments": [{"id": "stt-index-<i>", "startMs": i*3000, "endMs": i*3000+2500, "text": quote}]}`), `families.json`(`{"<videoId>": familyId}`), `targets.json`(`[{"videoId", "targetId", "statementId"}]`). videoId는 `statementId` 정렬 순서로 6001부터.

- [ ] **Step 1: 실패 테스트 작성**
  - `test_extract_quotes_handles_all_quote_styles_and_dedupes`: `"그가 ‘여기 중국 아니에요?’라고 했고 '소촌이에요' 그리고 ‘여기 중국 아니에요?’"` → `["여기 중국 아니에요?", "소촌이에요"]`.
  - `test_statement_without_quotes_is_excluded_with_reason`: 인용 없는 발언 → `excluded`에 `NO_QUOTE`, transcript 없음.
  - `test_video_ids_follow_sorted_statement_ids_and_segments_are_ordered`.
  - `test_in_runtime_flag_matches_runtime_example_ids`.
  - `test_output_dir_must_be_new`(기존 디렉터리면 `FileExistsError`).
- [ ] **Step 2: 실패 확인** — `python3 -m unittest tools/tests/test_casebook_reasons.py`.
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인.**
- [ ] **Step 5: 커밋** — `feat: build casebook evaluation inputs from quoted statements`

### Task 5: 사례집 비판 이유 초안·확인·확정

**Files:**
- Modify: `tools/casebook_reasons.py` (`draft`, `show`, `finalize`)
- Create: `docs/experiments/label-instructions/Casebook-reason-v1.md`
- Test: `tools/tests/test_casebook_reasons.py`

**Interfaces:**
- Consumes: `casebook-items-v1.json`, `ai_labels.openai_model`, `ai_labels.parse_output`, `_write_once`.
- Produces:
  - 지시문 본문 요지(정확한 문구는 구현자가 쓰되 아래를 담는다): 입력 `sourceText`만 근거로, "누구를/무엇을, 어떤 방식으로, 왜 문제인가"를 한국어 한 문장(40~200자)으로. 사건명·인물명·지명 등 고유명사는 일반 명사로 바꾼다(검색이 이름이 아니라 이유로 맞도록). 출력 `{"reason": "..."}`.
  - `check_reason(doc: dict) -> list[str]`: `reason`이 문자열, 40~200자, 오류 목록 반환.
  - `draft ITEMS NEW_OUT_DIR --model gpt-6.1-sol --instructions PATH --max-calls 40` (유료): 발언당 1회, 응답·usage·`estimatedUsd`를 `run-record.json`에, 결과 `reasons-draft.json` `{statementId: {"reason", "errors"}}`.
  - `show ITEMS DRAFT NEW.md`: 번호·statementId·인용·초안 이유 카드.
  - `finalize ITEMS DRAFT CORRECTIONS|- NEW_OUT.json`: CORRECTIONS `{"statementId": "수정 문장"}`; 출력 `{"format": "casebook-reasons-v1", "reasons": {statementId: {"reason", "source": "DRAFT_CONFIRMED"|"HUMAN_EDITED"}}}`. 초안에 오류가 남은 발언은 수정 없으면 실패.

- [ ] **Step 1: 실패 테스트 작성** — 가짜 model callable로:
  - `test_draft_stops_at_max_calls`(3발언, `--max-calls 2` → 2회 후 `RuntimeError`, 이미 쓴 파일 없음).
  - `test_check_reason_rejects_short_long_and_missing`.
  - `test_finalize_applies_corrections_and_marks_source`.
  - `test_finalize_rejects_unknown_statement_ids_and_unfixed_errors`.
- [ ] **Step 2–4:** 실패 확인 → 구현 → 통과(`python3 -m unittest tools/tests/test_casebook_reasons.py`).
- [ ] **Step 5: 커밋** — `feat: draft and confirm casebook criticism reasons`

### Task 6: 대상 비판 이유(발견 전 호출)

**Files:**
- Create: `tools/query_reasons.py`, `docs/experiments/label-instructions/Query-reason-v1.md`
- Test: `tools/tests/test_query_reasons.py`

**Interfaces:**
- Consumes: 작업 3 트라이얼 `prepare`가 쓴 `plan.json`(`spec.transcripts`, `targets[].contextIds`), `ai_labels.openai_model`.
- Produces:
  - 지시문 요지: 맥락 발언과 표시된 대상 발언을 보고, 대상이 비판받는다면 그 이유를 사례집과 같은 형식의 한 문장으로. 비판받을 여지가 없으면 `null`. 출력 `{"reason": "..." | null}`.
  - `build_input(target: dict, segments: dict[str, dict], context_ids: list[str]) -> str`: 맥락을 transcript 순서로, 대상 줄에 `[대상]` 표시.
  - `check_query(doc: dict) -> str`: `"OK"` | `"NULL"` | `"INVALID"`(문자열 아님, 300자 초과, 키 없음, 파싱 실패).
  - CLI `python3 tools/query_reasons.py PLAN.json NEW_OUT_DIR --model gpt-6-luna --instructions PATH --max-calls N` (유료) → `query-reasons.json` `{"<videoId>/<targetId>": {"reason": str|null, "status": "OK"|"NULL"|"INVALID"}}` + `run-record.json`. 대상은 plan에서 중복 없이 한 번씩.

- [ ] **Step 1: 실패 테스트 작성**
  - `test_build_input_marks_target_and_keeps_transcript_order`.
  - `test_invalid_json_or_overlong_reason_is_recorded_invalid_not_retried`(가짜 모델 호출 수 1).
  - `test_null_reason_is_status_null`.
  - `test_max_calls_cap`.
- [ ] **Step 2–4:** 실패 확인 → 구현 → 통과.
- [ ] **Step 5: 커밋** — `feat: write pre-discovery criticism reasons for experiment targets`

### Task 7: 벡터 선택과 찾기 지표

**Files:**
- Create: `tools/reason_vectors.py`
- Test: `tools/tests/test_reason_vectors.py`

**Interfaces:**
- Consumes: `casebook-reasons-v1.json`, `casebook-items-v1.json`, `families.json`, `query-reasons.json`, runtime(예시 `mechanismIds`, `familyId`), `embed_segments.bge_m3_encoder(revision)`.
- Produces:
  - `select_top(queries: dict[str, list[float]], docs: dict[str, list[float]], doc_family: dict[str, str], query_family: dict[str, str|None], k: int = 3) -> dict[str, list[str]]`: 코사인(정규화 벡터 내적) 내림차순, 동점은 문서 ID 오름차순, `query_family`와 같은 family 문서 제외. 문서 = `inRuntime` 발언만.
  - `chance_hit(pool: list[str], hits: set[str], k: int = 3) -> float` = `1 - C(N-h, k) / C(N, k)`(N<k이면 hit 존재 여부).
  - `retrieval_metrics(selected: dict[str, list[str]], query_meta: dict[str, dict], doc_meta: dict[str, dict]) -> dict`: 대상마다 hit = 선택 중 같은 mechanism을 하나 이상 가진 다른 family 문서가 있음. 유형별·전체 hit 수, 분모, 우연 기대 hit 합, 제외 대상 수(단일 사건 유형, reason NULL/INVALID).
  - CLI `select` → `vector-selections-v1.json`(작업 3의 형식; reason이 NULL/INVALID인 대상은 `[]`, J1 대상은 family 제외 없음) + `vectors-v1.json`(모델 revision, `modelArtifactsHash`, 벡터).
  - CLI `retrieval VECTOR_SELECTIONS KEYWORD_RUN_DIR ... NEW_OUT.json`: 벡터는 선택 파일, 키워드는 KEYWORD 실행 `results.jsonl`의 repeat 0 `discoveryReferenceIds`(사례집 외 예시는 non-hit). 사례집 대상(videoId ≥ 6001)만 집계.

- [ ] **Step 1: 실패 테스트 작성** — 2차원 손수 만든 벡터로:
  - `test_same_family_documents_are_never_selected`.
  - `test_ties_break_by_document_id`.
  - `test_null_or_invalid_query_reason_gives_empty_selection`.
  - `test_chance_hit_matches_hypergeometric`(N=10, h=3, k=3 → `1 - 35/120`).
  - `test_single_family_mechanisms_leave_the_denominator`.
  - `test_keyword_selection_of_non_casebook_example_is_not_a_hit`.
- [ ] **Step 2–4:** 실패 확인 → 구현 → 통과.
- [ ] **Step 5: 커밋** — `feat: select casebook references by reason vectors and score retrieval`

### Task 8: 판정 지표, 규칙 동결, 실행 문서

**Files:**
- Create: `tools/casebook_vector_eval.py`, `tools/tests/test_casebook_vector_eval.py`
- Create: `docs/experiments/retrieval-rules/casebook-vector-v1.json`
- Create: `docs/experiments/사례집-비판이유-벡터검색-실험.md`

**Interfaces:**
- Consumes: 세 조건 실행 디렉터리의 `results.jsonl`, `targets.json`, J1 `final-labels-v2.json`·`panel-comparison.json`.
- Produces:
  - `statement_flag_rate(records: list[dict], targets: list[dict]) -> dict`: 반복마다 발언의 대상 중 하나라도 `REVIEW_REQUIRED`면 그 반복에서 flagged; 비율 = flagged (발언×반복) / 전체, 유형별 포함.
  - `false_alarm_rate(records, j1_random_no_risk: set[str]) -> dict`: J1 무작위 층 기준 PASS 대상의 실행 중 `REVIEW_REQUIRED` 비율(`count`, `value`).
  - CLI `python3 tools/casebook_vector_eval.py BASE NEW_OUT.json` → 조건 `NONE`/`KEYWORD`/`VECTOR`별 위 두 지표.
  - 규칙 파일: 지시문 두 개 SHA-256, 모델 두 개, k=3, `casebook-items-v1.json`·`casebook-reasons-v1.json` SHA-256, J1 무작위 대상 목록, 조건 3개, 반복 3, 각 조건 `capUsd`·`maxHttpAttempts`.
  - 실험 문서: 아래 실행 순서의 명령(사용자가 유료 단계 실행)과 결과 기록 칸.
- [ ] **Step 1–4:** `test_any_flagged_target_flags_the_statement_for_that_repeat`, `test_false_alarm_counts_only_random_layer_pass_targets` → 실패 확인 → 구현 → 통과.
- [ ] **Step 5: 커밋** — `feat: score the casebook vector experiment and freeze its rules`

## 실행 순서(구현 후, 사용자와 함께)

1. `items` 실행(무료) → 인용 추출 결과를 채팅에서 가볍게 확인.
2. `draft`(유료, 사용자) → `show` → 채팅 확인 → `finalize`.
3. 규칙 파일 동결·커밋.
4. 세 조건 spec 작성 → `NONE`, `KEYWORD` `prepare`(무료). 대상 = 사례집 transcript 전체 세그먼트 + J1 무작위 층 대상, 선택기 A0.
5. `NONE` plan으로 `query_reasons.py`(유료, 사용자).
6. `reason_vectors.py select`(무료) → `VECTOR` `prepare`.
7. J1처럼 영상 하나 5개 대상으로 비용 probe 후 세 조건 본 실행(유료, 사용자).
8. `reason_vectors.py retrieval`, `casebook_vector_eval.py`(무료) → 실험 문서에 기록.
