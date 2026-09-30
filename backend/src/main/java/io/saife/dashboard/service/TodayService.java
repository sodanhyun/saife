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
 * "오늘 할 일" — 기억이 만든 인박스 (설계 원칙 1: 기억은 끼어드는 것이다).
 *
 * <p>7종 규칙이 각자 데이터 코어를 훑어 항목을 만든다. <b>규칙마다 private 메서드
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

    /** 가상 사업장 1곳. 테넌시가 없어 상수로 둔다 */
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
    /** PERIODIC_DUE — 정기평가 만료가 이 일수 이내로 다가오면 항목을 만든다 */
    private static final int PERIODIC_DUE_LOOKAHEAD_DAYS = 60;

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
        items.addAll(overdueActions(actions, hazardById, equipmentById, today));
        items.addAll(dueActions(actions, hazardById, equipmentById, today));
        items.addAll(riskyWorkPlans(workPlans, equipmentById, today));
        items.addAll(pendingApprovals(workPlans, equipmentById, today));
        items.addAll(reportDue(today, equipmentById));
        items.addAll(patrolDue(site, assessments, today));
        items.addAll(periodicDue(assessments, today));

        items.sort(Comparator
                .comparingInt((TodayDtos.TodayItem i) -> emphasisRank(i.emphasis()))
                .thenComparing(TodayDtos.TodayItem::daysRemaining,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        List<TodayDtos.TodayItem> capped = items.size() > MAX_ITEMS
                ? List.copyOf(items.subList(0, MAX_ITEMS))
                : List.copyOf(items);

        int critical = (int) capped.stream().filter(i -> i.emphasis() == Emphasis.CRITICAL).count();
        int warning = (int) capped.stream().filter(i -> i.emphasis() == Emphasis.WARNING).count();

        log.info("[TODAY] {} — 항목 {}건(긴급 {} · 주의 {})", today, capped.size(), critical, warning);

        return new TodayDtos.TodayView(today, capped, critical, warning);
    }

    // ---------- 규칙 1: OVERDUE_ACTION ----------

    /** status=OVERDUE 또는 (기한이 지났는데 아직 DONE이 아님). CRITICAL 고정 */
    private List<TodayDtos.TodayItem> overdueActions(List<Action> actions, Map<Long, Hazard> hazardById,
                                                      Map<Long, Equipment> equipmentById, LocalDate today) {
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
            String title = "기한 초과 조치 — %s (%s)".formatted(action.getContent(), dDayLabel(daysRemaining));
            out.add(new TodayDtos.TodayItem(KIND_OVERDUE_ACTION, Emphasis.CRITICAL, title,
                    "기한 " + action.getDueDate(),
                    equipment == null ? null : equipment.getId(),
                    equipment == null ? null : equipment.getName(),
                    action.getDueDate(), daysRemaining, LINK_EQUIPMENT, action.getId()));
        }
        return out;
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
            String title = "조치 기한 %s — %s".formatted(dDayLabel(daysRemaining), action.getContent());
            out.add(new TodayDtos.TodayItem(KIND_DUE_ACTION, Emphasis.WARNING, title,
                    "기한 " + due,
                    equipment == null ? null : equipment.getId(),
                    equipment == null ? null : equipment.getName(),
                    due, daysRemaining, LINK_EQUIPMENT, action.getId()));
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
                    ? "설비 미이행 조치 %d건".formatted(summary.unfinishedActionCount())
                    : "설비 위험등급 '상'";
            String title = "%s 작업 — %s (%s)".formatted(dDayLabel(daysRemaining), plan.getWorkName(), qualifier);
            Equipment equipment = equipmentById.get(plan.getEquipmentId());
            out.add(new TodayDtos.TodayItem(KIND_RISKY_WORK_PLAN, Emphasis.CRITICAL, title, qualifier,
                    plan.getEquipmentId(), equipment == null ? null : equipment.getName(),
                    workDate, daysRemaining, LINK_WORK_PLAN, plan.getId()));
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
            String title = "승인 대기 — %s".formatted(plan.getWorkName());
            out.add(new TodayDtos.TodayItem(KIND_PENDING_APPROVAL, Emphasis.WARNING, title,
                    "작업일 " + plan.getWorkDate(),
                    plan.getEquipmentId(), equipment == null ? null : equipment.getName(),
                    plan.getWorkDate(), daysRemaining, LINK_WORK_PLAN, plan.getId()));
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
            String axisLabel = incident.getAccidentType() == null ? "" : incident.getAccidentType().getLabel();
            String equipmentName = equipment == null ? "" : equipment.getName();
            String title = "조사표 제출 %s — %s %s".formatted(dDayLabel(daysRemaining), equipmentName, axisLabel)
                    .replaceAll("\\s+", " ").strip();
            out.add(new TodayDtos.TodayItem(KIND_REPORT_DUE, emphasis, title,
                    "기한 " + incident.getReportDueDate(),
                    incident.getEquipmentId(), equipment == null ? null : equipment.getName(),
                    incident.getReportDueDate(), daysRemaining, LINK_INCIDENT, incident.getId()));
        }
        return out;
    }

    // ---------- 규칙 6: PATROL_DUE ----------

    /** site.regular_track=true & 이번 달 assessed_on인 ROUTINE 평가 없음. WARNING 고정 */
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
        String title = "이번 달 순회점검 미실시 (상시평가 요건)";
        String detail = "월 1회 순회점검·아차사고 확인은 상시평가 트랙(수시·정기 면제)의 요건 중 하나입니다.";
        return List.of(new TodayDtos.TodayItem(KIND_PATROL_DUE, Emphasis.WARNING, title, detail,
                null, null, dueDate, daysRemaining, LINK_ASSESSMENT, null));
    }

    // ---------- 규칙 7: PERIODIC_DUE ----------

    /** 가장 최근 INITIAL/REGULAR 평가 + 1년이 오늘+60일 이내. WARNING 고정 */
    private List<TodayDtos.TodayItem> periodicDue(List<Assessment> assessments, LocalDate today) {
        Assessment latest = assessments.stream()
                .filter(a -> a.getKind() == AssessmentKind.INITIAL || a.getKind() == AssessmentKind.REGULAR)
                .max(Comparator.comparing(Assessment::getAssessedOn))
                .orElse(null);
        if (latest == null) {
            return List.of();
        }
        LocalDate dueDate = latest.getAssessedOn().plusYears(1);
        if (dueDate.isAfter(today.plusDays(PERIODIC_DUE_LOOKAHEAD_DAYS))) {
            return List.of();
        }
        long daysRemaining = ChronoUnit.DAYS.between(today, dueDate);
        String title = "정기평가 %s".formatted(dDayLabel(daysRemaining));
        String detail = "최근 %s 평가(%s) 기준 1년 주기 갱신 예정".formatted(
                latest.getKind().getLabel(), latest.getAssessedOn());
        return List.of(new TodayDtos.TodayItem(KIND_PERIODIC_DUE, Emphasis.WARNING, title, detail,
                null, null, dueDate, daysRemaining, LINK_ASSESSMENT, latest.getId()));
    }

    // ---------- 보조 ----------

    private Equipment equipmentOf(Action action, Map<Long, Hazard> hazardById, Map<Long, Equipment> equipmentById) {
        Hazard hazard = hazardById.get(action.getHazardId());
        if (hazard == null || hazard.getEquipmentId() == null) {
            return null;
        }
        return equipmentById.get(hazard.getEquipmentId());
    }

    /** {@code daysRemaining>=0}이면 "D-n", 음수면 "n일 경과" */
    private String dDayLabel(long daysRemaining) {
        return daysRemaining >= 0 ? "D-" + daysRemaining : (-daysRemaining) + "일 경과";
    }

    private int emphasisRank(Emphasis emphasis) {
        return switch (emphasis) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case NORMAL -> 2;
        };
    }
}
