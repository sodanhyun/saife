package io.saife.workplan.dto;

import io.saife.evidence.Evidence;
import io.saife.workplan.domain.WorkPlanStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * UC3 작업계획서 DTO. 프론트 {@code src/types/workPlan.ts}와 1:1.
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
                         String warningNote) {}

    /**
     * @param ledgerValue   설비 대장이 알고 있던 값
     * @param answeredValue 작업자가 오늘 답한 값
     * @param conflicted    둘이 다르다. <b>오늘 답변을 등급 계산에 쓰고, 차이는 그대로 남긴다</b>
     */
    public record Slot(String slotKey,
                       String question,
                       String ledgerValue,
                       String answeredValue,
                       boolean conflicted,
                       OffsetDateTime answeredAt) {}

    public record Worker(Long id, String name, String position, String duty) {}

    public record ApproveRequest(String approver, String condition) {}
}
