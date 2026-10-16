package com.example.oops.analyzer;

/** Experimental text discovery contract. Shared judgment policy and visual contract stay unchanged. */
final class SourceRelationPrompts {
    private SourceRelationPrompts() {}
    static final String DISCOVERY = CandidateReviewPrompts.POLICY + """
            # 원문 관계 후보 탐색 (실험)
            primaryIds를 모두 검토하고 실제 읽은 ID만 reviewedSegmentIds에 반환한다.
            raw의 보조 문맥 ID는 evidence·sourceRelations에 인용할 수 있지만 reviewedSegmentIds·anchorId·
              uncertainSegments.segmentId로 반환하지 않는다. anchorId·불확실 구간 ID는 reviewedSegmentIds에도 포함한다.
            재검토할 구체적인 표현 또는 연결을 찾되, 이 단계에서 비하 대상이나 논란 판정을 확정하지 않는다.
            정상적인 리뷰·대체 행동·메뉴 유래 설명만 있으면 candidates에 넣지 않는다.
            필요한 정보가 없으면 uncertainSegments에 {"segmentId":"실제 원문 ID","missingInformation":["부족한 정보"]}를 반환한다.
            missingInformation은 문자열 배열이며 reason이나 문자열 하나로 바꾸지 않는다.
            각 텍스트 후보는 anchorId, axis, evidence, sourceRelations를 반환하고 reason은 넣지 않는다.
            anchor는 primaryIds의 대표 표현이며 상황·주체·행동·평가·지칭 중 실제 역할에 둔다.
              anchor를 억지로 평가 역할에 넣지 않는다. TARGET_TREATMENT는 targetWindows,
              EXPRESSION_CONTENT는 windows 중 anchorIds와 evidence 및 모든 역할 ID를 포함하는 한 창을 사용한다.
            evidence는 anchor를 포함한 허용 segmentId 객체 1~8개이며 quote는 반환하지 않는다.
              후보는 visualCandidates와 합쳐 최대 12개다. 초과하면 truncated=true로 표시한다.
            sourceRelations에는 아래 다섯 배열을 모두 반환한다. 원문에 없거나 구별 못 하면 []다.
              situationIds: 평가에 연결되는 장소·조건·상황을 말한 줄.
              subjectIds: 원문에 직접 등장한 행동·경험의 주체를 말한 줄. 화자 신원 추정은 금지한다.
              actionIds: 그 주체의 행동·선택을 말한 줄. 동기·궁핍함을 추정하지 않는다.
              evaluationIds: 실제 비교·평가·반문·표현 자체가 있는 줄. 최소 한 줄이 필요하다.
              referentIds: 평가가 가리킬 수 있는 대상·장소·대명사가 있는 줄. 공격 대상의 확정이 아니다.
            evidence는 역할 ID들의 합집합을 포함한다. 서버도 누락된 역할 ID의 원문을 합치므로
              evidence와 모든 역할 ID를 합쳐 서로 다른 ID가 최대 8개여야 한다. 같은 줄은 여러 역할에 속할 수 있다.
              연결의 출발점·주체·평가를 중요하다는 이유 없이 음식이나 한 대상만으로 축약하지 않는다.
              제공된 원문만 연결하며, 시간 인접만으로 같은 사람·대상·사건이라고 단정하지 않는다.
            정상 해석과 비판 해석 중 어느 쪽도 확정하지 않는다. category·score·target·판정 문장은 반환하지 않는다.
            신체 훼손 비유는 EXPRESSION_CONTENT다. 비유 속 인물을 실제 공격 대상으로 만들지 않는다.
            모든 primaryIds의 화면 의존성도 별도로 확인한다. 텍스트 위험 없음은 화면 확인 불필요가 아니다.
              장면이 판단에 필수인 후보는 visualCandidates로 반환한다. 국가명·지역명 자체는 후보 이유가 아니다.
              visualCandidates만 기존 anchorId·axis·reason(필요한 시각 정보, 300자 이내)·evidence 형식을 쓰고
              sourceRelations는 넣지 않는다. 전체 영상·억양을 본 것으로 가정하지 않는다.
            JSON 객체 하나만 반환한다. 아래 ID/axis 예시는 실제 허용 값으로 바꾼다.
            다섯 최상위 필드를 항상 반환한다. 후보가 없으면 candidates/visualCandidates는 [],
              불확실 구간이 없으면 uncertainSegments는 []다. reviewedSegmentIds에는 primaryIds만 넣는다.
            JSON: {"reviewedSegmentIds":["실제-ID-1"],"candidates":[{
              "anchorId":"실제-ID-1","axis":"TARGET_TREATMENT",
              "evidence":[{"segmentId":"실제-ID-1"}],"sourceRelations":{
              "situationIds":[],"subjectIds":[],"actionIds":[],"evaluationIds":["실제-ID-1"],"referentIds":[]}}],
              "visualCandidates":[],"uncertainSegments":[],"truncated":false}
            """;
    static String verification(int evidenceLimit) {
        return CandidateReviewEngine.verificationPrompt(evidenceLimit) + """

                # 원문 관계 전달 (실험)
                이 텍스트 후보에는 hypothesisNotEvidence가 없다. sourceRelations는 원문 ID의 역할 제안일 뿐,
                  확정된 관계·평가 대상·논란 판정이 아니다. 역할 오류이면 실제 raw를 기준으로 판단한다.
                상황 → 주체/행동 → 평가 표현 → 실제 평가 대상을 시간순으로 다시 확인한다.
                  referentIds가 있다고 공격 대상으로 확정하지 않으며 subjectIds를 공격 대상과 혼동하지 않는다.
                음식·상품의 품질 평가인지, 그 선택이나 생활을 낮추는지 실제 원문으로 구별한다.
                  관계 슬롯이 채워졌다는 이유나 선택지 부재·대체 행동만으로 경고하지 않는다.
                역할 밖의 허용 raw도 읽는다. 역할에 없는 새 내용을 추측하지 않는다.
                최종 target·category·판정은 여기서 독립적으로 결정하며 PASS/UNCERTAIN도 가능하다.
                """;
    }
}
