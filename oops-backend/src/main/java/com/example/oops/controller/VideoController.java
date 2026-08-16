package com.example.oops.controller;

import com.example.oops.common.ApiResponse;
import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.Video;
import com.example.oops.dto.*;
import com.example.oops.service.AnalysisService;
import com.example.oops.service.ReviewActionService;
import com.example.oops.service.VideoDeletionService;
import com.example.oops.service.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "1. 영상 · 분석", description = "영상 등록과 분석 결과 조회")
@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;
    private final AnalysisService analysisService;
    private final VideoDeletionService videoDeletionService;
    private final ReviewActionService reviewActionService;

    @Operation(summary = "영상 업로드",
            description = """
                    영상 파일을 올리면 **즉시 분석이 시작된다**. 별도의 분석 시작 API 는 없다.

                    - 지원 형식: mp4, mov, avi
                    - 최대 크기: 500MB
                    - `genre` (선택): 영상 유형을 지정하면 그에 맞는 분석기가 돌아간다.
                      `TALK_PODCAST`, `GENERAL`
                      비워두면 대본을 보고 자동으로 판별한다.
                    - 응답의 `jobId` 는 이번 분석 실행의 식별자다. 재시도하면 새로 발급된다.
                    - `streamUrl` 로 영상을 재생할 수 있다.

                    실패 코드: `UNSUPPORTED_VIDEO_FORMAT`(415), `MAX_UPLOAD_SIZE_EXCEEDED`(413),
                    `WORKER_UNAVAILABLE`(503, 분석 서버가 꺼져 있음)
                    """)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<VideoUploadResponse>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "genre", required = false) String genre) {

        Video video = videoService.createFromUpload(file, genre);
        AnalysisJob job = analysisService.startAnalysis(video.getId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("영상 업로드 성공", VideoUploadResponse.of(video, job)));
    }

    @Operation(summary = "유튜브 링크로 등록",
            description = """
                    파일 대신 링크로 등록한다. 응답 형태는 업로드와 같다.

                    주의: 로컬에 영상 파일이 없으므로 `GET /stream` 은 동작하지 않는다.
                    재생이 필요하면 프론트에서 유튜브 임베드를 쓴다.
                    """)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<VideoUploadResponse>> registerByUrl(
            @Valid @RequestBody VideoRegisterRequest request) {

        Video video = videoService.createFromUrl(request);
        AnalysisJob job = analysisService.startAnalysis(video.getId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("영상 등록 성공", VideoUploadResponse.of(video, job)));
    }

    @Operation(summary = "분석 상태 조회",
            description = """
                    진행률 폴링용. WebSocket 이 끊겼을 때의 대체 수단이기도 하다.

                    - `status`: PENDING / PROCESSING / COMPLETED / FAILED
                    - `stage`: UPLOAD / STT / TEXT_RISK / SCENE_DETECTION / OCR / MULTIMODAL / FINALIZING / COMPLETED
                    - `progress`: 0~100
                    """)
    @GetMapping("/{videoId}/status")
    public ApiResponse<VideoStatusResponse> status(@PathVariable Long videoId) {
        return ApiResponse.ok("분석 상태 조회 성공", analysisService.getStatus(videoId));
    }

    @Operation(summary = "분석 결과 조회 (Timeline Report)",
            description = """
                    `events` 는 **우선순위 내림차순**이다. 위에서부터 그리면 된다.

                    `type` 을 기준으로 분기하는 유니온 타입이다.
                    - `SPEECH`: `text`, `riskTypes` 가 온다
                    - `CAPTION`: `speechText`, `captionText` 가 온다

                    해당 타입에 없는 필드는 JSON 에서 아예 빠진다.
                    `frameUrl` 이 있으면 그 시점의 화면 캡처를 보여줄 수 있다.

                    분석이 안 끝났으면 `ANALYSIS_NOT_COMPLETED`(409) 가 온다.
                    """)
    @GetMapping("/{videoId}/report")
    public ApiResponse<AnalysisReportResponse> report(@PathVariable Long videoId) {
        return ApiResponse.ok("분석 결과 조회 성공", analysisService.getReport(videoId));
    }

    @Operation(summary = "분석 재시도",
            description = """
                    같은 영상을 다시 분석한다. `videoId` 는 유지되고 `jobId` 만 새로 발급된다.

                    이미 분석 중이면 `ANALYSIS_IN_PROGRESS`(409) 가 온다.
                    """)
    @PostMapping("/{videoId}/analysis/retry")
    public ResponseEntity<ApiResponse<AnalysisRetryResponse>> retry(@PathVariable Long videoId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok("분석 재시작 요청이 접수되었습니다.", analysisService.retry(videoId)));
    }

    @Operation(summary = "검수 이력",
            description = """
                    업로드한 영상을 최신순으로 100건 준다. (명세 3-2)

                    - `uploadedAt` 은 ISO-8601 **UTC** 문자열이다.
                    - `progress` 는 현재 Job 의 진행률(0~100).
                    - `eventCount` 는 리포트의 `events.length` 와 같은 기준.
                    - `streamUrl` 은 원본이 서버에 없으면 `null`.
                    """)
    @GetMapping
    public ApiResponse<List<VideoSummaryResponse>> list() {
        return ApiResponse.ok("영상 목록 조회 성공", videoService.findRecent());
    }

    @Operation(summary = "검수 액션 저장",
            description = """
                    제작자가 검토 후보를 어떻게 처리했는지 저장한다. (명세 9-2)

                    - `eventId` 는 리포트의 `events[].id`.
                    - `action`: `CONFIRMED` / `EDITED` / `HOLD` / `NOT_USEFUL`
                    - 같은 후보를 다시 보내면 마지막 값으로 덮는다.

                    다른 영상의 후보를 보내면 `EVENT_NOT_FOUND`(404) 가 온다.
                    """)
    @PostMapping("/{videoId}/review-actions")
    public ApiResponse<ReviewActionResponse> saveReviewAction(
            @PathVariable Long videoId,
            @Valid @RequestBody ReviewActionRequest request) {

        return ApiResponse.ok("검수 액션 저장 성공",
                reviewActionService.save(videoId, request));
    }

    @Operation(summary = "검수 액션 목록",
            description = """
                    저장된 처리 내역. 새로고침 후 화면을 복구할 때 쓴다.

                    명세에는 없지만, 저장만 하고 못 읽으면 저장하는 의미가 없어서 추가했다.
                    """)
    @GetMapping("/{videoId}/review-actions")
    public ApiResponse<List<ReviewActionResponse>> reviewActions(@PathVariable Long videoId) {
        return ApiResponse.ok("검수 액션 조회 성공", reviewActionService.findByVideo(videoId));
    }

    @Operation(summary = "영상 삭제",
            description = """
                    영상과 분석 결과, 디스크에 저장된 원본·프레임 이미지를 모두 지운다.
                    되돌릴 수 없다.

                    분석이 진행 중이면 `ANALYSIS_IN_PROGRESS`(409) 가 온다.
                    백그라운드 작업이 사라진 데이터를 건드리는 것을 막기 위해서다.
                    """)
    @DeleteMapping("/{videoId}")
    public ApiResponse<Void> delete(@PathVariable Long videoId) {
        videoDeletionService.delete(videoId);
        return ApiResponse.ok("영상 삭제 성공");
    }

    @Operation(summary = "[디버깅] STT 대본 원문",
            description = "음성 인식 결과 전체. 분석이 왜 그렇게 나왔는지 확인할 때 쓴다.")
    @GetMapping("/{videoId}/transcript")
    public ApiResponse<List<TranscriptLineDto>> transcript(@PathVariable Long videoId) {
        return ApiResponse.ok("대본 조회 성공", analysisService.getTranscript(videoId));
    }

    @Operation(summary = "[디버깅] OCR 화면 자막 원문",
            description = "화면에서 읽어낸 텍스트 전체. OCR 이 글자를 어떻게 인식했는지 볼 수 있다.")
    @GetMapping("/{videoId}/screen-texts")
    public ApiResponse<List<TranscriptLineDto>> screenTexts(@PathVariable Long videoId) {
        return ApiResponse.ok("화면 자막 조회 성공", analysisService.getScreenTexts(videoId));
    }
}
