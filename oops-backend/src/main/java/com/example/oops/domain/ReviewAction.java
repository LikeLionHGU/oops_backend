package com.example.oops.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 검토 후보 하나에 대한 제작자의 처리. 명세 §9-2.
 *
 * 이걸 서버에 저장하는 이유:
 *   프론트 화면 상태로만 두면 새로고침하면 사라진다.
 *   그러면 검수를 하다 만 사람이 어디까지 봤는지 알 수 없다.
 *
 *   그리고 우리가 알아야 하는 건 "AI 가 몇 건 찾았나" 가 아니라
 *   "그중 제작자가 실제로 쓸모 있다고 본 게 몇 건인가" 다.
 *   NOT_USEFUL 이 쌓이는 자리가 곧 고쳐야 할 오탐이다.
 *   이 데이터가 없으면 품질을 느낌으로만 말하게 된다.
 */
@Getter
@Entity
@Table(name = "review_action",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_review_action_finding", columnNames = "finding_id"),
        indexes = @Index(name = "idx_review_action_video", columnList = "video_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewAction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id")
    private Video video;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finding_id")
    private RiskFinding finding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private ReviewActionType action;

    /** 무엇을 어떻게 고쳤는지. 선택 */
    @Column(length = 500)
    private String note;

    private ReviewAction(Video video, RiskFinding finding,
                         ReviewActionType action, String note) {
        this.video = video;
        this.finding = finding;
        this.action = action;
        this.note = note;
    }

    public static ReviewAction of(Video video, RiskFinding finding,
                                  ReviewActionType action, String note) {
        return new ReviewAction(video, finding, action, trim(note));
    }

    /** 같은 후보를 다시 처리하면 마지막 것으로 덮는다. 이력은 남기지 않는다. */
    public void update(ReviewActionType action, String note) {
        this.action = action;
        this.note = trim(note);
    }

    private static String trim(String note) {
        if (note == null) return null;
        String v = note.trim();
        if (v.isEmpty()) return null;
        return v.length() <= 500 ? v : v.substring(0, 500);
    }
}
