package io.saife.workplan.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 위험작업 작업계획서 (UC3) — 시연의 주인공.
 *
 * <p>{@code briefingAckAt}이 상시평가 트랙의 TBM 요건을 충족한다.
 * 구두 TBM과 달리 확인 기록이 남으므로 증빙이 더 강하다.
 * <b>이 컬럼을 지우면 상시평가 트랙 주장이 무너진다.</b>
 */
@Entity
@Table(name = "work_plan")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WorkPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    /**
     * 이 초안을 만든 대화. 되묻기로 같은 도구가 여러 번 호출돼도
     * 한 대화가 계획서 하나만 만들도록 묶는 키다.
     */
    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "process_id")
    private Long processId;

    @Column(name = "equipment_id")
    private Long equipmentId;

    @Column(name = "work_name", nullable = false, length = 200)
    private String workName;

    @Column(name = "work_place", length = 200)
    private String workPlace;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "work_hours", precision = 4, scale = 1)
    private BigDecimal workHours;

    /** 작업순서 및 작업방법 */
    @Column(columnDefinition = "text")
    private String method;

    /** 전달사항 */
    @Column(columnDefinition = "text")
    private String notes;

    /** 생성된 위험 브리핑. TBM의 디지털 구현체 */
    @Column(columnDefinition = "text")
    private String briefing;

    /** 작업자 브리핑 확인 시각 */
    @Column(name = "briefing_ack_at")
    private OffsetDateTime briefingAckAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkPlanStatus status;

    /** 조건부 승인 시 조건 */
    @Column(name = "approval_note", columnDefinition = "text")
    private String approvalNote;

    /**
     * 사고 연쇄(UC2)가 붙인 경고 (R48, V8 컬럼). {@code approvalNote}와는 별개 컬럼이다 —
     * 승인 조건과 사고 경고를 한 칸에 섞으면 승인자가 조건을 다시 읽을 때 경고 문구가 끼어든다.
     */
    @Column(name = "warning_note", columnDefinition = "text")
    private String warningNote;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
        if (this.status == null) {
            this.status = WorkPlanStatus.DRAFT;
        }
    }

    public void submit() {
        this.status = WorkPlanStatus.SUBMITTED;
    }

    public void approve(String approver, String condition) {
        this.status = (condition == null || condition.isBlank())
                ? WorkPlanStatus.APPROVED
                : WorkPlanStatus.CONDITIONAL;
        this.approvedBy = approver;
        this.approvalNote = condition;
        this.approvedAt = OffsetDateTime.now();
    }

    public void acknowledgeBriefing() {
        this.briefingAckAt = OffsetDateTime.now();
    }

    /** 산업재해 발생 후 수시평가가 끝날 때까지 작업을 보류한다(시행규칙 제37조제2항제3호) */
    public void hold() {
        this.status = WorkPlanStatus.HOLD;
    }

    public void close() {
        this.status = WorkPlanStatus.CLOSED;
        this.closedAt = OffsetDateTime.now();
    }

    public void attachBriefing(String briefing) {
        this.briefing = briefing;
    }

    /**
     * 사고 연쇄가 경고를 붙인다. 기존 경고가 있으면 줄바꿈으로 이어붙인다 (R48) —
     * 이전 사고가 남긴 경고를 지우지 않는다. 같은 설비에서 사고가 반복되면 전부 남아야
     * "이 작업계획서가 몇 번째 경고인가"를 승인자가 읽을 수 있다.
     */
    public void appendWarning(String text) {
        this.warningNote = (this.warningNote == null || this.warningNote.isBlank())
                ? text
                : this.warningNote + "\n" + text;
    }
}
