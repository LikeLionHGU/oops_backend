package com.example.oops.client;

import com.example.oops.config.AnalysisServerProperties;
import com.example.oops.domain.Video;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.*;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AnalysisServerFailureIsolationTest {
    @Test void successfulEmptyOcrDoesNotInheritFailedStt() {
        var builder=RestClient.builder().baseUrl("http://worker");
        var server=MockRestServiceServer.bindTo(builder).build();
        var rest=builder.build();
        var storage=mock(StorageService.class);
        var video=Video.builder().filename("test.mp4").build();
        when(storage.resolve(any())).thenReturn(Path.of("/media/test.mp4"));
        when(storage.frameDir(any())).thenReturn(Path.of("/media/frames"));
        var client=new AnalysisServerClient(rest, rest, new AnalysisServerProperties(null,null,null), storage);
        server.expect(requestTo("http://worker/transcribe")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"STT unavailable\"}"));
        server.expect(requestTo("http://worker/ocr")).andRespond(withSuccess("{\"items\":[]}",MediaType.APPLICATION_JSON));
        assertThat(client.transcribe(video)).isEmpty();
        assertThat(client.lastFailureDetail()).hasValue("STT unavailable");
        assertThat(client.ocr(video)).isPresent();
        assertThat(client.lastFailureDetail()).isEmpty();
        server.verify();
    }
}
