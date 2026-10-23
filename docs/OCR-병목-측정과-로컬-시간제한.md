# OCR 병목 측정과 로컬 시간 제한

2026-10-23 · 프레임 축소 시험 보류, 기존 처리량으로 측정

## 현재 계약

| 구분 | 기본/운영 | 명시적 local |
|---|---|---|
| Python OCR 전체 제한 | 기본540초 | 기본0: 전체 제한 없음 |
| Spring OCR 읽기 제한 | 일반 timeout 상속, 기본10분 | 별도 ocr-timeout=0s: 제한 없음 |
| Spring 일반 분석 | 기본10분 | 기존25분 |
| 헬스 확인 | 별도2초 | 별도2초 |

`OCR_TIMEOUT_SEC` 양수로 OCR 제한을 다시 지정할 수 있다.0은 local에서만 허용하며 기본 프로필에서는 설정 오류다. OCR 전용 Spring 클라이언트를 분리하고 연결 수립10초 제한은 유지한다. 연결 종료·취소 때 자식 프로세스 회수와 한 작업 admission도 유지한다. 무제한은 성공 보증이 아니며 무진행 watchdog은 아직 없다.

ffprobe20초·ffmpeg 기본300초(MEDIA_TIMEOUT_SEC 별도 설정)·다운로드 도구의 재시도/네트워크 제한은 별개로 남는다. 모든 단계의 무제한 실행이나 긴 영상의 완료를 보장하지 않는다. 부모 SIGKILL 때 고아 프로세스 방지도 기존 한계다.

## 단계 측정

`[ocr-timing]`은 원문·URL·키 없이 PID·단계·경과 시간·성공/실패를 기록한다.

- prepare_media: 다운로드/파일 준비와 길이 확인의 합계. 이 단계가 크면 다운로드/probe를 더 나눈다.
- model_init: Paddle import·모델 초기화.
- extract_frames: ffmpeg 프레임 추출.
- recognize_frames: 모든 engine 호출 시도의 합계와 프레임별 p50/p95. 실패 시도도 포함한다.
- assemble_items: 영역 추적·이미지 보존·응답 항목 정리.
- worker_total: 요청 처리와 원본 임시 작업 정리. 프로세스 import/startup은 부모 reaped elapsed와 구분한다.

인식 뒤 파싱 등 루프 부가 비용은 engine 호출 합계에 포함되지 않는다. 기존 요약도 setupAndExtractionSec/postExtractionSec로 이름을 바로잡아 모델 초기화를 순수 추출 시간으로 오해하지 않게 했다. `processPeakRssKiB`는 프로세스 누적 최고 RSS이지 단계별 현재 RSS·증가량이 아니다. macOS bytes는 KiB로 정규화하며 지원 불가 환경은 None이다. 이 값만으로 swap이 병목이라고 확정하지 않는다.

프레임 상한300·요청 간격4초·해상도1600·인식 배치1·판정 기준은 유지한다.120장·10분 시험은 보류한다. 기존 전체 OCR 약13분이 전부 네이티브 인식 시간이었다고 아직 단정하지 않는다. Paddle import가 root 로그 수준을 바꾸더라도 측정 로그가 차단되지 않게 해당 logger 수준을 명시한다.

## 적용

양쪽 서버 재시작이 필요하다. 키가 설정된 기존 환경을 유지하며 Python 실행 줄에서 이전 시험 설정을 명시적으로 덮어쓴다.

```bash
cd /Users/im_inwook/dev/oops_backend/oops-analysis
ANALYSIS_PROFILE=local OCR_TIMEOUT_SEC=0 MAX_OCR_FRAMES=300 \
  /opt/anaconda3/envs/oops-analysis/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Spring은 기존 local 실행 명령을 유지한다. 예전 시험용 `--oops.analysis-server.timeout=12m`과 별도 `ocr-timeout` 재정의가 있다면 제거한다. local의 `ocr-timeout=0s`는 OCR 전용 클라이언트에만 적용된다. health에서 `analysisProfile=local`, `ocrTimeoutSec=0`, `maxOcrFrames=300`을 확인한다. 키 파일 전체를 출력하지 않는다.

## 무료 검증과 한계

### 저장 프레임 실제 네이티브 측정

같은 Paddle 모델(인식 배치1), 저장된 가로1600 이미지3장을 한 프로세스에서 각1회 인식했다. 새 영상 다운로드·STT·LLM 호출은0회다. 모델 초기화2.705초, 프레임 인식은 다음과 같다.

| 저장 화면 | 파싱된 글자 영역 | engine 인식 시간 |
|---|---:|---:|
| 생성 지시 문서 | 51 | 14.489초 |
| MT 선호 UI | 24 | 7.495초 |
| 인물·짧은 자막 | 3 | 1.144초 |

이 표본에서는 모델 초기화보다 개별 인식 비용이 컸으며 밀집 화면에서 훨씬 느렸다. 하지만 화면 내용·순서가 달라 영역 수만의 인과 효과를 분리한 것은 아니다. 초기화는 cold-cache 보장이 없고 각각 반복1회뿐이다. 다운로드·프레임 추출과 전체 영상 분포를 측정하지 않았으므로 전체13분의 원인을 확정하지 않는다. 첫 측정은 Paddle 로그 수준 변경으로 단계 로그가 숨겨져 숫자를 얻지 못했고, 보완 후 두 번째 실행에서는 명시적 수치 출력으로 위 값을 확보했다. 원문은 출력하지 않았다.

Python140·Spring1,156 통과. Python local 무제한 성공·회수·연결 종료, 운영0 제한 거절, 양수 제한 유지와 로그 성공/실패를 확인했다. Spring은 로컬 합성 HTTP 지연 응답에서 OCR 무제한과 일반 클라이언트 제한을 구별하고 전용 클라이언트 배선을 확인했다. 첫 전체 실행의 record 생성자 바인딩 문제를 수정한 뒤 전체 통과했다.

서버를 임의 재시작하지 않았으며 새 STT·LLM 호출은 없다. 실제 저장 프레임의 네이티브 인식 보정 측정과 전체 영상 병목 측정은 구별한다. 다음 한 편의 단계별 로그를 수집한 뒤 검수 범위를 줄이지 않는 최적화부터 판단한다.
