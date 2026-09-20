package io.saife.dashboard.service;

import io.saife.core.domain.*;
import io.saife.core.repository.*;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.dashboard.dto.TimelineDtos.Emphasis;
import io.saife.dashboard.dto.TimelineDtos.EventType;
import io.saife.dashboard.dto.TimelineDtos.TimelineEvent;
import io.saife.incident.domain.Incident;
import io.saife.incident.repository.IncidentRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.common.error.ApiExceptions.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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

        events.addAll(assessmentEvents(hazardById, incidents, assessmentEventIdByAssessment));
        events.addAll(actionEvents(actions, assessmentEventIdByAssessment));
        events.addAll(workPlanEvents(workPlans, actions));
        events.addAll(incidentEvents(incidents, hazardById, actions, assessmentEventIdByAssessment));

        events.sort(Comparator.comparing(TimelineEvent::at)
                .thenComparingInt(TimelineEvent::causalOrder)
                .thenComparing(TimelineEvent::id));

        // 내부 식별자(assessment-1)를 화면에 띄우지 않는다. 심사위원이 보는 화면이다
        events = withLinkLabels(events);

        TimelineDtos.TimelineSummary summary =
                summary(hazards, incidents, workPlans, actions, events);

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
                                                 Map<Long, String> eventIdOut) {
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

            String detail = entry.getValue().size() + "건의 위험요인 평가"
                    + (worst != null && worst.getRuleTrace() != null
                            ? " · " + worst.getRuleTrace() : "");

            out.add(new TimelineEvent(eventId, EventType.ASSESSMENT,
                    assessment.getAssessedOn(), assessment.getCreatedAt(),
                    "%s 위험성평가".formatted(assessment.getKind().getLabel()),
                    detail, level,
                    worst == null ? null : axisOf(hazardById, worst.getHazardId()),
                    assessment.getStatus(), assessment.getId(), linked, List.of(),
                    triggerIncidentId != null ? ORDER_FOLLOW_UP : ORDER_ASSESSMENT,
                    level == RiskLevel.HIGH ? Emphasis.WARNING : Emphasis.NORMAL));
        }
        return out;
    }

    /** 조치 사건. 기한 위에 놓는다 — 타임라인에서 의미 있는 날짜는 기한이다 */
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

            boolean overdue = action.getStatus() == ActionStatus.OVERDUE;
            String detail = switch (action.getStatus()) {
                case DONE -> "이행 완료";
                case OVERDUE -> "기한 경과 · 미이행";
                case PENDING -> "이행 예정";
            };
            if (action.getGuideRef() != null && !action.getGuideRef().isBlank()) {
                detail += " · 근거 " + action.getGuideRef();
            }

            out.add(new TimelineEvent("action-" + action.getId(), EventType.ACTION,
                    action.getDueDate(), action.getCreatedAt(),
                    action.getContent(), detail, null, null,
                    action.getStatus().name(), action.getId(), linked, List.of(),
                    ORDER_ACTION,
                    overdue ? Emphasis.CRITICAL : Emphasis.NORMAL));
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

            String detail = acknowledged
                    ? "브리핑 확인 완료 (TBM 이행 기록)"
                    : "브리핑 미확인";
            if (!linked.isEmpty()) {
                detail += " · 미이행 조치 %d건을 경고".formatted(linked.size());
            }

            out.add(new TimelineEvent("workplan-" + plan.getId(), EventType.WORK_PLAN,
                    plan.getWorkDate(), plan.getBriefingAckAt(),
                    plan.getWorkName(), detail, null, null,
                    plan.getStatus().name(), plan.getId(), linked, List.of(),
                    ORDER_WORK_PLAN,
                    acknowledged && !linked.isEmpty() ? Emphasis.WARNING : Emphasis.NORMAL));
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
            if (incident.getReportDueDate() != null) {
                if (detail.length() > 0) {
                    detail.append(" · ");
                }
                detail.append("조사표 제출 기한 ").append(incident.getReportDueDate());
            }
            if (detail.length() == 0) {
                detail.append(nvl(incident.getDescription(), "재해 발생"));
            }

            out.add(new TimelineEvent("incident-" + incident.getId(), EventType.INCIDENT,
                    incident.getOccurredAt().toLocalDate(), incident.getOccurredAt(),
                    incidentTitle(incident, hazardById), detail.toString(),
                    null, incident.getAccidentType(),
                    incident.getReportStatus().name(), incident.getId(), linked, List.of(),
                    ORDER_INCIDENT, Emphasis.CRITICAL));
        }
        return out;
    }

    private String incidentTitle(Incident incident, Map<Long, Hazard> hazardById) {
        String axis = incident.getAccidentType() == null ? "재해"
                : incident.getAccidentType().getLabel();
        return "%s 사고 발생".formatted(axis);
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
                    e.refId(), e.linkedEventIds(), labels, e.causalOrder(), e.emphasis()));
        }
        return out;
    }

    // ---------- 요약 ----------

    private TimelineDtos.TimelineSummary summary(List<Hazard> hazards,
                                                 List<Incident> incidents,
                                                 List<WorkPlan> workPlans,
                                                 List<Action> actions,
                                                 List<TimelineEvent> events) {
        TimelineEvent latestAssessment = events.stream()
                .filter(e -> e.type() == EventType.ASSESSMENT)
                .reduce((a, b) -> b)
                .orElse(null);

        RiskLevel current = null;
        AccidentType currentAxis = null;
        LocalDate lastAssessedOn = null;
        if (latestAssessment != null) {
            current = latestAssessment.riskLevel();
            currentAxis = latestAssessment.accidentType();
            lastAssessedOn = latestAssessment.at();
        }

        int overdue = (int) actions.stream().filter(a -> a.getStatus() == ActionStatus.OVERDUE).count();
        int unfinished = (int) actions.stream().filter(a -> a.getStatus() != ActionStatus.DONE).count();
        int assessmentCount = (int) events.stream()
                .filter(e -> e.type() == EventType.ASSESSMENT).count();

        return new TimelineDtos.TimelineSummary(current, currentAxis, lastAssessedOn,
                assessmentCount, workPlans.size(), incidents.size(), unfinished, overdue,
                headline(current, incidents, overdue, hazards));
    }

    /**
     * 한 줄 요약. 프로젝터에서 이 줄만 읽혀도 논지가 전달돼야 한다.
     *
     * <p>강한 사실부터 고른다. 없는 사실을 만들지 않는다 — 조용한 설비는
     * 조용하다고 말하는 게 맞다.
     */
    private String headline(RiskLevel current, List<Incident> incidents,
                            int overdue, List<Hazard> hazards) {
        if (!incidents.isEmpty() && overdue > 0) {
            return ("사고 %d건이 발생했고, 기한이 지난 미이행 조치가 %d건 남아 있습니다. "
                    + "평가에서 지적된 위험이 조치로 이어지지 않았습니다.")
                    .formatted(incidents.size(), overdue);
        }
        if (!incidents.isEmpty()) {
            return "사고 %d건 이후 수시평가로 이어졌습니다.".formatted(incidents.size());
        }
        if (overdue > 0) {
            return "기한이 지난 미이행 조치가 %d건 있습니다. 사고 전에 닫아야 하는 항목입니다."
                    .formatted(overdue);
        }
        if (current == RiskLevel.HIGH) {
            return "최근 평가에서 위험성 '상'으로 판정된 설비입니다.";
        }
        if (hazards.isEmpty()) {
            return "등록된 위험요인이 없습니다. 최초 평가가 필요합니다.";
        }
        return "현재 미이행 조치와 사고 이력이 없습니다.";
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

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
