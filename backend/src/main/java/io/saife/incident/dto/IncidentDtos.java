package io.saife.incident.dto;

import io.saife.core.domain.AccidentType;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.evidence.Evidence;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.domain.ReportStatus;
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
     * <p>필드 순서는 R40 합의를 따른다: {@code incident, reportDuty, recall, followUp, draft,
     * cascade, affectedWorkPlans, similarCases, evidence}. 뒤 두 필드는 B1 Task 5가 덧붙였다 —
     * 대시보드도 함께 쓰는 {@code TimelineDtos.RecallView}에는 넣지 않고(R28) 여기 맨 뒤에 추가만 한다.
     *
     * @param recall            설비 이력 소환. UC2의 핵심. {@code TimelineDtos.RecallView}를 그대로 쓴다 —
     *                          설비 홈의 사전 회상(UC3)과 같은 소환 로직이라 응답 모양도 하나로 둔다.
     *                          {@code knownSlots}가 추가로 실리지만 기존 필드는 그대로라 프론트
     *                          {@code types/incident.ts}는 손대지 않아도 깨지지 않는다(additive)
     * @param reportDuty        법정 제출 기한
     * @param followUp          자동 생성된 수시평가
     * @param draft             조사표 문안 초안
     * @param cascade           "한 사건이 세 곳을 차례로 바꾼다" — 백엔드가 정한 순서 4단계(2-2)
     * @param affectedWorkPlans 이 사고로 경고가 붙은, 같은 설비의 진행 중 작업계획서
     * @param similarCases      동종 유사 사고 사례 최대 3건(사진 우선). 검색 실패 시 빈 리스트
     * @param evidence          {@code similarCases} 3건 + 사고 후 조문 2건. {@code [#n]} 번호는 이 응답
     *                          안에서만 유일하다(대화 원장을 쓰지 않는다) — 조사표 초안의 인용이 이 번호를 쓴다
     */
    public record RegisterResponse(IncidentSummary incident,
                                   ReportDuty reportDuty,
                                   TimelineDtos.RecallView recall,
                                   FollowUpView followUp,
                                   DraftView draft,
                                   List<CascadeStep> cascade,
                                   List<AffectedWorkPlan> affectedWorkPlans,
                                   List<Evidence> similarCases,
                                   List<Evidence> evidence) {}

    /**
     * 사고 연쇄 스텝 하나. 백엔드가 <b>순서를 정한다</b> — 화면마다 다르게 판단하면
     * 시연에서 강조 색이 흔들린다. {@code title}·{@code detail}은 전부 데이터에서 만든다.
     *
     * @param kind    {@code "RECALL" | "FOLLOW_UP" | "REPORT" | "WORK_PLAN"} 중 하나
     * @param refId   이 스텝이 가리키는 대상의 id. 없으면 null
     * @param refType {@code refId}의 종류 — {@code "EQUIPMENT" | "ASSESSMENT" | "INCIDENT" | "WORK_PLAN"}
     */
    public record CascadeStep(int order,
                              String kind,
                              String title,
                              String detail,
                              TimelineDtos.Emphasis emphasis,
                              Long refId,
                              String refType) {}

    /**
     * 사고 영향을 받는 진행 중 작업계획서 한 건 — 같은 설비, 상태
     * SUBMITTED/APPROVED/CONDITIONAL, 작업일이 사고일 이후인 것만 담는다(R48).
     *
     * @param warning 이 사고가 새로 붙인 경고 문구. 작업계획서의 {@code warningNote}에도
     *                같은 문구가 줄바꿈으로 누적된다(기존 경고가 있었다면 그 뒤에)
     */
    public record AffectedWorkPlan(Long workPlanId,
                                   String workName,
                                   LocalDate workDate,
                                   String status,
                                   String warning) {}

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
