package io.saife.dashboard.service;

import io.saife.core.domain.*;
import io.saife.core.repository.*;
import io.saife.dashboard.dto.TodayDtos;
import io.saife.dashboard.dto.TimelineDtos.Emphasis;
import io.saife.incident.domain.Incident;
import io.saife.incident.domain.ReportStatus;
import io.saife.incident.repository.IncidentRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 홈 "오늘 할 일".
 *
 * <p>8종 규칙이 각자 데이터 코어를 훑어 항목을 만든다. <b>규칙마다 private 메서드
 * 하나</b>(경계값을 눈으로 확인하기 쉽게)이고, 문장은 전부 조회 결과에서 조립한다 —
 * 하드코딩한 문장은 하드코딩한 증거다({@code .claude/rules/*}, 원칙 3 참고).
 *
 * <p>정렬은 CRITICAL → WARNING → NORMAL, 같은 등급이면 {@code daysRemaining} 오름차순
 * (null은 마지막), 상한 20건. "오늘"은 항상 KST — 서버가 다른 타임존에서 돌아도
 * 대회 심사 시간대(한국)와 어긋나면 안 된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodayService {

    /** 사업장 1곳. 테넌시가 없어 상수로 둔다 */
    private static final Long DEMO_SITE_ID = 1L;
    private static final int MAX_ITEMS = 20;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final String KIND_OVERDUE_ACTION = "OVERDUE_ACTION";
    private static final String KIND_DUE_ACTION = "DUE_ACTION";
    private static final String KIND_RISKY_WORK_PLAN = "RISKY_WORK_PLAN";
    private static final String KIND_PENDING_APPROVAL = "PENDING_APPROVAL";
    private static final String KIND_REPORT_DUE = "REPORT_DUE";
    private static final String KIND_PATROL_DUE = "PATROL_DUE";
    private static final String KIND_PERIODIC_DUE = "PERIODIC_DUE";
    private static final String KIND_WORK_HOLD = "WORK_HOLD";

    private static final String LINK_EQUIPMENT = "EQUIPMENT";
    private static final String LINK_WORK_PLAN = "WORK_PLAN";
    private static final String LINK_INCIDENT = "INCIDENT";
    private static final String LINK_ASSESSMENT = "ASSESSMENT";

    /** DUE_ACTION 창(며칠 이내 기한이면 "곧 다가옴"으로 보는가) */
    private static final int DUE_ACTION_WINDOW_DAYS = 14;
    /** RISKY_WORK_PLAN 창(며칠 이내 작업이면 위험 작업으로 보는가) */
    private static final int RISKY_WORK_PLAN_WINDOW_DAYS = 7;
    /** REPORT_DUE에서 이 일수 이하로 남으면 CRITICAL */
    private static final int REPORT_DUE_CRITICAL_DAYS = 3;
    /** PERIODIC_DUE: 연말까지 이 일수 이하로 남으면 WARNING, 그 전에는 NORMAL */
    private static final int PERIODIC_DUE_WARNING_DAYS = 60;

    private final HazardRepository hazardRepository;
    private final ActionRepository actionRepository;
    private final EquipmentRepository equipmentRepository;
    private final AssessmentRepository assessmentRepository;
    private final SiteRepository siteRepository;
    private final WorkPlanRepository workPlanRepository;
    private final IncidentRepository incidentRepository;
    private final EquipmentTimelineService equipmentTimelineService;

    @Transactional(readOnly = true)
    public TodayDtos.TodayView today() {
        LocalDate today = LocalDate.now(KST);

        List<Hazard> hazards = hazardRepository.findBySiteId(DEMO_SITE_ID);
        Map<Long, Hazard> hazardById = new HashMap<>();
        hazards.forEach(h -> hazardById.put(h.getId(), h));
        List<Long> hazardIds = hazards.stream().map(Hazard::getId).toList();
        List<Action> actions = hazardIds.isEmpty() ? List.of()
                : actionRepository.findByHazardIdIn(hazardIds);

        Map<Long, Equipment> equipmentById = new HashMap<>();
        equipmentRepository.findBySiteIdOrderByIdAsc(DEMO_SITE_ID)
                .forEach(e -> equipmentById.put(e.getId(), e));

        List<WorkPlan> workPlans =
                workPlanRepository.findBySiteIdOrderByWorkDateDesc(DEMO_SITE_ID, Pageable.unpaged())
                        .getContent();

        List<Assessment> assessments = assessmentRepository.findBySiteIdOrderByAssessedOnDesc(DEMO_SITE_ID);
        Site site = siteRepository.findById(DEMO_SITE_ID).orElse(null);

        List<TodayDtos.TodayItem> items = new ArrayList<>();
        items.addAll(heldWorkPlans(workPlans, equipmentById,
                incidentRepository.findBySiteIdOrderByOccurredAtDesc(DEMO_SITE_ID, Pageable.unpaged()).getContent()));
        items.addAll(overdueActions(actions, hazardById, equipmentById, workPlans, today));
        items.addAll(dueActions(actions, hazardById, equipmentById, today));
        items.addAll(riskyWorkPlans(workPlans, equipmentById, today));
        items.addAll(pendingApprovals(workPlans, equipmentById, today));
        items.addAll(reportDue(today, equipmentById));
        items.addAll(patrolDue(site, assessments, today));
        items.addAll(periodicDue(assessments, today));

        items.sort(Comparator
                .comparingInt((TodayDtos.TodayItem i) -> emphasisRank(i.emphasis()))
                // 작업 보류는 기한이 없지만 긴급 중에서도 맨 위다(작업 재개 전에 막아야 한다)
                .thenComparingInt(i -> KIND_WORK_HOLD.equals(i.kind()) ? 0 : 1)
                .thenComparing(TodayDtos.TodayItem::daysRemaining,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        List<TodayDtos.TodayItem> capped = items.size() > MAX_ITEMS
                ? List.copyOf(items.subList(0, MAX_ITEMS))
                : List.copyOf(items);

        int critical = (int) capped.stream().filter(i -> i.emphasis() == Emphasis.CRITICAL).count();
        int warning = (int) capped.stream().filter(i -> i.emphasis() == Emphasis.WARNING).count();

        log.info("[TODAY] {} 항목 {}건(긴급 {}, 주의 {})", today, capped.size(), critical, warning);

        return new TodayDtos.TodayView(today, capped, critical, warning);
    }

    // ---------- 규칙 1: OVERDUE_ACTION ----------

    /** status=OVERDUE 또는 (기한이 지났는데 아직 DONE이 아님). CRITICAL 고정 */
    private List<TodayDtos.TodayItem> overdueActions(List<Action> actions, Map<Long, Hazard> hazardById,
                                                      Map<Long, Equipment> equipmentById,
                                                      List<WorkPlan> workPlans, LocalDate today) {
        List<TodayDtos.TodayItem> out = new ArrayList<>();
        for (Action action : actions) {
            if (action.getDueDate() == null) {
                continue;
            }
            boolean overdueStatus = action.getStatus() == ActionStatus.OVERDUE;
            boolean pastDueStillOpen = action.getDueDate().isBefore(today)
                    && action.getStatus() != ActionStatus.DONE;
            if (!overdueStatus && !pastDueStillOpen) {
                continue;
            }
            long daysRemaining = ChronoUnit.DAYS.between(today, action.getDueDate());
            Equipment equipment = equipmentOf(action, hazardById, equipmentById);
            // 제목은 조치 내용 그대로. 종류와 경과일은 화면이 kind/daysRemaining으로 붙인다.
            // 미이행이 길어지면 잠정조치가 필요하다(고시 제12조제4항). 이미 잠정조치를 정해 승인받았으면 "잠정조치 중"
            String title = action.getContent();
            String interim = hasInterimMeasure(action, equipment, workPlans) ? "잠정조치 중" : "잠정조치 필요";
            out.add(new TodayDtos.TodayItem(KIND_OVERDUE_ACTION, Emphasis.CRITICAL, title,
                    joinDetail(ownerOf(action), interim),
                    equipment == null ? null : equipment.getId(),
                    equipment == null ? null : equipment.getName(),
                    action.getDueDate(), daysRemaining, LINK_EQUIPMENT, action.getId(), null));
        }
        return out;
    }

    /**
     * 이 조치에 잠정조치가 걸려 있는가. 같은 설비의 작업 전 점검이 조치 등록 뒤에 잠정조치(승인 조건)를 달고
     * 승인됐으면 잠정조치 중으로 본다. 잠정조치는 승인 조건(approval_note)으로만 입력된다.
     */
    private static boolean hasInterimMeasure(Action action, Equipment equipment, List<WorkPlan> workPlans) {
        if (equipment == null) {
            return false;
        }
        return workPlans.stream().anyMatch(p -> equipment.getId().equals(p.getEquipmentId())
                && p.getApprovalNote() != null && !p.getApprovalNote().isBlank()
                && p.getApprovedAt() != null
                && (action.getCreatedAt() == null || p.getApprovedAt().isAfter(action.getCreatedAt())));
    }

    // ---------- 규칙 2: DUE_ACTION ----------

    /** status=PENDING & 오늘 <= 기한 <= 오늘+14. WARNING 고정 */
    private List<TodayDtos.TodayItem> dueActions(List<Action> actions, Map<Long, Hazard> hazardById,
                                                 Map<Long, Equipment> equipmentById, LocalDate today) {
        LocalDate windowEnd = today.plusDays(DUE_ACTION_WINDOW_DAYS);
        List<TodayDtos.TodayItem> out = new ArrayList<>();
        for (Action action : actions) {
            if (action.getStatus() != ActionStatus.PENDING || action.getDueDate() == null) {
                continue;
            }
            LocalDate due = action.getDueDate();
            if (due.isBefore(today) || due.isAfter(windowEnd)) {
                continue;
            }
            long daysRemaining = ChronoUnit.DAYS.between(today, due);
            Equipment equipment = equipmentOf(action, hazardById, equipmentById);
            String title = action.getContent();
            out.add(new TodayDtos.TodayItem(KIND_DUE_ACTION, Emphasis.WARNING, title,
                    joinDetail(ownerOf(action), "기한 " + monthDay(due)),
                    equipment == null ? null : equipment.getId(),
                    equipment == null ? null : equipment.getName(),
                    due, daysRemaining, LINK_EQUIPMENT, action.getId(), null));
        }
        return out;
    }

    // ---------- 규칙 3: RISKY_WORK_PLAN ----------

    private static final Set<WorkPlanStatus> UPCOMING_STATUSES =
            EnumSet.of(WorkPlanStatus.SUBMITTED, WorkPlanStatus.APPROVED, WorkPlanStatus.CONDITIONAL);

    /**
     * 진행 중 상태 & 오늘 <= 작업일 <= 오늘+7 & (그 설비에 미이행 조치 있음 또는 현재 등급 HIGH).
     * CRITICAL 고정. 등급·미이행 여부는 {@link EquipmentTimelineService}의 summary를
     * 그대로 재사용한다 — 카드-타임라인 불변식과 같은 계산을 두 번 만들지 않는다.
     */
    private List<TodayDtos.TodayItem> riskyWorkPlans(List<WorkPlan> workPlans,
                                                      Map<Long, Equipment> equipmentById, LocalDate today) {
        LocalDate windowEnd = today.plusDays(RISKY_WORK_PLAN_WINDOW_DAYS);
        List<TodayDtos.TodayItem> out = new ArrayList<>();
        for (WorkPlan plan : workPlans) {
            if (!UPCOMING_STATUSES.contains(plan.getStatus()) || plan.getEquipmentId() == null) {
                continue;
            }
            LocalDate workDate = plan.getWorkDate();
            if (workDate.isBefore(today) || workDate.isAfter(windowEnd)) {
                continue;
            }
            var summary = equipmentTimelineService.timeline(plan.getEquipmentId()).summary();
            boolean hasUnfinished = summary.unfinishedActionCount() > 0;
            boolean isHigh = summary.currentRiskLevel() == RiskLevel.HIGH;
            if (!hasUnfinished && !isHigh) {
                continue;
            }
            long daysRemaining = ChronoUnit.DAYS.between(today, workDate);
            String qualifier = hasUnfinished
                    ? "미이행 조치 %d건".formatted(summary.unfinishedActionCount())
                    : "현재 등급 상";
            String title = plan.getWorkName();
            Equipment equipment = equipmentById.get(plan.getEquipmentId());
            out.add(new TodayDtos.TodayItem(KIND_RISKY_WORK_PLAN, Emphasis.CRITICAL, title, qualifier,
                    plan.getEquipmentId(), equipment == null ? null : equipment.getName(),
                    workDate, daysRemaining, LINK_WORK_PLAN, plan.getId(), null));
        }
        return out;
    }

    // ---------- 규칙 4: PENDING_APPROVAL ----------

    /** status=SUBMITTED. WARNING 고정 */
    private List<TodayDtos.TodayItem> pendingApprovals(List<WorkPlan> workPlans,
                                                        Map<Long, Equipment> equipmentById, LocalDate today) {
        // 다른 규칙과 같은 today를 받는다 — 자정 경계에서 규칙마다 다른 날짜를 보지 않게(수정 목록 C 4a)
        List<TodayDtos.TodayItem> out = new ArrayList<>();
        for (WorkPlan plan : workPlans) {
            if (plan.getStatus() != WorkPlanStatus.SUBMITTED) {
                continue;
            }
            Long daysRemaining = plan.getWorkDate() == null ? null
                    : ChronoUnit.DAYS.between(today, plan.getWorkDate());
            Equipment equipment = plan.getEquipmentId() == null ? null : equipmentById.get(plan.getEquipmentId());
            String title = plan.getWorkName();
            out.add(new TodayDtos.TodayItem(KIND_PENDING_APPROVAL, Emphasis.WARNING, title,
                    plan.getWorkDate() == null ? "" : "작업일 " + monthDay(plan.getWorkDate()),
                    plan.getEquipmentId(), equipment == null ? null : equipment.getName(),
                    plan.getWorkDate(), daysRemaining, LINK_WORK_PLAN, plan.getId(), null));
        }
        return out;
    }

    // ---------- 규칙 5: REPORT_DUE ----------

    /**
     * report_status가 아직 제출 전(법정 기한이 살아 있음) & report_due_date 있음.
     * ≤3일 CRITICAL, 그 외 WARNING.
     *
     * <p>설계서 표는 {@code REQUIRED,DRAFTED} 두 상태를 말하지만 {@code ReportStatus}에
     * {@code DRAFTED}는 없다(도입되지 않았다). 그 자리를 대신하는 건 {@code OVERDUE}다 —
     * 기한을 넘겼는데 아직 제출 전인 상태이므로 여전히 인박스에 남아야 한다.
     */
    private List<TodayDtos.TodayItem> reportDue(LocalDate today, Map<Long, Equipment> equipmentById) {
        List<Incident> incidents = incidentRepository.findBySiteIdAndReportStatusIn(
                DEMO_SITE_ID, List.of(ReportStatus.REQUIRED, ReportStatus.OVERDUE));
        List<TodayDtos.TodayItem> out = new ArrayList<>();
        for (Incident incident : incidents) {
            if (incident.getReportDueDate() == null) {
                continue;
            }
            long daysRemaining = ChronoUnit.DAYS.between(today, incident.getReportDueDate());
            Emphasis emphasis = daysRemaining <= REPORT_DUE_CRITICAL_DAYS ? Emphasis.CRITICAL : Emphasis.WARNING;
            Equipment equipment = incident.getEquipmentId() == null ? null
                    : equipmentById.get(incident.getEquipmentId());
            String axisLabel = incident.getIncidentType() == null ? "재해" : incident.getIncidentType().getLabel();
            String title = "%s 사고 %s".formatted(axisLabel,
                    monthDay(incident.getOccurredAt().atZoneSameInstant(KST).toLocalDate()));
            // 기한은 날짜 칸이 말한다. 보조 줄은 휴업일수만
            String detail = incident.getLeaveDays() == null ? "" : "휴업 %d일".formatted(incident.getLeaveDays());
            out.add(new TodayDtos.TodayItem(KIND_REPORT_DUE, emphasis, title, detail,
                    incident.getEquipmentId(), equipment == null ? null : equipment.getName(),
                    incident.getReportDueDate(), daysRemaining, LINK_INCIDENT, incident.getId(), null));
        }
        return out;
    }

    // ---------- 규칙 6: PATROL_DUE ----------

    /**
     * site.regular_track=true & 이번 달 assessed_on인 ROUTINE 평가 없음. WARNING 고정.
     * 순회점검은 근로자 참여의 기본 방법이다(시행규칙 제37조의2)
     */
    private List<TodayDtos.TodayItem> patrolDue(Site site, List<Assessment> assessments, LocalDate today) {
        if (site == null || !site.isRegularTrack()) {
            return List.of();
        }
        YearMonth thisMonth = YearMonth.from(today);
        boolean patrolledThisMonth = assessments.stream()
                .anyMatch(a -> a.getKind() == AssessmentKind.ROUTINE
                        && YearMonth.from(a.getAssessedOn()).equals(thisMonth));
        if (patrolledThisMonth) {
            return List.of();
        }
        LocalDate dueDate = thisMonth.atEndOfMonth();
        long daysRemaining = ChronoUnit.DAYS.between(today, dueDate);
        String title = "이번 달 순회점검 미실시";
        String detail = "근로자 참여 (시행규칙 제37조의2)";
        return List.of(new TodayDtos.TodayItem(KIND_PATROL_DUE, Emphasis.WARNING, title, detail,
                null, null, dueDate, daysRemaining, LINK_ASSESSMENT, null, null));
    }

    // ---------- 규칙 7: PERIODIC_DUE ----------

    /**
     * 올해 정기평가 미실시. 가장 최근 최초/정기 평가가 올해가 아니면 연말을 기한으로 항목을 만든다
     * (시행규칙 제37조제2항제2호, 매년). 연말까지 60일 이하면 WARNING, 그 전에는 NORMAL.
     * 최초/정기 평가 기록이 아예 없으면 만들지 않는다(최초평가가 먼저다).
     */
    private List<TodayDtos.TodayItem> periodicDue(List<Assessment> assessments, LocalDate today) {
        Assessment latest = assessments.stream()
                .filter(a -> a.getKind() == AssessmentKind.INITIAL || a.getKind() == AssessmentKind.REGULAR)
                .max(Comparator.comparing(Assessment::getAssessedOn))
                .orElse(null);
        if (latest == null || latest.getAssessedOn().getYear() >= today.getYear()) {
            return List.of();
        }
        LocalDate dueDate = LocalDate.of(today.getYear(), 12, 31);
        long daysRemaining = ChronoUnit.DAYS.between(today, dueDate);
        Emphasis emphasis = daysRemaining <= PERIODIC_DUE_WARNING_DAYS ? Emphasis.WARNING : Emphasis.NORMAL;
        String title = "올해 정기평가 미실시";
        String detail = "최근 %s평가 %s".formatted(latest.getKind().getLabel(), latest.getAssessedOn());
        return List.of(new TodayDtos.TodayItem(KIND_PERIODIC_DUE, emphasis, title, detail,
                null, null, dueDate, daysRemaining, LINK_ASSESSMENT, latest.getId(), null));
    }

    // ---------- 규칙 8: WORK_HOLD ----------

    /**
     * 작업 보류. 같은 설비에 산업재해가 발생해 HOLD가 된 작업 전 점검이다. 수시평가가 끝나기 전에는
     * 작업을 재개할 수 없다(시행규칙 제37조제2항제3호). 기한이 아니라 금지라 daysRemaining은 null,
     * 정렬은 긴급 맨 위.
     */
    private List<TodayDtos.TodayItem> heldWorkPlans(List<WorkPlan> workPlans, Map<Long, Equipment> equipmentById,
                                                    List<Incident> incidents) {
        List<TodayDtos.TodayItem> out = new ArrayList<>();
        for (WorkPlan plan : workPlans) {
            if (plan.getStatus() != WorkPlanStatus.HOLD) {
                continue;
            }
            Equipment equipment = plan.getEquipmentId() == null ? null : equipmentById.get(plan.getEquipmentId());
            out.add(new TodayDtos.TodayItem(KIND_WORK_HOLD, Emphasis.CRITICAL, plan.getWorkName(),
                    "수시평가 완료 전 작업 재개 금지",
                    plan.getEquipmentId(), equipment == null ? null : equipment.getName(),
                    plan.getWorkDate(), null, LINK_WORK_PLAN, plan.getId(),
                    followUpAssessmentOf(plan.getEquipmentId(), incidents)));
        }
        return out;
    }

    // ---------- 보조 ----------

    /** 이 설비 사고가 만든 수시평가 중 가장 최근 것. 작업 보류를 푸는 화면이 이 평가다. 없으면 null */
    private static Long followUpAssessmentOf(Long equipmentId, List<Incident> incidents) {
        if (equipmentId == null) {
            return null;
        }
        return incidents.stream()
                .filter(i -> equipmentId.equals(i.getEquipmentId()) && i.getFollowUpAssessmentId() != null)
                .max(Comparator.comparing(Incident::getOccurredAt))
                .map(Incident::getFollowUpAssessmentId)
                .orElse(null);
    }

    private Equipment equipmentOf(Action action, Map<Long, Hazard> hazardById, Map<Long, Equipment> equipmentById) {
        Hazard hazard = hazardById.get(action.getHazardId());
        if (hazard == null || hazard.getEquipmentId() == null) {
            return null;
        }
        return equipmentById.get(hazard.getEquipmentId());
    }

    private static String ownerOf(Action action) {
        String owner = action.getOwner();
        return owner == null || owner.isBlank() ? null : "담당 " + owner.strip();
    }

    /** null과 빈 값을 건너뛰고 쉼표로 잇는다 */
    private static String joinDetail(String... parts) {
        return Arrays.stream(parts).filter(p -> p != null && !p.isBlank())
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    /** 촘촘한 목록의 날짜 표기 MM-DD */
    private static String monthDay(LocalDate date) {
        return date == null ? "" : "%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth());
    }

    private int emphasisRank(Emphasis emphasis) {
        return switch (emphasis) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case NORMAL -> 2;
        };
    }
}
