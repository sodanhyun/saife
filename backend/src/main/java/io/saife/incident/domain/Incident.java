package io.saife.incident.domain;

import io.saife.core.domain.AccidentType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 산업재해 (UC2) — <b>루프가 닫히는 곳.</b>
 *
 * <p>{@code followUpAssessmentId}가 이 출품작의 논지를 증명하는 필드다.
 * 사고가 나면 같은 설비의 이력이 소환되고 수시평가가 자동으로 생긴다.
 * 위험성평가 → 작업계획서 → 사고 → 재평가가 <b>하나의 설비 ID</b> 위에서 이어진다.
 * <b>이 필드를 지우면 출품작의 논지가 증명되지 않는다.</b>
 *
 * <p>제출 기한은 {@code leaveDays >= 3} 하나로 결정한다. 중대재해 즉시보고 판정은
 * 의도적으로 구현하지 않았다 — {@link IncidentSeverity} 참조.
 */
@Entity
@Table(name = "incident")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Incident {

    /** 산업재해조사표 제출 의무가 생기는 휴업일수 (시행규칙 제73조) */
    public static final int REPORTABLE_LEAVE_DAYS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    /** 사고가 난 설비. 이력 소환의 키다 */
    @Column(name = "equipment_id")
    private Long equipmentId;

    /** 사고 당시 수행 중이던 작업계획서. 있으면 "경고했는데 일어났다"가 성립한다 */
    @Column(name = "work_plan_id")
    private Long workPlanId;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "victim_name", length = 100)
    private String victimName;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private IncidentSeverity severity;

    @Column(name = "leave_days")
    private Integer leaveDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "accident_type", length = 30)
    private AccidentType accidentType;

    @Column(columnDefinition = "text")
    private String description;

    /** 재해발생 원인. AI 초안이되 확정은 사람이 한다 */
    @Column(columnDefinition = "text")
    private String cause;

    /** 재발방지 계획. AI 초안이되 확정은 사람이 한다 */
    @Column(columnDefinition = "text")
    private String prevention;

    @Column(name = "report_due_date")
    private LocalDate reportDueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_status", nullable = false, length = 20)
    private ReportStatus reportStatus;

    /** 자동 생성된 수시평가. 재해 발생 작업은 재개 전 수시평가 대상이다 */
    @Column(name = "follow_up_assessment_id")
    private Long followUpAssessmentId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
        if (this.reportStatus == null) {
            this.reportStatus = ReportStatus.NOT_REQUIRED;
        }
    }

    /**
     * 제출 의무와 기한을 결정한다.
     *
     * <p>휴업 3일 이상이면 발생일로부터 1개월. 날짜 계산이라 구현이 거의 공짜인데,
     * "법적 의무"를 화면으로 증명하는 건 이것뿐이다.
     */
    public void decideReportDuty() {
        if (leaveDays == null) {
            // 모르는 것을 "의무 없음"으로 읽지 않는다.
            // 기본값이 법정 의무를 조용히 면제하는 설계는 위험하다.
            this.reportDueDate = null;
            this.reportStatus = ReportStatus.UNDETERMINED;
            return;
        }
        if (leaveDays >= REPORTABLE_LEAVE_DAYS) {
            this.reportDueDate = occurredAt.toLocalDate().plusMonths(1);
            this.reportStatus = ReportStatus.REQUIRED;
        } else {
            this.reportDueDate = null;
            this.reportStatus = ReportStatus.NOT_REQUIRED;
        }
    }

    /** 기한까지 남은 일수. 음수면 지났다. 의무가 없으면 null */
    public Long daysUntilDue(LocalDate today) {
        if (reportDueDate == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(today, reportDueDate);
    }

    /** 기한이 지났으면 상태를 올린다. 제출 완료된 건은 건드리지 않는다 */
    public void refreshOverdue(LocalDate today) {
        if (reportStatus == ReportStatus.REQUIRED
                && reportDueDate != null && today.isAfter(reportDueDate)) {
            this.reportStatus = ReportStatus.OVERDUE;
        }
    }

    public void attachFollowUpAssessment(Long assessmentId) {
        this.followUpAssessmentId = assessmentId;
    }

    /** AI가 쓴 초안. 사람이 고쳐 확정한다 */
    public void attachDraft(String cause, String prevention) {
        this.cause = cause;
        this.prevention = prevention;
    }

    public void markSubmitted() {
        this.reportStatus = ReportStatus.SUBMITTED;
    }
}
