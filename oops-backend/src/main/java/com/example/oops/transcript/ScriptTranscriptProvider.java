package com.example.oops.transcript;

import com.example.oops.domain.Video;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 붙여넣은 유튜브 스크립트를 대본으로 쓴다.
 *
 * @Order(0) 이라 Whisper(1) 보다 먼저 선택된다. 스크립트가 있는 영상은 음성 인식을 하지 않는다.
 * 유튜브 링크를 함께 넣었더라도 내려받지 않는다 (링크는 리포트의 영상 임베드에만 쓴다).
 */
@Slf4j
@Order(0)
@Component
public class ScriptTranscriptProvider implements TranscriptProvider {

    @Override
    public boolean supports(Video video) {
        return video.hasScript();
    }

    @Override
    public List<TranscriptLine> fetch(Video video) {
        ScriptParser.Result result = ScriptParser.parse(video.getScriptText());
        if (result.estimated()) {
            log.info("[script] videoId={} 시각 정보가 없어 글자 수로 추정했습니다 ({}줄)",
                    video.getId(), result.lines().size());
        } else {
            log.info("[script] videoId={} 스크립트 {}줄", video.getId(), result.lines().size());
        }
        video.updateMetadata(null, null, result.durationSec());
        return result.lines();
    }
}
