# 공통 논란 카드 JSON

2026-10-10 · 스키마 `controversy-cards-1`

하나의 논란 포인트가 카드 하나다. 사건은 여러 카드를 포함하고 각 카드는 표현·전후 맥락·논란 이유의 해석 초안·관련 댓글을 연결한다. 사건별 키워드 금지 목록이나 댓글을 이용한 제작 의도 단정으로 바꾸지 않는다.

## 저장 위치와 관계

- 공통 초안: 로컬 `datasets/controversy/drafts/cards.json`
- 반응 단계 분류 계획: 로컬 `datasets/controversy/research/reaction-classification-plan.json`
- 진행 목록: 로컬 `datasets/controversy/catalog.json`의 `commonCardsDraft`
- 기존 원문·사례 초안: `uploads/comment-collections/`에서 변경 없이 보존
- 정식 검수 원장: `datasets/controversy/curated/dataset.json`은 여전히 비어 있음

`datasets/`와 `uploads/`는 Git 제외 대상이다. 도구·가상 테스트·설명만 커밋한다. 공통 초안에 댓글 발췌가 있으므로 원본과 같은 보관·검수 제한을 적용한다. 파일별 갱신·삭제 기한은 `collections`에 보존하며 공통 파일을 만든 날짜로 연장하지 않는다. 만료되면 원본뿐 아니라 발췌·파생본·백업도 함께 점검해야 한다. 자동 삭제는 하지 않는다.

## 필드

| 범위 | 필드 | 의미 |
| --- | --- | --- |
| 전체 | `schemaVersion`, `status`, `collections`, `incidents` | 버전·미검수 상태·원본 수집 참조·사건 묶음 |
| 사건 | `familyId`, `split`, `cards` | 같은 사건의 뉴스·재편집 자료를 같은 family로 관리 |
| 카드 | `knownPoint` | 알려진 논란 포인트와 선정 근거; 독립 사실 검수와 구별 |
| 카드 | `evidence` | STT 발췌 또는 보도 직접 인용, 출처, 시간·원음 확인 상태 |
| 카드 | `context` | 맥락 범위·대화 순서 해석·빠진 정보·대상 확인 상태 |
| 카드 | `controversy.reasonHypotheses` | 댓글과 연결된 비판 이유 해석 초안; 해당 반응 ID를 참조 |
| 카드 | `reactions` | 실제 댓글 발췌와 별도의 해석·매핑·사용 제한 |
| 카드 | `review`, `provenance` | 승인·사용 범위와 이전 파일의 이름·SHA-256 |

현재 변환본은 사람 확인을 거치지 않은 과거 STT 발췌와 보도 인용만 지원한다. 원영상이 없어도 인용 기반 사례를 남길 수 있지만 보도 인용을 STT로 바꾸거나 시간을 만들지 않는다. 화자·공격 대상·억양·장면을 새로 추정하지 않으며 대상은 `null`과 `NOT_RECONSTRUCTED`로 남긴다. 원영상 확인 자료와 승인 사례의 도입은 별도 스키마·검수 단계에서 다룬다.

## 댓글의 세 가지 구분

1. `role`: 비판 보조 반응 / 참고 반론. 반론은 비판 취소에 사용하지 않는다.
2. `stage`: `CONTENT` / `PUBLICATION_TIMING` / `POST_CONTROVERSY_RESPONSE` / `UNKNOWN`. 작성 날짜가 아니라 **무엇에 대한 반응인지**를 분류한다. 날짜·사건 사실의 확인 상태는 별개다.
3. `mapping.scope`: 특정 포인트 직접 지목 / 대화 흐름 / 사건 전체. 기존 연결 범위를 자동으로 강화하지 않는다.

사후 해명 비판과 미분류 반응은 보관하지만 카드의 내용·게시 시점 비판 이유 목록에서 제외한다. 참고 반론도 별도로 보존한다. 모든 단계 분류는 근거 설명과 `humanVerified: false`를 가진다. 직접 발언과 매핑돼 있어도 `detectionEvidenceEligible: false`다. 구조 변환은 사람 검수나 탐지 규칙 승격이 아니다.

`excerpt`는 원댓글 안의 부분 문자열과 Unicode 코드 포인트 시작·끝 위치를 기록한다. 원댓글 전체를 다시 복사해 욕설·추측을 확산시키지 않고, 발췌 문맥 검수는 `PENDING`으로 남긴다. 짧은 발췌의 문맥 부족이 자동으로 해결됐다는 뜻이 아니다. `interpretation`과 `unverifiedCommentClaims`는 실제 영상 근거와 별개다.

## 검증과 재생성

```sh
# 구조·원본·기한·명시적 분류 계획을 검사. 기본 출력은 건수뿐이다.
python3 tools/controversy_cards.py

# 저장한 공통 초안이 원본 및 계획으로 재구성한 결과와 일치하는지 검사
python3 tools/controversy_cards.py --bundle datasets/controversy/drafts/cards.json

# 공개 코드의 테스트는 실제 데이터 없이 실행
python3 -m unittest discover -s tools/tests -q
```

`--build`는 로컬 저장을 위해 공통 JSON 전체를 stdout으로 출력하는 명시적 옵션이다. 댓글 발췌가 포함되므로 채팅·공개 로그·CI 출력에 사용하지 않는다. 기본 명령은 네트워크·모델 호출·파일 변경·승인·학습·런타임 내보내기를 하지 않는다. 도구로 검증되는 것은 참조·해시·원문 발췌·기한·구조 일치이며, 연결 의미나 사용권·범용 탐지 정확도는 검증하지 않는다.

## 이번 이관 결과

3사건·6카드·22반응을 보존했다. 단계는 내용 관련 19개(참고 반론 포함), 게시 시점 2개, 사후 해명 1개다. 승인 사례는 0개다. 기존 패턴 가설과 정식 원장은 자동 변경하지 않았다. 다음 작업은 짧은 발췌의 문맥, 간접 매핑, 개별 비판 이유와 대상의 의미 검수다.
