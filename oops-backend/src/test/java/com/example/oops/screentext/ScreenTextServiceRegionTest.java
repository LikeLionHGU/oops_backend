package com.example.oops.screentext;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.domain.*;
import com.example.oops.repository.*;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ScreenTextServiceRegionTest {
    @Test
    void persistsSeparateRegionsRawWhitespaceAndMetadataAndReusesFrame() {
        var client = mock(AnalysisServerClient.class);
        var repo = mock(ScreenTextRepository.class);
        var frames = mock(VideoFrameRepository.class);
        var storage = mock(StorageService.class);
        var service = new ScreenTextService(client, repo, frames, storage);
        var video = Video.builder().filename("test.mp4").build();
        var first = new AnalysisServerClient.OcrResponse.Item(0, 1000, " 자막 ", 0.9, "/test/frame.jpg",
                0.1, 0.8, 0.5, 0.05, "region-0", 1, 3);
        var second = new AnalysisServerClient.OcrResponse.Item(0, 1000, "메뉴 9000원", 0.9, "/test/frame.jpg",
                0.2, 0.2, 0.2, 0.1, "region-1", 1, 0);
        when(client.ocr(video)).thenReturn(Optional.of(new AnalysisServerClient.OcrResponse(List.of(first, second), "regions-v1")));
        when(storage.toStorageKey(java.nio.file.Path.of("/test/frame.jpg"))).thenReturn("frames/test.jpg");
        when(frames.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        when(repo.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        var texts = service.extractAndSave(video);
        assertThat(texts).extracting(ScreenText::getText).containsExactly(" 자막 ", "메뉴 9000원");
        assertThat(texts.get(0).getRegion().getBoxY()).isEqualTo(0.8);
        assertThat(texts.get(0).getRegion().getSlotTextChanges()).isEqualTo(3);
        assertThat(texts.get(0).getFrame()).isSameAs(texts.get(1).getFrame());
        verify(frames, times(1)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void oldAndNewPythonResponseFormatsDeserialize() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var old = mapper.readValue("""
                {"items":[{"startMs":0,"endMs":1000,"text":"원문","confidence":0.9,"framePath":null}]}
                """, AnalysisServerClient.OcrResponse.class);
        assertThat(old.items().get(0).boxX()).isNull();
        var current = mapper.readValue("""
                {"formatVersion":"regions-v1","items":[{"startMs":0,"endMs":1000,"text":"원문","confidence":0.9,
                "framePath":null,"boxX":0.1,"boxY":0.8,"boxWidth":0.5,"boxHeight":0.05,
                "trackId":"region-0","observations":2,"slotTextChanges":4}]}
                """, AnalysisServerClient.OcrResponse.class);
        assertThat(current.items().get(0).trackId()).isEqualTo("region-0");
        assertThat(current.formatVersion()).isEqualTo("regions-v1");
    }
}
