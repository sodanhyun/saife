package io.saife.workplan.service;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Action;
import io.saife.core.domain.ActionStatus;
import io.saife.core.domain.AssessmentHazard;
import io.saife.core.domain.Equipment;
import io.saife.core.domain.Hazard;
import io.saife.core.domain.RiskLevel;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.core.service.RiskRuleEngine;
import io.saife.core.service.RiskRuleEngine.SlotKeys;
import io.saife.publicapi.domain.MsdsCache;
import io.saife.publicapi.repository.MsdsCacheRepository;
import io.saife.publicapi.service.MsdsResolver;
import io.saife.workplan.domain.WorkDocument;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TBM의 구조화 뷰. 등급 판정, 위험 포인트와 지킬 것, MSDS 요약, 승인 조건을 한곳에서 만든다.
 *
 * <p>문장 TBM({@link BriefingComposer})과 결과 카드, 승인 규칙({@code WorkPlanService.approve})이
 * 모두 이 결과를 쓴다. 판정이 한 군데서만 나와야 화면, 서식, 승인 조건이 어긋나지 않는다.
 *
 * <p>위험 포인트와 지킬 것은 모델이 아니라 판정 결과에서 규칙으로 만든다. 반장이 작업 전에
 * 그대로 읽을 수 있는 말투로 쓴다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BriefingViewBuilder {

    private static final List<String> MSDS_SECTIONS = List.of("02", "05", "07", "08");
    private static final int MAX_POINTS = 3;

    private final HazardRepository hazardRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final ActionRepository actionRepository;
    private final WorkPlanSlotRepository workPlanSlotRepository;
    private final MsdsCacheRepository msdsCacheRepository;
    private final MsdsResolver msdsResolver;
    private final RiskRuleEngine riskRuleEngine;
    private final EquipmentRepository equipmentRepository;

    /** 브리핑이 아직 없는 초안이면 null */
    @Transactional(readOnly = true)
    public WorkPlanDtos.BriefingView build(WorkPlan plan) {
        if (plan.getBriefing() == null || plan.getBriefing().isBlank()) {
            return null;
        }
        return compute(plan);
    }

    /** 제출 전(브리핑 생성 시점)에도 쓰는 계산. 저장 상태와 무관하게 지금 슬롯으로 판정한다 */
    @Transactional(readOnly = true)
    public WorkPlanDtos.BriefingView compute(WorkPlan plan) {
        Map<String, String> slots = slotsOf(plan);
        List<Hazard> hazards = plan.getEquipmentId() != null
                ? hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(plan.getEquipmentId())
                : List.of();
        List<WorkPlanDtos.PendingAction> pending = pendingActions(hazards);
        List<WorkPlanDtos.HazardDecision> decisions = decisions(hazards, slots);
        WorkPlanDtos.MsdsSummary msds = msds(slots.get(SlotKeys.PRODUCT_NAME));
        boolean high = decisions.stream().anyMatch(d -> d.riskLevel() == RiskLevel.HIGH);
        // 고시 제12조④: 상 판정인데 대책이 아직 이행되지 않았으면 잠정조치를 정해야 작업할 수 있다.
        // 이 설비에 미이행 조치가 남아 있거나, 상 판정의 권고 대책(작업발판 확보 등)이 오늘 작업에 없는 경우다.
        boolean interim = high && (!pending.isEmpty()
                || decisions.stream().anyMatch(d -> d.riskLevel() == RiskLevel.HIGH && d.recommendation() != null));
        String equipmentName = plan.getEquipmentId() == null ? null
                : equipmentRepository.findById(plan.getEquipmentId()).map(Equipment::getName).orElse(null);
        String documentType = WorkDocument.of(plan.getWorkName(), equipmentName).type();
        return new WorkPlanDtos.BriefingView(pending, decisions, msds,
                riskPoints(decisions, slots), keepPoints(decisions, slots), interim,
                preSurvey(documentType, slots.get(SlotKeys.EQUIPMENT_KIND)));
    }

    /** 저장된 슬롯 + 설비 종류(파생). 판정 기준이 설비마다 다르다 */
    Map<String, String> slotsOf(WorkPlan plan) {
        Map<String, String> map = new LinkedHashMap<>();
        for (WorkPlanSlot s : workPlanSlotRepository.findByWorkPlanId(plan.getId())) {
            if (s.getAnsweredValue() != null) map.put(s.getSlotKey(), s.getAnsweredValue());
        }
        String equipmentName = plan.getEquipmentId() == null ? null
                : equipmentRepository.findById(plan.getEquipmentId()).map(Equipment::getName).orElse(null);
        return RiskRuleEngine.withEquipmentKind(map, equipmentName, plan.getWorkName());
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

    /** 설비에 등록된 위험요인의 발생형태 + 오늘 슬롯으로 판정 가능한 발생형태 */
    private List<WorkPlanDtos.HazardDecision> decisions(List<Hazard> hazards, Map<String, String> slots) {
        Set<AccidentType> axes = new LinkedHashSet<>();
        hazards.forEach(h -> axes.add(h.getAccidentType()));
        if (slots.containsKey(SlotKeys.WORK_HEIGHT)) axes.add(AccidentType.FALL);
        if (slots.containsKey(SlotKeys.PRODUCT_NAME) || RiskRuleEngine.isHotWork(slots)) axes.add(AccidentType.FIRE);
        String kind = slots.getOrDefault(SlotKeys.EQUIPMENT_KIND, "");
        if (RiskRuleEngine.KIND_CRANE.equals(kind)) axes.add(AccidentType.DROP);
        if (RiskRuleEngine.KIND_PRESS.equals(kind)) axes.add(AccidentType.CAUGHT);
        if (RiskRuleEngine.KIND_FORKLIFT.equals(kind)) axes.add(AccidentType.STRUCK);
        if (!axes.isEmpty()) axes.add(AccidentType.PPE);
        List<WorkPlanDtos.HazardDecision> out = new ArrayList<>();
        for (AccidentType axis : axes) {
            RiskRuleEngine.Decision d = riskRuleEngine.decide(axis, slots);
            out.add(new WorkPlanDtos.HazardDecision(axis, axis.getLabel(), d.riskLevel(),
                    d.frequency(), d.severity(), d.ruleTrace(), riskRuleEngine.recommendation(axis, slots, d)));
        }
        // 높은 등급이 먼저. 결과 카드의 첫 줄이 가장 위험한 것이어야 한다
        out.sort(Comparator.comparingInt(x -> x.riskLevel().ordinal()));
        return out;
    }

    // ── TBM 문구 ─────────────────────────────────────────────────────

    /** 위험 포인트가 둘이 안 될 때 채우는 일반 항목 */
    static final List<String> RISK_FALLBACK = List.of(
            "작업 구역이 정리되지 않으면 걸려 넘어질 수 있습니다",
            "공구나 자재가 떨어지면 아래에 있는 사람이 다칠 수 있습니다");
    /** 지킬 것이 둘이 안 될 때 채우는 일반 항목 */
    static final List<String> KEEP_FALLBACK = List.of(
            "작업 전 공구와 보호구 상태를 확인합니다",
            "작업 구역을 정리하고 통로를 비워 둡니다");
    private static final int MIN_POINTS = 2;

    /**
     * 위험 포인트: 판정이 높은 순으로, 반장이 읽는 말투로 2~3개. 설비 종류마다 말이 다르다
     * (크레인은 인양물, 프레스는 금형, 용접은 불티).
     */
    static List<String> riskPoints(List<WorkPlanDtos.HazardDecision> decisions, Map<String, String> slots) {
        List<String> out = new ArrayList<>();
        Double height = parseHeight(slots.get(SlotKeys.WORK_HEIGHT));
        String h = height == null ? null : meters(height);
        String kind = slots.getOrDefault(SlotKeys.EQUIPMENT_KIND, "");
        boolean hotWork = RiskRuleEngine.isHotWork(slots);
        for (WorkPlanDtos.HazardDecision d : decisions) {
            switch (d.accidentType()) {
                case FALL -> out.addAll(fallRisks(d, kind, height, h, slots));
                case FIRE -> {
                    if (hotWork) {
                        out.add("불티가 튀어 주변 가연물에 불이 붙을 수 있습니다");
                        if (d.riskLevel() == RiskLevel.HIGH) out.add("인화성 증기가 있는 곳에서 용접하면 불이 붙을 수 있습니다");
                        continue;
                    }
                    if (d.riskLevel() == RiskLevel.LOW) continue;
                    String product = slots.getOrDefault(SlotKeys.PRODUCT_NAME, "페인트").trim();
                    out.add(d.riskLevel() == RiskLevel.HIGH
                            ? "%s 증기 근처에서 화기 작업이 있어 불이 붙을 수 있습니다".formatted(product)
                            : "%s 증기는 불이 잘 붙고, 들이마시면 어지러울 수 있습니다".formatted(product));
                }
                case CAUGHT -> {
                    if (RiskRuleEngine.KIND_PRESS.equals(kind)) {
                        out.add("금형 사이에 손이 들어가면 끼일 수 있습니다");
                        out.add("방호장치를 끄거나 떼고 작업하면 손이 위험한계에 들어갑니다");
                    } else {
                        out.add(d.riskLevel() == RiskLevel.HIGH
                                ? "덮개 없는 회전부에 손이 끼일 수 있습니다"
                                : "회전부에 손이나 옷이 말려 들어갈 수 있습니다");
                    }
                }
                case DROP -> {
                    if (RiskRuleEngine.KIND_CRANE.equals(kind)) {
                        out.add("인양물이 떨어지거나 흔들려 사람에 맞을 수 있습니다");
                        out.add("훅 해지장치나 슬링이 손상되면 줄걸이가 빠질 수 있습니다");
                        out.add("인양물 아래로 들어가면 피할 곳이 없습니다");
                    } else if (RiskRuleEngine.KIND_FORKLIFT.equals(kind)) {
                        out.add("적재 높이를 넘기면 화물이 떨어질 수 있습니다");
                    } else {
                        out.add("위에서 물건이 떨어질 수 있습니다");
                    }
                }
                case STRUCK -> out.add(RiskRuleEngine.KIND_FORKLIFT.equals(kind)
                        ? "지게차 후진 중 보행자와 부딪힐 수 있습니다"
                        : "지게차, 대차 동선과 작업 위치가 겹칩니다");
                case PPE -> {
                    if (hotWork) out.add("보안면 없이 아크 빛을 보면 눈을 다칠 수 있습니다");
                }
            }
        }
        return fill(out, RISK_FALLBACK);
    }

    private static List<String> fallRisks(WorkPlanDtos.HazardDecision d, String kind, Double height, String h,
                                          Map<String, String> slots) {
        List<String> out = new ArrayList<>();
        switch (kind) {
            case RiskRuleEngine.KIND_LADDER -> {
                if (height != null && height > 3.5) {
                    out.add("발판 높이 %s, 사다리로 작업할 수 있는 높이(3.5m)를 넘습니다".formatted(h));
                    return out;
                }
                if (isTrue(SlotKeys.TOP_STEP, slots)) out.add("사다리 맨 위나 바로 아래 칸에 서면 중심을 잃고 떨어질 수 있습니다");
                if (isFalse(SlotKeys.TIP_GUARD, slots)) out.add("잡아주는 사람이나 고정 없이 사다리가 넘어질 수 있습니다");
                if (h != null) out.add("발판 높이 %s, 떨어지면 크게 다치는 높이입니다".formatted(h));
            }
            case RiskRuleEngine.KIND_AERIAL_PLATFORM -> out.add(d.riskLevel() == RiskLevel.HIGH
                    ? "작업대 안전난간이 빠진 곳으로 떨어질 수 있습니다"
                    : "작업대 위에서 몸을 난간 밖으로 내밀면 떨어질 수 있습니다");
            case RiskRuleEngine.KIND_ROOF -> {
                if (h != null) out.add("높이 %s 지붕 작업입니다. 떨어지면 크게 다칩니다".formatted(h));
                out.add("채광창은 사람 무게를 버티지 못합니다");
            }
            case RiskRuleEngine.KIND_MOBILE_SCAFFOLD -> {
                if (h != null && d.riskLevel() != RiskLevel.LOW) out.add("높이 %s 작업입니다. 떨어지면 크게 다칩니다".formatted(h));
                out.add("바퀴가 고정되지 않으면 비계가 움직여 떨어질 수 있습니다");
            }
            default -> {
                if (d.riskLevel() != RiskLevel.LOW && h != null) {
                    out.add(d.riskLevel() == RiskLevel.HIGH && !RiskRuleEngine.KIND_TRESTLE.equals(kind)
                            ? "높이 %s에서 안전대를 걸 곳이 없습니다".formatted(h)
                            : "높이 %s 작업입니다. 떨어지면 크게 다칩니다".formatted(h));
                }
            }
        }
        return out;
    }

    /** 지킬 것: 감소대책 우선순위(작업발판 확보, 방호, 관리, 보호구) 순으로 2~3개 */
    static List<String> keepPoints(List<WorkPlanDtos.HazardDecision> decisions, Map<String, String> slots) {
        List<String> out = new ArrayList<>();
        String kind = slots.getOrDefault(SlotKeys.EQUIPMENT_KIND, "");
        boolean hotWork = RiskRuleEngine.isHotWork(slots);
        String ppe = null;
        for (WorkPlanDtos.HazardDecision d : decisions) {
            switch (d.accidentType()) {
                case FALL -> {
                    switch (kind) {
                        case RiskRuleEngine.KIND_LADDER -> out.add(d.riskLevel() == RiskLevel.HIGH
                                ? "사다리 대신 이동식 비계(안전난간)나 말비계를 씁니다"
                                : "사다리는 평평한 바닥에 세우고 맨 위 두 칸에는 서지 않습니다");
                        case RiskRuleEngine.KIND_AERIAL_PLATFORM -> out.add(d.riskLevel() == RiskLevel.HIGH
                                ? "작업대 안전난간을 보수한 뒤 올라갑니다"
                                : "작업대 난간을 확인하고 안전대를 겁니다");
                        case RiskRuleEngine.KIND_MOBILE_SCAFFOLD -> out.add("바퀴 브레이크와 안전난간을 확인하고 올라갑니다");
                        case RiskRuleEngine.KIND_TRESTLE -> out.add("보조부재를 걸고 평평한 바닥에 세웁니다");
                        case RiskRuleEngine.KIND_ROOF -> {
                            out.add("안전대를 구명줄에 걸고 작업합니다");
                            out.add("채광창 위로 올라서지 않습니다");
                        }
                        default -> {
                            if (d.riskLevel() == RiskLevel.HIGH) out.add("작업발판과 안전난간을 먼저 설치합니다");
                        }
                    }
                }
                case FIRE -> {
                    if (hotWork) {
                        out.add("반경 안의 가연물을 치우고 방화포를 덮습니다");
                        out.add("화재감시자를 두고 소화기를 옆에 둡니다");
                    } else if (d.riskLevel() != RiskLevel.LOW) {
                        out.add("창과 문을 열어 환기하고, 주변에 불씨를 두지 않습니다");
                    }
                }
                case CAUGHT -> {
                    if (RiskRuleEngine.KIND_PRESS.equals(kind)) {
                        out.add("금형 교체 전에 전원을 끄고 잠급니다");
                        out.add("안전블록을 끼우고 작업합니다");
                    } else {
                        out.add("정비 전에 전원을 끄고 잠급니다");
                        out.add("작업 후 덮개를 다시 고정합니다");
                    }
                }
                // 제38조 대상(지게차 하역, 크레인 인양) 작업도 지킬 것이 비지 않게 한다
                case DROP -> {
                    if (RiskRuleEngine.KIND_CRANE.equals(kind)) {
                        out.add("줄걸이 용구와 훅 해지장치를 확인하고 인양합니다");
                        out.add("신호수 한 명이 신호하고 인양물 아래 출입을 막습니다");
                    } else if (RiskRuleEngine.KIND_FORKLIFT.equals(kind)) {
                        out.add("포크를 내리고 이동하며 정해진 적재 높이를 넘기지 않습니다");
                    } else {
                        out.add("인양물이나 적재물 아래로 들어가지 않습니다");
                    }
                }
                case STRUCK -> out.add("보행자는 구획된 통로로 다니고 유도자가 신호합니다");
                case PPE -> ppe = ppeLine(d.ruleTrace());
                default -> { }
            }
        }
        List<String> distinct = new ArrayList<>(out.stream().distinct().toList());
        // 보호구는 마지막 수단(고시 제12조)이지만 매번 말해야 하므로, 앞의 대책 둘 다음 자리에 둔다
        if (ppe != null) distinct.add(Math.min(distinct.size(), 2), ppe);
        return fill(distinct, KEEP_FALLBACK);
    }

    /** 중복을 빼고 3개까지 자르고, 2개가 안 되면 일반 항목으로 채운다 */
    private static List<String> fill(List<String> items, List<String> fallback) {
        List<String> out = new ArrayList<>(items.stream().distinct().limit(MAX_POINTS).toList());
        for (String f : fallback) {
            if (out.size() >= MIN_POINTS) break;
            if (!out.contains(f)) out.add(f);
        }
        return List.copyOf(out);
    }

    /**
     * 제38조 작업계획서의 사전조사 항목. 점검표(TBM)에는 없다(빈 목록).
     * 크레인 인양은 인양물과 줄걸이, 지게차 하역은 운행 경로와 화물이 핵심이다.
     */
    static List<String> preSurvey(String documentType, String kind) {
        if (!WorkDocument.WORK_PLAN.equals(documentType)) return List.of();
        return switch (kind == null ? "" : kind) {
            case RiskRuleEngine.KIND_CRANE -> List.of("인양물 중량과 무게중심", "줄걸이 용구(와이어로프, 슬링)와 훅 해지장치 상태",
                    "인양 경로와 하부 출입 통제 구역");
            case RiskRuleEngine.KIND_FORKLIFT -> List.of("운행 경로의 폭과 바닥 상태", "화물 중량과 적재 높이", "보행자 동선과 유도자 위치");
            default -> List.of("작업 장소의 바닥, 통로 상태", "취급 화물의 중량과 형태", "작업 구역 출입 통제 방법");
        };
    }

    /** 보호구 판정 문구("높이 3.2m, 유기용제 취급: 안전모, 안전대, 방독마스크 필요 (제32조)")를 말로 바꾼다 */
    private static String ppeLine(String trace) {
        if (trace == null || !trace.contains(" 필요")) return "작업에 맞는 보호구를 갖추고 시작합니다";
        String items = trace.substring(0, trace.indexOf(" 필요")).trim();
        int colon = items.indexOf(": ");
        if (colon >= 0) items = items.substring(colon + 2).trim();
        if (items.startsWith("작업에 맞는")) return "작업에 맞는 보호구를 갖추고 시작합니다";
        return items + objectParticle(items) + " 착용합니다";
    }

    /** 목적격 조사. 마지막 글자에 받침이 있으면 "을", 없으면 "를" */
    static String objectParticle(String word) {
        if (word == null || word.isEmpty()) return "를";
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return "를";
        return (last - 0xAC00) % 28 == 0 ? "를" : "을";
    }

    private static boolean isTrue(String key, Map<String, String> slots) {
        String v = slots.get(key);
        return v != null && Boolean.TRUE.equals(SlotKeys.TOP_STEP.equals(key) ? topStep(v) : bool(v));
    }

    private static boolean isFalse(String key, Map<String, String> slots) {
        String v = slots.get(key);
        return v != null && Boolean.FALSE.equals(SlotKeys.TOP_STEP.equals(key) ? topStep(v) : bool(v));
    }

    private static Boolean topStep(String v) {
        String d = SlotKeys.displayValue(SlotKeys.TOP_STEP, v);
        return "사용".equals(d) ? Boolean.TRUE : "사용 안 함".equals(d) ? Boolean.FALSE : null;
    }

    private static Boolean bool(String v) {
        String d = SlotKeys.displayValue(SlotKeys.TIP_GUARD, v);
        return "있음".equals(d) ? Boolean.TRUE : "없음".equals(d) ? Boolean.FALSE : null;
    }

    private static Double parseHeight(String raw) {
        String d = SlotKeys.displayValue(SlotKeys.WORK_HEIGHT, raw);
        if (d == null || !d.endsWith(" m")) return null;
        try {
            return Double.parseDouble(d.substring(0, d.length() - 2));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String meters(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + "m";
    }

    // ── MSDS ─────────────────────────────────────────────────────────

    /**
     * MSDS 요약: 유해성(H코드), 노출기준, 보호구, 소화. 제품명으로 바로 찾지 못하고 주성분으로
     * 찾았으면 inferred=true(화면과 TBM에 "추정 주성분, 제품 MSDS 확인 필요"로 표시한다).
     */
    WorkPlanDtos.MsdsSummary msds(String productName) {
        if (productName == null || productName.isBlank()) return null;
        try {
            String chemId = msdsResolver.resolveChemId(productName);
            if (chemId == null) return null;
            List<MsdsCache> rows = msdsCacheRepository.findByChemIdAndSectionCodeIn(chemId, MSDS_SECTIONS);
            if (rows.isEmpty()) return null;
            boolean inferred = msdsCacheRepository.findChemIdsByName(productName.trim()).isEmpty();
            List<WorkPlanDtos.MsdsLine> lines = new ArrayList<>();
            add(lines, "유해성", hazardCode(rows));
            add(lines, "노출기준", detail(rows, "노출기준", s -> s.replace("(허용기준)", "").replace(" : ", " ")));
            add(lines, "보호구", detail(rows, "개인보호구", BriefingViewBuilder::stripLabels));
            add(lines, "소화", firstOf(rows, "소화제"));
            String name = rows.get(0).getChemNameKor() != null ? rows.get(0).getChemNameKor() : productName;
            return new WorkPlanDtos.MsdsSummary(name, productName.trim(), inferred, List.copyOf(lines));
        } catch (Exception e) {
            // MSDS 요약 실패가 상세 조회 실패가 되면 안 된다
            log.warn("[BRIEFING-VIEW] MSDS 요약 실패 productName={}: {}", productName, e.getMessage());
            return null;
        }
    }

    private static void add(List<WorkPlanDtos.MsdsLine> lines, String item, String text) {
        if (text != null && !text.isBlank()) lines.add(new WorkPlanDtos.MsdsLine(item, clean(text)));
    }

    /** H코드 문구 중 첫 줄: "H225 고인화성 액체 및 증기" */
    private static String hazardCode(List<MsdsCache> rows) {
        for (MsdsCache r : rows) {
            if (r.getItemDetail() == null) continue;
            for (String line : r.getItemDetail().split("\\|")) {
                String t = line.trim();
                if (t.matches("H\\d{3}.*")) return t.replaceFirst("\\s*:\\s*", " ");
            }
        }
        return firstOf(rows, "분류");
    }

    private static String detail(List<MsdsCache> rows, String itemKeyword, java.util.function.UnaryOperator<String> fmt) {
        for (MsdsCache r : rows) {
            if (r.getItemName() != null && r.getItemName().contains(itemKeyword) && r.getItemDetail() != null) {
                return fmt.apply(String.join(", ", r.getItemDetail().split("\\|")));
            }
        }
        return null;
    }

    private static String firstOf(List<MsdsCache> rows, String itemKeyword) {
        for (MsdsCache r : rows) {
            if (r.getItemName() != null && r.getItemName().contains(itemKeyword) && r.getItemDetail() != null) {
                return r.getItemDetail().split("\\|")[0].trim();
            }
        }
        return null;
    }

    /** "호흡기 보호 : 유기가스용 방독마스크, 눈 보호 : 보안경" 에서 앞 분류를 뺀다 */
    private static String stripLabels(String s) {
        return s.replaceAll("(^|,\\s*)[가-힣]+ 보호 : ", "$1").replace(" : ", " ");
    }

    /** 화면 문자열 규칙: 가운뎃점을 쉼표로 */
    private static String clean(String s) {
        return s.replace("·", ", ").replace("ㆍ", ", ").replace(" - ", ", ").replaceAll("\\s{2,}", " ").trim();
    }
}
