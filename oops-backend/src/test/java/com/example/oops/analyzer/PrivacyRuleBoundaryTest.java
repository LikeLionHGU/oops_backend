package com.example.oops.analyzer;

import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PrivacyRuleBoundaryTest {
    private final RiskRuleEngine rules = new RiskRuleEngine();

    @Test void incompleteOcrPhoneAndUnlabeledNumbersAreNotBankAccounts() {
        for (String text : List.of("054-481-245","체인점문의:010-6387-994","123-456-789","2026-10-15"))
            assertThat(rules.detect(text)).as(text).isEmpty();
    }
    @Test void completeUnlabeledPhonesRemainPrivacyCandidates() {
        for (String text : List.of("010-1234-5678","01012345678","054-481-2456","02-123-4567"))
            assertThat(rules.detect(text)).as(text).singleElement().satisfies(hit -> {
                assertThat(hit.reason()).contains("전화번호").doesNotContain("계좌");
                assertThat(hit.score()).isEqualTo(.85);
            });
        assertThat(rules.detect("010-1234-56789")).isEmpty();
    }
    @Test void explicitBusinessPhoneLabelsAreNarrowExceptionsNotWholeLineExemptions() {
        for (String prefix : List.of("체인점문의:","가맹 문의 : ","예약 문의：","대표전화 ","매장 전화:"))
            assertThat(rules.detect(prefix+"010-1234-5678")).as(prefix).isEmpty();
        assertThat(rules.detect("체인점문의:010-1234-5678 다른 번호 010-9876-5432"))
                .singleElement().satisfies(hit -> assertThat(hit.reason()).contains("010-9876-5432"));
        assertThat(rules.detect("문의 010-1234-5678")).hasSize(1);
    }
    @Test void privateDisclosureOverridesBusinessLabelsAndDoesNotHideOtherIdentifiers() {
        for (String context : List.of("개인번호 유출 ","직원 ","비공개 ","피해자 "))
            assertThat(rules.detect(context+"체인점문의:010-1234-5678")).hasSize(1);
        assertThat(rules.detect("대표전화 010-1234-5678 이메일 private@example.com"))
                .singleElement().satisfies(hit -> assertThat(hit.reason()).contains("이메일"));
        assertThat(rules.detect("가맹문의 010-1234-5678 주민등록번호 900101-1234567"))
                .singleElement().satisfies(hit -> assertThat(hit.reason()).contains("주민등록번호"));
    }
    @Test void accountsRequireContextAndDoNotClaimVerifiedPrivateInformation() {
        assertThat(rules.detect("입금 계좌 123-456-789"))
                .singleElement().satisfies(hit -> {
                    assertThat(hit.reason()).contains("계좌 관련 문맥", "원본에서 확인");
                    assertThat(hit.score()).isEqualTo(.6);
                });
        assertThat(rules.detect("계좌번호라고는 했지만 숫자는 없음")).isEmpty();
        assertThat(rules.detect("입금 날짜 2026-10-15")).isEmpty();
        assertThat(rules.detect("계좌 문의 010-1234-567")).isEmpty();
        assertThat(rules.detect("은행 앞 가게 간판 123-456-789")).isEmpty();
    }
    @Test void actualOcrAndSttAnalyzersUseTheSameBoundaries() {
        var video = Video.builder().filename("boundary.mp4").build();
        var text = "체인점문의:010-1234-5678";
        var c = new AnalysisContext(video, ContentGenre.GENERAL,
                List.of(new TranscriptSegment(video,0,1000,text)),
                List.of(new ScreenText(video,0,1000,text,.9,null)));
        assertThat(new ScreenTextAnalyzer(rules).analyze(c)).isEmpty();
        assertThat(new SubtitleAnalyzer(rules).analyze(c)).isEmpty();
    }
}
