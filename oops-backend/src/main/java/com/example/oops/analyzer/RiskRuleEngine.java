package com.example.oops.analyzer;

import com.example.oops.domain.RiskCategory;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Local privacy signals only. Words alone never publish context warnings. */
@Component
public class RiskRuleEngine {
    private record PrivacyRule(Pattern pattern, String label, boolean contact) {}
    private static final Pattern ACCOUNT_CONTEXT = Pattern.compile("계좌|입금|송금");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("(?<![\\d-])\\d{2,6}-\\d{2,6}-\\d{2,6}(?![\\d-])");
    private static final Pattern DATE_OR_PHONE_PREFIX = Pattern.compile("(?:(?:19|20)\\d{2}-\\d{1,2}-\\d{1,2}|0(?:1[016789]|2|[3-6][1-5]|70)-.*)");
    private static final Pattern BUSINESS_PREFIX = Pattern.compile("(?:체인점\\s*문의|가맹(?:점)?\\s*문의|예약\\s*문의|대표\\s*전화|매장\\s*전화)\\s*[:：]?\\s*$");
    private static final Pattern PRIVATE_CONTEXT = Pattern.compile("개인\\s*(?:번호|연락처)|사적|비공개|공개\\s*금지|유출|몰래|직원|고객|피해자|집\\s*전화");
    private static final List<PrivacyRule> PRIVACY_RULES = List.of(
            new PrivacyRule(Pattern.compile("(?<![\\d-])01[016789]-?\\d{3,4}-?\\d{4}(?![\\d-])"), "전화번호", true),
            new PrivacyRule(Pattern.compile("(?<![\\d-])0(?:2|[3-6][1-5]|70)-\\d{3,4}-\\d{4}(?![\\d-])"), "전화번호", true),
            new PrivacyRule(Pattern.compile("(?<!\\d)\\d{6}-?[1-4]\\d{6}(?!\\d)"), "주민등록번호", false),
            new PrivacyRule(Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}"), "이메일 주소", false));
    public List<Hit> detect(String text) {
        if (text == null || text.isBlank()) return List.of();
        for (var rule : PRIVACY_RULES) {
            var matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                // A narrowly attached business label is not permission to expose other contacts.
                if (rule.contact() && businessContact(text, matcher.start())) continue;
                return List.of(new Hit(RiskCategory.PRIVACY, 0.85,
                        rule.label() + "로 보이는 값(" + matcher.group() + ")이 나옵니다. 공개해도 되는 정보인지 확인해 보세요."));
            }
        }
        // Hyphenated digits alone cannot identify a bank account, especially with truncated OCR.
        if (ACCOUNT_CONTEXT.matcher(text).find()) {
            var matcher = ACCOUNT_NUMBER.matcher(text);
            while (matcher.find()) {
                if (DATE_OR_PHONE_PREFIX.matcher(matcher.group()).matches()) continue;
                return List.of(new Hit(RiskCategory.PRIVACY, 0.6,
                        "계좌 관련 문맥에 숫자(" + matcher.group() + ")가 나옵니다. 실제 계좌번호와 공개 목적은 원본에서 확인해 보세요."));
            }
        }
        return List.of();
    }
    private static boolean businessContact(String text, int start) {
        if (PRIVATE_CONTEXT.matcher(text).find()) return false;
        return BUSINESS_PREFIX.matcher(text.substring(Math.max(0,start-24),start)).find();
    }
    public record Hit(RiskCategory category, double score, String reason) {}
}
