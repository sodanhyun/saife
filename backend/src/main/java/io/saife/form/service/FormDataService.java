package io.saife.form.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.core.domain.*;
import io.saife.core.repository.*;
import io.saife.evidence.Evidence;
import io.saife.evidence.domain.WorkPlanEvidence;
import io.saife.evidence.repository.WorkPlanEvidenceRepository;
import io.saife.form.dto.FormViews;
import io.saife.incident.domain.Incident;
import io.saife.incident.repository.IncidentRepository;
import io.saife.incident.service.EquipmentHistoryRecaller;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.repository.WorkPlanSlotRepository;
import io.saife.workplan.repository.WorkPlanWorkerRepository;
import io.saife.common.error.ApiExceptions.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 법정 서식에 들어갈 값을 모은다.
 *
 * <p>템플릿에서 계산하지 않는다. 서식은 법정 요건이라 표시 로직이 값을 바꾸면 안 되고,
 * 무엇보다 <b>화면과 출력물이 다른 값을 말하면 그 순간 신뢰를 잃는다.</b>
 *
 * <p>모든 서식에 "작성 보조" 고지를 넣는다. 법률 자문이 아니고 최종 확정·제출은 사람이 한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FormDataService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 서식의 모든 시각은 한국 시간으로 찍는다.
     *
     * <p>DB의 {@code timestamptz}를 Hibernate가 UTC 오프셋으로 돌려주므로
     * {@code OffsetDateTime.format()}을 그냥 쓰면 <b>9시간 틀린 시각이 법정 문서에 박힌다.</b>
     * 실제로 14:20 사고가 조사표에 05:20으로 찍혔다 (2026-09-20 실측).
     * API 응답은 Jackson이 변환해 주지만 서식은 직접 변환해야 한다.
     */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final String AI_NOTICE =
            "이 문서는 SAIFE의 작성 보조 결과를 포함합니다. 법률 자문이 아니며, "
                    + "최종 확정과 제출은 사업장 담당자가 합니다. "
                    + "위험성 등급은 생성형 모델이 아니라 사전 정의된 규칙 엔진이 판정했습니다.";

    private static final String RETENTION_NOTICE =
            "산업안전보건법 시행규칙 제37조에 따라 유해위험요인, 위험성 결정 내용, "
                    + "조치 내용을 포함하여 3년간 보존합니다.";

    private static final String TBM_NOTICE =
            "작업자의 브리핑 확인 기록은 고용노동부고시 제2023-19호 상시평가 요건 중 "
                    + "매 작업일 TBM 실시의 증빙으로 보존합니다.";

    private final SiteRepository siteRepository;
    private final EquipmentRepository equipmentRepository;
    private final ProcessRepository processRepository;
    private final HazardRepository hazardRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final ActionRepository actionRepository;
    private final IncidentRepository incidentRepository;
    private final WorkPlanRepository workPlanRepository;
    private final WorkPlanSlotRepository workPlanSlotRepository;
    private final WorkPlanWorkerRepository workPlanWorkerRepository;
    private final WorkPlanEvidenceRepository workPlanEvidenceRepository;
    private final ObjectMapper objectMapper;
    private final EquipmentHistoryRecaller recaller;

    // ---------- 위험성평가표 ----------

    @Transactional(readOnly = true)
    public FormViews.AssessmentForm assessmentForm(Long assessmentId) {
        Assessment assessment = assessmentRepository.findById(assessmentId).orElseThrow(
                () -> new NotFoundException("평가를 찾을 수 없습니다: " + assessmentId));
        Site site = siteRepository.findById(assessment.getSiteId()).orElse(null);

        AtomicInteger no = new AtomicInteger(1);
        List<FormViews.AssessmentRow> rows = new ArrayList<>();
        int rejected = 0;

        for (AssessmentHazard link : assessmentHazardRepository.findByAssessmentId(assessmentId)) {
            Hazard hazard = hazardRepository.findById(link.getHazardId()).orElse(null);
            if (hazard == null) {
                continue;
            }
            // 사람이 반려한 AI 후보는 유해위험요인이 아니다. 서식에 올리지 않고 건수만 고지한다
            if (hazard.isAiSuggested() && Boolean.FALSE.equals(hazard.getAiAdopted())) {
                rejected++;
                continue;
            }
            Action action = actionFor(hazard.getId(), assessmentId);

            rows.add(new FormViews.AssessmentRow(
                    no.getAndIncrement(),
                    equipmentName(hazard.getEquipmentId()),
                    hazard.getAccidentType() == null ? "-" : hazard.getAccidentType().getLabel(),
                    // ① 유해·위험요인
                    nvl(hazard.getMissingControl(), hazard.getDescription()),
                    // ② 위험성 결정 내용 — 등급만 적으면 요건을 못 채운다. 근거를 같이 적는다
                    decisionText(link),
                    riskLabel(link.getRiskLevel()),
                    // ③ 조치 내용
                    action == null ? "(등록된 감소대책 없음)" : action.getContent(),
                    action == null ? null : action.getOwner(),
                    action == null ? null : action.getDueDate(),
                    action == null ? null : actionStatusLabel(action.getStatus()),
                    hazard.isAiSuggested(),
                    adoptedLabel(hazard.getAiAdopted()),
                    action == null ? null : action.getGuideRef(),
                    action == null || action.getCompletedAt() == null ? null : format(action.getCompletedAt())));
        }

        String aiNotice = rejected == 0 ? AI_NOTICE
                : AI_NOTICE + " AI 후보 중 사람이 반려한 %d건은 이 표에서 제외했습니다.".formatted(rejected);

        return new FormViews.AssessmentForm(
                site == null ? "-" : site.getName(),
                "(가상 사업장)",
                "(가상 대표자)",
                assessment.getKind().getLabel(),
                triggerLabel(assessment.getTriggerType(), assessment.getTriggerRefId()),
                assessment.getAssessedOn(),
                nvl(assessment.getParticipants(), "-"),
                assessmentStatusLabel(assessment.getStatus()),
                rows, RETENTION_NOTICE, aiNotice);
    }

    /**
     * ③ 조치 내용에 넣을 조치. <b>이 평가에서 등록한 조치</b>가 우선이고, 없으면 그 위험요인의
     * 미이행 조치, 그것도 없으면 가장 최근 조치. 다른 평가의 조치를 아무거나 집으면
     * 사진 점검에서 막 등록한 대책 대신 몇 달 전 대책이 서식에 찍힌다.
     */
    private Action actionFor(Long hazardId, Long assessmentId) {
        List<Action> actions = actionRepository.findByHazardId(hazardId);
        return actions.stream()
                .filter(a -> assessmentId.equals(a.getAssessmentId()))
                .max(java.util.Comparator.comparing(Action::getId))
                .or(() -> actions.stream()
                        .filter(a -> a.getStatus() != ActionStatus.DONE)
                        .max(java.util.Comparator.comparing(Action::getId)))
                .or(() -> actions.stream().max(java.util.Comparator.comparing(Action::getId)))
                .orElse(null);
    }

    /**
     * ② 위험성 결정 내용.
     *
     * <p>등급 글자만 적으면 "위험성을 결정했다"는 요건을 형식적으로만 채운다.
     * 빈도·강도와 판정 근거를 같이 적어야 특화점검에서 "제대로 했느냐"에 답할 수 있다.
     */
    private String decisionText(AssessmentHazard link) {
        StringBuilder sb = new StringBuilder();
        sb.append("빈도 ").append(link.getFrequency() == null ? "-" : link.getFrequency());
        sb.append(" × 강도 ").append(link.getSeverity() == null ? "-" : link.getSeverity());
        sb.append(" → 위험성 '").append(riskLabel(link.getRiskLevel())).append("'");
        if (link.getRuleTrace() != null && !link.getRuleTrace().isBlank()) {
            sb.append("\n판정 근거: ").append(link.getRuleTrace());
        }
        return sb.toString();
    }

    // ---------- 산업재해조사표 ----------

    @Transactional(readOnly = true)
    public FormViews.IncidentForm incidentForm(Long incidentId) {
        Incident incident = incidentRepository.findById(incidentId).orElseThrow(
                () -> new NotFoundException("사고를 찾을 수 없습니다: " + incidentId));
        Site site = siteRepository.findById(incident.getSiteId()).orElse(null);

        EquipmentHistoryRecaller.Recall recall = recaller.recall(
                incident.getEquipmentId(), incident.getAccidentType(),
                incident.getOccurredAt(), incident.getCreatedAt(),
                EquipmentHistoryRecaller.Purpose.POST_INCIDENT);

        List<String> recallLines = new ArrayList<>();
        recallLines.add(recall.headline());
        for (EquipmentHistoryRecaller.UnfinishedAction a : recall.unfinishedActions()) {
            recallLines.add("미이행 조치: %s (기한 %s%s)".formatted(
                    a.content(), a.dueDate(),
                    a.overdueDays() != null && a.overdueDays() > 0
                            ? ", 사고 시점에 %d일 경과".formatted(a.overdueDays()) : ""));
        }
        if (recall.warnedAt() != null) {
            recallLines.add("작업 전 브리핑 확인: " + format(recall.warnedAt()));
        }

        return new FormViews.IncidentForm(
                site == null ? "-" : site.getName(),
                "(가상 사업장)",
                "(가상 대표자)",
                recall.equipmentName(),
                recall.locationTag(),
                format(incident.getOccurredAt()),
                nvl(incident.getVictimName(), "-"),
                incident.getSeverity() == null ? "-" : incident.getSeverity().getLabel(),
                incident.getLeaveDays(),
                incident.getAccidentType() == null ? "-" : incident.getAccidentType().getLabel(),
                nvl(incident.getDescription(), "-"),
                nvl(incident.getCause(), "(작성 필요)"),
                nvl(incident.getPrevention(), "(작성 필요)"),
                incident.getReportStatus().getLabel(),
                incident.getReportDueDate(),
                reportBasis(incident),
                incident.getFollowUpAssessmentId(),
                recallLines, AI_NOTICE);
    }

    private String reportBasis(Incident incident) {
        Integer days = incident.getLeaveDays();
        if (days == null) {
            return "휴업일수 미입력 — 제출 의무를 판단하지 못했습니다. 확인 후 입력하십시오.";
        }
        if (days >= Incident.REPORTABLE_LEAVE_DAYS) {
            return ("휴업 %d일(3일 이상) → 산업안전보건법 시행규칙 제73조에 따라 발생일로부터 "
                    + "1개월 이내 관할 지방고용노동관서에 제출합니다.").formatted(days);
        }
        return "휴업 %d일(3일 미만) → 산업재해조사표 제출 의무는 없습니다. 사내 기록으로 보존합니다."
                .formatted(days);
    }

    // ---------- 작업계획서 ----------

    @Transactional(readOnly = true)
    public FormViews.WorkPlanForm workPlanForm(Long workPlanId) {
        WorkPlan plan = workPlanRepository.findById(workPlanId).orElseThrow(
                () -> new NotFoundException("작업계획서를 찾을 수 없습니다: " + workPlanId));
        Site site = siteRepository.findById(plan.getSiteId()).orElse(null);

        AtomicInteger no = new AtomicInteger(1);
        List<FormViews.WorkerRow> workers =
                workPlanWorkerRepository.findByWorkPlanId(workPlanId).stream()
                        .map(w -> new FormViews.WorkerRow(no.getAndIncrement(),
                                w.getName(), nvl(w.getPosition(), "-"), nvl(w.getDuty(), "-")))
                        .toList();

        var questions = io.saife.core.service.RiskRuleEngine.SlotKeys.questions();
        List<FormViews.SlotRow> slots = workPlanSlotRepository.findByWorkPlanId(workPlanId).stream()
                .map(s -> new FormViews.SlotRow(
                        questions.getOrDefault(s.getSlotKey(), s.getSlotKey()),
                        nvl(s.getLedgerValue(), "-"),
                        nvl(s.getAnsweredValue(), "-"),
                        s.isConflicted()))
                .toList();

        List<FormViews.ReferenceRow> references = workPlanEvidenceRepository
                .findByWorkPlanIdOrderByEvidenceNo(workPlanId).stream()
                .map(this::toReferenceRow)
                .filter(Objects::nonNull)
                .toList();

        return new FormViews.WorkPlanForm(
                site == null ? "-" : site.getName(),
                equipmentName(plan.getEquipmentId()),
                plan.getWorkName(),
                nvl(plan.getWorkPlace(), "-"),
                plan.getWorkDate(),
                plan.getWorkHours() == null ? "-" : plan.getWorkHours() + "시간",
                nvl(plan.getMethod(), "-"),
                workPlanStatusLabel(plan.getStatus()),
                nvl(plan.getApprovedBy(), "-"),
                format(plan.getApprovedAt()),
                nvl(plan.getApprovalNote(), "-"),
                plan.getWarningNote(),
                workers, slots,
                nvl(plan.getBriefing(), "(브리핑 미생성)"),
                format(plan.getBriefingAckAt()),
                TBM_NOTICE, AI_NOTICE, references);
    }

    /**
     * 근거 원장 스냅샷 한 행 → 서식 "참고 자료" 줄.
     *
     * <p>payload 디코딩 실패는 그 한 건만 건너뛴다 — 근거 카드 하나가 깨졌다고
     * 법정 서식 전체 렌더링이 막히면 안 된다.
     */
    private FormViews.ReferenceRow toReferenceRow(WorkPlanEvidence row) {
        try {
            Evidence e = objectMapper.readValue(row.getPayload(), Evidence.class);
            return new FormViews.ReferenceRow(e.no(), e.title(), e.sourceUrl(), format(e.fetchedAt()));
        } catch (Exception ex) {
            log.warn("[FORM] 근거 복원 실패 workPlanId={} no={}: {}", row.getWorkPlanId(), row.getEvidenceNo(), ex.getMessage());
            return null;
        }
    }

    // ---------- 라벨 ----------

    private String equipmentName(Long equipmentId) {
        if (equipmentId == null) {
            return "-";
        }
        return equipmentRepository.findById(equipmentId)
                .map(e -> {
                    String loc = e.getProcessId() == null ? e.getLocationTag()
                            : processRepository.findById(e.getProcessId())
                                    .map(WorkProcess::getLocationTag).orElse(e.getLocationTag());
                    return loc == null ? e.getName() : "%s (%s)".formatted(e.getName(), loc);
                })
                .orElse("-");
    }

    private String riskLabel(RiskLevel level) {
        if (level == null) {
            return "-";
        }
        return switch (level) {
            case HIGH -> "상";
            case MEDIUM -> "중";
            case LOW -> "하";
        };
    }

    private String actionStatusLabel(ActionStatus status) {
        return switch (status) {
            case PENDING -> "이행 예정";
            case DONE -> "이행 완료";
            case OVERDUE -> "기한 경과, 미이행";
        };
    }

    private String assessmentStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "CONFIRMED" -> "확정";
            case "ANALYZING" -> "사진 판독 중";
            case "ANALYZED" -> "사진 판독 완료 (미확정)";
            case "FAILED" -> "사진 판독 실패";
            default -> "초안 (미확정)";
        };
    }

    private String workPlanStatusLabel(io.saife.workplan.domain.WorkPlanStatus status) {
        return switch (status) {
            case DRAFT -> "작성 중";
            case SUBMITTED -> "승인 대기";
            case APPROVED -> "승인";
            case CONDITIONAL -> "조건부 승인";
            case REJECTED -> "반려";
            case CLOSED -> "완료";
        };
    }

    private String triggerLabel(String triggerType, Long refId) {
        String base = switch (triggerType == null ? "" : triggerType) {
            case "INCIDENT" -> "산업재해 발생";
            case "PHOTO" -> "현장 사진 판독";
            case "PATROL" -> "순회점검";
            case "EQUIPMENT_CHANGE" -> "설비 변경";
            case "SCHEDULE" -> "정기 일정";
            default -> "-";
        };
        return refId == null ? base : "%s (#%d)".formatted(base, refId);
    }

    /** null은 "아직 판단하지 않음"이다. 채택과 반려 사이의 제3상태를 서식에도 남긴다 */
    private String adoptedLabel(Boolean adopted) {
        if (adopted == null) {
            return "판단 전";
        }
        return adopted ? "채택" : "반려";
    }

    private String format(OffsetDateTime ts) {
        return ts == null ? "-" : ts.atZoneSameInstant(KST).format(TS);
    }

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
