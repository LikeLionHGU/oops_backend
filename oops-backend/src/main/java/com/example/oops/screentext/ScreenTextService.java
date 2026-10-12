package com.example.oops.screentext;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.domain.ScreenText;
import com.example.oops.domain.OcrRegion;
import com.example.oops.domain.Video;
import com.example.oops.domain.VideoFrame;
import com.example.oops.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 화면 자막과 프레임 참조를 추출한다. DB 저장은 AnalysisResultWriter에서 수행한다.
 * 실패해도 빈 리스트를 돌려주고 분석은 계속된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenTextService {

    private final AnalysisServerClient analysisServerClient;
    private final StorageService storageService;

    public void releaseFrames(Video video, Long jobId) {
        storageService.releaseJobFrames(video.getId(), jobId);
    }

    /** Never replace committed frames during external computation. */
    public List<ScreenText> extract(Video video, Long jobId) {
        String directory = storageService.frameDir(video.getId()).resolve("jobs/" + jobId).toString();
        var response = analysisServerClient.ocr(video, directory).orElse(null);
        if (response != null) storageService.markOcrComplete(video.getId(), jobId);
        if (response == null || response.items() == null || response.items().isEmpty()) {
            log.info("[ocr] 화면 자막 없음 videoId={}", video.getId());
            return List.of();
        }

        List<ScreenText> texts = new ArrayList<>();
        java.util.Map<String, VideoFrame> frames = new java.util.HashMap<>();
        for (var item : response.items()) {
            if (item.text() == null || item.text().isBlank()) continue;

            String frameKey = item.startMs() + "|" + item.framePath();
            VideoFrame frame = frames.computeIfAbsent(frameKey, ignored -> saveFrame(video, item.startMs(), item.framePath()));
            ScreenText text = new ScreenText(video, item.startMs(), item.endMs(), item.text(), item.confidence(), frame);
            text.attachRegion(new OcrRegion(item.boxX(), item.boxY(), item.boxWidth(), item.boxHeight(),
                    item.trackId(), item.observations(), item.slotTextChanges()));
            texts.add(text);
        }

        log.info("[ocr] videoId={} 화면 자막 {}건", video.getId(), texts.size());
        return texts;
    }

    /** 분석 서버가 보관해 준 프레임 파일의 미저장 참조를 만든다. */
    private VideoFrame saveFrame(Video video, long timeMs, String framePath) {
        if (framePath == null || framePath.isBlank()) {
            return null;
        }
        try {
            Path absolute = Paths.get(framePath);
            String storageKey = storageService.toStorageKey(absolute);
            return new VideoFrame(video, timeMs, storageKey, "image/jpeg");
        } catch (Exception e) {
            log.warn("[ocr] 프레임 등록 실패 path={} : {}", framePath, e.getMessage());
            return null;
        }
    }
}
