package com.example.oops.controller;

import com.example.oops.common.Ids;
import com.example.oops.common.ApiResponse;
import com.example.oops.service.TranscriptReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Local-only paid analysis entry point; protected by LocalDiagnosticsAccess. */
@RestController
@Profile("local")
@RequiredArgsConstructor
@RequestMapping("/api/v1/videos")
public class TranscriptReplayController {
    private final TranscriptReplayService replays;
    @PostMapping(value="/{videoId}/analysis/replay-transcript", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TranscriptReplayService.Response>> replay(@PathVariable String videoId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(replays.create(Ids.parse(videoId))));
    }
}
