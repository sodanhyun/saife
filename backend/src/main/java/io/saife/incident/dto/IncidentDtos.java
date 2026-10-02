package io.saife.incident.dto;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.ActionStatus;
import io.saife.core.domain.RiskLevel;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.evidence.Evidence;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.domain.IncidentType;
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
     *
     * @param incidentType 사고 발생형태(공단 분류). 필수
     * @param severity     재해 정도. 필수. 휴업이면 {@code leaveDays}(휴업예상일수)도 필수다
     * @param injuryType   상해 종류(질병명). 조사표 항목, 선택
     * @param injuryPart   상해 부위(질병 부위). 조사표 항목, 선택
     */
    public record RegisterRequest(Long equipmentId,
                                  String equipmentQuery,
                                  Long workPlanId,
                                  OffsetDateTime occurredAt,
                                  String victimName,
                                  IncidentSeverity severity,
                                  Integer leaveDays,
                                  IncidentType incidentType,
                                  String description,
                                  String injuryType,
                                  String injuryPart) {}

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
     * @param affectedWorkPlans 이 사고로 작업 보류된, 같은 설비의 진행 중 작업 전 점검
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
     * 사고로 작업 보류(HOLD)된 작업 전 점검 한 건. 같은 설비, 사고 당시 상태
     * SUBMITTED/APPROVED/CONDITIONAL, 작업일이 사고일 이후인 것만 담는다(R48).
     * 수시평가(시행규칙 제37조제2항제3호)가 끝나기 전에는 작업을 재개하지 않는다.
     *
     * @param warning 이 사고가 새로 붙인 보류 문구. 작업계획서의 {@code warningNote}에도
     *                같은 문구가 줄바꿈으로 누적된다(기존 경고가 있었다면 그 뒤에)
     */
    public record AffectedWorkPlan(Long workPlanId,
                                   String workName,
                                   LocalDate workDate,
                                   String status,
                                   String warning) {}

    /**
     * @param incidentType 사고 발생형태(공단 분류). 화면 표기는 {@code incidentTypeLabel}
     * @param accidentType 위험요인 6축 대응(사고 전 기록과 잇는 값). 대응이 없으면 null
     */
    public record IncidentSummary(Long id,
                                  Long siteId,
                                  Long equipmentId,
                                  String equipmentName,
                                  Long workPlanId,
                                  OffsetDateTime occurredAt,
                                  String victimName,
                                  IncidentSeverity severity,
                                  Integer leaveDays,
                                  IncidentType incidentType,
                                  String incidentTypeLabel,
                                  AccidentType accidentType,
                                  String description,
                                  String injuryType,
                                  String injuryPart,
                                  Long followUpAssessmentId) {}

    /**
     * 법정 제출 기한.
     *
     * @param daysRemaining           남은 일수. 음수면 지났다
     * @param basis                   판단 근거. 화면에 그대로 띄운다
     * @param seriousAccidentPossible 사망 재해라 중대재해에 해당할 수 있다. 판정이 아니라 "지체 없이
     *                                관할 지방고용노동관서 보고" 안내를 띄우기 위한 표시다
     */
    public record ReportDuty(ReportStatus status,
                             String statusLabel,
                             LocalDate dueDate,
                             Long daysRemaining,
                             String basis,
                             boolean seriousAccidentPossible,
                             LocalDate submittedOn) {}

    /**
     * @param kindLabel   "수시" — 심사위원이 평가 종류를 바로 읽을 수 있어야 한다
     * @param status      {@code "DRAFT" | "CONFIRMED"}
     * @param confirmedOn 확정일. 작성 중이면 null
     */
    public record FollowUpView(Long assessmentId,
                               String kindLabel,
                               String legalBasis,
                               List<FollowUpAssessmentService.Regrade> regraded,
                               Long newHazardId,
                               String status,
                               LocalDate confirmedOn) {}

    /** @param aiGenerated false면 모델이 아니라 폴백이 쓴 문안이다. 화면에 구분해 표시한다 */
    public record DraftView(String cause, String prevention, boolean aiGenerated, String disclaimer) {}

    /** 목록·타이머 화면용 */
    public record IncidentListItem(Long id,
                                   Long equipmentId,
                                   String equipmentName,
                                   OffsetDateTime occurredAt,
                                   IncidentType incidentType,
                                   String incidentTypeLabel,
                                   IncidentSeverity severity,
                                   Integer leaveDays,
                                   ReportStatus reportStatus,
                                   String reportStatusLabel,
                                   LocalDate reportDueDate,
                                   Long daysRemaining,
                                   LocalDate reportSubmittedOn,
                                   Long followUpAssessmentId) {}

    // ────────────────────────── 수시평가 (사고 후, 작업 재개 전) ──────────────────────────

    /**
     * 사고가 만든 수시평가 한 건. 프론트 {@code /assessment/:id} 화면이 이 응답 하나로 그려진다.
     *
     * @param status      {@code "DRAFT" | "CONFIRMED"}
     * @param confirmedOn 확정일. 작성 중이면 null
     * @param workPlans   이 사고로 작업 보류된 작업 전 점검. 확정하면 재승인 대기(SUBMITTED)로 돌아간다
     */
    public record FollowUpDetail(Long assessmentId,
                                 Long incidentId,
                                 String status,
                                 LocalDate assessedOn,
                                 LocalDate confirmedOn,
                                 Long equipmentId,
                                 String equipmentName,
                                 String locationTag,
                                 OffsetDateTime occurredAt,
                                 IncidentType incidentType,
                                 String incidentTypeLabel,
                                 IncidentSeverity severity,
                                 String legalBasis,
                                 String inspector,
                                 List<String> participants,
                                 List<FollowUpHazard> hazards,
                                 List<AffectedWorkPlan> workPlans) {}

    /**
     * 수시평가의 위험요인 한 건.
     *
     * @param before      사고 전 등급. 신규면 null
     * @param acceptable  허용 가능 여부. 사람이 정하지 않았으면 등급 기본값(상, 중은 불가)
     * @param action      이 수시평가에서 세운 개선대책. 없으면 null
     * @param priorAction 다른 평가에서 세운, 아직 끝나지 않은 대책(가장 이른 기한)
     * @param suggestion  감소대책 초안(기준표). 없으면 null
     */
    public record FollowUpHazard(Long hazardId,
                                 AccidentType accidentType,
                                 String missingControl,
                                 String description,
                                 RiskLevel before,
                                 RiskLevel riskLevel,
                                 String ruleTrace,
                                 boolean sameAxis,
                                 boolean acceptable,
                                 FollowUpAction action,
                                 FollowUpAction priorAction,
                                 FollowUpSuggestion suggestion) {}

    public record FollowUpAction(Long actionId, String content, String owner, LocalDate dueDate,
                                 ActionStatus status, LocalDate completedOn) {}

    /** @param lawRef 근거 조문(예: "산업안전보건기준에 관한 규칙 제42조제1항") */
    public record FollowUpSuggestion(String content, String lawRef) {}

    /**
     * 수시평가 저장, 확정 요청.
     *
     * @param hazards 위험요인별 허용 여부와 개선대책. 대책 칸을 비우면 새로 만들지 않는다
     */
    public record FollowUpRequest(String inspector, List<String> participants, List<FollowUpHazardInput> hazards) {}

    public record FollowUpHazardInput(Long hazardId, Boolean acceptable, String content, String owner, LocalDate dueDate) {}
}
