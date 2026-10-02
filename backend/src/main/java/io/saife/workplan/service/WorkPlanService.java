package io.saife.workplan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.core.domain.Equipment;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.service.RiskRuleEngine;
import io.saife.evidence.Evidence;
import io.saife.evidence.repository.WorkPlanEvidenceRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 작업 전 점검(점검표) 조회, 관리감독자 승인, TBM 실시 확인.
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
    private final WorkPlanEvidenceRepository workPlanEvidenceRepository;
    private final ObjectMapper objectMapper;
    private final BriefingViewBuilder briefingViewBuilder;

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
                    "TBM 내용이 아직 없습니다. 점검표를 먼저 제출하십시오 [id=%d]".formatted(workPlanId));
        }
        if (plan.getBriefingAckAt() != null) {
            log.debug("[UC3] 이미 확인된 TBM workPlanId={} at={}", workPlanId, plan.getBriefingAckAt());
            return toDetail(plan);
        }

        plan.acknowledgeBriefing();
        workPlanRepository.save(plan);
        log.info("[UC3] 브리핑 확인 기록 workPlanId={} at={}", workPlanId, plan.getBriefingAckAt());
        return toDetail(plan);
    }

    /**
     * 관리감독자 승인. 조건(잠정조치)을 달면 CONDITIONAL이 된다.
     *
     * <p>판정에 상이 있고 대책이 아직 이행되지 않았으면 잠정조치 없이는 승인하지 않는다
     * (고시 제12조④: 감소대책 이행이 늦어지면 잠정조치를 정해 작업한다). 화면도 같은 값
     * ({@code briefingView.interimRequired})으로 입력란과 "조건부 승인" 버튼을 그린다.
     */
    @Transactional
    public WorkPlanDtos.Detail approve(Long workPlanId, String approver, String condition) {
        WorkPlan plan = load(workPlanId);

        if (plan.getStatus() != WorkPlanStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "승인 대기 상태가 아닙니다 [id=%d, 상태=%s]".formatted(workPlanId, plan.getStatus()));
        }
        if ((condition == null || condition.isBlank()) && briefingViewBuilder.compute(plan).interimRequired()) {
            throw new InvalidRequestException("상 판정의 감소대책이 이행되지 않았습니다. 잠정조치를 입력해야 승인할 수 있습니다.");
        }

        plan.approve(approver == null || approver.isBlank() ? "관리감독자" : approver.trim(),
                condition == null || condition.isBlank() ? null : condition.trim());
        workPlanRepository.save(plan);
        log.info("[UC3] 작업계획서 {} {} 승인", workPlanId, plan.getStatus());
        return toDetail(plan);
    }

    private WorkPlan load(Long workPlanId) {
        return workPlanRepository.findById(workPlanId).orElseThrow(
                () -> new NotFoundException("작업계획서를 찾을 수 없습니다: " + workPlanId));
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
        // payload 디코딩 실패는 그 한 건만 건너뛴다 — 근거 한 장 복원 실패로 상세 조회 전체가 깨지면 안 된다.
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
                document.type(), document.title());
    }

    private String equipmentName(Long equipmentId) {
        return equipmentId == null ? null
                : equipmentRepository.findById(equipmentId).map(Equipment::getName).orElse(null);
    }
}
