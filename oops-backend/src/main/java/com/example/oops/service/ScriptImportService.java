package com.example.oops.service;

import com.example.oops.common.*;
import com.example.oops.domain.*;
import com.example.oops.repository.*;
import com.example.oops.transcript.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.*;

/** Local development text intake. Invoking create starts paid text analysis after commit. */
@Service
@RequiredArgsConstructor
public class ScriptImportService {
    private final VideoRepository videos;
    private final TranscriptSegmentRepository transcripts;
    private final AnalysisAdmissionService admission;
    public record Request(String sourceUrl, String title, String timePrecision,
                          List<TranscriptProvider.TranscriptLine> segments) {}
    public record Response(String videoId, String jobId, int transcriptSegments, String transcriptSha256,
                           String inputKind, String timePrecision) {}

    @Transactional
    public Response create(Request request) {
        validate(request);
        var lines = List.copyOf(request.segments());
        String sha = TranscriptSnapshot.sha256(lines);
        // Last timestamp is a lower bound, not an independently measured media duration.
        var video = Video.builder().sourceType(SourceType.YOUTUBE).sourceUrl(request.sourceUrl())
                .title(request.title()).genre(ContentGenre.TALK_PODCAST).build();
        video.assignImportedTranscriptSnapshot(sha);
        video = videos.save(video);
        var target = video;
        transcripts.saveAll(lines.stream().map(s -> new TranscriptSegment(target,s.startMs(),s.endMs(),s.text())).toList());
        var job = admission.admit(video.getId(), false);
        return new Response(video.getId().toString(), job.getJobKey(), lines.size(), sha,
                "USER_SUPPLIED_SCRIPT_NOT_STT_VERIFIED", request.timePrecision());
    }

    static void validate(Request r) {
        boolean valid = r != null && r.title() != null && !r.title().isBlank() && r.title().length() <= 300
                && "NEXT_CUE_BOUNDARIES_LAST_POINT".equals(r.timePrecision())
                && r.segments() != null && !r.segments().isEmpty() && r.segments().size() <= 2000;
        if (!valid) throw new BusinessException(ErrorCode.INVALID_REQUEST, "스크립트 입력 형식을 확인하세요.");
        try {
            var uri = URI.create(r.sourceUrl());
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || !Set.of("www.youtube.com", "youtube.com").contains(uri.getHost())
                    || !"/watch".equals(uri.getPath()) || r.sourceUrl().length() > 500) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new BusinessException(ErrorCode.INVALID_REQUEST, "유튜브 출처 URL이 필요합니다."); }
        long end = -1; int chars = 0;
        for (var s : r.segments()) {
            if (s == null || s.startMs() < 0 || s.startMs() < end || s.endMs() < s.startMs()
                    || s.endMs() > 24 * 60 * 60 * 1000L || s.text() == null || s.text().isBlank() || s.text().length() > 2000)
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "원문·시간 구간을 확인하세요.");
            end = s.endMs(); chars += s.text().length();
        }
        if (chars > 200000) throw new BusinessException(ErrorCode.INVALID_REQUEST, "스크립트가 너무 큽니다.");
    }
}
