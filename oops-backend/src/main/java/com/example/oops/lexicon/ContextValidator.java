package com.example.oops.lexicon;

import com.example.oops.client.OpenAiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 사전에 걸린 표현이 정말 그 뜻으로 쓰였는지 앞뒤 맥락으로 확인한다.
 *
 * 이 단계가 없으면 사전은 쓸모가 없다.
 * "수박 사왔어요" 와 "저 의원도 결국 수박이더라고" 는 같은 단어지만 전혀 다른 이야기다.
 *
 * 걸린 것을 한 번에 모아 한 통으로 물어본다.
 * 표현마다 호출하면 영상 하나에 수십 번이 나가서 요청 한도에 바로 걸린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextValidator {

    /** 한 번에 확인할 최대 건수. 넘으면 나눠 보낸다 */
    private static final int BATCH = 12;

    private static final String SYSTEM_PROMPT = """
            너는 영상 공개 전에 확인할 지점을 짚어주는 검수 보조자다.
            어떤 표현이 일반적인 뜻으로 쓰였는지, 아니면 알려진 특수한 뜻으로 쓰였는지만 가린다.

            **너는 옳고 그름을 판단하지 않는다.** 그 표현이 나쁜지, 지워야 하는지는 네 일이 아니다.
            제작자가 모르고 지나쳤을 만한 맥락이 있는지만 본다.

            각 항목에 대해 이렇게 판단해라:

            1. 이 표현은 일반적인 의미로 쓰였는가?
            2. 알려진 사회·정치·문화·역사적 의미와 연결되는가?
            3. 화자가 직접 쓴 것인가, 인용하거나 설명하거나 비판하는 것인가?
            4. 표현이 향하는 대상은 누구인가?
            5. 제작자가 이 맥락을 모르고 지나쳤을 가능성이 있는가?

            판정 값:
            - LITERAL      일반적인 의미로 썼다. 과일, 시간, 아이 이야기다
            - CONTEXTUAL   알려진 특수한 의미로 썼다
            - QUOTATION    남의 말을 인용하거나, 그 표현 자체를 설명·비판하고 있다
            - AMBIGUOUS    앞뒤만으로는 가릴 수 없다

            판정 원칙:
            - **애매하면 LITERAL 이다.** 이 판단은 잘못 잡으면 신뢰를 크게 잃는다.
              "7시에 만나요", "수박 먹었어요", "초등학생 조카" 를 잡으면
              제작자는 두 번째 영상부터 이 도구를 쓰지 않는다.
            - 표현을 설명하거나 비판하는 맥락이면 QUOTATION 이다.
              "그 사람들이 쓰는 말이잖아요" 는 그 표현을 쓴 게 아니다.
            - 정치 구호나 지지 표명은 그 자체로 문제가 아니다.
              어느 편인지로 판단을 바꾸지 마라. 같은 기준을 적용한다.
            - 확신이 없으면 AMBIGUOUS 를 써라. 억지로 고르지 마라.

            반드시 이 JSON 형식으로만 답한다:
            {"results":[{"index":0,"verdict":"LITERAL","target":"","note":""}]}

            index 는 받은 항목의 번호다.
            target 은 표현이 향하는 대상(사람·집단·지역). 없으면 빈 문자열.
            note 는 CONTEXTUAL 일 때만, 어떤 맥락으로 읽히는지 한 문장. 아니면 빈 문자열.
            한국어로 쓴다.
            """;

    private final OpenAiClient openAiClient;

    public boolean isEnabled() {
        return openAiClient.isEnabled();
    }

    /**
     * 확인이 필요한 것만 AI 에게 묻는다.
     *
     * needsContext 가 false 인 항목(예: '틀딱')은 묻지 않는다.
     * 일반 용법이 없는 표현이라 앞뒤를 봐도 답이 같다. 괜히 돈만 나간다.
     */
    public Map<Integer, Verdict> validate(List<Request> requests) {
        Map<Integer, Verdict> results = new HashMap<>();
        if (requests.isEmpty() || !openAiClient.isEnabled()) {
            return results;
        }

        for (int from = 0; from < requests.size(); from += BATCH) {
            List<Request> chunk = requests.subList(from, Math.min(requests.size(), from + BATCH));
            askOne(chunk, results);
        }
        return results;
    }

    private void askOne(List<Request> chunk, Map<Integer, Verdict> results) {
        StringBuilder prompt = new StringBuilder("확인할 표현들이다.\n\n");

        for (Request r : chunk) {
            prompt.append("[%d] 표현: \"%s\"%n".formatted(r.index(), r.matchedText()));
            prompt.append("    알려진 맥락: %s%n".formatted(r.knownContext()));
            if (r.before() != null && !r.before().isBlank()) {
                prompt.append("    앞: \"%s\"%n".formatted(r.before()));
            }
            prompt.append("    해당 발언: \"%s\"%n".formatted(r.line()));
            if (r.after() != null && !r.after().isBlank()) {
                prompt.append("    뒤: \"%s\"%n".formatted(r.after()));
            }
            prompt.append('\n');
        }

        BatchResult result = openAiClient
                .completeAsJson(SYSTEM_PROMPT, prompt.toString(), BatchResult.class)
                .orElse(null);

        if (result == null || result.results() == null) {
            log.warn("[lexicon] 맥락 확인에 실패했습니다. 해당 {}건은 올리지 않습니다.", chunk.size());
            return;
        }
        for (Verdict v : result.results()) {
            if (v.index() != null) {
                results.put(v.index(), v);
            }
        }
    }

    /** 확인 요청 1건. 앞뒤 줄을 함께 준다 */
    public record Request(int index, String matchedText, String knownContext,
                          String before, String line, String after) {}

    public record BatchResult(List<Verdict> results) {}

    public record Verdict(Integer index, String verdict, String target, String note) {

        public boolean isContextual() {
            return "CONTEXTUAL".equalsIgnoreCase(verdict);
        }

        public boolean isAmbiguous() {
            return "AMBIGUOUS".equalsIgnoreCase(verdict);
        }

        /** 올릴 만한지. 일반 용법과 인용은 버린다 */
        public boolean worthReporting() {
            return isContextual() || isAmbiguous();
        }
    }
}
