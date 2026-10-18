package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Prompt/wiring checks only. Mock decisions do not measure model understanding. */
class CandidateIssueScopeTest {
    @Test void preservesIssueScopeAcrossDefaultAndOptionalVerificationComposition() {
        for (String system : List.of(verificationPrompt(0), verificationPrompt(8),
                SourceRelationPrompts.verification(0),
                SituationFirstVerification.prompt(verificationPrompt(0)),
                FinalReviewBasisContract.prompt(verificationPrompt(0)))) {
            assertThat(system).contains("허용 raw 전체에서 anchor의 실제 발언·행위를 확인한다",
                    "주변의 별도 쟁점만으로 현재 후보를 경고하지 않는다",
                    "자기 설명·자기 제안을 상대 요구로 바꾸지 않는다",
                    "뜻풀이·인용·자기 제안도 자동 PASS는 아니다",
                    "새 후보·다른 anchor는 만들지 않는다");
        }
        assertThat(VERIFICATION_PROMPT).contains("원문으로", "시간 인접", "전체 연결");
    }

    @Test void rawContextAndCandidateHypothesisRemainIntactWithoutExtraCalls() {
        var f = new CandidateReviewEngineTest();
        var context = f.context("그 문구가 무슨 뜻인가요?", "개인 정보를 묻는 문장입니다.", "다른 이야기로 넘어갈게요.");
        var proposal = new Proposal("stt-index-1", "TARGET_TREATMENT", "검증할 가설",
                List.of(new Quote("stt-index-1", "개인 정보를 묻는 문장입니다."),
                        new Quote("stt-index-2", "다른 이야기로 넘어갈게요.")));
        f.discovery(List.of("stt-index-0", "stt-index-1", "stt-index-2"), proposal);
        f.verification(new Verification("candidate-1", f.linkedPass("stt-index-1", "개인 정보를 묻는 문장입니다.",
                "stt-index-0", "그 문구가 무슨 뜻인가요?")));
        var result = run(f.client, context, 24);
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client).completeAsJson(eq(VERIFICATION_PROMPT), input.capture(), eq(VerificationResult.class));
        assertThat(input.getValue()).contains("그 문구가 무슨 뜻인가요?", "다른 이야기로 넘어갈게요.",
                "검증할 가설", "hypothesisNotEvidence", "proposedEvidence");
        assertThat(result.findings()).isEmpty();
        verify(f.client, times(2)).completeAsJson(anyString(), anyString(), any());
    }
}
