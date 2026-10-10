package com.example.oops.analyzer;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisualContextReviewerTest {
    @Test void candidatePipelinePassesSameReferenceToActualImageRequest() {
        when(ai.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenReturn(Optional.of(
                new Discovery(List.of("stt-index-0"), List.of(), List.of(), false,
                        List.of(fixture.proposal("stt-index-0", "그 집")))));
        frames(); response("PASS", "scene-0");
        var references = new ReviewGuidelineLibraryTest();
        CandidateReviewEngine.run(ai, context(), 24, reviewer, null, references.library(references.enrichedArchive()));
        var system = ArgumentCaptor.forClass(String.class);
        verify(ai).completeWithImagesAsJson(system.capture(), anyString(), anyList(), any());
        assertThat(system.getValue()).contains("reviewGuidelines", "생활을 하대한다는 비판 가설")
                .doesNotContain("case-1", "family-1");
    }
    @Test void selectsQuotedContextFramesInsteadOfOnlyAnchorNeighboursWithoutIncreasingImageBudget() {
        var c = new AnalysisContext(video, ContentGenre.GENERAL, List.of(
                new TranscriptSegment(video, 1000, 2000, "이곳에 도착했어"),
                new TranscriptSegment(video, 20000, 21000, "여기는 이런 곳이네"),
                new TranscriptSegment(video, 40000, 41000, "아까 말한 곳의 음식이야")), List.of());
        var raw = c.reviewInput().segments();
        var proposal = new Proposal(raw.get(1).id(), "TARGET_TREATMENT", "장소와 후속 음식 평가의 화면 연결 확인",
                raw.stream().map(s -> new Quote(s.id(), s.text())).toList());
        assertThat(VisualContextReviewer.frameTimes(new Candidate("c", proposal, raw, false, false), raw.get(1), 90000))
                .containsExactly(1500L, 20500L, 40500L);
        var anchorOnly = new Proposal(raw.get(1).id(), proposal.axis(), proposal.reason(), List.of(proposal.evidence().get(1)));
        assertThat(VisualContextReviewer.frameTimes(new Candidate("c", anchorOnly, raw, false, false), raw.get(1), 90000))
                .containsExactly(18500L, 20500L, 22500L);
    }
    final CandidateReviewEngineTest fixture = new CandidateReviewEngineTest();
    final OpenAiClient ai = fixture.client;
    final AnalysisServerClient media = mock(AnalysisServerClient.class);
    final VisualContextReviewer reviewer = new VisualContextReviewer(media, ai, true, 3);
    final Video video = Video.builder().sourceType(SourceType.UPLOAD).storageKey("videos/1/original.mp4")
            .filename("generic.mp4").durationSec(90).build();
    AnalysisContext context() {
        return new AnalysisContext(video, ContentGenre.GENERAL,
                List.of(new TranscriptSegment(video, 0, 1500, "그 집 이용자는 수준이 낮아")), List.of());
    }
    Candidate candidate() {
        return new Candidate("candidate-1", fixture.proposal("stt-index-0", "그 집"), context().reviewInput().segments(), false, false);
    }
    void frames() {
        when(media.sceneFrames(eq(video), anyList())).thenReturn(Optional.of(new AnalysisServerClient.SceneFrames(List.of(
                new AnalysisServerClient.SceneFrames.Frame("scene-0", 0, 0, "/9j/2Q=="),
                new AnalysisServerClient.SceneFrames.Frame("scene-750", 750, 760, "/9j/2Q=="),
                new AnalysisServerClient.SceneFrames.Frame("scene-2750", 2750, 2760, "/9j/2Q==")))));
    }
    void response(String decision, String frameId) {
        when(ai.completeWithImagesAsJson(anyString(), anyString(), anyList(), eq(VisualContextReviewer.Response.class)))
                .thenReturn(Optional.of(new VisualContextReviewer.Response("candidate-1",
                        fixture.assessment("stt-index-0", decision, "그 집"),
                        List.of(new VisualContextReviewer.Observation(frameId, "화면에 상점 건물과 도로가 보입니다.")),
                        "주변 건물과 발언의 대상 연결을 확인하되 장소의 가치나 화자 의도를 화면만으로 확정하지 않습니다.")));
    }
    @Test void sendsActualImageInputsWithBoundedTimesAndSeparatesObservationsFromAssessment() {
        frames(); response("REVIEW_REQUIRED", "scene-750");
        var r = reviewer.review(context(), candidate());
        assertThat(r.validation().failureCode()).isNull();
        assertThat(r.validation().observation().decision()).isEqualTo(ReviewEvaluation.Decision.REVIEW_REQUIRED);
        assertThat(r.trace().frames()).hasSize(3);
        assertThat(r.trace().observations()).hasSize(1);
        ArgumentCaptor<List<OpenAiClient.ImageInput>> images = ArgumentCaptor.forClass(List.class);
        verify(ai).completeWithImagesAsJson(eq(VisualContextReviewer.PROMPT), anyString(), images.capture(), eq(VisualContextReviewer.Response.class));
        assertThat(images.getValue()).hasSize(3).extracting(OpenAiClient.ImageInput::timestampMs).containsExactly(0L, 760L, 2760L);
        assertThat(r.trace().toString()).doesNotContain("/9j/2Q==", "videos/1");
    }
    @Test void normalInterpretationCanPassWithoutPublishingWarning() {
        frames(); response("PASS", "scene-0");
        assertThat(reviewer.review(context(), candidate()).validation().observation().decision()).isEqualTo(ReviewEvaluation.Decision.PASS);
    }
    @Test void missingImagesDoNotBecomePassOrTriggerModelCall() {
        when(media.sceneFrames(any(), anyList())).thenReturn(Optional.empty());
        var r = reviewer.review(context(), candidate());
        assertThat(r.validation().failureCode()).isEqualTo("FRAME_EXTRACTION_FAILED");
        assertThat(r.validation().observation()).isNull(); assertThat(r.modelCalled()).isFalse();
        verifyNoInteractions(ai);
    }
    @Test void disabledAndYoutubeRemainExplicitlyUnassessed() {
        assertThat(new VisualContextReviewer(media, ai, false, 3).review(context(), candidate()).validation().failureCode())
                .isEqualTo("VISUAL_DISABLED");
        var youtube = new AnalysisContext(Video.builder().sourceType(SourceType.YOUTUBE).durationSec(90).build(),
                ContentGenre.GENERAL, context().transcript(), List.of());
        assertThat(reviewer.review(youtube, candidate()).validation().failureCode()).isEqualTo("VISUAL_UPLOAD_ONLY");
        verifyNoInteractions(media, ai);
    }
    @Test void unknownFrameEvidenceIsRejectedNotPublished() {
        frames(); response("REVIEW_REQUIRED", "invented-frame");
        assertThat(reviewer.review(context(), candidate()).validation().failureCode()).isEqualTo("VISUAL_EVIDENCE_ID_OR_DESCRIPTION");
    }
    @Test void wrongTimestampMetadataStopsBeforePaidCall() {
        when(media.sceneFrames(any(), anyList())).thenReturn(Optional.of(new AnalysisServerClient.SceneFrames(List.of(
                new AnalysisServerClient.SceneFrames.Frame("scene-0", 0, 8000, "x"),
                new AnalysisServerClient.SceneFrames.Frame("scene-750", 750, 750, "x"),
                new AnalysisServerClient.SceneFrames.Frame("scene-2750", 2750, 2750, "x")))));
        assertThat(reviewer.review(context(), candidate()).validation().failureCode()).isEqualTo("INVALID_FRAME_METADATA");
        verifyNoInteractions(ai);
    }
    @Test void visualDiscoveryRunsWithoutTextRiskCandidateAndProducesAuditableFinding() {
        when(ai.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenReturn(Optional.of(
                new Discovery(List.of("stt-index-0"), List.of(), List.of(), false, List.of(fixture.proposal("stt-index-0", "그 집")))));
        frames(); response("REVIEW_REQUIRED", "scene-0");
        var r = CandidateReviewEngine.run(ai, context(), 24, reviewer);
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).getReason()).contains("선택적 장면 검증", "상점 건물");
        assertThat(r.diagnostics().candidatePipeline().visualCandidates()).hasSize(1);
        verify(ai, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void visualBudgetExclusionIsPartialAndMakesNoMoreThanConfiguredCalls() {
        var p1 = fixture.proposal("stt-index-0", "그 집");
        var p2 = new Proposal(p1.anchorId(), p1.axis(), "다른 화면 연결에 필요한 정보 확인", p1.evidence());
        when(ai.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenReturn(Optional.of(
                new Discovery(List.of("stt-index-0"), List.of(), List.of(), false, List.of(p1, p2))));
        frames(); response("PASS", "scene-0");
        var r = CandidateReviewEngine.run(ai, context(), 24, new VisualContextReviewer(media, ai, true, 1));
        assertThat(r.status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(r.diagnostics().candidatePipeline().budgetSkipped()).isOne();
        verify(ai, times(1)).completeWithImagesAsJson(anyString(), anyString(), anyList(), any());
    }
    @Test void anchorEndingExactlyAtVideoBoundaryStillAttemptsBoundedExtraction() {
        var shortVideo = Video.builder().sourceType(SourceType.UPLOAD).storageKey("videos/1/original.mp4").durationSec(2).build();
        var c = new AnalysisContext(shortVideo, ContentGenre.GENERAL,
                List.of(new TranscriptSegment(shortVideo, 0, 2000, "그 집 이용자는 수준이 낮아")), List.of());
        when(media.sceneFrames(eq(shortVideo), anyList())).thenReturn(Optional.empty());
        var candidate = new Candidate("candidate-1", fixture.proposal("stt-index-0", "그 집"), c.reviewInput().segments(), false, false);
        assertThat(reviewer.review(c, candidate).validation().failureCode()).isEqualTo("FRAME_EXTRACTION_FAILED");
        verify(media).sceneFrames(shortVideo, List.of(0L, 1000L, 1999L));
    }
}
