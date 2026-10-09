package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import com.example.oops.news.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Grounding/plumbing only: does not prove semantic entailment or real-model accuracy. */
class ClaimExtractionTest {
    private final OpenAiClient client = mock(OpenAiClient.class);
    private final EntityCheckAnalyzer analyzer = new EntityCheckAnalyzer(client, List.of(), mock(SourceClassifier.class));
    private final Video video = Video.builder().filename("offline.mp4").build();
    private EntityCheckAnalyzer.Claim claim(int i, String raw) {
        return new EntityCheckAnalyzer.Claim(i, raw, "회사", "DATE", List.of("회사 설립 연도 공식 발표"),
                List.of(new EntityCheckAnalyzer.ClaimEvidence(i, raw)), "QUALIFIED", "2019년",
                "공개 설립 연도에 대한 원문 추측을 자료와 대조할 수 있습니다.");
    }
    @SuppressWarnings("unchecked")
    private List<EntityCheckAnalyzer.Claim> extract(List<TranscriptSegment> transcript) throws Exception {
        var method = EntityCheckAnalyzer.class.getDeclaredMethod("extractClaims", List.class);
        method.setAccessible(true);
        return (List<EntityCheckAnalyzer.Claim>) method.invoke(analyzer, transcript);
    }
    @Test void qualifiedClaimKeepsRawEvidenceAndTime() {
        String raw = "아마 회사는 2019년에 설립됐을 거야";
        var c = claim(0, raw);
        assertThat(EntityCheckAnalyzer.validClaim(c, List.of(new TranscriptSegment(video, 0, 1000, raw)))).isTrue();
        assertThat(c.claim()).contains("아마", "거야");
    }
    @Test void inventedQuoteWrongIndexUnknownTypeAndLegacyShapeAreRejected() {
        var transcript = List.of(new TranscriptSegment(video, 0, 1000, "회사는 2019년에 설립됐다"));
        assertThat(EntityCheckAnalyzer.validClaim(claim(0, "회사 2020년 설립"), transcript)).isFalse();
        assertThat(EntityCheckAnalyzer.validClaim(claim(9, "회사는 2019년에 설립됐다"), transcript)).isFalse();
        var c = claim(0, transcript.get(0).getText());
        assertThat(EntityCheckAnalyzer.validClaim(new EntityCheckAnalyzer.Claim(0, c.claim(), c.subject(), "UNKNOWN",
                c.searchQueries(), c.evidence(), c.assertionMode(), c.timeReference(), c.selectionReason()), transcript)).isFalse();
        assertThat(EntityCheckAnalyzer.validClaim(new EntityCheckAnalyzer.Claim(0, "회사 설립", "회사",
                "DATE", List.of("검색")), transcript)).isFalse();
    }
    @Test void fabricatedSubjectAndConvertedRelativeTimeAreRejected() {
        String raw = "회사는 3년 전에 설립됐다";
        var transcript = List.of(new TranscriptSegment(video, 0, 1000, raw));
        var evidence = List.of(new EntityCheckAnalyzer.ClaimEvidence(0, raw));
        assertThat(EntityCheckAnalyzer.validClaim(new EntityCheckAnalyzer.Claim(0, raw, "회사", "DATE",
                List.of("회사 설립"), evidence, "ASSERTION", "2023년", "설립 시점 대조"), transcript)).isFalse();
        assertThat(EntityCheckAnalyzer.validClaim(new EntityCheckAnalyzer.Claim(0, raw, "원문에 없는 기업", "DATE",
                List.of("회사 설립"), evidence, "ASSERTION", "3년 전", "설립 시점 대조"), transcript)).isFalse();
    }
    @Test void jsonInputAndLimitApplyAfterValidation() throws Exception {
        var transcript = java.util.stream.IntStream.range(0, 8).mapToObj(i ->
                new TranscriptSegment(video, i * 1000, i * 1000 + 500,
                        "회사" + i + "는 2019년에 설립됐을 거야")).toList();
        var claims = new ArrayList<EntityCheckAnalyzer.Claim>();
        claims.add(null);
        for (int i = 0; i < 8; i++) claims.add(claim(i, transcript.get(i).getText()));
        claims.add(claim(0, transcript.get(0).getText()));
        when(client.completeAsJson(anyString(), anyString(), eq(EntityCheckAnalyzer.ClaimResult.class)))
                .thenReturn(Optional.of(new EntityCheckAnalyzer.ClaimResult(claims)));
        assertThat(extract(transcript)).hasSize(6);
        assertThat(analyzer.consumeCoverageNotice(null)).hasValueSatisfying(s ->
                assertThat(s).contains("1건은 원문/형식 검증 실패", "2건은 검증 한도"));
        var system = ArgumentCaptor.forClass(String.class);
        var user = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(system.capture(), user.capture(), eq(EntityCheckAnalyzer.ClaimResult.class));
        var input = JsonMapper.builder().build().readTree(user.getValue());
        assertThat(input.get("recordedAtKnown").asBoolean()).isFalse();
        assertThat(input.get("segments").get(0).get("text").asText()).isEqualTo(transcript.get(0).getText());
        assertThat(system.getValue()).contains("검색어 1~2개", "추측·전언·조건·부정·범위 한정",
                "현재 화자의 사적 감정·개인 경험", "분석 날짜를 촬영 날짜로 사용하지 않는다");
    }
    @Test void extractionFailureIsVisibleNotEquivalentToNoClaims() throws Exception {
        when(client.completeAsJson(anyString(), anyString(), eq(EntityCheckAnalyzer.ClaimResult.class)))
                .thenReturn(Optional.empty());
        assertThat(extract(List.of())).isEmpty();
        assertThat(analyzer.consumeCoverageNotice(null)).hasValueSatisfying(s -> assertThat(s).contains("추출에 실패"));
    }
    @Test void queryCountsAndDuplicateQueriesAreValidated() {
        String raw = "회사는 2019년에 설립됐다";
        var transcript = List.of(new TranscriptSegment(video, 0, 1000, raw));
        var c = claim(0, raw);
        for (var queries : List.of(List.<String>of(), List.of("동일", "동일"), List.of("1", "2", "3"))) {
            assertThat(EntityCheckAnalyzer.validClaim(new EntityCheckAnalyzer.Claim(0, raw, "회사", "DATE",
                    queries, c.evidence(), "ASSERTION", "2019년", "설립 연도 확인"), transcript)).isFalse();
        }
    }
}
