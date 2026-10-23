# OCR 빠른 시험 설정

2026-10-23 · 로컬 선택 설정 · 실제 속도/탐지율 검증 전

## 타임아웃과 처리시간은 다르다

최근978초 영상의 OCR 단계는 약12분47초~13분11초였다. Python20분·Spring25분은 기다리는 최대값이지 의도적인 대기 시간이 아니다. 같은 처리량에서 제한만 줄이면 빨라지는 것이 아니라 OCR 실패로 끝날 수 있다.

빠른 시험은 기존 환경변수를 사용해 프레임 작업량도 함께 줄인다. 정밀 기본값은 변경하지 않는다.

| 설정 | 기존 local | 빠른 시험 |
|---|---:|---:|
| MAX_OCR_FRAMES | 300 | 120 |
| OCR_TIMEOUT_SEC | 기본1200 | 600 |
| Spring 분석 서버 timeout | 기본25분 | 실행 인자12분 |
| 978초·요청 간격4초의 계산상 샘플 | 약245장 | 약120장 |
| 위 영상의 간격 | 4초 | 약8.15초 |

인식 모델·해상도1600·신뢰도 기준·STT·사전·판단 프롬프트는 바꾸지 않는다. 짧은8초 영상의 간격1초 보정도 유지된다. 제한은 전체 작업(다운로드·초기화·인식)에 적용되며 시간 초과에서 작업 프로세스를 회수하는 로직도 유지한다. 실제 추출 개수는 소스 PTS와 영상 끝 경계에 영향을 받을 수 있어 계산값과 구분한다.

## 실행

기존 Python은 해당 터미널에서 Ctrl+C 후 아래를 실행한다. 키·스토리지 설정이 들어 있는 기존 환경과 전용 Python을 그대로 사용한다.

```bash
cd /Users/im_inwook/dev/oops_backend/oops-analysis
ANALYSIS_PROFILE=local MAX_OCR_FRAMES=120 OCR_TIMEOUT_SEC=600 \
  /opt/anaconda3/envs/oops-analysis/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Spring은 기존 실행 명령의 `--args` 안에 `--oops.analysis-server.timeout=12m`을 추가한다. 예를 들어 기존 진단 옵션을 유지하면 다음과 같다. 키와 Java 환경은 기존 실행 터미널 설정을 유지한다.

```bash
cd /Users/im_inwook/dev/oops_backend/oops-backend
bash ./gradlew bootRun --args='--spring.profiles.active=local --server.address=127.0.0.1 --oops.analysis.review-request-trace-enabled=true --oops.analysis.startup-recovery-enabled=false --oops.analysis-server.timeout=12m'
```

Python `/health`에 `analysisProfile=local`, `maxOcrFrames=120`, `ocrTimeoutSec=600`을 표시한다. 모델 초기화·실행 성공의 보증은 아니다. 타임아웃·프레임 상한을 확인한 뒤 한 편만 실행한다.

## 비교와 한계

샘플이 줄면 잠깐 보이는 자막·간판·개인정보를 놓칠 수 있다. 빠른 시험을 정밀 OCR과 동등한 전체 검수라고 설명하지 않는다. 같은 원본 비교는 속도·기존 검토 문구의 유지 여부를, 다른 영상은 새 실패를 발견하는 용도로 구분한다. STT와 카드 수뿐 아니라 추출 구간·중요 화면 글자 누락·OCR 실패·작업 종료를 함께 본다. 10분 제한에 걸리면 성공으로 간주하거나 몰래 재시도하지 않는다.

처리량 계산은 절반가량이지만 지연시간 절반이나 정확도 유지를 보장하지 않는다. 실제 새 영상·재분석은 별도 비용·전송 승인 후 수행한다. 한 편 완료와 worker 종료를 확인한 뒤에만 다음 영상을 넣는다.

정밀 복귀는 Python의 `MAX_OCR_FRAMES=300`, `OCR_TIMEOUT_SEC` 재정의 해제(local 기본20분), Spring의12분 실행 인자 제거(local25분) 후 재시작한다. 운영 기본 설정은 이번에 바꾸지 않았다.

## 무료 검증

978초 영상의 계산상 간격4→8.15초·프레임245→120, 짧은 영상 보정 유지, 타임아웃 재정의·원래 기본값, health의 실제 설정 표출과 모델 미초기화를 검증한다. 네이티브 OCR 속도·미탐률을 측정한 결과는 아니다.
