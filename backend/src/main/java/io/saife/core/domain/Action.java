package io.saife.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 감소대책 / 조치.
 *
 * <p>미이행 조치가 UC3 브리핑에서 경고로 소환된다 —
 * "3개월 전 평가에서 '상'이었고 안전대 부착설비 설치가 미이행입니다".
 */
@Entity
@Table(name = "action")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Action {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hazard_id", nullable = false)
    private Long hazardId;

    @Column(name = "assessment_id")
    private Long assessmentId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(length = 100)
    private String owner;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActionStatus status;

    @Column(name = "evidence_path", length = 500)
    private String evidencePath;

    /** KOSHA GUIDE 규정번호 (techGdlnNo) */
    @Column(name = "guide_ref", length = 200)
    private String guideRef;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
        if (this.status == null) {
            this.status = ActionStatus.PENDING;
        }
    }

    public void complete(String evidencePath) {
        this.status = ActionStatus.DONE;
        this.completedAt = OffsetDateTime.now();
        this.evidencePath = evidencePath;
    }
}
