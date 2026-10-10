package com.example.oops.service;

import com.example.oops.analyzer.ReviewInput;
import com.example.oops.domain.*;
import com.example.oops.dto.ExpressionOccurrenceDto;
import com.example.oops.expression.ExpressionDictionary;
import com.example.oops.repository.ExpressionOccurrenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ExpressionService {
    private final ExpressionDictionary dictionary;
    private final ExpressionOccurrenceRepository repository;
    @Transactional
    public void replace(Video video, List<TranscriptSegment> segments) {
        repository.deleteByVideoId(video.getId());
        List<ExpressionOccurrence> hits = new ArrayList<>();
        for (int i=0; i<segments.size(); i++) {
            var segment=segments.get(i);
            String id=ReviewInput.id(TimelineEventType.SPEECH, segment.getId(), i);
            dictionary.detect(segment.getText()).forEach(h -> hits.add(new ExpressionOccurrence(video, segment, id, h)));
        }
        repository.saveAll(hits);
    }
    @Transactional(readOnly=true)
    public List<ExpressionOccurrenceDto> find(Long videoId) {
        return repository.findByVideoIdOrderByStartMsAscStartOffsetAsc(videoId).stream().map(ExpressionOccurrenceDto::from).toList();
    }
}
