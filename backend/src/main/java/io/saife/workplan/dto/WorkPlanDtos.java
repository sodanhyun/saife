package io.saife.workplan.dto;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.RiskLevel;
import io.saife.evidence.Evidence;
import io.saife.workplan.domain.WorkPlanStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 작업 전 점검(UC3) DTO. 프론트 {@code src/types/workPlan.ts}와 1:1.
 */
public final class WorkPlanDtos {

    private WorkPlanDtos() {}

    public record ListItem(Long id,
                           Long equipmentId,
                           String equipmentName,
                           String workName,
                           String workPlace,
                           LocalDate workDate,
                           WorkPlanStatus status,
                           boolean briefingAcknowledged,
                           OffsetDateTime briefingAckAt) {}

    /**
     * @param slots       되묻기로 채운 값들. 대장값과 다르면 {@code conflicted}가 참이다
     * @param evidence    createWorkPlan 시점에 대화 원장에 쌓여 있던 근거 카드(번호순). "참고 자료" 그리드가 읽는다
     * @param warningNote 사고 연쇄(UC2)가 붙인 경고(R48). 경고가 없으면 null
     * @param documentType  TBM_CHECKLIST(작업 전 안전점검표) 또는 WORK_PLAN(제38조 작업계획서)
     * @param documentTitle 화면과 서식 제목
     */
    public record Detail(Long id,
                         Long siteId,
                         Long equipmentId,
                         String equipmentName,
                         String conversationId,
                         String workName,
                         String workPlace,
                         LocalDate workDate,
                         BigDecimal workHours,
                         String method,
                         String notes,
                         String briefing,
                         OffsetDateTime briefingAckAt,
                         WorkPlanStatus status,
                         String approvalNote,
                         String approvedBy,
                         OffsetDateTime approvedAt,
                         List<Slot> slots,
                         List<Worker> workers,
                         List<Evidence> evidence,
                         String warningNote,
                         BriefingView briefingView,
                         String documentType,
                         String documentTitle) {}

    /**
     * TBM의 구조화 뷰. 결과 카드(등급 배지 + 판정 근거)와 승인 화면이 읽는다. 브리핑이 없는 초안이면 null.
     * 문장 TBM과 같은 판정에서 나온다({@code BriefingViewBuilder}).
     *
     * @param riskPoints      TBM "위험 포인트" (3개 이내)
     * @param keepPoints      TBM "지킬 것" (3개 이내)
     * @param interimRequired 상 판정이 있고 대책이 미이행이라 승인에 잠정조치 입력이 필요하다(고시 제12조④)
     */
    public record BriefingView(List<PendingAction> pendingActions,
                               List<HazardDecision> decisions,
                               MsdsSummary msds,
                               List<String> riskPoints,
                               List<String> keepPoints,
                               boolean interimRequired) {}

    /** 이 설비에 남아 있는 미이행 조치. overdueDays는 기한이 지났을 때만 값이 있다 */
    public record PendingAction(String content, LocalDate dueDate, Long overdueDays, RiskLevel lastGrade) {}

    /**
     * 발생형태 하나의 판정. ruleTrace가 화면에 그대로 뜬다(평문).
     *
     * @param recommendation 상일 때 먼저 검토할 감소대책(제거, 공학 우선). 없으면 null
     */
    public record HazardDecision(AccidentType accidentType, String label, RiskLevel riskLevel,
                                 short frequency, short severity, String ruleTrace, String recommendation) {}

    /**
     * @param inferred 제품명으로 MSDS를 찾지 못해 주성분을 추정했다. 화면에 "추정 주성분, 제품 MSDS 확인 필요"로 표시한다
     */
    public record MsdsSummary(String chemName, String productName, boolean inferred, List<MsdsLine> lines) {}

    public record MsdsLine(String item, String text) {}

    /**
     * @param ledgerValue   설비 대장이 알고 있던 값
     * @param answeredValue 작업자가 오늘 답한 값
     * @param conflicted    둘이 다르다. <b>오늘 답변을 등급 계산에 쓰고, 차이는 그대로 남긴다</b>
     * @param label         짧은 항목 이름 (발판 높이 등)
     * @param displayValue  단위와 표기를 정리한 값 (3.2 m, 사용, 없음)
     */
    public record Slot(String slotKey,
                       String label,
                       String displayValue,
                       String question,
                       String ledgerValue,
                       String answeredValue,
                       boolean conflicted,
                       OffsetDateTime answeredAt) {}

    public record Worker(Long id, String name, String position, String duty) {}

    public record ApproveRequest(String approver, String condition) {}
}
