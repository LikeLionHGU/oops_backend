package com.example.oops.controller;

import com.example.oops.common.ApiResponse;
import com.example.oops.common.Ids;
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
                .body(ApiResponse.ok(VideoUploadResponse.of(video, job)));
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
                .body(ApiResponse.ok(VideoUploadResponse.of(video, job)));
    }

    @Operation(summary = "분석 상태 조회",
            description = """
                    진행률 폴링용. WebSocket 이 끊겼을 때의 대체 수단이기도 하다.

                    - `status`: PENDING / PROCESSING / COMPLETED / FAILED
                    - `stage`: UPLOAD / STT / TEXT_RISK / SCENE_DETECTION / OCR / MULTIMODAL / FINALIZING / COMPLETED
                    - `progress`: 0~100
                    """)
    @GetMapping("/{videoId}/status")
    public ApiResponse<VideoStatusResponse> status(@PathVariable String videoId) {
        return ApiResponse.ok(analysisService.getStatus(Ids.parse(videoId)));
    }

    @Operation(summary = "분석 결과 조회 (Timeline Report)",
            description = """
                    `events` 는 **우선순위 내림차순**이다. 위에서부터 그리면 된다.

                    `type` 을 기준으로 분기하는 유니온 타입이다.
                    - `SPEECH`: `text`, `contextBefore`, `contextAfter` 가 온다
                    - `CAPTION`: `speechText`, `captionText` 가 온다

                    `candidateType` 은 **왜** 확인하는지다 (`SPEECH_REVIEW` / `FACT_CHECK`).
                    **어디서** 나왔는지는 `type` 이 답한다. 화면 문구는 `type` 으로 정해라.

                    위험도 점수(`severity`)와 내부 분류(`riskTypes`)는 내려가지 않는다.
                    이 도구는 판정하지 않고 확인할 지점만 올린다.

                    해당 타입에 없는 필드는 JSON 에서 아예 빠진다.
                    `frameUrl` 이 있으면 그 시점의 화면 캡처를 보여줄 수 있다.

                    분석이 안 끝났으면 `ANALYSIS_NOT_COMPLETED`(409) 가 온다.
                    """)
    @GetMapping("/{videoId}/report")
    public ApiResponse<AnalysisReportResponse> report(@PathVariable String videoId) {
        return ApiResponse.ok(analysisService.getReport(Ids.parse(videoId)));
    }

    @Operation(summary = "분석 재시도",
            description = """
                    같은 영상을 다시 분석한다. `videoId` 는 유지되고 `jobId` 만 새로 발급된다.

                    이미 분석 중이면 `ANALYSIS_IN_PROGRESS`(409) 가 온다.
                    """)
    @PostMapping("/{videoId}/analysis/retry")
    public ResponseEntity<ApiResponse<AnalysisRetryResponse>> retry(@PathVariable String videoId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(analysisService.retry(Ids.parse(videoId))));
    }

    @Operation(summary = "검수 이력",
            description = """
                    업로드한 영상을 최신순으로 준다. (명세 4)

                    - `status`: `ALL` | `COMPLETED` | `FAILED` (기본 ALL)
                    - `page`: 0부터. `size`: 기본 20
                    - `analysisStatus` 와 `reviewStatus` 는 **다른 값**이다.
                      분석은 끝났어도 사람이 아직 안 봤을 수 있다.
                    """)
    @GetMapping("/history")
    public ApiResponse<VideoHistoryResponse> history(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(videoService.findHistory(status, page, size));
    }

    @Operation(summary = "분석 취소",
            description = """
                    진행 중인 분석을 취소한다. (명세 7)

                    - `PENDING` 또는 `PROCESSING` 만 취소할 수 있다.
                    - 그 밖의 상태면 `INVALID_ANALYSIS_STATE`(409).
                    - 취소한 작업은 재시도할 수 있다.
                    """)
    @PostMapping("/{videoId}/analysis/cancel")
    public ApiResponse<AnalysisRetryResponse> cancel(@PathVariable String videoId) {
        return ApiResponse.ok(analysisService.cancel(Ids.parse(videoId)));
    }

    @Operation(summary = "검수 결정 저장",
            description = """
                    후보 한 건의 결정을 저장하거나 수정한다. (명세 6)

                    - `eventId` 는 리포트의 `events[].id`.
                    - `action`: `CONFIRMED` / `EDITED` / `HOLD` / `NOT_USEFUL`
                    - 같은 요청을 반복해도 결과가 같다.
                    - 첫 결정을 저장하면 `reviewStatus` 가 `IN_REVIEW` 가 된다.

                    다른 영상의 후보를 보내면 `EVENT_NOT_FOUND`(404).
                    """)
    @PutMapping("/{videoId}/review-actions/{eventId}")
    public ApiResponse<ReviewActionResponse> saveReviewAction(
            @PathVariable String videoId,
            @PathVariable String eventId,
            @Valid @RequestBody ReviewActionRequest request) {

        return ApiResponse.ok(
                reviewActionService.save(Ids.parse(videoId), eventId, request));
    }

    @Operation(summary = "검수 완료",
            description = """
                    모든 후보를 결정한 뒤 검수를 마친다. (명세 6)

                    결정하지 않은 후보가 남아 있으면 `REVIEW_INCOMPLETE`(409) 가 온다.
                    "다 봤다" 는 기록은 실제로 다 봤을 때만 남아야 하기 때문이다.
                    """)
    @PostMapping("/{videoId}/review-completion")
    public ApiResponse<ReviewCompletionResponse> completeReview(@PathVariable String videoId) {
        return ApiResponse.ok(reviewActionService.complete(Ids.parse(videoId)));
    }

    @Operation(summary = "검수 결정 목록",
            description = "저장된 결정 내역. 리포트의 events[].reviewAction 으로도 확인할 수 있다.")
    @GetMapping("/{videoId}/review-actions")
    public ApiResponse<List<ReviewActionResponse>> reviewActions(@PathVariable String videoId) {
        return ApiResponse.ok(reviewActionService.findByVideo(Ids.parse(videoId)));
    }

    @Operation(summary = "영상 삭제",
            description = """
                    영상과 분석 결과, 디스크에 저장된 원본·프레임 이미지를 모두 지운다.
                    되돌릴 수 없다.

                    분석이 진행 중이면 `ANALYSIS_IN_PROGRESS`(409) 가 온다.
                    백그라운드 작업이 사라진 데이터를 건드리는 것을 막기 위해서다.
                    """)
    @DeleteMapping("/{videoId}")
    public ApiResponse<Void> delete(@PathVariable String videoId) {
        videoDeletionService.delete(Ids.parse(videoId));
        return ApiResponse.ok();
    }

    @Operation(summary = "[디버깅] STT 대본 원문",
            description = "음성 인식 결과 전체. 분석이 왜 그렇게 나왔는지 확인할 때 쓴다.")
    @GetMapping("/{videoId}/transcript")
    public ApiResponse<List<TranscriptLineDto>> transcript(@PathVariable String videoId) {
        return ApiResponse.ok(analysisService.getTranscript(Ids.parse(videoId)));
    }

    @Operation(summary = "[디버깅] OCR 화면 자막 원문",
            description = "화면에서 읽어낸 텍스트 전체. OCR 이 글자를 어떻게 인식했는지 볼 수 있다.")
    @GetMapping("/{videoId}/screen-texts")
    public ApiResponse<List<TranscriptLineDto>> screenTexts(@PathVariable String videoId) {
        return ApiResponse.ok(analysisService.getScreenTexts(Ids.parse(videoId)));
    }
}
