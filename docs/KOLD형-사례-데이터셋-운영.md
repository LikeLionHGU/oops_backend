# 문맥 대조 사례 데이터셋과 B·C·D 벤치마크

> 2026-10-10 최신 경계: 승인 사례집 EMPTY와 작업 참고 기준집 v4는 다른 경로다. 현재 참고 사전 연결은 [범용 기준집](범용-논란-기준집.md)을 따른다. 욕설·논쟁 표현의 사용 사실은 [별도 탐지 설계](표현-탐지-및-선택-필터-설계.md)로 구분하며 이 문서의 PASS/REVIEW_REQUIRED 라벨로 적중을 덮어쓰지 않는다.

> 목적 개정: [문맥 논란 사례 파일럿](archive/댓글-수집-및-문맥-파일럿-기록.md)이 최신 구축 방향이다. 알려진 논란 사례에 실제 비판 이유를 보강하고 범용 발생 조건을 탐색한다. 반론은 참고만 하며 비판 취소/다수결에 쓰지 않는다. 새 파일럿 초안 3개는 기존 PASS/REVIEW_REQUIRED 합의 원장과 별도 스키마이며 기존 원장·런타임 사례집은 여전히 EMPTY다.

2026-10-09 · minwook · 오프라인 데이터셋 계약 v1. 런타임 분석 개정 20은 변경하지 않는다.

## 1. 목표와 이번 구현 범위

첫 개발 목표는 피식대학 v1의 B/C/D **서로 다른 3개 검토 카드**다. A는 앞 맥락이 잘린 보조 관찰 항목이며 실패/미평가여도 필수 목표 달성을 막지 않는다. 국가명·브랜드명·부정적인 맛 평가만으로 카드를 늘리는 것은 성공이 아니다. 각 카드가 원문·문맥·대상 또는 표현 자체·정상 해석과의 차이를 설명해야 한다.

| 목표 | 구간 | 확인할 근거 | 정상 대조 |
|---|---|---|---|
| A | 0~3초 | 중국 비교 발언과 실제 화면의 연결 | 위치·국가 질문, 중립 비교 |
| B | 23.5~61.5초 | 매장 부재 → 대신 먹음 → 못 먹으니까라는 누적 대상 취급 | 매장 부재 정보·불편·추억 |
| C | 65.5~73초 | 메뉴·음식 평가가 누적 폄하로 이어지는 실제 이유 | 강한 음식 리뷰·취향 |
| D | 75~80.5초 | 할머니 맛 → 살을 뜯는다는 신체 훼손 비유 | 할머니 음식 맛·추억 |

이것은 **사용자가 설정한 개발 목표**다. A/C 등을 독립 검수로 확정한 정답으로 가장하지 않는다. 원본을 보고 정상 해석이 타당하다고 합의하면 벤치마크를 버전 변경하고 이유를 남긴다. 현재 `status=USER_EXPECTED_PENDING_INDEPENDENT_REVIEW`다. v1은 이미 개발에 반복 사용했으므로 독립 TEST가 아닌 DEVELOPMENT다.

