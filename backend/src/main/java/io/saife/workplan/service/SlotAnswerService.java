package io.saife.workplan.service;

import io.saife.core.domain.Action;
import io.saife.core.domain.ActionStatus;
import io.saife.core.domain.Hazard;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.core.service.RiskRuleEngine;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanSlot;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.repository.WorkPlanSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 되묻기 답변 기록.
 *
 * <p><b>데이터 코어와 슬롯의 역할이 다르다.</b>
 * <ul>
 *   <li>데이터 코어 = 대장상 기록. 에이전트가 <b>먼저 말한다</b></li>
 *   <li>슬롯 = 데이터 코어가 알 수 없는 것. <b>묻는다</b></li>
 * </ul>
 *
 * <p>둘이 다르면 {@code conflicted}로 남기고 <b>오늘 답변을 등급 계산에 쓴다.</b>
 * 그리고 해당 조치의 이행 상태를 갱신한다 — 불일치가 UC1의 조치 추적으로 되먹임된다.
 *
 * <p>자기보고가 법정 등급을 낮추는 문제가 있다. "설치했습니다"가 '상'을 '중'으로 내린다.
 * 그래서 화면에는 세 출처(대장 기록 · 작업자 답변 · 사진 탐지 결과)를 나란히 표시한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlotAnswerService {

    private final WorkPlanRepository workPlanRepository;
    private final WorkPlanSlotRepository workPlanSlotRepository;
    private final HazardRepository hazardRepository;
    private final ActionRepository actionRepository;

    @Transactional
    public void record(String conversationId, String slotKey, String value) {
        if (slotKey == null || slotKey.isBlank()) {
            return;
        }
        // 화면에 떠 있던 칸과 작업자가 실제로 답한 내용이 다를 수 있다(모델은 높이를 묻는데 칸은 최상부 디딤대).
        // 판정에 쓸 수 없는 답은 저장하지 않는다. 모델이 문장을 읽고 extractWorkPlan으로 제 칸에 넣는다
        if (!io.saife.core.service.RiskRuleEngine.isUsableAnswer(slotKey, value)) {
            log.debug("[SLOT] 칸에 맞지 않는 답이라 저장하지 않음 slot={} value={}", slotKey, value);
            return;
        }

        // 이 대화가 만들고 있는 작업계획서. 대화 ID로 찾고, 없을 때만 가장 최근 초안으로 떨어진다
        Optional<WorkPlan> target = conversationId == null ? Optional.empty()
                : workPlanRepository.findFirstByConversationIdAndStatusOrderByIdDesc(conversationId, WorkPlanStatus.DRAFT);
        if (target.isEmpty()) target = latestDraft();
        if (target.isEmpty()) {
            log.warn("[SLOT] 대상 작업계획서 없음 conversationId={} slot={}", conversationId, slotKey);
            return;
        }
        WorkPlan plan = target.get();

        String ledgerValue = lookupLedgerValue(plan, slotKey);
        boolean conflicted = ledgerValue != null && !equivalent(ledgerValue, value);

        WorkPlanSlot slot = workPlanSlotRepository
                .findByWorkPlanIdAndSlotKey(plan.getId(), slotKey)
                .orElseGet(() -> WorkPlanSlot.builder()
                        .workPlanId(plan.getId())
                        .slotKey(slotKey)
                        .build());

        workPlanSlotRepository.save(WorkPlanSlot.builder()
                .id(slot.getId())
                .workPlanId(plan.getId())
                .slotKey(slotKey)
                .answeredValue(value)
                .ledgerValue(ledgerValue)
                .conflicted(conflicted)
                .build());

        if (conflicted) {
            log.info("[SLOT] 불일치 기록 workPlanId={} slot={} 대장='{}' 답변='{}'",
                    plan.getId(), slotKey, ledgerValue, value);
            reconcileAction(plan, slotKey, value);
        }
    }

    /**
     * 대장이 알고 있는 값.
     *
     * <p>{@code anchor_installed}는 대장에 있다 — 미이행 조치가 남아 있으면 "없음"이다.
     * {@code work_height}는 대장에 없다. 그래서 그게 등급 전환점이다.
     */
    private String lookupLedgerValue(WorkPlan plan, String slotKey) {
        if (!RiskRuleEngine.SlotKeys.ANCHOR_INSTALLED.equals(slotKey) || plan.getEquipmentId() == null) {
            return null;
        }

        List<Hazard> hazards = hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(plan.getEquipmentId());
        if (hazards.isEmpty()) {
            return null;
        }

        List<Long> ids = hazards.stream().map(Hazard::getId).toList();
        List<Action> pending = actionRepository.findPendingByHazardIds(ids, ActionStatus.DONE);

        boolean anchorPending = pending.stream()
                .anyMatch(a -> a.getContent() != null && a.getContent().contains("안전대"));

        return anchorPending ? "미설치(조치 미이행)" : null;
    }

    /**
     * 불일치 되먹임 — 작업자가 "설치했다"고 답하면 해당 조치를 이행 완료로 올린다.
     *
     * <p>증빙 없이 상태만 바꾸므로 {@code evidencePath}에 출처를 남긴다.
     * 사진 증빙은 UC1 경로에서 따로 받는다.
     */
    private void reconcileAction(WorkPlan plan, String slotKey, String value) {
        if (!RiskRuleEngine.SlotKeys.ANCHOR_INSTALLED.equals(slotKey)) {
            return;
        }
        if (!isAffirmative(value)) {
            return;
        }

        List<Hazard> hazards = hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(plan.getEquipmentId());
        List<Long> ids = hazards.stream().map(Hazard::getId).toList();
        for (Action a : actionRepository.findPendingByHazardIds(ids, ActionStatus.DONE)) {
            if (a.getContent() != null && a.getContent().contains("안전대")) {
                a.complete("작업자 자기보고 (작업계획서 id=%d)".formatted(plan.getId()));
                actionRepository.save(a);
                log.info("[SLOT] 조치 이행 갱신 actionId={} — 자기보고 근거", a.getId());
            }
        }
    }

    private Optional<WorkPlan> latestDraft() {
        List<WorkPlan> drafts = workPlanRepository.findBySiteIdAndStatus(1L, WorkPlanStatus.DRAFT);
        return drafts.stream().max((a, b) -> a.getId().compareTo(b.getId()));
    }

    private boolean isAffirmative(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim().toLowerCase();
        return s.matches(".*(있|설치|했|예|네|yes|true).*") && !s.matches(".*(없|미설치|안|아니|no|false).*");
    }

    /** "미설치(조치 미이행)" vs "없습니다" 처럼 표현이 달라도 같은 뜻이면 불일치가 아니다 */
    private boolean equivalent(String ledger, String answer) {
        boolean ledgerPositive = isAffirmative(ledger);
        boolean answerPositive = isAffirmative(answer);
        return ledgerPositive == answerPositive;
    }
}
