package com.example.oops.screentext;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.domain.ScreenText;
import com.example.oops.domain.Video;
import com.example.oops.domain.VideoFrame;
import com.example.oops.repository.ScreenTextRepository;
import com.example.oops.repository.VideoFrameRepository;
import com.example.oops.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 화면 자막 OCR 결과와 그때 사용된 프레임 이미지를 저장한다.
 * 실패해도 빈 리스트를 돌려주고 분석은 계속된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenTextService {

    private final AnalysisServerClient analysisServerClient;
    private final ScreenTextRepository screenTextRepository;
    private final VideoFrameRepository videoFrameRepository;
    private final StorageService storageService;

    @Transactional
    public List<ScreenText> extractAndSave(Video video) {
        screenTextRepository.deleteByVideoId(video.getId());
        videoFrameRepository.deleteByVideoId(video.getId());
        storageService.deleteFrames(video.getId());

        var response = analysisServerClient.ocr(video).orElse(null);
        if (response == null || response.items() == null || response.items().isEmpty()) {
            log.info("[ocr] 화면 자막 없음 videoId={}", video.getId());
            return List.of();
        }

        List<ScreenText> texts = new ArrayList<>();
        for (var item : response.items()) {
            if (item.text() == null || item.text().isBlank()) continue;

            VideoFrame frame = saveFrame(video, item.startMs(), item.framePath());
            texts.add(new ScreenText(video, item.startMs(), item.endMs(),
                    item.text().trim(), item.confidence(), frame));
        }

        log.info("[ocr] videoId={} 화면 자막 {}건", video.getId(), texts.size());
        return screenTextRepository.saveAll(texts);
    }

    /** 분석 서버가 보관해 준 프레임 파일을 VideoFrame 으로 등록한다. */
    private VideoFrame saveFrame(Video video, long timeMs, String framePath) {
        if (framePath == null || framePath.isBlank()) {
            return null;
        }
        try {
            Path absolute = Paths.get(framePath);
            String storageKey = storageService.toStorageKey(absolute);
            return videoFrameRepository.save(new VideoFrame(video, timeMs, storageKey, "image/jpeg"));
        } catch (Exception e) {
            log.warn("[ocr] 프레임 등록 실패 path={} : {}", framePath, e.getMessage());
            return null;
        }
    }
}
