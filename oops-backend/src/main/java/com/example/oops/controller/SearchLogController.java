package com.example.oops.controller;

import com.example.oops.common.Ids;
import com.example.oops.service.SearchLogService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 영상별 검색 기록 조회. 프롬프트·검색 튜닝용 확인 도구다. (2026-10 고도화) */
@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class SearchLogController {

    private final SearchLogService searchLogService;

    @Operation(summary = "검색 기록 조회 (튜닝용)",
            description = """
                    사실 확인·배경 확인이 이 영상을 분석하면서 검색한 내용을 한 번에 돌려준다.
                    주장·주제마다: 영상의 줄, 검색어, 검색 결과 전체(제목·링크·날짜·본문 일부),
                    AI 에게 넘긴 자료, AI 판정(verdict·score·reason), 최종 처리(outcome).

                    분석이 끝날 때 저장되며 14일(oops.search-log.retention-days) 뒤 자동 삭제된다.
                    """)
    @GetMapping(value = "/{videoId}/search-log", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> searchLog(@PathVariable String videoId) {
        long id = Ids.parse(videoId);
        return searchLogService.read(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"이 영상의 검색 기록이 없습니다. 기록 기능이 켜진 뒤 분석했는지, 14일이 지나 지워졌는지 확인하세요.\"}"));
    }
}
