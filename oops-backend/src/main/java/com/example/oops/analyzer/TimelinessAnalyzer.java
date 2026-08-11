package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.news.NewsSearchClient;
import com.example.oops.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * "지금 이 시점에 이 얘기를 올려도 되는가" 를 본다.
 *
 * 다른 분석기들은 발언 자체가 문제인지를 보지만, 이건 다르다.
 * "재선거" 같은 표현은 그 자체로는 아무 문제가 없다.
 * 그런데 마침 재선거가 진행 중이거나 관련 논란이 터진 시점이라면,
 * 같은 영상이라도 반응이 완전히 달라진다.
 *
 * LLM 은 학습 시점 이후의 뉴스를 모르므로 혼자서는 이 판단을 할 수 없다.
 * 그래서 세 단계로 나눴다.
 *
 *   1. 대본·자막에서 "시사성이 있을 수 있는 주제" 를 뽑는다        (LLM)
 *   2. 그 주제로 최근 뉴스를 검색한다                              (네이버)
 *   3. 오늘 날짜와 기사 목록을 함께 주고 위험한지 판단하게 한다     (LLM)
 *
 * 뉴스 검색은 NewsSearchClient 가 맡는다. 네이버 키가 있으면 네이버를,
 * 없으면 키가 필요 없는 구글 뉴스 RSS 를 자동으로 쓴다.
 * OpenAI 키가 없으면 통째로 스킵된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelinessAnalyzer implements ContentAnalyzer {

    /** 비용과 시간을 아끼려고 주제 수를 제한한다. */
    private static final int MAX_TOPICS = 5;
    private static final int NEWS_PER_TOPIC = 8;

    /**
     * 이런 검색어로는 "지금 논란인가" 를 판단할 수 없다.
     * LLM 이 프롬프트를 어겨도 여기서 한 번 더 거른다.
     */
    private static final Set<String> TOO_GENERIC = Set.of(
            "정치", "선거", "사회", "경제", "문화", "연예", "스포츠", "사건", "사고",
            "뉴스", "이슈", "논란", "정당", "국회", "정부", "종교", "젠더", "여성", "남성",
            "재난", "범죄", "노동", "부동산", "세금", "교육", "역사", "전쟁");

    private static final String EXTRACT_PROMPT = """
            너는 영상 검수자를 돕는 보조자다.
            영상의 발언과 화면 자막을 받아서, 시사적으로 민감해질 수 있는 주제를 뽑아낸다.

            뽑아야 하는 것:
            - 선거, 정치, 정당, 정치인
            - 사건사고, 재난, 범죄
            - 사회적으로 논쟁 중인 이슈 (젠더, 노동, 부동산, 세금, 교육 등)
            - 특정 기업, 브랜드, 유명인의 실명
            - 종교, 역사, 국제 분쟁

            뽑지 말아야 하는 것:
            - 일상 대화, 인사말, 감탄사
            - 일반명사, 보통의 상황 묘사
            - 채널 홍보 문구

            주의: 화면 자막은 OCR 결과라 글자가 깨져 있을 수 있다.
            "재선커", "재신거" 처럼 깨진 글자는 원래 단어를 추론해서 keyword 에 정확히 적어라.

            keyword 작성 규칙 (중요):
            뉴스 검색창에 넣었을 때 "특정 사건" 이 나와야 한다.
            "정치", "선거", "사회", "경제" 같은 포괄적인 단어는 쓸모가 없다.
            수만 건이 나오거나 아무것도 안 나온다.

            나쁜 예: "정치" / "선거" / "연예인" / "사건"
            좋은 예: "서울시장 재보궐선거" / "OO그룹 횡령 수사" / "△△역 화재"

            영상에 구체적인 인물명, 지역명, 사건명이 없어서 포괄어밖에 못 만들겠다면
            그 주제는 아예 뽑지 마라. 검색해도 의미 있는 결과가 안 나온다.

            반드시 이 JSON 형식으로만 답한다:
            {"topics":[{"index":0,"keyword":"구체적인 검색어","context":"영상에서 어떤 맥락으로 나왔는지"}]}

            index 는 그 주제가 "실제로 등장한 줄" 의 번호다.
            영상 전체의 주제가 아니라, 그 단어가 나온 바로 그 줄을 가리켜야 한다.
            여러 줄에 나오면 가장 뚜렷하게 나온 줄을 고른다.

            keyword 는 3~20자. 해당하는 주제가 없으면 {"topics":[]} 를 반환한다.
            최대 5개까지만 뽑는다.
            """;

    private static final String JUDGE_PROMPT = """
            너는 유튜브 영상 공개 전에 위험을 점검하는 검수자다.
            영상에 등장한 주제와, 그 주제로 검색한 최신 뉴스 목록을 받는다.

            판단할 것:
            지금 이 시점에 이 주제를 영상에서 다루면 논란이 될 가능성이 있는가?

            논란 가능성이 높은 경우:
            - 해당 주제가 지금 진행 중인 사건이다 (선거 기간, 재판 진행 중, 수사 중)
            - 최근 기사에서 여론이 갈리거나 갈등이 보도되고 있다
            - 피해자나 유족이 있는 사건이라 가볍게 다루면 문제가 된다
            - 선거법, 광고법 등 법적 제약이 걸릴 수 있는 시기다

            논란 가능성이 낮은 경우:
            - 오래전에 마무리된 사안이고 최근 기사가 없다
            - 기사들이 단순 정보 전달이고 갈등 요소가 없다
            - 주제가 일반적이라 특정 사건과 무관하다

            반드시 이 JSON 형식으로만 답한다:
            {"risky":true,"score":0.8,"reason":"왜 지금 위험한지 두 문장 이내","issue":"관련된 현재 이슈를 한 줄로"}

            score 는 0.0~1.0 이다. 근거가 약하면 낮게 준다.
            risky 가 false 면 나머지 필드는 비워도 된다. reason 은 한국어로 쓴다.
            """;

    private final OpenAiClient openAiClient;

    /** @Order 순서대로 주입된다. 사용 가능한 첫 번째를 쓴다. */
    private final List<NewsSearchClient> newsSearchClients;

    @Override
    public String key() {
        return "timeliness";
    }

    @Override
    public String displayName() {
        return "시의성 검토";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        if (!openAiClient.isEnabled() || newsClient() == null) {
            return false;
        }
        return context.hasTranscript() || context.hasScreenText();
    }

    private NewsSearchClient newsClient() {
        return newsSearchClients.stream()
                .filter(NewsSearchClient::isEnabled)
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        List<Line> lines = collectLines(context);
        if (lines.isEmpty()) {
            return List.of();
        }

        // 1단계 — 검색할 주제 뽑기
        List<Topic> topics = extractTopics(lines);
        if (topics.isEmpty()) {
            log.info("[timeliness] videoId={} 시사 주제 없음", context.video().getId());
            return List.of();
        }

        NewsSearchClient newsClient = newsClient();
        if (newsClient == null) {
            return List.of();
        }

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy년 M월 d일"));
        List<RiskFinding> findings = new ArrayList<>();
        log.info("[timeliness] 뉴스 소스={} 기준일={}", newsClient.providerName(), today);

        for (Topic topic : topics.stream().limit(MAX_TOPICS).toList()) {
            String keyword = topic.keyword() == null ? "" : topic.keyword().trim();
            if (keyword.length() < 3 || TOO_GENERIC.contains(keyword)) {
                log.info("[timeliness] '{}' 는 너무 포괄적이라 건너뜁니다", keyword);
                continue;
            }

            // 2단계 — 최근 뉴스 검색
            List<NewsSearchClient.NewsItem> news =
                    newsClient.searchRecent(keyword, NEWS_PER_TOPIC);
            if (news.isEmpty()) {
                log.info("[timeliness] '{}' 관련 최근 기사 없음 → 건너뜀", keyword);
                continue;
            }

            // 3단계 — 오늘 기준으로 위험한지 판정
            Judgement judgement = judge(today, topic, news);
            if (judgement == null || !Boolean.TRUE.equals(judgement.risky())) {
                continue;
            }

            // LLM 이 줄 번호를 자주 틀린다. 키워드가 실제로 등장한 줄을 다시 찾는다.
            int lineIndex = locate(lines, keyword, topic.index());
            if (lineIndex < 0) {
                log.info("[timeliness] '{}' 가 등장한 줄을 못 찾아 건너뜁니다", keyword);
                continue;
            }

            Line line = lines.get(lineIndex);
            double score = judgement.score() == null
                    ? 0.6 : Math.max(0.0, Math.min(1.0, judgement.score()));

            findings.add(build(context, line, topic, judgement, score));
            log.info("[timeliness] '{}' 위험 판정 score={} 위치={}ms({}) issue={}",
                    keyword, score, line.startMs(),
                    line.type() == TimelineEventType.SPEECH ? "발언" : "화면",
                    judgement.issue());
        }

        log.info("[timeliness] videoId={} 주제={}개 findings={}",
                context.video().getId(), topics.size(), findings.size());
        return findings;
    }

    private RiskFinding build(AnalysisContext context, Line line, Topic topic,
                              Judgement judgement, double score) {
        String reason = "[%s] %s".formatted(
                judgement.issue() == null ? topic.keyword() : judgement.issue(),
                judgement.reason() == null ? "지금 시점에 민감할 수 있는 주제입니다." : judgement.reason());

        RiskFinding.RiskFindingBuilder builder = RiskFinding.builder()
                .video(context.video())
                .eventType(line.type())
                .category(RiskCategory.TIMING_SENSITIVE)
                .source(line.type() == TimelineEventType.SPEECH
                        ? EvidenceSource.SUBTITLE : EvidenceSource.VISION)
                .score(score)
                .startMs(line.startMs())
                .endMs(line.endMs())
                .reason(reason)
                .frame(line.frame());

        if (line.type() == TimelineEventType.SPEECH) {
            builder.text(line.text());
        } else {
            builder.captionText(line.text());
        }
        return builder.build();
    }

    /**
     * 키워드가 실제로 등장한 줄을 찾는다.
     *
     * LLM 이 알려준 index 를 먼저 확인하고, 그 줄에 키워드 흔적이 없으면 전체를 뒤진다.
     * OCR 이 글자를 틀리게 읽는 경우가 많아서(재선거 → 재선커) 정확히 일치하는지가 아니라
     * 글자가 얼마나 겹치는지로 판단한다.
     */
    private int locate(List<Line> lines, String keyword, Integer suggested) {
        if (suggested != null && suggested >= 0 && suggested < lines.size()
                && overlap(keyword, lines.get(suggested).text()) >= 0.5) {
            return suggested;
        }

        int best = -1;
        double bestScore = 0.4;   // 이보다 낮으면 관련 없다고 본다
        for (int i = 0; i < lines.size(); i++) {
            double score = overlap(keyword, lines.get(i).text());
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** 키워드의 글자 중 몇 퍼센트가 그 줄에 들어 있는지 */
    private double overlap(String keyword, String text) {
        if (keyword == null || text == null || keyword.isBlank()) return 0;
        String k = keyword.replaceAll("[^가-힣a-zA-Z0-9]", "");
        if (k.isEmpty()) return 0;

        int hit = 0;
        for (char c : k.toCharArray()) {
            if (text.indexOf(c) >= 0) hit++;
        }
        return (double) hit / k.length();
    }

    /** 발언과 화면 자막을 하나의 번호 목록으로 합친다. LLM 이 index 로 위치를 지목할 수 있게. */
    private List<Line> collectLines(AnalysisContext context) {
        List<Line> lines = new ArrayList<>();

        if (context.hasTranscript()) {
            for (TranscriptSegment s : context.transcript()) {
                lines.add(new Line(TimelineEventType.SPEECH,
                        s.getStartMs(), s.getEndMs(), s.getText(), null));
            }
        }
        if (context.hasScreenText()) {
            for (ScreenText s : context.screenTexts()) {
                lines.add(new Line(TimelineEventType.CAPTION,
                        s.getStartMs(), s.getEndMs(), s.getText(), s.getFrame()));
            }
        }
        return lines;
    }

    private List<Topic> extractTopics(List<Line> lines) {
        StringBuilder prompt = new StringBuilder("영상의 발언과 화면 자막이다.\n\n");
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            prompt.append("[%d] (%s) %s%n".formatted(
                    i,
                    line.type() == TimelineEventType.SPEECH ? "발언" : "화면자막",
                    line.text()));
        }

        TopicResult result = openAiClient
                .completeAsJson(EXTRACT_PROMPT, prompt.toString(), TopicResult.class)
                .orElse(null);

        return result == null || result.topics() == null ? List.of() : result.topics();
    }

    private Judgement judge(String today, Topic topic, List<NewsSearchClient.NewsItem> news) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("오늘 날짜: ").append(today).append("\n\n");
        prompt.append("영상에 등장한 주제: ").append(topic.keyword()).append("\n");
        if (topic.context() != null) {
            prompt.append("영상에서의 맥락: ").append(topic.context()).append("\n");
        }
        prompt.append("\n최근 뉴스 (최신순):\n");

        for (NewsSearchClient.NewsItem item : news) {
            prompt.append("- (%s) %s%n  %s%n".formatted(
                    item.pubDate() == null ? "날짜미상" : item.pubDate(),
                    item.title(),
                    item.description()));
        }

        return openAiClient.completeAsJson(JUDGE_PROMPT, prompt.toString(), Judgement.class)
                .orElse(null);
    }

    /** 발언·자막을 구분 없이 다루기 위한 내부 표현 */
    private record Line(TimelineEventType type, long startMs, long endMs,
                        String text, VideoFrame frame) {}

    record TopicResult(List<Topic> topics) {}

    record Topic(Integer index, String keyword, String context) {}

    record Judgement(Boolean risky, Double score, String reason, String issue) {}
}
