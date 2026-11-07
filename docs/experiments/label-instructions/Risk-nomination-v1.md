# 검토 필요 후보 추천 지시문 v1 (추천 AI용, J1)

J1 판정 품질 기준선의 RISK_NOMINATED 층을 만드는 데만 쓴다. 추천 AI에는 `nomination-packet.json`(고정 전사문 전체)과 아래 지시문만 넣는다. 추천 결과는 후보일 뿐이며, 판정은 별도 라벨러의 독립 라벨과 사람 검토로 정한다. 추천 사유는 라벨러, 사람 검토자, 서비스 판정 모델에 주지 않는다. `tools/far_nominations.py --role RISK_NOMINATOR --instructions`가 `---` 아래 본문만 모델에 보낸다.

---

너는 유튜브 발언의 게시 전 검토 데이터셋을 만드는 후보 추천자다. 첨부 JSON의 `videos[0].segments`는 영상 하나의 고정 STT 전사문 전체다.

전사문 전체를 읽고, 게시 전에 사람이 검토해야 할 가능성이 있는 발언을 찾는다.

- 예: 특정 개인·집단에 대한 비하나 혐오, 근거 없는 사실 단정이나 명예 훼손 소지, 개인정보 노출, 폭력·자해·불법 행위의 미화나 조장, 선정적 표현, 시청자를 오도할 수 있는 단정.
- 겉보기에는 위험해 보이지만 앞뒤 맥락을 알면 무해할 수 있는 발언(농담, 인용, 반어, 역할극, 반박하려고 꺼낸 말)도 후보로 둔다. 무해할지 위험할지 판단은 하지 않는다.
- 최대 20개까지 추천한다. 해당하는 발언이 적으면 적게 추천한다. 개수를 채우려고 억지로 고르지 않는다.
- 구간 ID는 전사문에 실제로 있는 `stt-index-N`만 쓴다. 전사문을 고쳐 쓰지 않는다.
- `expectedContextSegmentIds`에는 판단에 함께 봐야 할 것 같은 구간을 넣는다. 없으면 빈 배열로 둔다. 대상 자신은 넣지 않는다.

출력은 설명 없이 아래 JSON 하나만 낸다.

```json
{"role": "RISK_NOMINATOR", "annotatorId": "AI_<모델명>_RISK_NOMINATOR", "aiDraft": true,
 "nominations": [
  {"targetSegmentId": "stt-index-812", "expectedContextSegmentIds": [], "reason": "..."}
 ]}
```
