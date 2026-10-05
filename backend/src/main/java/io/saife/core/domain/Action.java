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

    /** 이행 내용. 확인자가 적는다 */
    @Column(name = "result_note", columnDefinition = "text")
    private String resultNote;

    /** 이행을 확인한 사람 (직책 이름) */
    @Column(name = "verified_by", length = 100)
    private String verifiedBy;

    /** 개선 후 위험성. 상이면 이행 확인을 받지 않는다 */
    @Enumerated(EnumType.STRING)
    @Column(name = "residual_level", length = 10)
    private RiskLevel residualLevel;

    /** 증빙 사진 대조 결과 (JSON 문자열). 사진이 대책을 보여 주는지 항목별 판정 */
    @Column(name = "photo_check", columnDefinition = "text")
    private String photoCheck;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
        if (this.status == null) {
            this.status = ActionStatus.PENDING;
        }
    }

    /** 증빙 사진을 붙인다. 다시 올리면 앞의 사진과 대조 결과를 바꾼다 */
    public void attachEvidence(String evidencePath, String photoCheck) {
        this.evidencePath = evidencePath;
        this.photoCheck = photoCheck;
    }

    /** 이행 확인. 증빙은 {@link #attachEvidence}로 먼저 붙어 있어야 한다 */
    public void verify(String resultNote, String verifiedBy, RiskLevel residualLevel) {
        this.status = ActionStatus.DONE;
        this.completedAt = OffsetDateTime.now();
        this.resultNote = resultNote;
        this.verifiedBy = verifiedBy;
        this.residualLevel = residualLevel;
    }
}
