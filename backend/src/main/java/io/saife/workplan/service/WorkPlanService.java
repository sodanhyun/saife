package io.saife.workplan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.core.domain.Equipment;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.service.RiskRuleEngine;
import io.saife.evidence.Evidence;
import io.saife.evidence.repository.WorkPlanEvidenceRepository;
import io.saife.incident.domain.Incident;
import io.saife.incident.repository.IncidentRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.domain.WorkPlanWorker;
import io.saife.workplan.dto.WorkPlanDtos;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.repository.WorkPlanSlotRepository;
import io.saife.workplan.repository.WorkPlanWorkerRepository;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.common.error.ApiExceptions.NotFoundException;
import io.saife.workplan.domain.WorkDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 작업 전 점검(점검표) 조회, 관리감독자 승인, 작업 보류.
 *
 * <p>작성은 에이전트 도구가 한다({@code WorkPlanTools}). 여기는 그 뒤의 수명주기다.
 * 순서는 승인 대기, 승인(또는 조건부 승인)이다. 승인 뒤 서식을 출력해 작업자 안내와 서명을 받는다.
 *
 * <p>화면에 그대로 뜨는 오류 문구에는 내부 ID와 상태 코드를 넣지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkPlanService {

    /** 잠정조치가 이 말을 담고 있으면 그 작업은 하지 않는다는 뜻이다. 승인이 아니라 보류다 */
    private static final String STOP_WORK = "(?s).*(작업\\s*금지|작업\\s*중지|작업\\s*중단).*";
    /** 관리감독자(작업지휘자)로 보는 직책 */
    private static final String SUPERVISOR_POSITION = ".*(반장|관리감독자|작업지휘자|조장|팀장).*";

    private final WorkPlanRepository workPlanRepository;
    private final WorkPlanSlotRepository workPlanSlotRepository;
    private final WorkPlanWorkerRepository workPlanWorkerRepository;
    private final EquipmentRepository equipmentRepository;
    private final WorkPlanEvidenceRepository workPlanEvidenceRepository;
    private final ObjectMapper objectMapper;
    private final BriefingViewBuilder briefingViewBuilder;
    private final IncidentRepository incidentRepository;

    @Transactional(readOnly = true)
    public Page<WorkPlanDtos.ListItem> list(Long siteId, Pageable pageable) {
        return list(siteId, null, null, pageable);
    }

    /**
     * 점검 기록 목록. 작업명 또는 설비명 검색, 상태 필터.
     *
     * @param keyword 검색어. 비우면 전체
     * @param status  상태. null이면 전체
     */
    @Transactional(readOnly = true)
    public Page<WorkPlanDtos.ListItem> list(Long siteId, String keyword, WorkPlanStatus status, Pageable pageable) {
        String pattern = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.strip().toLowerCase() + "%";
        List<WorkPlanStatus> statuses = status == null ? Arrays.asList(WorkPlanStatus.values()) : List.of(status);
        return workPlanRepository.search(siteId, pattern, statuses, pageable)
                .map(plan -> {
                    String equipmentName = equipmentName(plan.getEquipmentId());
                    return new WorkPlanDtos.ListItem(
                            plan.getId(), plan.getEquipmentId(), equipmentName,
                            plan.getWorkName(), plan.getWorkPlace(), plan.getWorkDate(),
                            plan.getStatus(), plan.getBriefingAckAt() != null, plan.getBriefingAckAt(),
                            WorkDocument.of(plan.getWorkName(), equipmentName).type());
                });
    }

    @Transactional(readOnly = true)
    public WorkPlanDtos.Detail detail(Long workPlanId) {
        return toDetail(load(workPlanId));
    }

    /**
     * 관리감독자 승인. 조건(잠정조치)을 달면 CONDITIONAL이 된다.
     *
     * <p>판정에 상이 있고 대책이 아직 이행되지 않았으면 잠정조치 없이는 승인하지 않는다
     * (고시 제12조제4항: 감소대책 이행이 늦어지면 잠정조치를 정해 작업한다). 화면도 같은 값
     * ({@code briefingView.interimRequired})으로 입력란과 "조건부 승인" 버튼을 그린다.
     *
     * <p>잠정조치가 "작업 금지", "작업 중지"면 그 작업을 하지 않는다는 뜻이라 승인할 수 없다. 보류로 둔다.
     *
     * @param approver 승인자. 비우면 이 점검표의 관리감독자(작업 담당 반장)
     */
    @Transactional
    public WorkPlanDtos.Detail approve(Long workPlanId, String approver, String condition) {
        WorkPlan plan = load(workPlanId);

        if (plan.getStatus() != WorkPlanStatus.SUBMITTED) {
            throw new IllegalStateException("승인 대기 중인 점검 기록이 아닙니다.");
        }
        if (condition != null && condition.matches(STOP_WORK)) {
            throw new InvalidRequestException("잠정조치가 작업 금지입니다. 승인하지 말고 작업 보류로 두십시오.");
        }
        if ((condition == null || condition.isBlank()) && briefingViewBuilder.compute(plan).interimRequired()) {
            throw new InvalidRequestException("상 판정의 감소대책이 이행되지 않았습니다. 잠정조치를 입력해야 승인할 수 있습니다.");
        }

        String by = approver == null || approver.isBlank() ? supervisorOf(plan.getId()) : approver.trim();
        plan.approve(by == null ? "관리감독자" : by, condition == null || condition.isBlank() ? null : condition.trim());
        workPlanRepository.save(plan);
        log.info("[UC3] 점검표 {} {} ({})", workPlanId, plan.getStatus(), plan.getApprovedBy());
        return toDetail(plan);
    }

    /**
     * 작업 보류. 승인 대기 중인 점검표를 승인하지 않고 멈춘다. 사유는 경고로 남아 화면, 서식, 오늘 할 일에 보인다.
     */
    @Transactional
    public WorkPlanDtos.Detail hold(Long workPlanId, String reason) {
        WorkPlan plan = load(workPlanId);
        if (plan.getStatus() != WorkPlanStatus.SUBMITTED) {
            throw new IllegalStateException("승인 대기 중인 점검 기록만 보류할 수 있습니다.");
        }
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("보류 사유를 입력하십시오.");
        }
        plan.hold();
        plan.appendWarning("작업 보류: " + reason.strip());
        workPlanRepository.save(plan);
        log.info("[UC3] 점검표 {} 작업 보류", workPlanId);
        return toDetail(plan);
    }

    /**
     * 수시평가 확정 후 같은 설비의 보류를 푼다(UC2 수시평가 화면이 부른다). 보류된 점검표는 승인 대기로 돌아가
     * 관리감독자가 다시 승인한다.
     *
     * @return 보류를 푼 점검표 수
     */
    @Transactional
    public int releaseHolds(Long equipmentId) {
        if (equipmentId == null) {
            return 0;
        }
        int released = 0;
        for (WorkPlan plan : workPlanRepository.findByEquipmentIdOrderByWorkDateDesc(equipmentId)) {
            if (plan.getStatus() == WorkPlanStatus.HOLD) {
                plan.releaseHold();
                workPlanRepository.save(plan);
                released++;
            }
        }
        if (released > 0) log.info("[UC3] 설비 {} 작업 보류 해제 {}건", equipmentId, released);
        return released;
    }

    private WorkPlan load(Long workPlanId) {
        return workPlanRepository.findById(workPlanId).orElseThrow(
                () -> new NotFoundException("점검 기록을 찾을 수 없습니다."));
    }

    /** 작업자 중 반장(관리감독자) 실명. 없으면 null */
    private String supervisorOf(Long workPlanId) {
        return workPlanWorkerRepository.findByWorkPlanId(workPlanId).stream()
                .filter(w -> w.getPosition() != null && w.getPosition().matches(SUPERVISOR_POSITION))
                .map(WorkPlanWorker::getName)
                .findFirst()
                .orElse(null);
    }

    /** 보류를 푸는 수시평가: 같은 설비의 가장 최근 사고가 만든 평가 */
    private Long holdAssessmentId(WorkPlan plan) {
        if (plan.getStatus() != WorkPlanStatus.HOLD || plan.getEquipmentId() == null) {
            return null;
        }
        return incidentRepository.findByEquipmentIdOrderByOccurredAtDesc(plan.getEquipmentId()).stream()
                .map(Incident::getFollowUpAssessmentId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private WorkPlanDtos.Detail toDetail(WorkPlan plan) {
        String equipmentName = equipmentName(plan.getEquipmentId());
        String kind = RiskRuleEngine.equipmentKind(equipmentName, plan.getWorkName());
        Map<String, String> labels = RiskRuleEngine.SlotKeys.labels();

        // 되묻는 순서(발판 높이, 디딤대, 넘어짐 방지, 제품명)대로 보인다
        List<String> order = new java.util.ArrayList<>(labels.keySet());
        List<WorkPlanDtos.Slot> slots = workPlanSlotRepository.findByWorkPlanId(plan.getId())
                .stream()
                .sorted(java.util.Comparator.comparingInt(s -> order.indexOf(s.getSlotKey()) < 0 ? 99 : order.indexOf(s.getSlotKey())))
                .map(s -> new WorkPlanDtos.Slot(s.getSlotKey(),
                        labels.getOrDefault(s.getSlotKey(), s.getSlotKey()),
                        RiskRuleEngine.SlotKeys.displayValue(s.getSlotKey(), s.getAnsweredValue()),
                        RiskRuleEngine.SlotKeys.question(s.getSlotKey(), kind),
                        s.getLedgerValue(), s.getAnsweredValue(), s.isConflicted(), s.getAnsweredAt()))
                .toList();
        WorkDocument document = WorkDocument.of(plan.getWorkName(), equipmentName);

        List<WorkPlanDtos.Worker> workers = workPlanWorkerRepository.findByWorkPlanId(plan.getId())
                .stream()
                .map(w -> new WorkPlanDtos.Worker(w.getId(), w.getName(), w.getPosition(), w.getDuty()))
                .toList();

        // createWorkPlan 시점에 원장 스냅샷으로 저장된 근거. "참고 자료" 그리드가 이걸 읽는다.
        // payload 디코딩 실패는 그 한 건만 건너뛴다. 근거 한 장 복원 실패로 상세 조회 전체가 깨지면 안 된다.
        List<Evidence> evidence = workPlanEvidenceRepository.findByWorkPlanIdOrderByEvidenceNo(plan.getId())
                .stream()
                .map(r -> {
                    try {
                        return objectMapper.readValue(r.getPayload(), Evidence.class);
                    } catch (Exception e) {
                        log.warn("[WORKPLAN] 근거 복원 실패 workPlanId={} no={}: {}",
                                plan.getId(), r.getEvidenceNo(), e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        return new WorkPlanDtos.Detail(plan.getId(), plan.getSiteId(), plan.getEquipmentId(),
                equipmentName, plan.getConversationId(),
                plan.getWorkName(), plan.getWorkPlace(), plan.getWorkDate(), plan.getWorkHours(),
                plan.getMethod(), plan.getNotes(), plan.getBriefing(), plan.getBriefingAckAt(),
                plan.getStatus(), plan.getApprovalNote(), plan.getApprovedBy(), plan.getApprovedAt(),
                slots, workers, evidence, plan.getWarningNote(), briefingViewBuilder.build(plan),
                document.type(), document.title(), supervisorOf(plan.getId()), holdAssessmentId(plan));
    }

    private String equipmentName(Long equipmentId) {
        return equipmentId == null ? null
                : equipmentRepository.findById(equipmentId).map(Equipment::getName).orElse(null);
    }
}
