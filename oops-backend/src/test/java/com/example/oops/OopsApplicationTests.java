package com.example.oops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 스프링 설정이 온전한지 확인한다.
 *
 * 빈 하나가 주입이 안 되거나 설정 값이 틀리면 여기서 잡힌다.
 * 컨트롤러를 늘리거나 리포지토리를 추가할 때 실수를 가장 먼저 알려주는 테스트다.
 *
 * "test" 프로파일을 쓰는 이유는 메모리 DB 를 따로 쓰기 위해서다.
 * 운영 설정은 H2 파일 모드라, 서버를 켜둔 채 테스트를 돌리면
 * 파일이 잠겨 있어서 실패한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class OopsApplicationTests {

    @org.springframework.beans.factory.annotation.Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    @DisplayName("애플리케이션 컨텍스트가 뜬다")
    void contextLoads() {
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void regionAndRoleMetadataSurviveDatabaseRoundTrip() {
        var video = com.example.oops.domain.Video.builder()
                .sourceType(com.example.oops.domain.SourceType.UPLOAD).filename("test.mp4").build();
        entityManager.persist(video);
        var text = new com.example.oops.domain.ScreenText(video, 0, 1_000, "원문", 0.9, null);
        text.attachRegion(new com.example.oops.domain.OcrRegion(0.1, 0.8, 0.5, 0.05, "region-0", 2, 3));
        text.classify(com.example.oops.domain.ScreenTextRole.EDITORIAL, "같은 시간대 발언 일치");
        entityManager.persist(text);
        entityManager.flush();
        Long id = text.getId();
        entityManager.clear();
        var restored = entityManager.find(com.example.oops.domain.ScreenText.class, id);
        org.assertj.core.api.Assertions.assertThat(restored.getRegion().getBoxY()).isEqualTo(0.8);
        org.assertj.core.api.Assertions.assertThat(restored.getRegion().getTrackId()).isEqualTo("region-0");
        org.assertj.core.api.Assertions.assertThat(restored.roleOrUncertain()).isEqualTo(com.example.oops.domain.ScreenTextRole.EDITORIAL);
    }
}
