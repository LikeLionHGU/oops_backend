package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.expression.ExpressionDictionary;
import com.example.oops.repository.ExpressionOccurrenceRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExpressionServiceTest {
    @Test void replacesPersistedMentionsAndPreservesQuoteAndSegmentTime() {
        var repo=mock(ExpressionOccurrenceRepository.class);
        var service=new ExpressionService(new ExpressionDictionary(), repo);
        var video=Video.builder().filename("test.mp4").build();
        var segment=new TranscriptSegment(video, 1000, 3000, "'허버허버'라는 단어를 설명합니다.");
        when(repo.saveAll(anyList())).thenAnswer(call -> {
            List<ExpressionOccurrence> hits=call.getArgument(0);
            assertThat(hits).hasSize(1);
            var h=hits.get(0);
            assertThat(h.getMatchedText()).isEqualTo("허버허버");
            assertThat(h.getStartMs()).isEqualTo(1000);
            assertThat(h.getEndMs()).isEqualTo(3000);
            assertThat(h.getSegmentId()).isEqualTo("stt-index-0");
            return hits;
        });
        service.replace(video, List.of(segment));
        var order=inOrder(repo);
        order.verify(repo).deleteByVideoId(video.getId());
        order.verify(repo).saveAll(anyList());
    }
}
