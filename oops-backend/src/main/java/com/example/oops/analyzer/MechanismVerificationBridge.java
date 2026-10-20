package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import java.util.*;

/** Offline preparation and response replay only. No client, Spring bean, repair or publication path. */
final class MechanismVerificationBridge {
    static final String REVISION="2026-10-20-rule-verification-bridge-1";
    private static final String RULE_CONTRACT="""
            # 규칙 후보 입력 계약
            이 요청의 후보는 표면 표현 조합으로 확보한 검토 항목이며 AI의 비판 가설이 아니다.
            hypothesisNotEvidence는 제공하지 않는다. 누락된 가설이나 의도를 만들어 채우지 않는다.
            surfaceSignals는 원문 일부의 적중 위치일 뿐 실제 대상·수식 관계·비유·잘못을 입증하지 않는다.
            proposedEvidence는 확인을 시작할 원문이며 최종 PRIMARY/TARGET/CONTEXT 역할은 검증 후 결정한다.
            원문 창 전체에서 정상 안내·인용·뜻풀이·자기 설명과 실제 문제 표현을 구별한다.
            규칙 적중이나 참고 사례의 존재를 이유로 경고하지 않는다. 기존 판단 기준·출력 계약을 따른다.
            """;
    record AiSource(CandidateUnionShadow.AiCandidate unionCandidate, CandidateReviewEngine.Candidate original) {}
    record Prepared(String revision, String inputSha256, CandidateReviewEngine.Candidate candidate,
                    String system, String user, String referenceSha256,
                    ReviewGuidelineLibrary.SelectionTrace referenceTrace, int evidenceLimit) {}

    /** Preserve the original AI hypothesis, relation contract and permitted raw window separately. */
    static AiSource retainAi(ReviewInput input,CandidateReviewEngine.Candidate candidate) {
        if(candidate.raw().isEmpty() || candidate.raw().stream().anyMatch(s -> !input.find(s.id()).map(s::equals).orElse(false)))
            throw new IllegalArgumentException("AI source window differs from snapshot");
        var p=candidate.proposal();
        if(candidate.candidateId()==null || candidate.candidateId().isBlank()
                || !Set.of("TARGET_TREATMENT","EXPRESSION_CONTENT").contains(p.axis()==null?"":p.axis())
                || p.evidence()==null || p.evidence().isEmpty() || p.evidence().stream().noneMatch(q -> q!=null && Objects.equals(p.anchorId(),q.segmentId()))
                || p.evidence().stream().anyMatch(q -> q==null || q.quote()==null || q.quote().isBlank()
                || candidate.raw().stream().noneMatch(s -> s.id().equals(q.segmentId()) && s.text().contains(q.quote()))))
            throw new IllegalArgumentException("AI evidence must remain in original window");
        return new AiSource(new CandidateUnionShadow.AiCandidate(candidate.candidateId(),CandidateUnionShadow.snapshotHash(input),
                p.anchorId(),p.axis(),p.evidence().stream().map(CandidateReviewEngine.Quote::segmentId).toList()),candidate);
    }

