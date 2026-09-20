package io.saife.workplan.service;

import io.saife.core.domain.Equipment;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.service.RiskRuleEngine;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.dto.WorkPlanDtos;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.repository.WorkPlanSlotRepository;
import io.saife.workplan.repository.WorkPlanWorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 작업계획서 조회·승인·브리핑 확인.
 *
 * <p>작성은 에이전트 도구가 한다({@code WorkPlanTools}). 여기는 그 뒤의 수명주기다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkPlanService {

    private final WorkPlanRepository workPlanRepository;
    private final WorkPlanSlotRepository workPlanSlotRepository;
    private final WorkPlanWorkerRepository workPlanWorkerRepository;
    private final EquipmentRepository equipmentRepository;

    @Transactional(readOnly = true)
    public Page<WorkPlanDtos.ListItem> list(Long siteId, Pageable pageable) {
        return workPlanRepository.findBySiteIdOrderByWorkDateDesc(siteId, pageable)
                .map(plan -> new WorkPlanDtos.ListItem(
                        plan.getId(), plan.getEquipmentId(), equipmentName(plan.getEquipmentId()),
                        plan.getWorkName(), plan.getWorkPlace(), plan.getWorkDate(),
                        plan.getStatus(), plan.getBriefingAckAt() != null, plan.getBriefingAckAt()));
    }

    @Transactional(readOnly = true)
    public WorkPlanDtos.Detail detail(Long workPlanId) {
        return toDetail(load(workPlanId));
    }

    /**
     * 작업자가 브리핑을 확인했다.
     *
     * <p>이 시각이 상시평가 트랙의 TBM 이행 증빙이고, 사고가 나면 UC2가
     * "경고는 전달됐다"의 근거로 소환한다. <b>확인을 두 번 눌러도 최초 시각을 유지한다</b> —
     * 덮어쓰면 "언제 알렸나"가 사라진다.
     */
    @Transactional
    public WorkPlanDtos.Detail acknowledgeBriefing(Long workPlanId) {
        WorkPlan plan = load(workPlanId);

        if (plan.getBriefing() == null || plan.getBriefing().isBlank()) {
            throw new IllegalStateException(
                    "브리핑이 아직 생성되지 않았습니다. 작업계획서를 먼저 제출하십시오 [id=%d]".formatted(workPlanId));
        }
        if (plan.getBriefingAckAt() != null) {
            log.debug("[UC3] 이미 확인된 브리핑 workPlanId={} at={}", workPlanId, plan.getBriefingAckAt());
            return toDetail(plan);
        }

        plan.acknowledgeBriefing();
        workPlanRepository.save(plan);
        log.info("[UC3] 브리핑 확인 기록 workPlanId={} at={}", workPlanId, plan.getBriefingAckAt());
        return toDetail(plan);
    }

    /** 관리부 승인. 조건을 달면 CONDITIONAL이 된다 */
    @Transactional
    public WorkPlanDtos.Detail approve(Long workPlanId, String approver, String condition) {
        WorkPlan plan = load(workPlanId);

        if (plan.getStatus() != WorkPlanStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "승인 대기 상태가 아닙니다 [id=%d, 상태=%s]".formatted(workPlanId, plan.getStatus()));
        }

        plan.approve(approver == null || approver.isBlank() ? "관리부" : approver, condition);
        workPlanRepository.save(plan);
        log.info("[UC3] 작업계획서 {} {} 승인", workPlanId, plan.getStatus());
        return toDetail(plan);
    }

    private WorkPlan load(Long workPlanId) {
        return workPlanRepository.findById(workPlanId).orElseThrow(
                () -> new IllegalArgumentException("작업계획서를 찾을 수 없습니다: " + workPlanId));
    }

    private WorkPlanDtos.Detail toDetail(WorkPlan plan) {
        Map<String, String> questions = RiskRuleEngine.SlotKeys.questions();

        List<WorkPlanDtos.Slot> slots = workPlanSlotRepository.findByWorkPlanId(plan.getId())
                .stream()
                .map(s -> new WorkPlanDtos.Slot(s.getSlotKey(),
                        questions.getOrDefault(s.getSlotKey(), s.getSlotKey()),
                        s.getLedgerValue(), s.getAnsweredValue(), s.isConflicted(), s.getAnsweredAt()))
                .toList();

        List<WorkPlanDtos.Worker> workers = workPlanWorkerRepository.findByWorkPlanId(plan.getId())
                .stream()
                .map(w -> new WorkPlanDtos.Worker(w.getId(), w.getName(), w.getPosition(), w.getDuty()))
                .toList();

        return new WorkPlanDtos.Detail(plan.getId(), plan.getSiteId(), plan.getEquipmentId(),
                equipmentName(plan.getEquipmentId()), plan.getConversationId(),
                plan.getWorkName(), plan.getWorkPlace(), plan.getWorkDate(), plan.getWorkHours(),
                plan.getMethod(), plan.getNotes(), plan.getBriefing(), plan.getBriefingAckAt(),
                plan.getStatus(), plan.getApprovalNote(), plan.getApprovedBy(), plan.getApprovedAt(),
                slots, workers);
    }

    private String equipmentName(Long equipmentId) {
        return equipmentId == null ? null
                : equipmentRepository.findById(equipmentId).map(Equipment::getName).orElse(null);
    }
}
