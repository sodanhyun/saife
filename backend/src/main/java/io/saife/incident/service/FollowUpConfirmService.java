package io.saife.incident.service;

import io.saife.common.error.ApiExceptions.ConflictException;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.common.error.ApiExceptions.NotFoundException;
import io.saife.core.action.ActionDtos;
import io.saife.core.action.ActionService;
import io.saife.core.action.ActionSuggestionTable;
import io.saife.core.action.InspectionRecordStore;
import io.saife.core.action.InspectionRules;
import io.saife.core.domain.*;
import io.saife.core.repository.*;
import io.saife.incident.domain.Incident;
import io.saife.incident.dto.IncidentDtos;
import io.saife.incident.repository.FollowUpRecordStore;
import io.saife.incident.repository.IncidentRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.service.WorkPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 사고가 만든 수시평가를 사람이 채우고 확정한다. <b>작업 보류를 푸는 유일한 경로다.</b>
 *
 * <p>시행규칙 제37조제2항제3호: 산업재해가 발생한 경우 관련 작업을 시작하기 전까지 수시평가.
 * 확정 조건은 순회점검 기록과 같은 규칙({@link InspectionRules#complete})이다. 참여 근로자가 있고
 * (제37조의2), 허용 불가 위험요인마다 개선대책(담당, 기한)이 있어야 한다.
 *
 * <p>확정하면 같은 설비의 작업 보류(HOLD) 작업 전 점검을 재승인 대기(SUBMITTED)로 돌린다.
 * 원래 승인 상태로 바로 되돌리지 않는다. 수시평가로 대책이 바뀌었으니 관리감독자가 다시 승인해야 한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FollowUpConfirmService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    static final String STATUS_DRAFT = "DRAFT";
    static final String STATUS_CONFIRMED = "CONFIRMED";

    /** 보류를 푼 작업 전 점검에 남기는 문구의 머리. 사고 화면이 이 문구로 "이 사고가 보류했다 푼 작업"을 찾는다 */
    public static final String RELEASE_PREFIX = "보류 해제: ";
    /** 보류를 푼 작업 전 점검에 남기는 문구. 날짜는 확정일 */
    static final String RELEASE_NOTE = RELEASE_PREFIX + "%s 수시평가 확정, 관리감독자 재승인 필요";

    private static final int TEXT_MAX = 500;
    private static final int NAME_MAX = 100;

    private final AssessmentRepository assessmentRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final HazardRepository hazardRepository;
    private final ActionRepository actionRepository;
    private final EquipmentRepository equipmentRepository;
    private final ProcessRepository processRepository;
    private final IncidentRepository incidentRepository;
    private final WorkPlanRepository workPlanRepository;
    private final ActionService actionService;
    private final InspectionRecordStore records;
    private final FollowUpRecordStore followUpRecords;
    private final FollowUpAssessmentService followUpAssessmentService;
    private final WorkPlanService workPlanService;

    // ────────────────────────── 조회 ──────────────────────────

    @Transactional(readOnly = true)
    public IncidentDtos.FollowUpDetail detail(Long assessmentId) {
        Assessment assessment = findFollowUp(assessmentId);
        Incident incident = incidentOf(assessment);
        return view(assessment, incident);
    }

    // ────────────────────────── 저장, 확정 ──────────────────────────

    /** 작성 중 저장. 검증은 형식만 본다(확정 조건은 보지 않는다) */
    @Transactional
    public IncidentDtos.FollowUpDetail save(Long assessmentId, IncidentDtos.FollowUpRequest request) {
        Assessment assessment = findFollowUp(assessmentId);
        requireDraft(assessment);
        Incident incident = incidentOf(assessment);
        apply(assessment, request);
        return view(assessment, incident);
    }

    /**
     * 확정. 입력을 저장하고, 확정 조건을 확인한 뒤, 평가를 CONFIRMED로 바꾸고 작업 보류를 푼다.
     * 조건이 모자라면 400으로 무엇이 빠졌는지 말한다(아무것도 바꾸지 않는다: 트랜잭션이 되돌린다).
     */
    @Transactional
    public IncidentDtos.FollowUpDetail confirm(Long assessmentId, IncidentDtos.FollowUpRequest request) {
        Assessment assessment = findFollowUp(assessmentId);
        requireDraft(assessment);
        Incident incident = incidentOf(assessment);
        apply(assessment, request);

        String participants = records.inspection(assessmentId)
                .map(InspectionRecordStore.Inspection::participants)
                .orElse(assessment.getParticipants());
        if (InspectionRules.splitParticipants(participants).isEmpty()) {
            throw new InvalidRequestException("참여 근로자를 입력하십시오.");
        }
        Map<Long, Boolean> stored = records.acceptableByHazard(assessmentId);
        for (AssessmentHazard link : assessmentHazardRepository.findByAssessmentId(assessmentId)) {
            boolean acceptable = InspectionRules.acceptable(stored.get(link.getHazardId()), link.getRiskLevel());
            if (acceptable) {
                continue;
            }
            if (!hasPlan(link.getHazardId(), assessmentId)) {
                String name = hazardRepository.findById(link.getHazardId())
                        .map(h -> h.getMissingControl() == null ? h.getDescription() : h.getMissingControl())
                        .orElse("위험요인");
                throw new InvalidRequestException("허용 불가 위험요인에 개선대책(담당, 기한)을 입력하십시오: " + name);
            }
        }

        assessment.confirm();
        assessmentRepository.save(assessment);
        OffsetDateTime now = OffsetDateTime.now();
        followUpRecords.saveConfirmedAt(assessmentId, now);

        List<WorkPlan> released = releaseHolds(incident, now.atZoneSameInstant(KST).toLocalDate());
        log.info("[UC2] 수시평가 {} 확정, 사고 {}, 보류 해제 {}건", assessmentId,
                incident == null ? null : incident.getId(), released.size());
        return view(assessment, incident);
    }

    /** 입력 반영: 점검 정보, 위험요인별 허용 여부, 새 개선대책 */
    private void apply(Assessment assessment, IncidentDtos.FollowUpRequest request) {
        if (request == null) {
            return;
        }
        Long assessmentId = assessment.getId();
        String inspector = trimToNull(request.inspector());
        if (inspector != null && inspector.length() > NAME_MAX) {
            throw new InvalidRequestException("담당자는 %d자 이하로 입력하십시오.".formatted(NAME_MAX));
        }
        records.saveInspection(assessmentId, inspector, InspectionRules.joinParticipants(request.participants()));

        if (request.hazards() == null) {
            return;
        }
        Set<Long> linked = new HashSet<>();
        assessmentHazardRepository.findByAssessmentId(assessmentId).forEach(l -> linked.add(l.getHazardId()));
        LocalDate today = LocalDate.now(KST);
        for (IncidentDtos.FollowUpHazardInput in : request.hazards()) {
            if (in == null || in.hazardId() == null) {
                continue;
            }
            if (!linked.contains(in.hazardId())) {
                throw new InvalidRequestException("이 수시평가에 없는 위험요인입니다.");
            }
            if (in.acceptable() != null) {
                records.saveAcceptable(assessmentId, in.hazardId(), in.acceptable());
            }
            String content = trimToNull(in.content());
            if (content == null || Boolean.TRUE.equals(in.acceptable())) {
                continue;
            }
            if (actionService.findFor(in.hazardId(), assessmentId).isPresent()) {
                continue;   // 이 평가에서 이미 세운 대책이 있다(다시 누른 확정)
            }
            String owner = trimToNull(in.owner());
            if (owner == null) {
                throw new InvalidRequestException("개선대책의 담당을 입력하십시오.");
            }
            if (in.dueDate() == null) {
                throw new InvalidRequestException("개선대책의 기한을 입력하십시오.");
            }
            if (in.dueDate().isBefore(today)) {
                throw new InvalidRequestException("개선대책의 기한은 오늘 이후로 입력하십시오.");
            }
            if (content.length() > TEXT_MAX) {
                throw new InvalidRequestException("개선대책은 %d자 이하로 입력하십시오.".formatted(TEXT_MAX));
            }
            Hazard hazard = hazardRepository.findById(in.hazardId()).orElse(null);
            ActionSuggestionTable.Suggestion s = hazard == null ? null
                    : ActionSuggestionTable.suggest(hazard.getAccidentType(), hazard.getMissingControl(), null);
            actionService.createForHazard(in.hazardId(), new ActionDtos.CreateActionRequest(
                    assessmentId, content, owner, in.dueDate(), null,
                    s != null && s.content().equals(content) ? s.priority() : null));
        }
    }

    /** 개선대책이 있는가: 이 평가에서 세운 것, 또는 기한이 남은 기존 미완료 대책 */
    private boolean hasPlan(Long hazardId, Long assessmentId) {
        if (actionService.findFor(hazardId, assessmentId).isPresent()) {
            return true;
        }
        return actionService.findPriorOpen(hazardId, assessmentId)
                .filter(a -> !InspectionRules.overdue(a.getDueDate()))
                .isPresent();
    }

    /**
     * 같은 설비의 작업 보류를 재승인 대기로 돌린다. 보류를 푸는 일은 작업 전 점검 쪽
     * ({@link WorkPlanService#releaseHolds})이 한다(승인 기록과 보류 문구를 지운다). 그 뒤 이 사고가 보류했던
     * 점검표에 해제 문구를 남겨, 사고 화면과 재발방지 검토서가 "보류했다가 푼 작업"을 계속 찾을 수 있게 한다.
     */
    private List<WorkPlan> releaseHolds(Incident incident, LocalDate confirmedOn) {
        if (incident == null || incident.getEquipmentId() == null) {
            return List.of();
        }
        List<Long> ours = heldPlans(incident, List.of(WorkPlanStatus.HOLD)).stream().map(WorkPlan::getId).toList();
        workPlanService.releaseHolds(incident.getEquipmentId());
        List<WorkPlan> out = new ArrayList<>();
        for (Long id : ours) {
            workPlanRepository.findById(id).ifPresent(plan -> {
                plan.appendWarning(RELEASE_NOTE.formatted(confirmedOn));
                workPlanRepository.save(plan);
                out.add(plan);
            });
        }
        return out;
    }

    /** 이 사고가 보류한(또는 보류했다가 푼) 작업 전 점검(상태 조건 포함) */
    private List<WorkPlan> heldPlans(Incident incident, List<WorkPlanStatus> statuses) {
        LocalDate occurredOn = incident.getOccurredAt().atZoneSameInstant(KST).toLocalDate();
        return workPlanRepository
                .findBySiteIdAndEquipmentIdAndStatusInAndWorkDateGreaterThanEqualOrderByWorkDateAsc(
                        incident.getSiteId(), incident.getEquipmentId(), statuses, occurredOn)
                .stream()
                .filter(FollowUpConfirmService::heldByIncident)
                .toList();
    }

    /** 사고가 붙인 보류 문구나, 수시평가 확정으로 남긴 해제 문구가 있는 점검표 */
    public static boolean heldByIncident(WorkPlan plan) {
        String note = plan.getWarningNote();
        return note != null && (note.contains(IncidentService.HOLD_WARNING) || note.contains(RELEASE_PREFIX));
    }

    // ────────────────────────── 뷰 ──────────────────────────

    private IncidentDtos.FollowUpDetail view(Assessment assessment, Incident incident) {
        Long assessmentId = assessment.getId();
        Equipment equipment = incident == null || incident.getEquipmentId() == null ? null
                : equipmentRepository.findById(incident.getEquipmentId()).orElse(null);
        String location = equipment == null ? null
                : Optional.ofNullable(equipment.getProcessId())
                        .flatMap(processRepository::findById)
                        .map(WorkProcess::getLocationTag)
                        .orElse(equipment.getLocationTag());

        InspectionRecordStore.Inspection inspection = records.inspection(assessmentId).orElse(null);
        String participantsRaw = inspection == null ? assessment.getParticipants() : inspection.participants();
        Map<Long, Boolean> stored = records.acceptableByHazard(assessmentId);

        Map<Long, RiskLevel> before = new HashMap<>();
        followUpAssessmentService.reconstruct(assessmentId).regraded()
                .forEach(g -> before.put(g.hazardId(), g.before()));

        List<IncidentDtos.FollowUpHazard> hazards = new ArrayList<>();
        for (AssessmentHazard link : assessmentHazardRepository.findByAssessmentId(assessmentId)) {
            Hazard h = hazardRepository.findById(link.getHazardId()).orElse(null);
            if (h == null) {
                continue;
            }
            boolean sameAxis = incident != null && incident.getAccidentType() != null
                    && incident.getAccidentType() == h.getAccidentType();
            Action own = actionService.findFor(h.getId(), assessmentId).orElse(null);
            Action prior = actionService.findPriorOpen(h.getId(), assessmentId).orElse(null);
            ActionSuggestionTable.Suggestion s = ActionSuggestionTable.suggest(h.getAccidentType(), h.getMissingControl(), null);
            hazards.add(new IncidentDtos.FollowUpHazard(h.getId(), h.getAccidentType(), h.getMissingControl(),
                    h.getDescription(), before.get(h.getId()), link.getRiskLevel(), link.getRuleTrace(), sameAxis,
                    InspectionRules.acceptable(stored.get(h.getId()), link.getRiskLevel()),
                    actionView(own), actionView(prior),
                    s == null ? null : new IncidentDtos.FollowUpSuggestion(s.content(), s.lawRef())));
        }
        // 사고와 같은 발생형태가 맨 위, 그다음 등급이 높은 순
        hazards.sort(Comparator.comparing(IncidentDtos.FollowUpHazard::sameAxis).reversed()
                .thenComparing(g -> rank(g.riskLevel()), Comparator.reverseOrder()));

        List<IncidentDtos.AffectedWorkPlan> plans = incident == null || incident.getEquipmentId() == null ? List.of()
                : heldPlans(incident, Arrays.asList(WorkPlanStatus.values())).stream()
                        .map(p -> new IncidentDtos.AffectedWorkPlan(p.getId(), p.getWorkName(), p.getWorkDate(),
                                p.getStatus().name(), IncidentService.HOLD_WARNING))
                        .toList();

        boolean confirmed = STATUS_CONFIRMED.equals(assessment.getStatus());
        return new IncidentDtos.FollowUpDetail(
                assessmentId,
                incident == null ? null : incident.getId(),
                confirmed ? STATUS_CONFIRMED : STATUS_DRAFT,
                assessment.getAssessedOn(),
                confirmed ? confirmedOn(assessment) : null,
                equipment == null ? null : equipment.getId(),
                equipment == null ? null : equipment.getName(),
                location,
                incident == null ? null : incident.getOccurredAt(),
                incident == null ? null : incident.getIncidentType(),
                incident == null || incident.getIncidentType() == null ? null : incident.getIncidentType().getLabel(),
                incident == null ? null : incident.getSeverity(),
                IncidentService.FOLLOW_UP_LEGAL_BASIS,
                inspection == null ? null : inspection.inspector(),
                InspectionRules.splitParticipants(participantsRaw),
                hazards,
                plans);
    }

    /** 확정일. 확정 시각 기록이 없으면(시드 등) 평가일 */
    LocalDate confirmedOn(Assessment assessment) {
        return followUpRecords.confirmedAt(assessment.getId())
                .map(t -> t.atZoneSameInstant(KST).toLocalDate())
                .orElse(assessment.getAssessedOn());
    }

    private IncidentDtos.FollowUpAction actionView(Action a) {
        if (a == null) {
            return null;
        }
        return new IncidentDtos.FollowUpAction(a.getId(), a.getContent(), a.getOwner(), a.getDueDate(),
                a.getStatus(), a.getCompletedAt() == null ? null : a.getCompletedAt().atZoneSameInstant(KST).toLocalDate());
    }

    // ────────────────────────── 조회 보조 ──────────────────────────

    private Assessment findFollowUp(Long assessmentId) {
        Assessment a = assessmentRepository.findById(assessmentId).orElseThrow(
                () -> new NotFoundException("수시평가를 찾을 수 없습니다."));
        if (a.getKind() != AssessmentKind.OCCASIONAL
                || !FollowUpAssessmentService.TRIGGER_INCIDENT.equals(a.getTriggerType())) {
            throw new NotFoundException("수시평가를 찾을 수 없습니다.");
        }
        return a;
    }

    private void requireDraft(Assessment assessment) {
        if (STATUS_CONFIRMED.equals(assessment.getStatus())) {
            throw new ConflictException("이미 확정한 수시평가입니다.");
        }
    }

    private Incident incidentOf(Assessment assessment) {
        return incidentRepository.findFirstByFollowUpAssessmentId(assessment.getId())
                .or(() -> assessment.getTriggerRefId() == null ? Optional.empty()
                        : incidentRepository.findById(assessment.getTriggerRefId()))
                .orElse(null);
    }

    private static int rank(RiskLevel level) {
        if (level == null) {
            return 0;
        }
        return switch (level) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
