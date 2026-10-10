package com.example.oops.service;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.common.BusinessException;
import com.example.oops.domain.SourceType;
import com.example.oops.domain.Video;
import com.example.oops.dto.VideoRegisterRequest;
import com.example.oops.repository.*;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScriptRegistrationTest {

    private VideoService service;
    private AnalysisServerClient analysisServer;

    @BeforeEach
    void setUp() {
        VideoRepository videos = mock(VideoRepository.class);
        when(videos.save(any(Video.class))).thenAnswer(inv -> inv.getArgument(0));
        analysisServer = mock(AnalysisServerClient.class);
        service = new VideoService(videos, mock(AnalysisJobRepository.class), mock(RiskFindingRepository.class),
                mock(ReviewActionRepository.class), mock(StorageService.class), analysisServer);
    }

    @Test
    @DisplayName("링크 칸에 붙여넣은 값이 링크인지 스크립트인지 가린다")
    void urlOrScript() {
        assertThat(VideoService.looksLikeUrl("https://www.youtube.com/watch?v=abc")).isTrue();
        assertThat(VideoService.looksLikeUrl("youtu.be/abc")).isTrue();
        assertThat(VideoService.looksLikeUrl("0:00 아 아니 여기 중국 아니에요?")).isFalse();
        assertThat(VideoService.looksLikeUrl("0:00\n안녕")).isFalse();
    }

    @Test
    @DisplayName("txt 파일(메모장 ANSI=CP949)로 등록하면 스크립트 영상이 되고 분석 서버를 찾지 않는다")
    void registersFromTxtFile() {
        byte[] cp949 = "0:00\n아 아니 여기 중국 아니에요?\n0:03\n롯데리아 없나?\n".getBytes(Charset.forName("MS949"));
        Video v = service.createFromScriptFile(new MockMultipartFile("script", "5-script.txt", "text/plain", cp949),
                "https://www.youtube.com/watch?v=abcdefghijk", null, null);

        assertThat(v.hasScript()).isTrue();
        assertThat(v.getScriptText()).contains("롯데리아 없나?");
        assertThat(v.getSourceType()).isEqualTo(SourceType.YOUTUBE);
        assertThat(v.getSourceUrl()).isEqualTo("https://www.youtube.com/watch?v=abcdefghijk");
        assertThat(v.getTitle()).isEqualTo("5-script");
        assertThat(v.getDurationSec()).isPositive();
        verifyNoInteractions(analysisServer);
    }

    @Test
    @DisplayName("JSON: url 칸에 스크립트를 넣어도 받고, 링크만 보내면 거절한다")
    void jsonCompatAndRejectLinkOnly() {
        Video v = service.createFromUrl(new VideoRegisterRequest("0:00 첫 줄\n0:03 둘째 줄", null, null, null, null));
        assertThat(v.hasScript()).isTrue();
        assertThat(v.getSourceUrl()).isNull();

        assertThatThrownBy(() -> service.createFromUrl(
                new VideoRegisterRequest("https://www.youtube.com/watch?v=abc", null, null, null, null)))
                .isInstanceOf(BusinessException.class);
    }
}
