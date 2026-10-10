# 분석 평가와 STT 원음 재확인 — 2026-10-10

## 구현 범위

v1 재검증 → 평가 프로토콜/기록 도구 → 후보 관련 전사 불확실 구간 선정 순서로 진행했다. 정답의 사람 승인, 반복 실행의 안정성 확보, 재전사·교정 저장은 완료하지 않았다. 이 문서는 코드의 테스트 통과와 실제 탐지 성공을 구분한다.

## 고정한 개발 평가 기준

| 항목 | 성공/실패를 판단하는 방법 |
| --- | --- |
| 논란 흐름 발견 | 기존 v1 B/C/D 목표 유지, A는 앞 맥락이 잘린 보조 항목. 시간 겹침·키워드 존재만으로 MATCH 처리하지 않음 |
| 정상 리뷰 과탐지 | 원문/앞뒤 연결/정상 해석을 검수한 뒤 falsePositiveEventIds 기록. v2 제품 리뷰도 아직 승인된 정상 정답이 아님 |
| 쟁점 중복 | 사람이 확인한 duplicateGroups의 추가 카드 수. 동일 시간·원문 묶음은 기계적 힌트일 뿐 확정 중복이 아님 |
| 원문 밖 해석 | 전체 카드를 검수한 뒤 unsupportedInterpretationEventIds 기록. 원문 밖 대상·행위·의도 추정 여부 확인 |
| 반복 실행 | 다른 실행 ID의 목표 판정과 카드 수, 전사 해시, 모델/실행 개정 비교. 같은 카드 수를 같은 정확도로 해석하지 않음 |
| 시간/비용 | 시작~완료 시간과 별도 사용량 로그. 비용 자료가 없으면 null, 0원으로 대체하지 않음 |

필수 B/C/D MATCH, 검수한 정상 대조에서 과탐지 없음, 전체 카드 검수 후 의미상 중복과 원문 밖 해석 없음이 개발 품질 게이트다. 검수 자료가 빠지면 qualityGateMet=null이다. 이 게이트는 특정 개발 영상의 기준이며 범용 정확도나 법적·도덕적 유죄 판정이 아니다.

정답셋은 기존 데이터셋 도구의 독립 검수자 2명·불일치 조정·사건 단위 분할·권한/개인정보 확인을 따로 충족해야 한다. 이번 도구의 단일 실행 평가는 사람의 선언을 검증하는 형식 검사일 뿐 독립 검수를 인증하지 않는다. 알려진 사건이라고 영상 전체를 위험 정답으로 만들지 않는다. 새 정답 라벨을 자동 승인하거나 학습용으로 승격하지 않았다.

## 실행 기록 도구

저장소 루트에서 실행한다. `capture`는 127.0.0.1:8080의 완료 리포트·상태·임시 진단·전사를 읽기만 한다. 업로드·AI·검색을 호출하지 않는다. private JSON에는 원문과 진단이 포함되므로 Git 제외 영역에 저장한다. 진단은 1시간/20영상 메모리 제한이며 서버 재시작 전에 보관해야 한다. 리포트 자체는 DB에 유지된다.

```bash
python tools/evaluate_analysis_run.py capture 44 \
  --output datasets/controversy/experiments/run-44.json

python tools/evaluate_analysis_run.py evaluate \
  datasets/controversy/benchmarks/pisik-v1.json \
  datasets/controversy/experiments/run-44.json \
  --output datasets/controversy/experiments/evaluation-44.json

python tools/evaluate_analysis_run.py template \
  datasets/controversy/benchmarks/pisik-v1.json \
  datasets/controversy/experiments/run-44.json \
  --output datasets/controversy/experiments/assessment-44.json
```

양식의 MATCH, 원문/대상/정상 대조 확인, 전체 카드 검수 필드는 자동 채우지 않는다. 양식 상태 그대로 `--assessment`로 제출하면 완료된 품질 검수가 아니므로 거부된다. 검수 후 평가에 `--assessment <검수 파일>`을 추가한다. 여러 평가 파일은 `compare <파일1> <파일2> ...`로 비교한다. 모델/프롬프트 개정이 다르거나 전사 해시가 없으면 엄밀한 동일 조건 반복 시험이 아니다.

## STT 확인 구간 선정 — 로컬 진단 전용

`GET /api/v1/videos/{id}/analysis/stt-review-plan`을 추가했다. 기존 review-diagnostics-enabled 조건에 묶여 기본 운영 설정에서는 노출하지 않는다. 로그인/영상 소유권이 없는 공개 서버에서 이 설정을 켜면 안 된다. Spring 재시작 후 사용할 수 있다.

선정 개정은 `2026-10-10-stt-review-plan-1`이다. 발언 진단의 UNCERTAIN에서 전사/오인식/음성/발음/알아듣기 관련 누락 정보가 명시된 구간만 고려한다. 대상 신원·외부 사건·문맥 부족만으로 음성을 다시 처리하지 않는다. PASS된 단순 오타나 현재 진단에 표시되지 않은 오류를 임의로 찾거나 교정하지 않는다.

- 원문 ID/시작·끝과 DB 전사를 대조하고 rawText를 그대로 반환한다. 원문 시간 불일치면 SOURCE_SNAPSHOT_MISMATCH이며 임의 매핑하지 않는다.
- 실제 유효 후보의 anchor/제공 문맥에 포함된 구간만 원음 확인 계획에 넣는다. 문맥 포함은 판단을 막는 오류라는 확정이 아니므로 사람 확인이 필요하다.
- 후보 연결이 없으면 DEFERRED_NO_CANDIDATE_LINK로 남긴다. 현재는 시간순 최대 3구간/합산 20초, 각 원문 앞뒤 1초 계획만 만든다. 겹치는 클립도 각각 계산하는 보수적 합산이다.
- 계획 한도는 실제 API 과금 상한이 아니다. executionEnabled=false/additionalCalls=0이며 음성 추출·인식·원문 수정·DB 쓰기는 하지 않는다.
- 진단이 없거나 만료되면 DIAGNOSTICS_UNAVAILABLE이지 오류 없음/PASS가 아니다. 진단에서 놓친 전사 오류와 한영 표현 차이는 자동으로 발견하지 못한다.

다음 재전사 단계에서는 후보 우선순위, 겹치는 원음 범위 합산, 모델·실제 비용/시간 상한, 수정 전사 별도 테이블과 원음 근거·시간 정렬을 설계한다. GPT의 문맥 추측을 원문 교정으로 저장하지 않는다.

## 이번 관찰과 다음 순서

ID 44는 43과 동일 STT 원문/시간인데 C/D만 발행했다. B는 후보 탐색 누락·전사 차이·병합 제거가 아니라 검증 PASS로 사라졌다. 이번 실행에는 병합할 B finding 자체가 없어서 병합 2의 실영상 효과는 검증하지 못했다. 판정 보류 0건이므로 이 오류 선정기는 B 누락을 음성 오류로 자동 분류하지 않는다.

1. 동일 전사에서 B의 정상 해석/문제 해석과 실제 근거 연결을 사람이 검수하고 기준을 맞춘다.
2. B를 강제 발행하지 않고 동일 조건 반복 및 정상 리뷰 대조를 고정된 평가 항목으로 비교한다.
3. 실제로 판단을 막는 전사 불확실 구간이 생겼을 때 원음 확인 계획을 검수한다. 유료 재전사는 별도 구현 전에는 실행하지 않는다.
