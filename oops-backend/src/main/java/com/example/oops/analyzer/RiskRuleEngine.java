package com.example.oops.analyzer;

import com.example.oops.domain.RiskCategory;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Local privacy signals only. Words alone never publish context warnings. */
@Component
public class RiskRuleEngine {
    private record PrivacyRule(Pattern pattern, String label) {}
    private static final List<PrivacyRule> PRIVACY_RULES = List.of(
            new PrivacyRule(Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}"), "전화번호"),
            new PrivacyRule(Pattern.compile("\\d{6}-?[1-4]\\d{6}"), "주민등록번호"),
            new PrivacyRule(Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}"), "이메일 주소"),
            new PrivacyRule(Pattern.compile("\\d{2,3}-\\d{2,6}-\\d{2,6}"), "계좌번호로 보이는 숫자"));
    public List<Hit> detect(String text) {
        if (text == null || text.isBlank()) return List.of();
        for (var rule : PRIVACY_RULES) {
            var matcher = rule.pattern().matcher(text);
            if (matcher.find()) return List.of(new Hit(RiskCategory.PRIVACY, 0.85,
                    rule.label() + "로 보이는 값(" + matcher.group() + ")이 나옵니다. 공개해도 되는 정보인지 확인해 보세요."));
        }
        return List.of();
    }
    public record Hit(RiskCategory category, double score, String reason) {}
}
