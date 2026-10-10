package com.example.oops.controller;

import com.example.oops.common.*;
import com.example.oops.repository.VideoRepository;
import com.example.oops.service.ReviewRequestTraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "oops.analysis", name = "review-request-trace-enabled", havingValue = "true")
public class ReviewRequestTraceController {
    private final ReviewRequestTraceStore store;
    private final VideoRepository videos;
    private final String token;
    public ReviewRequestTraceController(ReviewRequestTraceStore store, VideoRepository videos,
            @Value("${oops.analysis.review-request-trace-token:}") String token) {
        this.store = store; this.videos = videos; this.token = token;
    }
    public record Response(boolean available, ReviewRequestTraceStore.Snapshot snapshot) {}
    @GetMapping("/api/v1/videos/{videoId}/analysis/request-traces")
    public ApiResponse<Response> trace(@PathVariable String videoId,
            @RequestHeader(value = "X-Review-Trace-Token", required = false) String supplied) {
        if (token.length() < 32 || supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Long id = Ids.parse(videoId);
        if (!videos.existsById(id)) { store.remove(id); throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND); }
        var snapshot = store.find(id).orElse(null);
        return ApiResponse.ok(new Response(snapshot != null, snapshot));
    }
}