[KOLD](https://aclanthology.org/2022.emnlp-main.744/)의 문맥·대상·표현 근거와 복수 주석 방향을 참고하되, 댓글의 공격성을 분류하는 과제와 원영상의 게시 전 검토 과제를 구별한다. 댓글이 화났다는 사실만으로 원발언을 문제로 라벨링하지 않는다.

이번 구현은 로컬 JSON 원장, 검증기, 검수 사례 검색용 내보내기, 사람 검토를 바탕으로 하는 벤치마크 집계다. 실제 댓글 수집·사용권 확보·사람 라벨링·모델 학습·유료 호출은 수행하지 않았다. 기본 원장은 **비어 있다**. 실제 서비스의 탐지율이 좋아졌다는 결과가 아니다.

## 2. 데이터 구조

원장: `datasets/controversy/curated/dataset.json`. 원본/반응/개별 판정/최종 합의를 분리하고, 모델 입력용 요약 사례집과도 분리한다. 현재 파일 배치와 진행 상태는 [데이터셋 안내](../datasets/controversy/README.md) 및 `datasets/controversy/catalog.json`을 기준으로 확인한다. 정식 원장은 아직 비어 있으며, 실제 댓글 매핑은 별도 미검수 초안이다.

작성 형식은 `datasets/controversy/templates/case-template.json`에서 시작할 수 있다. 이는 SYNTHETIC/DRAFT 가상 예시이며 검색용으로 내보낼 수 없다. 사람 검수자·합의를 가짜로 채우지 않는다. 실제 원장은 여전히 별도 빈 파일이다.

저장소에는 빈 원장·가상 양식·공개 가능한 기준만 둔다. 실제 원본 대본·댓글·검수 자료는 접근을 제한한 별도 위치에 보관하고 CLI에 그 절대 경로를 전달한다. 이 도구는 암호화·접근 제어·보존 기간·삭제 요청 처리를 제공하지 않으며, 원문을 Git에 커밋하거나 진단 로그에 출력하지 않는다.

| 컬렉션 | 필수 정보 및 책임 |
|---|---|
| sources | id, familyId, kind, split, provenance, rightsBasis, allowedUses, privacyReviewed, fullRawSpeech |
| cases | id, sourceId, status, contrastGroupId, contextSummary, retrievalTerms, anchorSegmentId, segments, frames |
| reactions | id, caseId, **댓글 자체 sourceId**, stance, redactedText, claim, contextRelation |
| annotations | id, caseId, reviewerId, stage, reactionIds, 아래 판단 필드 |
| adjudications | id, caseId, adjudicatorId, annotationIds, reviewedAt, resolutionReason, 아래 판단 필드 |

`sources.kind`: DIRECT_FEEDBACK / LICENSED_DATA / SYNTHETIC. 공개 영상이라는 이유로 LICENSED_DATA를 쓰지 않는다. 미확보 상태는 `rightsBasis`에 명시하고 `allowedUses=[]`로 둔다. 실제 자료 보관 자체도 허용되는지 먼저 확인한다.

`allowedUses`: LOCAL_REVIEW / RETRIEVAL / TRAINING 중 **실제로 허용된 용도만** 기록한다. RETRIEVAL 승인이 TRAINING 승인이라는 뜻은 아니다. 코드가 권리의 진위나 사람의 실제 검수 여부를 검증하지는 못한다.

`segments`: `{id, startMs, endMs, rawText, verifiedText, verifiedBy}`. STT 원문을 수정해서 덮어쓰지 않는다. 사람이 들은 전사는 verifiedText에 따로 남기고 확인자를 적는다. 현재 증거 검증은 rawText 기준이다. 전사 오류로 rawText에 근거가 없으면 UNCERTAIN과 누락 정보를 기록하고, 확인 전사를 활용하는 런타임 변경은 별도로 설계한다.

`frames`: `{id, timeMs, observation}`. 실제 장면에서 보이는 사실만 기록한다. 이 원장은 이미지 파일의 업로드·저장·보안·관찰의 진위를 관리하지 않는다. 영상 확인이 필요한 A는 별도 원본 접근과 사람 검수가 필요하다. 포즈·행동 분석은 계속 보류한다.

`reactions.stance`: CRITICISM / COUNTER / IRRELEVANT. `contextRelation`: DIRECT / NEWS_REACTION / UNKNOWN. 뉴스 댓글이 원영상 시청자의 직접 반응이라고 가정하지 않는다. 사용자명·프로필·이메일·불필요한 개인정보를 수동으로 제거하며 자동 익명화가 구현됐다고 주장하지 않는다.

판단 필드:

```json
{
  "decision": "REVIEW_REQUIRED",
  "axis": "TARGET_TREATMENT",
  "target": {
    "referent": "문맥으로 확인한 실제 대상",
    "mentionMode": "OMITTED",
    "rawMention": null,
    "contextReason": "앞뒤 원문이 이 대상을 가리키는 이유"
  },
  "reason": "정상 해석을 넘어서는 구체적 검토 이유",
  "normalInterpretation": "대조한 정상 해석",
  "missingInformation": [],
  "evidence": [{"segmentId": "actual_segment_id", "quote": "실제 rawText에 있는 인용"}]
}
```

decision은 PASS / REVIEW_REQUIRED / UNCERTAIN. axis는 TARGET_TREATMENT / EXPRESSION_CONTENT / NONE. 세부 유형을 에이전트 9개로 나눌 필요는 없다. UNCERTAIN에는 필수 정보 누락을 기록하고, 사람 의견이 갈린다는 이유만으로 UNCERTAIN으로 처리하지 않는다.

target은 null일 수 있다. EXPLICIT는 인용 속 실제 rawMention이 필요하며 CONTEXTUAL/OMITTED는 가짜 대상 인용을 만들지 않고 contextReason과 실제 발언 근거를 남긴다. **데이터셋의 대상 생략 지원을 추가했을 뿐, 런타임의 TARGET 인용 계약을 완화한 것은 아니다.** 관찰·문맥 추론·판정을 혼동하지 않도록 검수한다.

## 3. 주석 순서

1. 권리·보관 가능 범위·개인정보를 확인하고 출처를 등록한다.
2. 같은 원영상·재업로드·관련 뉴스·편집본은 같은 familyId로 묶는다.
3. 두 사람이 댓글을 보지 않고 독립 BLIND 주석을 남긴다. BLIND의 reactionIds는 비워 둔다.
4. 허용된 비판·반론을 확인한 뒤 REACTION_INFORMED 주석을 **새 레코드**로 남긴다. 기존 의견을 덮어쓰지 않는다.
5. 두 독립 주석을 연결하여 합의하고 이유를 남긴다. 연결된 판정이 다르면 제3 검수자가 조정한다.
6. 합의가 끝난 case만 READY로 바꾼다. 사람 2명을 AI 페르소나 2개로 대체해 승인하지 않는다.

동일 표현의 정상/문제 대조 사례는 contrastGroupId로 묶는다. 명시적으로 묶인 대조 쌍은 같은 split에 둔다. familyId도 TRAIN/DEVELOPMENT/VALIDATION/TEST를 넘어서 나눌 수 없다. 자동으로 모든 재업로드·유사 사례를 알아내는 중복 검출은 없으므로 사람이 묶어야 한다.

초기에는 소규모 사례로 지침을 맞춘 뒤 확대한다. 댓글 숫자보다 서로 다른 문맥, 정상 대조, 의견 불일치의 이유가 중요하다. 긍정 사례 수를 맞추려고 정상 해석을 문제로 바꾸지 않는다.

## 4. 검증과 검색용 내보내기

추가 패키지 없이 Python 표준 라이브러리로 실행한다. 저장소 루트 기준:

```bash
python tools/review_dataset.py validate datasets/controversy/curated/dataset.json
python tools/review_dataset.py export-retrieval datasets/controversy/curated/dataset.json
python -m unittest discover -s tools/tests -v
```

두 번째 명령은 결과를 stdout으로 보여준다. 런타임 사례집은 자동으로 덮어쓰지 않는다. 검수 후 별도 파일로 저장하고 [사례 검색 운영](검수-사례-검색-운영.md)의 로컬 파일 설정으로 연결한다.

런타임에서도 `REVIEW_CASES_EXCLUDED_FAMILIES=pisik-yeongyang`를 유지하여 이 사건 계열을 제외한다. 기존 설정에 다른 제외 계열이 있으면 쉼표로 추가한다. 내보내기 이후에도 테스트 계열이 섞이지 않도록 관리해야 하며, JSON 기록의 지문만으로 모든 재편집본을 차단할 수는 없다.

내보내기 조건: READY + 복수 BLIND 주석 + 최종 합의 + TRAIN + 실제 자료 + RETRIEVAL 허용 + 개인정보 검수. 관련 비판 댓글의 별도 출처도 RETRIEVAL 허용이어야 한다. 검색어·요약·인용·문자 수와 전체 파일 한도를 검증하며 초과하면 오류를 반환한다. 조용히 잘라서 의미를 바꾸지 않는다.

피식대학 family `pisik-yeongyang`은 CLI 기본 제외다. `--exclude-family`로 추가 제외할 수 있다. 모든 비TRAIN 자료도 제외한다. 전체 rawSpeech로 기존 Java 검색기의 정규화 SHA-256 방식에 맞춘 지문을 계산한다. 이것은 동일 전사 검출 수단이지 영상의 모든 편집본을 식별하는 해시가 아니다.

검색 아카이브에는 기존 ReviewCaseLibrary 계약에 맞춘 요약만 출력한다. 주석 원장·출처 상세·댓글 원문·개별 검수자 의견 전체는 모델에 보내지 않는다. **파인튜닝 데이터 내보내기와 학습은 아직 구현하지 않았다.** TRAINING 권리와 별도 모델 지원·평가 조건을 확인한 후 진행한다.

## 5. B·C·D 필수 평가와 A 보조 관찰

목표 파일: `datasets/controversy/benchmarks/pisik-v1.json`.
평가 양식: `datasets/controversy/templates/benchmark-assessment-template.json`.

사용자가 새 실행의 report JSON을 로컬 파일로 저장하고, 사람 검수로 평가 양식을 채운다. matches의 한 항목 예시:

```json
{
  "goalId": "D",
  "eventId": "리포트의 실제 카드 ID 문자열",
  "status": "MATCH",
  "targetCorrect": true,
  "interpretationCorrect": true,
  "normalContrastChecked": true,
  "originalEvidenceChecked": true,
  "sceneChecked": false,
  "reportQuote": "그냥 할머니의 살을 뜯는 거 같다",
  "reason": "원본 확인 후 음식 평가와 신체 훼손 비유의 연결을 확인했다"
}
```

`sceneChecked`는 A에서 true가 필수다. 다른 항목도 실제 확인하지 않았다면 true로 쓰지 않는다. 누락은 `{goalId, eventId:null, status:"MISSING", reason}`; 아직 검수하지 않은 것은 UNASSESSED다. 배열에 없는 목표도 UNASSESSED다.

```bash
python tools/review_dataset.py benchmark \
  datasets/controversy/benchmarks/pisik-v1.json \
  /absolute/path/report.json \
  /absolute/path/assessment.json
```

집계기는 report의 videoId·COMPLETED 상태·실제 카드 ID·시간 겹침·카드 원문 인용·사람 확인 필드를 검사한다. **키워드나 시간 겹침만으로 정답을 자동 판정하지 않는다.** 의미 판정의 진위는 사람이 책임진다. 한 카드를 A/B/C/D에 중복 배정할 수 없다.

개발 목표 충족은 B·C·D의 3개 MATCH와 정상 대조 검수 완료, 기록한 오탐 0이 모두 필요하다. `falsePositiveEventIds`에 잘못 발행된 실제 카드 ID를 적는다. `normalControlsReviewed`는 이 영상의 정상 대목을 검수했는지 표시할 뿐, 여러 장르의 독립 정상 영상 평가를 대신하지 않는다. 출력은 일반 정확도/재현율을 측정했다고 주장하지 않는다。A를 평가할 때에도 실제 장면 확인 계약은 유지한다. 집계 출력의 `matches`/`goals`는 필수 항목만 세고 `auxiliaryOutcomes`는 보조 결과를 따로 표시한다. 기준 버전은 `2026-10-09-bcd-development-goal-2`다.

## 6. 다음 실작업

우선 두 검수자가 원영상으로 A~D와 정상 대목을 확인한다. 이 단계가 끝나기 전 사례나 인간 합의 정답을 만들어 넣지 않는다. 다른 영상의 허용된 반응까지 모아 TRAIN 사례를 구축하고, v1은 DEVELOPMENT로 유지한다. 이어 독립 영상 VALIDATION/TEST를 확보한다.

이후 분석 개선은 평가와 별도 변경으로 진행한다. ID 24에서 발견한 응답 계약 실패·후반 탐색 누락·같은 화면 후보 중복 호출을 먼저 줄일 필요가 있다. 데이터셋만 추가해 이러한 구현 실패가 해결됐다고 해석하지 않는다.
