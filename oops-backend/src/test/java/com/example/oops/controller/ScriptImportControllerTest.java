package com.example.oops.controller;

import com.example.oops.service.ScriptImportService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptImportControllerTest {
    @Test void importExistsOnlyInLocalProfile() {
        var runner = new ApplicationContextRunner().withUserConfiguration(ScriptImportController.class)
                .withBean(ScriptImportService.class, () -> mock(ScriptImportService.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(ScriptImportController.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> assertThat(context).hasSingleBean(ScriptImportController.class));
    }
}
