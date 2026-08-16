package com.example.oops.dto;

import com.example.oops.domain.AnalysisCoverage;

/**
 * 사용자에게 알려야 할 미수행 단계. 명세 §5-1.
 *
 * coverage[] 가 전체 현황이라면 이건 "이건 꼭 보세요" 만 추린 것이다.
 * 프론트는 이 배열이 비어 있지 않으면 결과 위에 눈에 띄게 띄워야 한다.
 *
 * 이게 없으면 "확인할 지점 0곳" 이 두 가지를 동시에 뜻하게 된다.
 *   - 검수했더니 괜찮다
 *   - 검수를 못 했다
 * 후자를 전자로 읽고 영상을 올리면 도구가 없느니만 못하다.
 */
public record AnalysisWarningDto(
        String stage,
        String code,
        String message
) {
    public static AnalysisWarningDto from(AnalysisCoverage c) {
        String message = c.getMessage() == null
                ? "%s 을(를) 완료하지 못했습니다.".formatted(c.getStep().getLabel())
                : "%s — %s".formatted(c.getStep().getLabel(), c.getMessage());

        return new AnalysisWarningDto(c.getStep().name(), c.warningCode(), message);
    }
}
