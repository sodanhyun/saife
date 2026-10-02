package io.saife.dashboard.service;

import io.saife.core.domain.*;
import io.saife.core.repository.*;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.dashboard.dto.TimelineDtos.Emphasis;
import io.saife.dashboard.dto.TimelineDtos.EventType;
import io.saife.dashboard.dto.TimelineDtos.TimelineEvent;
import io.saife.incident.domain.Incident;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.incident.domain.ReportStatus;
import io.saife.incident.repository.IncidentRepository;
import io.saife.incident.service.EquipmentHistoryRecaller;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.common.error.ApiExceptions.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * UC4 — 설비 1개의 타임라인.
 *
 * <p>이 화면이 증명하는 것은 기능이 아니라 <b>구조</b>다. 평가에서 나온 조치가
 * 미이행으로 남고, 그 설비 위에서 작업계획서가 쓰이고, 사고가 나고, 그 사고가
 * 수시평가를 만든다 — 전부 같은 설비 ID에 매달려 있다.
 *
 * <p>읽기 전용이다. 여기서 상태를 바꾸지 않는다. 시연 중 조회 때문에 데이터가
 * 변하면 같은 화면을 두 번 보여줄 수 없다.
 *
 * <p><b>연결선은 백엔드가 계산한다.</b> 프론트가 추론하게 두면 화면마다 다른 선이
 * 그려지고, 심사위원 앞에서 "왜 저 선이 저기 있나"를 설명하지 못한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EquipmentTimelineService {

    /**
     * 같은 날짜 안에서의 인과 순서.
     *
     * <p>날짜만으로 정렬하면 <b>사고와 그 사고가 만든 수시평가가 같은 날일 때 순서가
     * 뒤집힌다.</b> 실제로 화면에서 수시평가가 사고보다 위에 놓여 화살표가 거꾸로 갔다
     * (2026-09-21 QA 실측). 원인이 결과보다 먼저 와야 그림이 논지를 말한다.
     */
    private static final int ORDER_ASSESSMENT = 0;
    private static final int ORDER_ACTION = 1;
    private static final int ORDER_WORK_PLAN = 2;
    private static final int ORDER_INCIDENT = 3;
    /** 사고가 만든 수시평가는 그 사고 뒤에 온다 */
    private static final int ORDER_FOLLOW_UP = 4;

    private final EquipmentRepository equipmentRepository;
    private final ProcessRepository processRepository;
    private final HazardRepository hazardRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final ActionRepository actionRepository;
    private final WorkPlanRepository workPlanRepository;
    private final IncidentRepository incidentRepository;
    private final EquipmentHistoryRecaller equipmentHistoryRecaller;
    private final KoshaGuideRepository koshaGuideRepository;

    /** 진행 중(=아직 안 끝난) 작업계획서 상태. "예정 작업" 카운트에 쓴다 */
    private static final Set<WorkPlanStatus> UPCOMING_STATUSES =
            EnumSet.of(WorkPlanStatus.SUBMITTED, WorkPlanStatus.APPROVED, WorkPlanStatus.CONDITIONAL);

    /** 제출 안 된(=아직 밀린) 조사표 상태. 카드 CRITICAL 판정에 쓴다 */
    private static final Set<ReportStatus> UNSUBMITTED_REPORT_STATUSES =
            EnumSet.of(ReportStatus.REQUIRED, ReportStatus.OVERDUE);

    /**
     * 설비 홈 카드 — 사업장 안의 설비 전부를 한 화면에.
     *
     * <p>설비별로 기존 {@link #timeline(Long)}의 {@code summary()}를 그대로 재사용한다.
     * 홈과 상세가 같은 설비에 다른 숫자를 보이면 그 순간 심사위원이 짚는다 — 그래서
     * 계산을 두 번 만들지 않고 <b>한 곳(summary)만 계산하고 카드는 그 결과를 읽는다.</b>
     *
     * <p><b>N+1 주의:</b> 설비마다 {@code timeline()}을 통째로 돌린다. 가상 사업장이
     * 설비 6개뿐이라 지금은 허용하지만, <b>설비가 20개 이상으로 늘면</b> 설비별 반복 대신
     * 사업장 단위로 한 번에 집계하는 쿼리로 바꿔야 한다 (지금 그대로 두면 화면 하나 열 때
     * 쿼리가 설비 수 × 6~7회로 불어난다).
     */
    @Transactional(readOnly = true)
    public List<TimelineDtos.EquipmentCard> cards(Long siteId) {
        List<Equipment> equipments = equipmentRepository.findBySiteIdOrderByIdAsc(siteId);
        // "오늘 할 일"과 같은 기준(KST)을 쓴다 — 자정 근처에 서버 로컬 시간대와
        // 어긋나면 카드와 인박스가 다른 날짜를 "오늘"로 보게 된다 (C 1a minor)
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        List<TimelineDtos.EquipmentCard> cards = new ArrayList<>();
        for (Equipment equipment : equipments) {
            TimelineDtos.EquipmentTimeline timeline = timeline(equipment.getId());
            TimelineDtos.TimelineSummary summary = timeline.summary();

            List<WorkPlan> workPlans =
                    workPlanRepository.findByEquipmentIdOrderByWorkDateDesc(equipment.getId());
            int upcoming = (int) workPlans.stream()
                    .filter(p -> UPCOMING_STATUSES.contains(p.getStatus())
                            && !p.getWorkDate().isBefore(today))
                    .count();

            List<Incident> incidents =
                    incidentRepository.findByEquipmentIdOrderByOccurredAtDesc(equipment.getId());
            boolean unsubmittedReport = incidents.stream()
                    .anyMatch(i -> UNSUBMITTED_REPORT_STATUSES.contains(i.getReportStatus()));

            LocalDate lastEventOn = timeline.events().isEmpty() ? null
                    : timeline.events().get(timeline.events().size() - 1).at();

            Emphasis emphasis = emphasisOf(summary, unsubmittedReport);

            cards.add(new TimelineDtos.EquipmentCard(
                    equipment.getId(), timeline.equipment().name(),
                    timeline.equipment().locationTag(), timeline.equipment().processName(),
                    summary.currentRiskLevel(), summary.currentRiskAxis(), summary.lastAssessedOn(),
                    summary.unfinishedActionCount(), summary.overdueActionCount(), upcoming,
                    summary.incidentCount(), summary.nearMissCount(), lastEventOn, emphasis, summary.headline()));
        }

        cards.sort(Comparator
                .comparingInt((TimelineDtos.EquipmentCard c) -> emphasisRank(c.emphasis()))
                .thenComparing(TimelineDtos.EquipmentCard::lastEventOn,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(TimelineDtos.EquipmentCard::id));

        return cards;
    }

    /**
     * 카드 톤 — CRITICAL: 기한 초과 조치 있음 또는 미제출 조사표 /
     * WARNING: 미이행 조치 또는 '상' 등급 / NORMAL.
     */
    private Emphasis emphasisOf(TimelineDtos.TimelineSummary summary, boolean unsubmittedReport) {
        if (summary.overdueActionCount() > 0 || unsubmittedReport) {
            return Emphasis.CRITICAL;
        }
        if (summary.unfinishedActionCount() > 0 || summary.currentRiskLevel() == RiskLevel.HIGH) {
            return Emphasis.WARNING;
        }
        return Emphasis.NORMAL;
    }

    private int emphasisRank(Emphasis emphasis) {
        return switch (emphasis) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case NORMAL -> 2;
        };
    }

    /**
     * 설비 회상 — 작업 신고(UC3) 진입 시 "묻기 전에 먼저 말한다"의 근거.
     *
     * <p>사고가 아직 없는 문맥이라 {@code axis=null}, {@code occurredAt=knownAsOf=now}로
     * {@link EquipmentHistoryRecaller#recall}을 부른다. {@code Purpose.PRE_WORK}가
     * headline 문구를 "예고" 대신 "지금 아는 것" 톤으로 바꾼다.
     */
    @Transactional(readOnly = true)
    public TimelineDtos.RecallView recall(Long equipmentId) {
        Equipment equipment = equipmentRepository.findById(equipmentId).orElseThrow(
                () -> new NotFoundException("설비를 찾을 수 없습니다: " + equipmentId));
        String processName = equipment.getProcessId() == null ? null
                : processRepository.findById(equipment.getProcessId())
                        .map(WorkProcess::getName).orElse(null);

        OffsetDateTime now = OffsetDateTime.now();
        EquipmentHistoryRecaller.Recall recall = equipmentHistoryRecaller.recall(
                equipmentId, null, now, now, EquipmentHistoryRecaller.Purpose.PRE_WORK);

        return TimelineDtos.RecallView.from(recall, processName);
    }

    @Transactional(readOnly = true)
    public TimelineDtos.EquipmentTimeline timeline(Long equipmentId) {
        Equipment equipment = equipmentRepository.findById(equipmentId).orElseThrow(
                () -> new NotFoundException("설비를 찾을 수 없습니다: " + equipmentId));

        List<Hazard> hazards = hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(equipmentId);
        Map<Long, Hazard> hazardById = new HashMap<>();
        hazards.forEach(h -> hazardById.put(h.getId(), h));

        List<Incident> incidents =
                incidentRepository.findByEquipmentIdOrderByOccurredAtDesc(equipmentId);
        List<WorkPlan> workPlans = workPlanRepository.findByEquipmentIdOrderByWorkDateDesc(equipmentId);
        List<Action> actions = hazards.isEmpty() ? List.of()
                : actionRepository.findByHazardIdIn(hazards.stream().map(Hazard::getId).toList());

        List<TimelineEvent> events = new ArrayList<>();
        Map<Long, String> assessmentEventIdByAssessment = new HashMap<>();

        Map<Long, Assessment> assessmentById = new HashMap<>();
        events.addAll(assessmentEvents(hazardById, incidents, assessmentEventIdByAssessment, assessmentById));
        events.addAll(actionEvents(actions, assessmentEventIdByAssessment));
        events.addAll(workPlanEvents(workPlans, actions));
        events.addAll(incidentEvents(incidents, hazardById, actions, assessmentEventIdByAssessment));

        events.sort(Comparator.comparing(TimelineEvent::at)
                .thenComparingInt(TimelineEvent::causalOrder)
                .thenComparing(TimelineEvent::id));

        // 내부 식별자(assessment-1)를 화면에 띄우지 않는다. 심사위원이 보는 화면이다
        events = withLinkLabels(events);

        TimelineDtos.TimelineSummary summary =
                summary(hazards, hazardById, assessmentById, incidents, workPlans, actions, events);

        log.info("[UC4] 설비 {} 타임라인 — 사건 {}건", equipmentId, events.size());

        return new TimelineDtos.EquipmentTimeline(head(equipment), summary, events);
    }

    // ---------- 사건 만들기 ----------

    /**
     * 평가 사건. 이 설비의 위험요인이 등장한 평가만 올린다.
     *
     * <p>등급은 그 평가에서 <b>이 설비가 받은 최고 등급</b>이다. 평균이나 대표값이
     * 아니라 최고값인 이유는, 위험성평가에서 의미 있는 건 가장 높은 등급이기 때문이다.
     */
    private List<TimelineEvent> assessmentEvents(Map<Long, Hazard> hazardById,
                                                 List<Incident> incidents,
                                                 Map<Long, String> eventIdOut,
                                                 Map<Long, Assessment> assessmentOut) {
        if (hazardById.isEmpty()) {
            return List.of();
        }
        List<AssessmentHazard> links =
                assessmentHazardRepository.findByHazardIdIn(List.copyOf(hazardById.keySet()));

        Map<Long, List<AssessmentHazard>> byAssessment = new LinkedHashMap<>();
        links.forEach(l -> byAssessment.computeIfAbsent(l.getAssessmentId(), k -> new ArrayList<>()).add(l));

        // 사고 id → 그 사고가 만든 수시평가를 되찾기 위한 역방향 색인
        Map<Long, Long> incidentByAssessment = new HashMap<>();
        incidents.forEach(i -> {
            if (i.getFollowUpAssessmentId() != null) {
                incidentByAssessment.put(i.getFollowUpAssessmentId(), i.getId());
            }
        });

        List<TimelineEvent> out = new ArrayList<>();
        for (Map.Entry<Long, List<AssessmentHazard>> entry : byAssessment.entrySet()) {
            Assessment assessment = assessmentRepository.findById(entry.getKey()).orElse(null);
            if (assessment == null) {
                continue;
            }
            String eventId = "assessment-" + assessment.getId();
            eventIdOut.put(assessment.getId(), eventId);
            assessmentOut.put(assessment.getId(), assessment);

            AssessmentHazard worst = entry.getValue().stream()
                    .max(Comparator.comparingInt(l -> severityRank(l.getRiskLevel())))
                    .orElse(null);
            RiskLevel level = worst == null ? null : worst.getRiskLevel();

            List<String> linked = new ArrayList<>();
            Long triggerIncidentId = incidentByAssessment.get(assessment.getId());
            if (triggerIncidentId != null) {
                // 루프가 닫히는 선 — 사고가 이 평가를 만들었다
                linked.add("incident-" + triggerIncidentId);
            }

            // 룰 근거는 detail에 섞지 않고 ruleTrace로 따로 낸다. 화면이 등급 옆에 근거를 붙여 띄운다
            String detail = "위험요인 %d건 평가".formatted(entry.getValue().size());
            String ruleTrace = worst == null ? null : plainSeparators(worst.getRuleTrace());

            out.add(new TimelineEvent(eventId, EventType.ASSESSMENT,
                    assessment.getAssessedOn(), assessment.getCreatedAt(),
                    "%s평가".formatted(assessment.getKind().getLabel()),
                    detail, level,
                    worst == null ? null : axisOf(hazardById, worst.getHazardId()),
                    assessment.getStatus(), assessment.getId(), linked, List.of(),
                    triggerIncidentId != null ? ORDER_FOLLOW_UP : ORDER_ASSESSMENT,
                    level == RiskLevel.HIGH ? Emphasis.WARNING : Emphasis.NORMAL, ruleTrace));
        }
        return out;
    }

    /**
     * 조치 사건. 미이행 조치는 기한 위에, 완료된 조치는 완료일 위에 놓는다.
     * 완료된 조치가 기한(미래 날짜)에 놓이면 아직 일어나지 않은 일처럼 읽힌다.
     */
    private List<TimelineEvent> actionEvents(List<Action> actions,
                                             Map<Long, String> assessmentEventId) {
        List<TimelineEvent> out = new ArrayList<>();
        for (Action action : actions) {
            if (action.getDueDate() == null) {
                continue;
            }
            List<String> linked = new ArrayList<>();
            String source = assessmentEventId.get(action.getAssessmentId());
            if (source != null) {
                linked.add(source);
            }

            boolean overdue = isOverdue(action);
            String detail = action.getStatus() == ActionStatus.DONE ? "이행 완료"
                    : overdue ? "기한 경과, 미이행" : "이행 예정";
            if (action.getGuideRef() != null && !action.getGuideRef().isBlank()) {
                detail += " (근거 " + guideName(action.getGuideRef()) + ")";
            }

            out.add(new TimelineEvent("action-" + action.getId(), EventType.ACTION,
                    placedOn(action), action.getCreatedAt(),
                    action.getContent(), detail, null, null,
                    overdue ? ActionStatus.OVERDUE.name() : action.getStatus().name(),
                    action.getId(), linked, List.of(),
                    ORDER_ACTION,
                    overdue ? Emphasis.CRITICAL : Emphasis.NORMAL, null));
        }
        return out;
    }

    /**
     * 작업계획서 사건.
     *
     * <p>브리핑을 확인한 작업계획서는 그 시점의 미이행 조치와 연결한다 —
     * <b>"경고는 전달됐다"가 그림으로 보이는 선</b>이다.
     */
    private List<TimelineEvent> workPlanEvents(List<WorkPlan> workPlans, List<Action> actions) {
        List<TimelineEvent> out = new ArrayList<>();
        for (WorkPlan plan : workPlans) {
            boolean acknowledged = plan.getBriefingAckAt() != null;

            List<String> linked = new ArrayList<>();
            if (acknowledged) {
                for (Action action : actions) {
                    if (action.getStatus() != ActionStatus.DONE
                            && action.getDueDate() != null
                            && action.getDueDate().isBefore(plan.getWorkDate())) {
                        linked.add("action-" + action.getId());
                    }
                }
            }

            String detail = acknowledged ? "TBM 실시" : "TBM 미실시";

            out.add(new TimelineEvent("workplan-" + plan.getId(), EventType.WORK_PLAN,
                    plan.getWorkDate(), plan.getBriefingAckAt(),
                    plan.getWorkName(), detail, null, null,
                    plan.getStatus().name(), plan.getId(), linked, List.of(),
                    ORDER_WORK_PLAN,
                    acknowledged && !linked.isEmpty() ? Emphasis.WARNING : Emphasis.NORMAL, null));
        }
        return out;
    }

    /** 사고 사건. 항상 CRITICAL이고, 예고되어 있었다면 그 근거들과 선으로 이어진다 */
    private List<TimelineEvent> incidentEvents(List<Incident> incidents,
                                               Map<Long, Hazard> hazardById,
                                               List<Action> actions,
                                               Map<Long, String> assessmentEventId) {
        List<TimelineEvent> out = new ArrayList<>();
        for (Incident incident : incidents) {
            List<String> linked = new ArrayList<>();

            // 사고 시점에 기한이 지나 있던 조치 — "예고되어 있었다"의 증거
            for (Action action : actions) {
                if (action.getStatus() != ActionStatus.DONE
                        && action.getDueDate() != null
                        && action.getDueDate().isBefore(incident.getOccurredAt().toLocalDate())) {
                    linked.add("action-" + action.getId());
                }
            }
            if (incident.getWorkPlanId() != null) {
                linked.add("workplan-" + incident.getWorkPlanId());
            }
            if (incident.getFollowUpAssessmentId() != null) {
                String followUp = assessmentEventId.get(incident.getFollowUpAssessmentId());
                if (followUp != null) {
                    linked.add(followUp);
                }
            }

            StringBuilder detail = new StringBuilder();
            if (incident.getLeaveDays() != null) {
                detail.append("휴업 ").append(incident.getLeaveDays()).append("일");
            }
            if (incident.getReportDueDate() != null && incident.getReportStatus() != ReportStatus.SUBMITTED) {
                if (detail.length() > 0) {
                    detail.append(", ");
                }
                detail.append("조사표 기한 ").append(monthDay(incident.getReportDueDate()));
            }

            out.add(new TimelineEvent("incident-" + incident.getId(), EventType.INCIDENT,
                    incident.getOccurredAt().toLocalDate(), incident.getOccurredAt(),
                    incidentTitle(incident, hazardById), detail.toString(),
                    null, incident.getAccidentType(),
                    incident.getReportStatus().name(), incident.getId(), linked, List.of(),
                    ORDER_INCIDENT, Emphasis.CRITICAL, null));
        }
        return out;
    }

    private String incidentTitle(Incident incident, Map<Long, Hazard> hazardById) {
        String axis = incident.getIncidentType() == null ? "재해"
                : incident.getIncidentType().getLabel();
        return "%s %s".formatted(axis, isNearMiss(incident) ? "아차사고" : "사고");
    }

    /**
     * 연결 대상의 표시 이름을 채운다.
     *
     * <p>{@code "연결: assessment-1"}은 개발자의 말이다. 화면에는
     * {@code "2026-06-20 상시 위험성평가"}처럼 사람이 읽는 말이 떠야 한다.
     */
    private List<TimelineEvent> withLinkLabels(List<TimelineEvent> events) {
        Map<String, String> labelById = new HashMap<>();
        for (TimelineEvent e : events) {
            labelById.put(e.id(), "%s %s".formatted(e.at(), e.title()));
        }
        List<TimelineEvent> out = new ArrayList<>(events.size());
        for (TimelineEvent e : events) {
            List<String> labels = e.linkedEventIds().stream()
                    .map(id -> labelById.getOrDefault(id, id))
                    .toList();
            out.add(new TimelineEvent(e.id(), e.type(), e.at(), e.occurredAt(),
                    e.title(), e.detail(), e.riskLevel(), e.accidentType(), e.status(),
                    e.refId(), e.linkedEventIds(), labels, e.causalOrder(), e.emphasis(), e.ruleTrace()));
        }
        return out;
    }

    // ---------- 요약 ----------

    private TimelineDtos.TimelineSummary summary(List<Hazard> hazards,
                                                 Map<Long, Hazard> hazardById,
                                                 Map<Long, Assessment> assessmentById,
                                                 List<Incident> incidents,
                                                 List<WorkPlan> workPlans,
                                                 List<Action> actions,
                                                 List<TimelineEvent> events) {
        CurrentGrade grade = currentGrade(hazardById, assessmentById);

        int overdue = (int) actions.stream().filter(EquipmentTimelineService::isOverdue).count();
        int unfinished = (int) actions.stream().filter(a -> a.getStatus() != ActionStatus.DONE).count();
        int assessmentCount = (int) events.stream()
                .filter(e -> e.type() == EventType.ASSESSMENT).count();
        int nearMiss = (int) incidents.stream().filter(EquipmentTimelineService::isNearMiss).count();
        int incidentCount = incidents.size() - nearMiss;

        return new TimelineDtos.TimelineSummary(grade.level(), grade.axis(), grade.lastAssessedOn(),
                assessmentCount, workPlans.size(), incidentCount, nearMiss, unfinished, overdue,
                headline(grade.level(), incidentCount, nearMiss, overdue, unfinished, hazards));
    }

    /** 현재 등급: 발생형태별 최신 평가 등급의 최댓값과 그 발생형태, 가장 최근 평가일 */
    record CurrentGrade(RiskLevel level, AccidentType axis, LocalDate lastAssessedOn) {}

    /**
     * 현재 등급.
     *
     * <p>마지막 평가 하나의 등급만 보면, 끼임만 다시 본 평가가 떨어짐 상을 덮어 설비가 하로 보인다.
     * 그래서 발생형태마다 가장 최근 평가의 등급(같은 평가 안에서는 가장 높은 것)을 고르고, 그중 가장
     * 높은 등급을 현재 등급으로 한다. 같은 등급이 여럿이면 더 최근에 평가된 발생형태를 고른다.
     */
    CurrentGrade currentGrade(Map<Long, Hazard> hazardById, Map<Long, Assessment> assessmentById) {
        if (hazardById.isEmpty() || assessmentById.isEmpty()) {
            return new CurrentGrade(null, null, null);
        }
        List<AssessmentHazard> links =
                assessmentHazardRepository.findByHazardIdIn(List.copyOf(hazardById.keySet()));
        Comparator<Assessment> recency = Comparator.comparing(Assessment::getAssessedOn)
                .thenComparing(Assessment::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Assessment::getId);

        // 발생형태 -> 그 형태의 가장 최근 평가, 그 평가 안의 최고 등급
        Map<AccidentType, Assessment> latestByAxis = new EnumMap<>(AccidentType.class);
        Map<AccidentType, RiskLevel> levelByAxis = new EnumMap<>(AccidentType.class);
        LocalDate lastAssessedOn = null;
        for (AssessmentHazard link : links) {
            Assessment a = assessmentById.get(link.getAssessmentId());
            AccidentType axis = axisOf(hazardById, link.getHazardId());
            if (a == null || axis == null || link.getRiskLevel() == null) {
                continue;
            }
            if (lastAssessedOn == null || a.getAssessedOn().isAfter(lastAssessedOn)) {
                lastAssessedOn = a.getAssessedOn();
            }
            Assessment cur = latestByAxis.get(axis);
            if (cur == null || recency.compare(a, cur) > 0) {
                latestByAxis.put(axis, a);
                levelByAxis.put(axis, link.getRiskLevel());
            } else if (cur.getId().equals(a.getId())
                    && severityRank(link.getRiskLevel()) > severityRank(levelByAxis.get(axis))) {
                levelByAxis.put(axis, link.getRiskLevel());
            }
        }
        AccidentType best = null;
        for (AccidentType axis : levelByAxis.keySet()) {
            if (best == null) {
                best = axis;
                continue;
            }
            int diff = severityRank(levelByAxis.get(axis)) - severityRank(levelByAxis.get(best));
            if (diff > 0 || (diff == 0 && recency.compare(latestByAxis.get(axis), latestByAxis.get(best)) > 0)) {
                best = axis;
            }
        }
        return best == null ? new CurrentGrade(null, null, lastAssessedOn)
                : new CurrentGrade(levelByAxis.get(best), best, lastAssessedOn);
    }

    static boolean isNearMiss(Incident incident) {
        return incident.getSeverity() == IncidentSeverity.NEAR_MISS;
    }

    /**
     * 상태 칩 하나. 서술 문장이 아니라 가장 강한 사실 하나의 이름과 숫자다
     * ("기한 경과 1", "사고 1", "최초 평가 필요", "미이행 1", "아차사고 1"). 해당 없으면 빈 문자열.
     * 아차사고는 사고 수에 넣지 않는다.
     */
    private String headline(RiskLevel current, int incidentCount, int nearMissCount,
                            int overdue, int unfinished, List<Hazard> hazards) {
        if (overdue > 0) {
            return "기한 경과 %d".formatted(overdue);
        }
        if (incidentCount > 0) {
            return "사고 %d".formatted(incidentCount);
        }
        if (hazards.isEmpty() || current == null) {
            return "최초 평가 필요";
        }
        if (unfinished > 0) {
            return "미이행 %d".formatted(unfinished);
        }
        if (nearMissCount > 0) {
            return "아차사고 %d".formatted(nearMissCount);
        }
        return "";
    }

    /** 지침 근거는 코드가 아니라 지침명으로 보인다. 캐시에 없으면 코드 그대로 */
    private String guideName(String guideRef) {
        String code = guideRef.strip();
        return koshaGuideRepository.findByGuideNo(code)
                .map(KoshaGuide::getGuideName)
                .filter(n -> !n.isBlank())
                .map(n -> plainSeparators(n.strip()))
                .orElse(code);
    }

    // ---------- 보조 ----------

    private TimelineDtos.EquipmentHead head(Equipment equipment) {
        String processName = equipment.getProcessId() == null ? null
                : processRepository.findById(equipment.getProcessId())
                        .map(WorkProcess::getName).orElse(null);
        String locationTag = equipment.getProcessId() == null ? equipment.getLocationTag()
                : processRepository.findById(equipment.getProcessId())
                        .map(WorkProcess::getLocationTag).orElse(equipment.getLocationTag());

        return new TimelineDtos.EquipmentHead(equipment.getId(), equipment.getName(),
                locationTag, processName, equipment.getObjectCode(), equipment.getIntroducedOn());
    }

    private AccidentType axisOf(Map<Long, Hazard> hazardById, Long hazardId) {
        Hazard h = hazardById.get(hazardId);
        return h == null ? null : h.getAccidentType();
    }

    private int severityRank(RiskLevel level) {
        if (level == null) {
            return -1;
        }
        return switch (level) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }

    /**
     * 기한 경과 판정. 상태값이 OVERDUE이거나, 기한(KST)이 지났는데 아직 완료가 아니면 경과다.
     *
     * <p>"오늘 할 일"(TodayService 규칙 1)과 같은 기준이다. 상태 갱신 배치가 없어 PENDING으로 남은
     * 조치가 홈에서는 "기한 경과", 타임라인에서는 "이행 예정"으로 갈리면 심사위원이 바로 짚는다.
     */
    static boolean isOverdue(Action action) {
        if (action.getStatus() == ActionStatus.OVERDUE) {
            return true;
        }
        return action.getStatus() != ActionStatus.DONE
                && action.getDueDate() != null
                && action.getDueDate().isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")));
    }

    /**
     * 화면 문장 구분자 정리. 룰 근거 원문(시드·룰 엔진)에 섞인 가운뎃점과 대시를
     * 쉼표로 바꾼다. 화면 어디에도 이 두 기호를 띄우지 않는 것이 표기 규칙이다.
     */
    static String plainSeparators(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("\\s*[\u2014\u2013]\\s*", ", ")
                .replaceAll("\\s+\u00B7\\s+", ", ")
                .replace("\u00B7", "/");
    }

    /** 완료된 조치는 완료일(KST), 그 밖에는 기한 */
    static LocalDate placedOn(Action action) {
        if (action.getStatus() == ActionStatus.DONE && action.getCompletedAt() != null) {
            return action.getCompletedAt().atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate();
        }
        return action.getDueDate();
    }

    /** 촘촘한 목록의 날짜 표기 MM-DD */
    private static String monthDay(LocalDate date) {
        return "%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth());
    }
}
