package io.saife.workplan.service;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Action;
import io.saife.core.domain.ActionStatus;
import io.saife.core.domain.AssessmentHazard;
import io.saife.core.domain.Hazard;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.core.service.RiskRuleEngine;
import io.saife.publicapi.domain.MsdsCache;
import io.saife.publicapi.repository.MsdsCacheRepository;
import io.saife.publicapi.service.MsdsResolver;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanSlot;
import io.saife.workplan.dto.WorkPlanDtos;
import io.saife.workplan.repository.WorkPlanSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 브리핑의 구조화 뷰. {@link BriefingComposer}가 만드는 문장과 <b>같은 판정</b>을 화면용 데이터로 낸다.
 *
 * <p>문장 브리핑은 법정 서식과 대화 기록에 남고, 이 뷰는 결과 카드(등급 배지 + 룰 근거)를 그린다.
 * 둘이 어긋나지 않도록 판정은 같은 룰 엔진·같은 슬롯·같은 위험요인 목록에서 나온다.
 * 유사 사고사례는 여기서 다시 검색하지 않는다 — 계획서에 저장된 근거 카드를 화면이 그대로 쓴다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BriefingViewBuilder {

    private static final List<String> MSDS_SECTIONS = List.of("02", "05", "07", "08");

    private final HazardRepository hazardRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final ActionRepository actionRepository;
    private final WorkPlanSlotRepository workPlanSlotRepository;
    private final MsdsCacheRepository msdsCacheRepository;
    private final MsdsResolver msdsResolver;
    private final RiskRuleEngine riskRuleEngine;

    /** 브리핑이 아직 없는 초안이면 null */
    @Transactional(readOnly = true)
    public WorkPlanDtos.BriefingView build(WorkPlan plan) {
        if (plan.getBriefing() == null || plan.getBriefing().isBlank()) {
            return null;
        }
        Map<String, String> slots = loadSlots(plan.getId());
        List<Hazard> hazards = plan.getEquipmentId() != null
                ? hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(plan.getEquipmentId())
                : List.of();
        return new WorkPlanDtos.BriefingView(pendingActions(hazards), decisions(hazards, slots),
                msds(slots.get(RiskRuleEngine.SlotKeys.PRODUCT_NAME)));
    }

    private List<WorkPlanDtos.PendingAction> pendingActions(List<Hazard> hazards) {
        if (hazards.isEmpty()) return List.of();
        List<Action> pending = actionRepository.findPendingByHazardIds(hazards.stream().map(Hazard::getId).toList(), ActionStatus.DONE);
        LocalDate today = LocalDate.now();
        List<WorkPlanDtos.PendingAction> out = new ArrayList<>();
        for (Action a : pending) {
            Long overdue = a.getDueDate() != null && a.getDueDate().isBefore(today)
                    ? ChronoUnit.DAYS.between(a.getDueDate(), today) : null;
            List<AssessmentHazard> history = assessmentHazardRepository.findHistoryByHazardId(a.getHazardId());
            out.add(new WorkPlanDtos.PendingAction(a.getContent(), a.getDueDate(), overdue,
                    history.isEmpty() ? null : history.get(0).getRiskLevel()));
        }
        return out;
    }

    /** BriefingComposer.appendRuleDecisions와 같은 축 집합, 같은 룰 엔진 호출 */
    private List<WorkPlanDtos.HazardDecision> decisions(List<Hazard> hazards, Map<String, String> slots) {
        Set<AccidentType> axes = new LinkedHashSet<>();
        hazards.forEach(h -> axes.add(h.getAccidentType()));
        if (slots.containsKey(RiskRuleEngine.SlotKeys.WORK_HEIGHT)) axes.add(AccidentType.FALL);
        if (slots.containsKey(RiskRuleEngine.SlotKeys.PRODUCT_NAME)) axes.add(AccidentType.FIRE);
        List<WorkPlanDtos.HazardDecision> out = new ArrayList<>();
        for (AccidentType axis : axes) {
            RiskRuleEngine.Decision d = riskRuleEngine.decide(axis, slots);
            out.add(new WorkPlanDtos.HazardDecision(axis, axis.getLabel(), d.riskLevel(),
                    d.frequency(), d.severity(), d.ruleTrace()));
        }
        // 높은 등급이 먼저 — 결과 카드의 첫 줄이 가장 위험한 것이어야 한다
        out.sort((x, y) -> Integer.compare(x.riskLevel().ordinal(), y.riskLevel().ordinal()));
        return out;
    }

    private WorkPlanDtos.MsdsSummary msds(String productName) {
        if (productName == null || productName.isBlank()) return null;
        try {
            String chemId = msdsResolver.resolveChemId(productName);
            if (chemId == null) return null;
            List<MsdsCache> rows = msdsCacheRepository.findByChemIdAndSectionCodeIn(chemId, MSDS_SECTIONS);
            if (rows.isEmpty()) return null;
            Map<String, WorkPlanDtos.MsdsLine> bySection = new LinkedHashMap<>();
            for (MsdsCache row : rows) {
                if (row.getItemDetail() == null || row.getItemDetail().isBlank()) continue;
                String first = row.getItemDetail().split("\\|")[0].trim();
                if (first.isBlank() || bySection.containsKey(row.getSectionCode())) continue;
                bySection.put(row.getSectionCode(), new WorkPlanDtos.MsdsLine(sectionName(row.getSectionCode()), first));
            }
            String name = rows.get(0).getChemNameKor() != null ? rows.get(0).getChemNameKor() : productName;
            return new WorkPlanDtos.MsdsSummary(name, productName, List.copyOf(bySection.values()));
        } catch (Exception e) {
            // MSDS 요약 실패가 상세 조회 실패가 되면 안 된다
            log.warn("[BRIEFING-VIEW] MSDS 요약 실패 productName={}: {}", productName, e.getMessage());
            return null;
        }
    }

    private static String sectionName(String s) {
        return switch (s) {
            case "02" -> "유해성";
            case "05" -> "화재 시 대처";
            case "07" -> "취급과 저장";
            case "08" -> "노출기준";
            default -> "항목 " + s;
        };
    }

    private Map<String, String> loadSlots(Long workPlanId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (WorkPlanSlot s : workPlanSlotRepository.findByWorkPlanId(workPlanId)) {
            if (s.getAnsweredValue() != null) map.put(s.getSlotKey(), s.getAnsweredValue());
        }
        return map;
    }
}
