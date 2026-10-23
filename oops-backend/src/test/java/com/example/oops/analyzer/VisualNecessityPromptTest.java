package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Prompt contracts, not evidence of live model accuracy or reduced call counts. */
class VisualNecessityPromptTest {
    @Test void discoveryRequiresObservableFactAndKeepsAudioOnlyGapsOutOfVisualRequests() {
        assertThat(CandidateReviewEngine.DISCOVERY_PROMPT)
                .contains("현재 판단에 필수인 화면 사실", "확인할 화면 사실 → 그 사실에 따라 달라지는 해석",
                        "텍스트로 충분한 후보는 candidates", "원음·연속 장면", "uncertainSegments")
                .doesNotContain("위험 후보 탐색과 별도로 모든 primaryIds에 대해 화면 의존성을 확인한다.");
        assertThat(CandidateReviewEngine.VERIFICATION_PROMPT).doesNotContain("현재 판단에 필수인 화면 사실");
    }
    @Test void imageReviewerSeparatesObservationFromInferenceAndDoesNotInventMissingAudio() {
        assertThat(VisualContextReviewer.PROMPT).contains("정지 샘플", "프레임 사이의 장면을 복원하지 않는다",
                "작은 글자가 안 읽히면 추측하지 않는다", "웃음소리는 이미지에서 확인할 수 없다",
                "새 쟁점은 만들지 않는다", "일부 프레임에 안 보인다고 전체 영상에서 없었다고 단정하지 않는다");
        assertThat(VisualContextReviewer.PROMPT).contains("observations", "connectionReason", "missingInformation");
    }
}
