package com.example.oops.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class LocalAnalysisTimeoutTest {
    @Test void localWaitHasMarginOverPythonLocalDeadlineWithoutChangingDefault() throws Exception {
        var source = new YamlPropertySourceLoader().load("local", new ClassPathResource("application-local.yml"));
        assertThat(source.get(0).getProperty("oops.analysis-server.timeout")).isEqualTo("25m");
        assertThat(source.get(0).getProperty("oops.analysis-server.ocr-timeout")).isEqualTo("0s");
        assertThat(new AnalysisServerProperties(null, null, null).timeoutOrDefault()).isEqualTo(Duration.ofMinutes(10));
        assertThat(new AnalysisServerProperties(null, null, null).ocrTimeoutOrDefault()).isEqualTo(Duration.ofMinutes(10));
        assertThat(new AnalysisServerProperties(null, Duration.ofMinutes(25), null, Duration.ZERO).ocrTimeoutOrDefault()).isZero();
    }
}
