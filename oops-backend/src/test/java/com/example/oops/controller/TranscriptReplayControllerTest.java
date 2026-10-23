package com.example.oops.controller;

import com.example.oops.service.TranscriptReplayService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TranscriptReplayControllerTest {
    @Test void endpointExistsOnlyInLocalProfile() {
        var runner=new ApplicationContextRunner().withUserConfiguration(TranscriptReplayController.class)
                .withBean(TranscriptReplayService.class,()->mock(TranscriptReplayService.class));
        runner.run(context->assertThat(context).doesNotHaveBean(TranscriptReplayController.class));
        runner.withInitializer(context->context.getEnvironment().setActiveProfiles("local"))
                .run(context->assertThat(context).hasSingleBean(TranscriptReplayController.class));
    }
}
