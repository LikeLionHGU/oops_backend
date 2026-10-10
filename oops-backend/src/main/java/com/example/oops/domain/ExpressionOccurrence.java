package com.example.oops.domain;

import com.example.oops.expression.ExpressionDictionary;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Entity
@Table(name="expression_occurrence", indexes=@Index(name="idx_expression_video", columnList="video_id"))
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ExpressionOccurrence {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="video_id") private Video video;
    @Column(nullable=false, length=64) private String expressionId;
    @Column(nullable=false, length=40) private String category;
    @Column(nullable=false, length=80) private String matchedText;
    @Column(nullable=false, length=80) private String segmentId;
    @Column(nullable=false) private long startMs;
    @Column(nullable=false) private long endMs;
    @Column(nullable=false) private int startOffset;
    @Column(nullable=false) private int endOffset;
    @Column(nullable=false, length=80) private String dictionaryVersion;
    @Column(length=300) private String commonUsageNote;
    public ExpressionOccurrence(Video video, TranscriptSegment segment, String segmentId, ExpressionDictionary.Hit hit) {
        this.video=video; this.segmentId=segmentId; this.expressionId=hit.expressionId();
        this.category=hit.category(); this.matchedText=hit.matchedText(); this.startMs=segment.getStartMs();
        this.endMs=segment.getEndMs(); this.startOffset=hit.startOffset(); this.endOffset=hit.endOffset();
        this.dictionaryVersion=hit.dictionaryVersion(); this.commonUsageNote=hit.commonUsageNote();
    }
}
