package com.example.oops.lexicon;

import java.util.List;

/**
 * 사전 항목 하나. context-lexicon.json 에서 읽는다.
 *
 * suppressHints 가 핵심이다. 이게 없으면 '7시에 만나요' 가 전부 걸린다.
 */
public record ContextLexiconEntry(
        String id,
        List<String> patterns,

        /** 정규식이 필요한 항목만 (예: 문장 끝의 '~노' 어미) */
        String regex,

        ContextTriggerMode triggerMode,

        /** 근처에 있으면 특수 용법일 가능성이 올라간다 */
        List<String> contextHints,

        /** 근처에 있으면 일반 용법이므로 버린다 */
        List<String> suppressHints,

        /** true 면 AI 에게 앞뒤 맥락을 확인시킨다 */
        Boolean needsContext,

        /** 사용자에게 보여줄 설명. 판정하지 않고 사실만 적는다 */
        String reason,

        /** 마지막 점검일. 표현의 쓰임은 몇 년 사이에도 바뀐다 */
        String reviewedAt
) {
    public boolean requiresContextCheck() {
        return Boolean.TRUE.equals(needsContext);
    }

    public List<String> contextHintsOrEmpty() {
        return contextHints == null ? List.of() : contextHints;
    }

    public List<String> suppressHintsOrEmpty() {
        return suppressHints == null ? List.of() : suppressHints;
    }

    public List<String> patternsOrEmpty() {
        return patterns == null ? List.of() : patterns;
    }
}
