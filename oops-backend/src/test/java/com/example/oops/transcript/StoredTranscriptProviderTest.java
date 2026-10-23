package com.example.oops.transcript;

import com.example.oops.domain.*;
import com.example.oops.repository.TranscriptSegmentRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredTranscriptProviderTest {
    @Test void snapshotPreservesWhitespaceTimesAndDetectsChanges() {
        var repository=mock(TranscriptSegmentRepository.class);var provider=new StoredTranscriptProvider(repository);
        var video=Video.builder().sourceType(SourceType.UPLOAD).build();
        org.springframework.test.util.ReflectionTestUtils.setField(video,"id",2L);
        var lines=List.of(new TranscriptProvider.TranscriptLine(100,900," 원문 그대로 "));
        video.assignTranscriptSnapshot(1L,TranscriptSnapshot.sha256(lines));
        when(repository.findByVideoIdOrderByStartMsAscIdAsc(2L)).thenReturn(List.of(new TranscriptSegment(video,100,900," 원문 그대로 ")));
        assertThat(provider.fetch(video)).isEqualTo(lines);
        assertThat(provider.supports(video)).isTrue();
        when(repository.findByVideoIdOrderByStartMsAscIdAsc(2L)).thenReturn(List.of(new TranscriptSegment(video,100,900,"원문 그대로")));
        assertThatThrownBy(()->provider.fetch(video)).isInstanceOf(IllegalStateException.class);
        when(repository.findByVideoIdOrderByStartMsAscIdAsc(2L)).thenReturn(List.of());
        assertThatThrownBy(()->provider.fetch(video)).isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.supports(Video.builder().sourceType(SourceType.YOUTUBE).build())).isFalse();
    }
    @Test void framingDistinguishesOrderTimesAndTextBoundaries() {
        var a=new TranscriptProvider.TranscriptLine(0,1,"ab");var b=new TranscriptProvider.TranscriptLine(1,2,"c");
        assertThat(TranscriptSnapshot.sha256(List.of(a,b))).isNotEqualTo(TranscriptSnapshot.sha256(List.of(b,a)));
        assertThat(TranscriptSnapshot.sha256(List.of(a,b))).isNotEqualTo(TranscriptSnapshot.sha256(List.of(new TranscriptProvider.TranscriptLine(0,1,"a"),new TranscriptProvider.TranscriptLine(1,2,"bc"))));
    }
}