    static List<Prepared> prepareRules(ReviewInput input,CandidateUnionShadow.Plan plan,
                                      ReviewGuidelineLibrary library,int evidenceLimit) {
        if(evidenceLimit<0 || !CandidateUnionShadow.REVISION.equals(plan.revision())
                || !CandidateUnionShadow.snapshotHash(input).equals(plan.inputSha256()))
            throw new IllegalArgumentException("Plan revision/snapshot mismatch");
        // Reconstruct permitted rule candidates/windows; a caller-supplied entry cannot invent raw or signals.
        var canonical=CandidateUnionShadow.plan(input,List.of(),1000).entries();
        List<Prepared> prepared=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for(var entry:plan.entries()) {
            if(entry.candidate().origin()!=CandidateUnionShadow.Origin.RULE) continue;
            if(!seen.add(entry.candidate().sourceId())) throw new IllegalArgumentException("Duplicate rule entry");
            var expected=canonical.stream().filter(e -> e.candidate().equals(entry.candidate())).findFirst().orElseThrow(
                    () -> new IllegalArgumentException("Unknown rule candidate"));
            if(!expected.raw().equals(entry.raw()) || expected.contextLimited()!=entry.contextLimited()
                    || entry.state()==CandidateUnionShadow.State.READY && expected.state()!=CandidateUnionShadow.State.READY
                    || entry.state()==CandidateUnionShadow.State.CONTEXT_INVALID && expected.state()!=entry.state())
                throw new IllegalArgumentException("Rule context differs from canonical window");
            if(entry.state()!=CandidateUnionShadow.State.READY) continue;
            var source=entry.candidate();
            var evidence=source.evidenceIds().stream().map(id -> new CandidateReviewEngine.Quote(id,input.find(id).orElseThrow().text())).toList();
            // null is intentional: rules never pass through AI discovery's reason requirement or repair path.
            var proposal=new CandidateReviewEngine.Proposal(source.anchorId(),source.axis(),null,evidence);
            String id="rule-"+(prepared.size()+1);
            var candidate=new CandidateReviewEngine.Candidate(id,proposal,entry.raw(),
                    entry.raw().size()>source.evidenceIds().size(),entry.contextLimited());
            var focus=entry.raw().stream().filter(s -> source.evidenceIds().contains(s.id())).toList();
            var selection=library==null?null:library.select(TimelineEventType.SPEECH,entry.raw(),id,focus);
            String reference=selection==null?"":selection.prompt();
            String baseline=CandidateReviewEngine.verificationPrompt(evidenceLimit);
            if(!baseline.contains("candidates는 탐색 모델의 가설이며 사실·판정 근거가 아니다."))
                throw new IllegalStateException("Verification baseline changed; re-audit rule contract");
            String system=baseline.replace("candidates는 탐색 모델의 가설이며 사실·판정 근거가 아니다.",
                    "candidates는 규칙으로 확보한 표면 신호이며 사실·판정 근거가 아니다.")+"\n"+RULE_CONTRACT+reference;
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("candidateId",id); item.put("anchorId",source.anchorId()); item.put("axis",source.axis());
            item.put("origin","RULE"); item.put("sourceRevision",source.sourceRevision());
            item.put("surfaceSignals",source.signals()); item.put("proposedEvidence",evidence);
            item.put("segmentIds",entry.raw().stream().map(ReviewInput.Segment::id).toList());
            item.put("contextExpanded",candidate.contextExpanded()); item.put("contextLimited",entry.contextLimited());
            var raw=entry.raw().stream().map(s -> Map.of("id",s.id(),"startMs",s.startMs(),"endMs",s.endMs(),"text",s.text())).toList();
            String request=ReviewGuidelineLibrary.JSON.writeValueAsString(Map.of("promptRevision",CandidateReviewEngine.REVISION,
                    "bridgeRevision",REVISION,"raw",raw,"candidates",List.of(item)));
            prepared.add(new Prepared(REVISION,plan.inputSha256(),candidate,system,request,
                    ReviewGuidelineLibrary.retrievalHash(reference),selection==null?null:selection.trace(),evidenceLimit));
        }
        return List.copyOf(prepared);
    }

    static CandidateReviewEngine.Validation validate(Prepared request,CandidateReviewEngine.VerificationResult response,ReviewInput input) {
        if(!REVISION.equals(request.revision()) || !CandidateUnionShadow.snapshotHash(input).equals(request.inputSha256()))
            return new CandidateReviewEngine.Validation(null,"BRIDGE_SNAPSHOT_MISMATCH");
        if(response==null || response.verifications()==null || response.verifications().size()!=1)
            return new CandidateReviewEngine.Validation(null,"BRIDGE_RESPONSE_CARDINALITY");
        var verification=response.verifications().get(0);
        if(verification==null || !request.candidate().candidateId().equals(verification.candidateId()))
            return new CandidateReviewEngine.Validation(null,"BRIDGE_CANDIDATE_ID");
        // Existing output-contract enforcement, including rejecting unsolicited extra experiment fields.
        return SourceUnderstandingContract.validate(request.candidate(),verification,input,request.evidenceLimit(),
                false,false,SourceUnderstandingContract.Version.ACTS_V1);
    }
}
