package com.example.oops.client;

import com.example.oops.config.AnalysisServerProperties;
import com.example.oops.domain.*;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AnalysisServerClientTest {
    @Test void ocrUsesItsDedicatedClientRatherThanTheGeneralClient() {
        var builder = RestClient.builder().baseUrl("http://worker.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://worker.invalid/ocr")).andRespond(
                withSuccess("{\"items\":[],\"processing\":{\"status\":\"SUCCESS\",\"totalFrames\":1,\"successfulFrames\":1,\"failedFrames\":0}}", MediaType.APPLICATION_JSON));
        var general = mock(RestClient.class);
        var storage = mock(StorageService.class);
        var video = mock(Video.class);
        when(video.getSourceType()).thenReturn(SourceType.YOUTUBE);
        when(video.getSourceUrl()).thenReturn("https://youtu.be/fixture");
        var client = new AnalysisServerClient(general, RestClient.create(), builder.build(),
                new AnalysisServerProperties(null, null, 4.0), storage);
        assertThat(client.ocr(video, "/fixture/frames")).isPresent();
        verifyNoInteractions(general);
        server.verify();
    }
    @Test void workerDeadlineDetailIsPreservedRatherThanReportedAsConnectionFailure() {
        var builder = RestClient.builder().baseUrl("http://worker.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://worker.invalid/ocr")).andRespond(
                withStatus(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"OCR_TIMEOUT: OCR 작업 제한 시간을 초과했습니다.\"}"));
        var storage = mock(StorageService.class);
        when(storage.frameDir(1L)).thenReturn(Path.of("/fixture/frames/1"));
        var video = mock(Video.class);
        when(video.getId()).thenReturn(1L);
        when(video.getSourceType()).thenReturn(SourceType.YOUTUBE);
        when(video.getSourceUrl()).thenReturn("https://youtu.be/fixture");
        var client = new AnalysisServerClient(builder.build(), RestClient.create(),
                new AnalysisServerProperties(null, null, 4.0), storage);
        assertThat(client.ocr(video)).isEmpty();
        assertThat(client.lastFailureDetail().orElseThrow()).startsWith("OCR_TIMEOUT:");
        server.verify();
    }
    @Test void transportTimeoutAndConnectionFailureAreDistinguishedWithoutNetwork() {
        for (var failure : java.util.List.of(
                new java.net.http.HttpTimeoutException("fixture timeout"),
                new java.net.SocketTimeoutException("fixture timeout"),
                new java.net.ConnectException("fixture refused"),
                new java.net.http.HttpConnectTimeoutException("fixture connect timeout"))) {
            var builder = RestClient.builder().baseUrl("http://worker.invalid");
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://worker.invalid/ocr")).andRespond(request -> { throw failure; });
            var storage = mock(StorageService.class);
            when(storage.frameDir(1L)).thenReturn(Path.of("/fixture/frames/1"));
            when(storage.resolve("fixture")).thenReturn(Path.of("/fixture/videos/1/original.mp4"));
            var video = mock(Video.class);
            when(video.getId()).thenReturn(1L);
            when(video.getSourceType()).thenReturn(SourceType.UPLOAD);
            when(video.getStorageKey()).thenReturn("fixture");
            var client = new AnalysisServerClient(builder.build(), RestClient.create(),
                    new AnalysisServerProperties(null, null, 4.0), storage);
            assertThat(client.ocr(video)).isEmpty();
            boolean connection = failure instanceof java.net.ConnectException
                    || failure instanceof java.net.http.HttpConnectTimeoutException;
            assertThat(client.lastFailureDetail().orElseThrow()).contains(
                    connection ? "WORKER_CONNECTION_FAILED" : "WORKER_READ_TIMEOUT");
            assertThat(client.lastOcrAssessment().orElseThrow().status()).isEqualTo(AnalyzerStatus.FAILED);
            server.verify();
        }
    }
    @Test void malformedOrMissingOcrPayloadIsFailedAndLegacyPayloadIsPartial() {
        for (String body : java.util.List.of("{}", "{\"items\":null}",
                "{\"items\":[],\"processing\":{\"status\":\"SUCCESS\",\"totalFrames\":2,\"successfulFrames\":1,\"failedFrames\":1}}",
                "{\"items\":[],\"formatVersion\":\"regions-v1\"}")) {
            var builder = RestClient.builder().baseUrl("https://worker.invalid");
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://worker.invalid/ocr")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            var storage = mock(StorageService.class);
            when(storage.frameDir(1L)).thenReturn(Path.of("/fixture/frames/1"));
            when(storage.resolve("fixture")).thenReturn(Path.of("/fixture/videos/1/original.mp4"));
            var video = mock(Video.class);
            when(video.getId()).thenReturn(1L);
            when(video.getSourceType()).thenReturn(SourceType.UPLOAD);
            when(video.getStorageKey()).thenReturn("fixture");
            var client = new AnalysisServerClient(builder.build(), RestClient.create(),
                    new AnalysisServerProperties(null, null, 4.0), storage);
            var result = client.ocr(video);
            boolean legacy = body.contains("formatVersion");
            assertThat(result.isPresent()).isEqualTo(legacy);
            assertThat(client.lastOcrAssessment().orElseThrow().status())
                    .isEqualTo(legacy ? AnalyzerStatus.PARTIAL : AnalyzerStatus.FAILED);
            server.verify();
        }
    }
    @Test void validAndLegacyFrameStatesAreNotConfused() {
        assertThat(AnalysisServerClient.assessOcr(new AnalysisServerClient.OcrProcessing("SUCCESS", 2, 2, 0)).status())
                .isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(AnalysisServerClient.assessOcr(new AnalysisServerClient.OcrProcessing("PARTIAL", 2, 1, 1)).status())
                .isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(AnalysisServerClient.assessOcr(null).status()).isEqualTo(AnalyzerStatus.PARTIAL);
        for (var p : java.util.List.of(new AnalysisServerClient.OcrProcessing("SUCCESS", 2, 1, 1),
                new AnalysisServerClient.OcrProcessing("SUCCESS", 2, 2, 1),
                new AnalysisServerClient.OcrProcessing("FAILED", 2, 0, 2),
                new AnalysisServerClient.OcrProcessing("SUCCESS", 0, 0, 0))) {
            assertThatThrownBy(() -> AnalysisServerClient.assessOcr(p)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void httpStatesPropagateAndAreResetBetweenVideosWithoutNetwork() {
        var builder = RestClient.builder().baseUrl("https://worker.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        var storage = mock(StorageService.class);
        when(storage.frameDir(1L)).thenReturn(Path.of("/fixture/frames/1"));
        when(storage.resolve("fixture")).thenReturn(Path.of("/fixture/videos/1/original.mp4"));
        var video = mock(Video.class);
        when(video.getId()).thenReturn(1L);
        when(video.getSourceType()).thenReturn(SourceType.UPLOAD);
        when(video.getStorageKey()).thenReturn("fixture");
        var client = new AnalysisServerClient(builder.build(), RestClient.create(),
                new AnalysisServerProperties(null, null, 4.0), storage);
        server.expect(requestTo("https://worker.invalid/ocr")).andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"OCR 전체 실패\"}"));
        server.expect(requestTo("https://worker.invalid/ocr")).andRespond(withSuccess(
                "{\"items\":[],\"processing\":{\"status\":\"PARTIAL\",\"totalFrames\":2,\"successfulFrames\":1,\"failedFrames\":1}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://worker.invalid/ocr")).andRespond(withSuccess(
                "{\"items\":[],\"processing\":{\"status\":\"SUCCESS\",\"totalFrames\":2,\"successfulFrames\":2,\"failedFrames\":0}}", MediaType.APPLICATION_JSON));
        assertThat(client.ocr(video)).isEmpty();
        assertThat(client.lastOcrAssessment().orElseThrow().status()).isEqualTo(AnalyzerStatus.FAILED);
        assertThat(client.lastFailureDetail()).contains("OCR 전체 실패");
        assertThat(client.ocr(video)).isPresent();
        assertThat(client.lastOcrAssessment().orElseThrow().status()).isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(client.lastFailureDetail()).isEmpty();
        assertThat(client.ocr(video)).isPresent();
        assertThat(client.lastOcrAssessment().orElseThrow().status()).isEqualTo(AnalyzerStatus.SUCCESS);
        server.verify();
    }
}
