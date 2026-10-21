package com.example.oops.storage;

import com.example.oops.config.OopsProperties;
import com.example.oops.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class LocalUploadLimitTest {
    OopsProperties properties=new OopsProperties(new OopsProperties.Storage("./uploads",0,0),null,null);
    MockMultipartFile large(String name) {
        return new MockMultipartFile("file",name,"video/mp4",new byte[]{1}) {
            @Override public long getSize() { return 5L*1024*1024*1024; }
        };
    }
    ApplicationContextRunner context() {
        return new ApplicationContextRunner().withBean(OopsProperties.class,() -> properties).withBean(StorageService.class);
    }
    @Test void defaultApplicationGuardStillRejectsOversizedVideo() {
        context().run(c -> assertThatThrownBy(() -> c.getBean(StorageService.class).validateVideoFile(large("large.mp4")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("500MB"));
    }
    @Test void localYamlDisablesBothMultipartAndApplicationGuardWithoutAllocatingLargeFile() throws Exception {
        var source=new YamlPropertySourceLoader().load("local",new ClassPathResource("application-local.yml")).get(0);
        assertThat(source.getProperty("spring.servlet.multipart.max-file-size").toString()).isEqualTo("-1");
        assertThat(source.getProperty("spring.servlet.multipart.max-request-size").toString()).isEqualTo("-1");
        context().withPropertyValues("oops.storage.max-file-size-bytes="+source.getProperty("oops.storage.max-file-size-bytes"))
                .run(c -> {
                    var storage=c.getBean(StorageService.class);
                    assertThatCode(() -> storage.validateVideoFile(large("large.mp4"))).doesNotThrowAnyException();
                    assertThatThrownBy(() -> storage.validateVideoFile(large("bad.exe"))).isInstanceOf(BusinessException.class);
                    assertThatThrownBy(() -> storage.validateVideoFile(new MockMultipartFile("file","empty.mp4","video/mp4",new byte[0])))
                            .isInstanceOf(BusinessException.class);
                });
    }
    @Test void invalidConfigurationCannotSilentlyDisableLimit() {
        assertThatThrownBy(() -> new StorageService(properties).setMaxFileSizeBytes(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageService(properties).setMaxFileSizeBytes(-2)).isInstanceOf(IllegalArgumentException.class);
    }
}
