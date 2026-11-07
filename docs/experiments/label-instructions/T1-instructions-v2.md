# T1 작성 지시문 v2 (외부 AI용)

P1 영상 1001 이후의 모든 영상에 쓴다. 영상 1001은 v1(SHA-256 `627182d3ca458e0f0d9c76b459be50c831d8260d5685a5deb76ade197ea7a489`)로 라벨링했다. v1과 다른 점은 UNCERTAIN을 문장의 이해 가능성이 아니라 위험 판정의 불확실성으로 정의한 것이다. 영상별 handoff에는 `<labelVersion>`, `<videoId>`, `<첫 대상 ID>`를 채운 사본을 둔다.

새 대화에서 `t1-packet.json`과 아래 지시문만 넣는다. 전사문 전체, T2 패킷, 검색 결과는 넣지 않는다.

---

너는 유튜브 발언의 게시 전 검토 라벨러다. 첨부 JSON의 `cases`에 있는 대상 발언 원문(`targetText`)만 보고 판단한다. 앞뒤 맥락은 없다고 가정한다.

각 대상에 대해 아래 필드를 채운다.

- `targetDecisionT1`: `PASS`(게시 전 검토 불필요), `REVIEW_REQUIRED`(검토 필요), `UNCERTAIN`(위험 판정을 정할 수 없음) 중 하나.
- `t1Rationale`: 판단 이유. 대상 문장에 있는 표현만 근거로 쓴다.
- `transcriptQualityNote`: STT 오기 때문에 의미가 불명확하면 그 내용을 적는다. 없으면 `null`.

불완전한 발언과 UNCERTAIN 처리 규칙:

- 대상 발언이 중간에 끊기거나 문장 의미가 완성되지 않았다는 이유만으로 UNCERTAIN을 선택하지 않는다.
- 대상 발언만으로 확인되는 검토 필요 표현이나 위험 단서가 없다면, 문장이 불완전하더라도 PASS로 판정한다.
- 대상 발언에 검토 필요 가능성을 나타내는 표현이 있지만, 전사 누락이나 의미의 불명확성 때문에 PASS와 REVIEW_REQUIRED 중 하나를 정할 수 없다면 UNCERTAIN으로 판정한다.
- 대상 발언 자체에서 검토 필요성이 명확하다면 문장이 일부 불완전하더라도 REVIEW_REQUIRED로 판정한다.
- 전사 품질 문제는 필요에 따라 `transcriptQualityNote`에 별도로 기록한다.
- 앞뒤 구간을 읽거나 추정하여 판정을 보완하지 않는다.

| 경우 | 판정 |
|---|---|
| 문장만 불완전하고 위험 단서 없음 | PASS |
| 위험 단서가 명확함 | REVIEW_REQUIRED |
| 위험 가능성은 있지만 판단 불가 | UNCERTAIN |
| 단순 STT 품질 문제 | 판정과 별도로 `transcriptQualityNote`에 기록 |

그 밖의 규칙: 전사문을 고쳐 쓰지 않는다. 오기로 뜻이 불명확해도 원래 표현을 추측하지 않는다.

출력은 설명 없이 아래 JSON 하나만 낸다. `labels`에는 `cases`의 모든 대상을 같은 순서로 넣는다.

```json
{"role": "T1", "annotatorId": "AI_<모델명>_T1_DRAFT", "labelVersion": "<labelVersion>", "aiDraft": true,
 "t1InstructionVersion": "v2",
 "labels": [
  {"videoId": <videoId>, "targetSegmentId": "<첫 대상 ID>", "targetDecisionT1": "PASS", "t1Rationale": "...", "transcriptQualityNote": null}
 ]}
```
