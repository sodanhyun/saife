package io.saife.incident.dto;

import io.saife.core.domain.AccidentType;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.domain.ReportStatus;
import io.saife.incident.service.EquipmentHistoryRecaller;
import io.saife.incident.service.FollowUpAssessmentService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * UC2 요청·응답 DTO.
 *
 * <p>프론트 {@code src/types/incident.ts}와 1:1로 맞춘다. 필드명은 camelCase 그대로다.
 * 규칙: {@code .claude/rules/api-contract.md}
 */
public final class IncidentDtos {

    private IncidentDtos() {}

    /**
     * 사고 등록 요청.
     *
     * <p>{@code equipmentId}를 모르면 {@code equipmentQuery}에 설비명이나 장소를 넣는다.
     * 설비 매칭은 UC3과 같은 매처를 쓴다 — <b>설비 ID가 쪼개지면 이력이 끊긴다.</b>
     */
    public record RegisterRequest(Long equipmentId,
                                  String equipmentQuery,
                                  Long workPlanId,
                                  OffsetDateTime occurredAt,
                                  String victimName,
                                  IncidentSeverity severity,
                                  Integer leaveDays,
                                  AccidentType accidentType,
                                  String description) {}

    /**
     * 등록 결과 — 화면 한 장이 이 응답 하나로 그려진다.
     *
     * @param recall    설비 이력 소환. UC2의 핵심
     * @param reportDuty 법정 제출 기한
     * @param followUp  자동 생성된 수시평가
     * @param draft     조사표 문안 초안
     */
    public record RegisterResponse(IncidentSummary incident,
                                   ReportDuty reportDuty,
                                   RecallView recall,
                                   FollowUpView followUp,
                                   DraftView draft) {}

    public record IncidentSummary(Long id,
                                  Long siteId,
                                  Long equipmentId,
                                  String equipmentName,
                                  Long workPlanId,
                                  OffsetDateTime occurredAt,
                                  String victimName,
                                  IncidentSeverity severity,
                                  Integer leaveDays,
                                  AccidentType accidentType,
                                  String description,
                                  Long followUpAssessmentId) {}

    /**
     * 법정 제출 기한.
     *
     * @param daysRemaining 남은 일수. 음수면 지났다
     * @param basis         판단 근거. 화면에 그대로 띄운다
     */
    public record ReportDuty(ReportStatus status,
                             String statusLabel,
                             LocalDate dueDate,
                             Long daysRemaining,
                             String basis) {}

    public record RecallView(Long equipmentId,
                             String equipmentName,
                             String locationTag,
                             String headline,
                             boolean predicted,
                             OffsetDateTime warnedAt,
                             List<EquipmentHistoryRecaller.PriorHazard> priorHazards,
                             List<EquipmentHistoryRecaller.UnfinishedAction> unfinishedActions,
                             List<EquipmentHistoryRecaller.PriorWorkPlan> priorWorkPlans,
                             List<EquipmentHistoryRecaller.PriorIncident> priorIncidents) {

        public static RecallView from(EquipmentHistoryRecaller.Recall r) {
            return new RecallView(r.equipmentId(), r.equipmentName(), r.locationTag(),
                    r.headline(), r.predicted(), r.warnedAt(),
                    r.priorHazards(), r.unfinishedActions(),
                    r.priorWorkPlans(), r.priorIncidents());
        }
    }

    /** @param kindLabel "수시" — 심사위원이 평가 종류를 바로 읽을 수 있어야 한다 */
    public record FollowUpView(Long assessmentId,
                               String kindLabel,
                               String legalBasis,
                               List<FollowUpAssessmentService.Regrade> regraded,
                               Long newHazardId) {}

    /** @param aiGenerated false면 모델이 아니라 폴백이 쓴 문안이다. 화면에 구분해 표시한다 */
    public record DraftView(String cause, String prevention, boolean aiGenerated, String disclaimer) {}

    /** 목록·타이머 화면용 */
    public record IncidentListItem(Long id,
                                   Long equipmentId,
                                   String equipmentName,
                                   OffsetDateTime occurredAt,
                                   AccidentType accidentType,
                                   IncidentSeverity severity,
                                   Integer leaveDays,
                                   ReportStatus reportStatus,
                                   String reportStatusLabel,
                                   LocalDate reportDueDate,
                                   Long daysRemaining,
                                   Long followUpAssessmentId) {}
}
