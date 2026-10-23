package com.example.oops.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import java.net.InetSocketAddress;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

/** Local synthetic HTTP only; no worker, media or provider calls. */
class OcrClientTimeoutTest {
    @Test void zeroOcrDeadlineDoesNotDisableTheGeneralClientDeadline() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(200);
                byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // Expected if the bounded client has already timed out.
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var properties = new AnalysisServerProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofMillis(40), 4.0, Duration.ZERO);
            var config = new RestClientConfig();
            assertThat(config.analysisOcrRestClient(properties).get().uri("/slow").retrieve().body(String.class))
                    .isEqualTo("ok");
            assertThatThrownBy(() -> config.analysisRestClient(properties).get().uri("/slow")
                    .retrieve().body(String.class)).isInstanceOf(RestClientException.class);
        } finally { server.stop(0); }
    }
}
