package io.saife.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.ai.agent.AgentContextKeys;
import io.saife.common.service.SseService;
import io.saife.core.service.RiskRuleEngine;
import io.saife.evidence.Evidence;
import io.saife.evidence.domain.WorkPlanEvidence;
import io.saife.evidence.ledger.EvidenceLedger;
import io.saife.evidence.repository.WorkPlanEvidenceRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanSlot;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.domain.WorkPlanWorker;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.repository.WorkPlanSlotRepository;
import io.saife.workplan.repository.WorkPlanWorkerRepository;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.domain.Equipment;
import io.saife.workplan.domain.WorkDocument;
import java.time.format.DateTimeFormatter;
import io.saife.workplan.service.BriefingComposer;
import io.saife.workplan.service.WorkPlanService;
import org.springframework.beans.factory.ObjectProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 도구 2·6 — 대화를 서식으로 구조화하고, 확정된 계획을 저장·제출한다.
 *
 * <p><b>빈 필수 슬롯은 처리하지 않는다.</b> {@link IncompleteResult}를 돌려주면
 * 모델이 작업자에게 되묻고, 답을 받아 같은 도구를 다시 호출한다.
 * <b>흐름은 모델이 쥔다</b> — 루프는 관찰하고 UI 힌트만 발행한다.
 *
 * <p>이 설계는 추측이 아니라 실측으로 정했다({@code docs/experiments/README.md}).
 * 27건 시험에서 값을 지어낸 적 0회, "알아서 적당히 넣어서 처리해줘요"라는 압박에도
 * 거부했다. 반면 도구 설명이 짧으면 어려운 대화에서 <b>도구를 아예 호출하지 않아</b>
 * 트레이스 패널이 비었다 — 그래서 {@code <usage-guide>} 블록이 필수다.
 *
 * <p>되묻기가 의미를 가지려면 <b>데이터 코어가 알 수 없는 값</b>이 등급을 바꿔야 한다.
 * 그래서 {@code work_height}가 필수 슬롯이고 등급 전환점이다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkPlanTools {

    private final WorkPlanRepository workPlanRepository;
    private final WorkPlanWorkerRepository workPlanWorkerRepository;
    private final WorkPlanSlotRepository workPlanSlotRepository;
    private final BriefingComposer briefingComposer;
    private final SseService sseService;
    private final EvidenceLedger evidenceLedger;
    private final WorkPlanEvidenceRepository workPlanEvidenceRepository;
    private final ObjectMapper objectMapper;
    private final EquipmentRepository equipmentRepository;
    /** 제출 직후 결과 카드(ai.workplan)를 상세 조회와 같은 모양으로 내기 위해. 순환 의존을 피해 지연 조회한다 */
    private final ObjectProvider<WorkPlanService> workPlanService;

    @Tool(description = """
            <tool-description>
            <purpose>작업자의 발화에서 작업 전 안전점검표 항목(작업명, 장소, 일자, 작업자, 현장 확인 값)을 정리해 등록합니다. 장소와 설비를 확인한 뒤 호출하세요.</purpose>
            <returns>등록 결과를 반환합니다. 필수 항목이 비어 있으면 status=INCOMPLETE와 missing 목록(무엇이 필요한지·왜 필요한지·어떻게 확인하는지)을 반환합니다.</returns>
            <prerequisites>findLocationEquipment로 장소·설비를 먼저 확인하세요.</prerequisites>
            <usage-guide>
            - status=INCOMPLETE가 오면 등록이 되지 않은 것입니다. 값을 지어내지 마세요.
            - missing에 있는 항목을 작업자에게 한 번에 하나씩 질문하세요. 질문은 missing의 expected 앞부분 문구를 그대로 쓰세요.
            - 이동식 사다리 작업이면 workHeight, topStep, tipGuard를 채웁니다. anchorInstalled는 사다리 작업에 쓰지 않습니다.
            - 이동식 비계 작업이면 workHeight, platformGuardrail, casterLock을 채웁니다.
            - 작업자 답변을 받은 뒤 이 도구를 다시 호출하세요. 이전에 채운 값은 그대로 다시 넣으세요.
            - 작업자가 모른다고 하거나 빨리 진행하자고 해도 필수 항목을 임의로 채우지 마세요.
              missing의 how_to_find를 안내하고 기다리세요.
            - 모든 필수 항목이 채워질 때까지 이 과정을 반복하세요.
            </usage-guide>
            </tool-description>
            """)
    @Transactional
    public String extractWorkPlan(
            @ToolParam(description = "작업명 (예: 천장 페인트 작업)") String workName,
            @ToolParam(description = "작업 장소") String workPlace,
            @ToolParam(description = "작업 일자 YYYY-MM-DD") String workDate,
            @ToolParam(description = "작업 시간(시간 단위 숫자)", required = false) String workHours,
            @ToolParam(description = "작업 순서 및 방법", required = false) String method,
            @ToolParam(description = "작업 인원. '성명/직책' 형식을 쉼표로 구분", required = false) String workers,
            @ToolParam(description = "설비 ID. findLocationEquipment에서 확인된 경우", required = false) Long equipmentId,
            @ToolParam(description = "바닥에서 발판(작업면)까지 높이(m). 작업자의 답을 그대로 넣으세요. 추정하지 마세요", required = false) String workHeight,
            @ToolParam(description = "이동식 사다리: 최상부 발판이나 그 바로 아래 디딤대에 올라서는지. 작업자의 답을 그대로 넣으세요", required = false) String topStep,
            @ToolParam(description = "이동식 사다리: 넘어짐 방지(아웃트리거, 고정, 잡아주는 사람)가 있는지. 작업자의 답을 그대로 넣으세요", required = false) String tipGuard,
            @ToolParam(description = "고소작업대, 이동식 비계: 작업대(작업발판) 안전난간이 빠짐없이 설치되어 있는지", required = false) String platformGuardrail,
            @ToolParam(description = "이동식 비계: 바퀴를 브레이크나 쐐기로 고정했는지. 작업자의 답을 그대로 넣으세요", required = false) String casterLock,
            @ToolParam(description = "사다리, 고소작업대가 아닌 고소작업에서 안전대 부착설비가 있는지. 사다리 작업에는 쓰지 않습니다", required = false) String anchorInstalled,
            @ToolParam(description = "사용 제품명(라벨 표기). 유기용제 여부로 화재, 중독 위험과 보호구가 달라집니다. 추정하지 마세요", required = false) String productName,
            ToolContext toolContext) {

        Long siteId = AgentContextKeys.siteId(toolContext);
        String conversationId = AgentContextKeys.conversationId(toolContext);

        return ToolCallTracker.execute("extractWorkPlan",
                Map.of("workName", String.valueOf(workName), "workDate", String.valueOf(workDate)),
                sseService, toolContext, () -> {

            if (workName == null || workName.isBlank()) {
                return ToolResult.of("작업명이 없습니다. 어떤 작업인지 물어보세요.");
            }

            LocalDate date = parseDate(workDate);
            if (date == null) {
                return ToolResult.of("작업 일자를 확인할 수 없습니다. 언제 하는 작업인지 물어보세요.");
            }

            // 되묻기 때문에 이 도구는 여러 번 호출된다. 그게 정상 흐름이다.
            // 호출마다 새로 만들면 같은 작업의 계획서가 쪼개지고 슬롯이 흩어진다.
            WorkPlan plan = upsertDraft(conversationId, siteId,
                    resolveEquipmentId(equipmentId, conversationId, toolContext), workName, workPlace,
                    date, workHours, method);

            saveWorkers(plan.getId(), workers);

            // 이번 호출로 들어온 슬롯 값을 먼저 반영한다 (재호출 시 이전 값 유지)
            persistSlot(plan.getId(), RiskRuleEngine.SlotKeys.WORK_HEIGHT, workHeight);
            persistSlot(plan.getId(), RiskRuleEngine.SlotKeys.TOP_STEP, topStep);
            persistSlot(plan.getId(), RiskRuleEngine.SlotKeys.TIP_GUARD, tipGuard);
            persistSlot(plan.getId(), RiskRuleEngine.SlotKeys.PLATFORM_GUARDRAIL, platformGuardrail);
            persistSlot(plan.getId(), RiskRuleEngine.SlotKeys.CASTER_LOCK, casterLock);
            persistSlot(plan.getId(), RiskRuleEngine.SlotKeys.ANCHOR_INSTALLED, anchorInstalled);
            persistSlot(plan.getId(), RiskRuleEngine.SlotKeys.PRODUCT_NAME, productName);

            String kind = kindOf(plan);
            List<String> missing = missingRequiredSlots(plan.getId(), plan.getWorkName(), kind);
            if (!missing.isEmpty()) {
                // 구조화된 불완전 결과. 모델이 이걸 받아 되묻고 재호출한다.
                // 흐름은 모델이 쥔다. 루프는 UI 힌트만 발행한다.
                ToolCallTracker.summarize("확인 필요 " + missing.size() + "건");
                return ToolResult.of(IncompleteResult.of(
                        "현장 확인 값이 비어 있어 점검표를 등록하지 않았습니다. (초안 id=%d)"
                                .formatted(plan.getId()),
                        missing.stream().map(k -> describeMissing(k, kind)).toList(),
                        "extractWorkPlan"));
            }

            int workerCount = workPlanWorkerRepository.findByWorkPlanId(plan.getId()).size();
            ToolCallTracker.summarize(plan.getWorkName() + ", "
                    + plan.getWorkDate().format(DateTimeFormatter.ofPattern("MM-dd"))
                    + (workerCount > 0 ? ", 작업자 " + workerCount + "명" : ""));
            return ToolResult.of("점검표 항목 정리 완료 [id=%d]. 필수 항목이 모두 채워졌습니다."
                    .formatted(plan.getId()));
        });
    }

    @Tool(description = """
            <tool-description>
            <purpose>정리된 작업 전 안전점검표를 저장하고 TBM 내용(위험 포인트, 지킬 것)을 만든 뒤 관리감독자 승인 대기에 올립니다. 대화의 마지막 도구입니다.</purpose>
            <returns>TBM 내용과 승인 요청 결과를 반환합니다. 위험성 등급은 시스템 판정 기준으로 이미 정해져 있습니다.</returns>
            <prerequisites>extractWorkPlan이 "필수 항목이 모두 채워졌습니다"를 반환한 뒤에 호출하세요. analyzeHazards, searchCases, getMsds로 근거를 먼저 모으세요.</prerequisites>
            <usage-guide>
            - 필수 항목이 비어 있으면 이 도구는 status=INCOMPLETE를 반환하고 제출하지 않습니다.
            - 그때는 missing 항목을 작업자에게 물어본 뒤 extractWorkPlan을 먼저 다시 호출하세요.
            - extractWorkPlan이 성공한 뒤에 이 도구를 호출하세요.
            </usage-guide>
            </tool-description>
            """)
    @Transactional
    public String createWorkPlan(
            @ToolParam(description = "점검표 ID (extractWorkPlan이 반환한 값)") Long workPlanId,
            ToolContext toolContext) {

        return ToolCallTracker.execute("createWorkPlan",
                Map.of("workPlanId", String.valueOf(workPlanId)),
                sseService, toolContext, () -> {

            if (workPlanId == null) {
                return ToolResult.of("점검표 ID가 없습니다. extractWorkPlan을 먼저 호출하세요.");
            }

            WorkPlan plan = workPlanRepository.findById(workPlanId).orElse(null);
            if (plan == null) {
                return ToolResult.of("점검표를 찾을 수 없습니다 [id=%d].".formatted(workPlanId));
            }

            // 필수 슬롯이 비어 있으면 제출하지 않는다.
            //
            // 모델은 되묻기 도중에도 이 도구를 부른다 (2026-09-20 실측: extractWorkPlan이
            // INCOMPLETE를 두 번 돌려준 직후에 호출했다). 그대로 제출하면 두 가지가 깨진다.
            //   1) 등급을 결정하는 값(작업높이)이 없는 계획서가 승인 큐에 올라간다
            //   2) 초안이 SUBMITTED로 바뀌어 다음 extractWorkPlan이 새 초안을 만든다 → 계획서가 쪼개진다
            // 거절이 곧 방어다.
            String kind = kindOf(plan);
            List<String> missing = missingRequiredSlots(plan.getId(), plan.getWorkName(), kind);
            if (!missing.isEmpty()) {
                return ToolResult.of(IncompleteResult.of(
                        "현장 확인 값이 비어 있어 제출하지 않았습니다. 초안(id=%d)은 그대로 있습니다."
                                .formatted(plan.getId()),
                        missing.stream().map(k -> describeMissing(k, kind)).toList(),
                        "extractWorkPlan"));
            }

            // 이미 제출된 걸 다시 부르면 그대로 돌려준다.
            // 모델은 마무리 턴에서 이 도구를 한 번 더 부르는 경우가 있다(실측).
            // 그때 submit()을 또 호출하면 <b>승인된 계획서가 조용히 SUBMITTED로 되돌아간다.</b>
            if (plan.getStatus() != WorkPlanStatus.DRAFT) {
                return ToolResult.of("""
                        점검표는 이미 제출되었습니다 [id=%d, 상태=%s]. 중복 제출하지 않았습니다.

                        [TBM]
                        %s
                        """.formatted(plan.getId(), plan.getStatus(),
                        plan.getBriefing() == null ? "(없음)" : plan.getBriefing()));
            }

            String briefing = briefingComposer.compose(plan);
            plan.attachBriefing(briefing);
            plan.submit();
            workPlanRepository.save(plan);

            // 이 턴까지 원장에 쌓인 근거를 계획서에 붙인다 — 브리핑 "참고 자료"와 법정 서식 하단 출처 목록이 이걸 읽는다.
            // 대화 ID가 없으면(테스트·수동 호출) 원장 자체가 없으므로 건너뛴다 — EvidenceLedger는
            // ConcurrentHashMap 기반이라 null 키를 그대로 넘기면 NPE가 난다.
            String cid = AgentContextKeys.conversationId(toolContext);
            if (cid != null && !cid.isBlank()) {
                for (Evidence e : evidenceLedger.all(cid).stream().filter(ev -> relevantToPlan(ev, kind)).toList()) {
                    try {
                        workPlanEvidenceRepository.save(WorkPlanEvidence.builder().workPlanId(plan.getId()).evidenceNo(e.no())
                                .payload(objectMapper.writeValueAsString(e)).build());
                    } catch (Exception ex) {
                        log.warn("[WORKPLAN] 근거 저장 실패 no={}: {}", e.no(), ex.getMessage());
                    }
                }
            }

            ToolCallTracker.summarize(WorkDocument.of(plan.getWorkName(), equipmentName(plan)).shortTitle() + " 작성, 승인 대기");
            emitWorkPlanCard(plan.getId(), toolContext);
            return ToolResult.of("""
                    점검표 제출 완료 [id=%d]. 관리감독자 승인 대기에 올렸습니다.
                    결과 카드가 화면에 표시됩니다. 등급과 근거를 다시 나열하지 말고 한두 문장으로만 안내하세요.

                    [TBM]
                    %s
                    """.formatted(plan.getId(), briefing));
        });
    }

    /** 떨어짐 조문 중 설비 종류에 따라 갈리는 것들. 이 중 그 설비 기준이 아닌 조문은 점검표 근거에 남기지 않는다 */
    private static final Set<Integer> FALL_ARTICLES = Set.of(42, 43, 44, 45, 67, 68, 186);

    /**
     * 점검표 "참고 자료"에 남길 근거인지. 설비 종류가 정해진 떨어짐 작업이면 그 기준 조문만 남긴다
     * (사다리 작업의 제44조 안전대 부착설비처럼 판정에 쓰지 않은 조문은 무관 조문이다).
     */
    static boolean relevantToPlan(Evidence e, String kind) {
        if (e == null || e.kind() != io.saife.evidence.EvidenceKind.LAW || kind == null || e.meta() == null) return true;
        Object no = e.meta().get("articleNo");
        Object law = e.meta().get("lawName");
        if (!(no instanceof Number n) || !io.saife.evidence.service.LawCitationTable.RULES.equals(law)) return true;
        if (!FALL_ARTICLES.contains(n.intValue())) return true;
        List<Integer> allowed = HazardAnalysisTools.citationsFor(io.saife.core.domain.AccidentType.FALL, kind, false).stream()
                .map(io.saife.evidence.service.LawCitationTable.Citation::articleNo).toList();
        return allowed.contains(n.intValue());
    }

    /**
     * 모델이 넘긴 설비 ID를 그대로 믿지 않는다. 실제 설비가 아니면 진입 설비, 이 대화에서 확정된 설비 순으로 쓴다.
     * 셋 다 없으면 null(설비 미지정 초안) — FK 위반으로 도구가 실패하는 것보다 낫다.
     */
    private Long resolveEquipmentId(Long requested, String conversationId, ToolContext toolContext) {
        if (requested != null && equipmentRepository.existsById(requested)) return requested;
        Long entry = AgentContextKeys.equipmentId(toolContext);
        if (entry != null && equipmentRepository.existsById(entry)) return entry;
        Long matched = MatchedEquipmentMemory.last(conversationId);
        if (matched != null && equipmentRepository.existsById(matched)) return matched;
        if (requested != null) log.warn("[WORKPLAN] 존재하지 않는 설비 ID {}를 무시합니다 cid={}", requested, conversationId);
        return null;
    }

    /**
     * 제출 결과 카드 — 상세 조회(GET /api/work-plan/{id})와 같은 DTO를 그대로 보낸다.
     * 화면은 이걸로 등급 배지와 룰 근거를 그리고, 모델 문장은 보조 설명으로 접는다. 발행 실패는 제출 실패가 아니다.
     */
    private void emitWorkPlanCard(Long workPlanId, ToolContext toolContext) {
        String conversationId = AgentContextKeys.conversationId(toolContext);
        String sessionId = AgentContextKeys.sessionId(toolContext);
        WorkPlanService service = workPlanService.getIfAvailable();
        if (conversationId == null || sessionId == null || service == null) return;
        try {
            sseService.send(sessionId, SseService.SseEvent.of("ai.workplan", conversationId, conversationId, service.detail(workPlanId)));
        } catch (Exception e) {
            log.warn("[WORKPLAN] 결과 카드 발행 실패 id={}: {}", workPlanId, e.getMessage());
        }
    }

    /** 계획서 설비의 종류(사다리, 고소작업대). 판정 기준과 되묻는 항목이 갈린다 */
    private String kindOf(WorkPlan plan) {
        return RiskRuleEngine.equipmentKind(equipmentName(plan), plan.getWorkName());
    }

    private String equipmentName(WorkPlan plan) {
        if (plan.getEquipmentId() == null) return null;
        return equipmentRepository.findById(plan.getEquipmentId()).map(Equipment::getName).orElse(null);
    }

    /**
     * 아직 답을 못 받은 필수 슬롯.
     *
     * <p>작업과 설비 종류마다 다르다. 이동식 사다리면 발판 높이, 최상부 디딤대, 넘어짐 방지(제42조제4항),
     * 도장이면 제품명이 필수다.
     */
    private List<String> missingRequiredSlots(Long workPlanId, String workName, String kind) {
        List<String> required = requiredSlotsFor(workName, kind);
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            boolean answered = workPlanSlotRepository
                    .findByWorkPlanIdAndSlotKey(workPlanId, key)
                    .map(s -> s.getAnsweredValue() != null && !s.getAnsweredValue().isBlank())
                    .orElse(false);
            if (!answered) {
                missing.add(key);
            }
        }
        return missing;
    }

    /** 높이를 묻지 않는 설비 종류(크레인, 지게차, 용접기) */
    private static final Set<String> NO_HEIGHT_KINDS = Set.of(
            RiskRuleEngine.KIND_CRANE, RiskRuleEngine.KIND_FORKLIFT, RiskRuleEngine.KIND_WELDER);

    /** 작업 유형과 설비 종류에 따른 필수 슬롯. 순서가 곧 되묻는 순서다 */
    static List<String> requiredSlotsFor(String workName, String kind) {
        String w = workName == null ? "" : workName;
        List<String> slots = new ArrayList<>();

        if (RiskRuleEngine.KIND_LADDER.equals(kind)) {
            slots.add(RiskRuleEngine.SlotKeys.WORK_HEIGHT);
            slots.add(RiskRuleEngine.SlotKeys.TOP_STEP);
            slots.add(RiskRuleEngine.SlotKeys.TIP_GUARD);
        } else if (RiskRuleEngine.KIND_AERIAL_PLATFORM.equals(kind)) {
            slots.add(RiskRuleEngine.SlotKeys.WORK_HEIGHT);
            slots.add(RiskRuleEngine.SlotKeys.PLATFORM_GUARDRAIL);
        } else if (RiskRuleEngine.KIND_MOBILE_SCAFFOLD.equals(kind)) {
            slots.add(RiskRuleEngine.SlotKeys.WORK_HEIGHT);
            slots.add(RiskRuleEngine.SlotKeys.PLATFORM_GUARDRAIL);
            slots.add(RiskRuleEngine.SlotKeys.CASTER_LOCK);
        } else if (RiskRuleEngine.KIND_TRESTLE.equals(kind)) {
            slots.add(RiskRuleEngine.SlotKeys.WORK_HEIGHT);
        } else if (RiskRuleEngine.KIND_PRESS.equals(kind)) {
            slots.add(RiskRuleEngine.SlotKeys.GUARD_INSTALLED);
        } else if (NO_HEIGHT_KINDS.contains(kind == null ? "" : kind)) {
            // 크레인 인양, 지게차 하역, 용접은 높이가 판정을 바꾸지 않는다. 묻지 않는다
        } else if (w.matches(".*(비계|고소|천장|지붕|옥상|단부).*")) {
            slots.add(RiskRuleEngine.SlotKeys.WORK_HEIGHT);
            slots.add(RiskRuleEngine.SlotKeys.ANCHOR_INSTALLED);
        }
        // 화학물질 신호: 도장, 페인트, 용제, 세척
        if (w.matches(".*(페인트|도장|용제|시너|세척|코팅).*")) {
            slots.add(RiskRuleEngine.SlotKeys.PRODUCT_NAME);
        }
        // 회전, 구동부 신호
        if (!slots.contains(RiskRuleEngine.SlotKeys.GUARD_INSTALLED)
                && w.matches(".*(정비|점검|청소|교체).*") && w.matches(".*(설비|기계|컨베이어|롤러).*")) {
            slots.add(RiskRuleEngine.SlotKeys.GUARD_INSTALLED);
        }

        if (slots.isEmpty() && !NO_HEIGHT_KINDS.contains(kind == null ? "" : kind)) {
            slots.add(RiskRuleEngine.SlotKeys.WORK_HEIGHT);
        }
        return slots;
    }

    /** 빠진 항목의 복구 정보. expected 앞부분이 그대로 묻는 문구이고, how_to_find가 "그걸 어떻게 확인해요?"에 답한다 */
    private IncompleteResult.MissingField describeMissing(String key, String kind) {
        String question = RiskRuleEngine.SlotKeys.question(key, kind);
        return switch (key) {
            case RiskRuleEngine.SlotKeys.WORK_HEIGHT -> new IncompleteResult.MissingField(
                    key, question + " (숫자, 예: 3.2)",
                    "2m 이상이면 안전모와 안전대가 필요하고, 이동식 사다리는 3.5m를 넘으면 쓸 수 없습니다",
                    "바닥에서 올라설 발판까지 줄자로 재거나 사다리 제원표로 확인합니다",
                    "3.2");
            case RiskRuleEngine.SlotKeys.TOP_STEP -> new IncompleteResult.MissingField(
                    key, question + " (예/아니요)",
                    "최상부 발판과 그 하단 디딤대에서는 작업할 수 없습니다(제42조제4항)",
                    "천장까지 손이 닿으려면 몇 번째 칸에 서야 하는지 작업자에게 확인합니다",
                    "맨 위 바로 아래 칸까지 올라갑니다");
            case RiskRuleEngine.SlotKeys.TIP_GUARD -> new IncompleteResult.MissingField(
                    key, question + " (있음/없음)",
                    "아웃트리거, 고정, 잡아주는 사람 중 하나도 없으면 사용할 수 없습니다(제42조제4항)",
                    "사다리 다리의 아웃트리거, 상단 고정 여부, 옆에서 잡아줄 사람이 있는지 확인합니다",
                    "없음");
            case RiskRuleEngine.SlotKeys.PLATFORM_GUARDRAIL -> new IncompleteResult.MissingField(
                    key, question + " (있음/없음)",
                    "작업대 안전난간이 빠져 있으면 떨어짐 위험이 큽니다(제186조)",
                    "작업대 네 면의 난간이 모두 설치되어 있는지 눈으로 확인합니다",
                    "있음");
            case RiskRuleEngine.SlotKeys.CASTER_LOCK -> new IncompleteResult.MissingField(
                    key, question + " (예/아니요)",
                    "바퀴를 고정하지 않으면 작업 중 비계가 움직여 떨어질 수 있습니다(제68조)",
                    "네 바퀴의 브레이크가 걸려 있는지, 쐐기를 끼웠는지 확인합니다",
                    "예");
            case RiskRuleEngine.SlotKeys.ANCHOR_INSTALLED -> new IncompleteResult.MissingField(
                    key, question + " (있음/없음)",
                    "2m 이상 작업에서 안전대를 걸 곳이 없으면 위험합니다(제44조)",
                    "작업 위치 주변에 안전대를 체결할 앵커, 구명줄이 있는지 확인합니다",
                    "없음");
            case RiskRuleEngine.SlotKeys.PRODUCT_NAME -> new IncompleteResult.MissingField(
                    key, question,
                    "유기용제 여부에 따라 화재, 중독 위험과 보호구가 달라집니다",
                    "페인트 통 라벨이나 물질안전보건자료(MSDS)에 적혀 있습니다",
                    "유성 에나멜 페인트");
            case RiskRuleEngine.SlotKeys.GUARD_INSTALLED -> new IncompleteResult.MissingField(
                    key, question + " (있음/없음)",
                    "회전, 구동부에 덮개가 없으면 끼임 위험이 큽니다(제87조)",
                    "설비의 회전부, 구동부에 덮개가 실제로 있는지 확인합니다",
                    "있음");
            default -> new IncompleteResult.MissingField(key, question, null, null, null);
        };
    }

    /**
     * 이 대화의 초안이 있으면 갱신하고, 없으면 만든다. <b>한 대화 = 한 초안.</b>
     *
     * <p>작업자가 답을 줄 때마다 모델이 이 도구를 다시 부른다. 그때 새 레코드를 만들면
     * 슬롯이 여러 계획서에 흩어져 브리핑이 반쪽만 나온다 — 실제로 스모크 테스트에서
     * 같은 작업의 계획서가 3건 생겼다.
     *
     * <p><b>식별 키는 대화 ID다.</b> 작업명으로 잡으면 모델이 대화 중간에 작업명을
     * 다듬는 순간 키가 바뀌어 똑같이 쪼개진다. 실제로 2건으로 쪼개졌다
     * ("천장 페인트 작업" → "공장동 후면 차양부 천장 페인트 작업").
     *
     * <p>기존 값은 새 값이 들어왔을 때만 덮어쓴다. 재호출에서 일부 필드가 빠져도
     * 앞서 채운 값이 지워지지 않는다.
     */
    private WorkPlan upsertDraft(String conversationId, Long siteId, Long equipmentId,
                                 String workName, String workPlace,
                                 LocalDate workDate, String workHours, String method) {

        WorkPlan existing = findDraft(conversationId, siteId, workName, workDate);

        if (existing == null) {
            return workPlanRepository.save(WorkPlan.builder()
                    .conversationId(conversationId)
                    .siteId(siteId)
                    .equipmentId(equipmentId)
                    .workName(workName)
                    .workPlace(workPlace)
                    .workDate(workDate)
                    .workHours(parseHours(workHours))
                    .method(method)
                    .build());
        }

        log.debug("[WORKPLAN] 초안 재사용 id={} conversationId={}", existing.getId(), conversationId);

        return workPlanRepository.save(WorkPlan.builder()
                .id(existing.getId())
                .conversationId(existing.getConversationId() != null
                        ? existing.getConversationId() : conversationId)
                .siteId(siteId)
                .processId(existing.getProcessId())
                .equipmentId(equipmentId != null ? equipmentId : existing.getEquipmentId())
                .workName(workName)
                .workPlace(workPlace != null ? workPlace : existing.getWorkPlace())
                .workDate(workDate)
                .workHours(workHours != null ? parseHours(workHours) : existing.getWorkHours())
                .method(method != null ? method : existing.getMethod())
                .briefing(existing.getBriefing())
                .briefingAckAt(existing.getBriefingAckAt())
                .status(WorkPlanStatus.DRAFT)
                .createdAt(existing.getCreatedAt())
                .build());
    }

    /** 대화 ID가 1순위. 없으면(테스트·수동 호출) 작업명+일자로 떨어진다 */
    private WorkPlan findDraft(String conversationId, Long siteId, String workName, LocalDate workDate) {
        if (conversationId != null && !conversationId.isBlank()) {
            return workPlanRepository
                    .findFirstByConversationIdAndStatusOrderByIdDesc(conversationId, WorkPlanStatus.DRAFT)
                    .orElse(null);
        }
        List<WorkPlan> drafts = workPlanRepository
                .findBySiteIdAndWorkNameAndWorkDateAndStatusOrderByIdDesc(
                        siteId, workName, workDate, WorkPlanStatus.DRAFT);
        return drafts.isEmpty() ? null : drafts.get(0);
    }

    /**
     * 슬롯 값을 기록한다.
     *
     * <p><b>{@code answeredAt}을 반드시 채운다.</b> {@code @PrePersist}는 INSERT에서만
     * 돌기 때문에, 기존 슬롯을 갱신할 때 이 값을 비우면 {@code NOT NULL} 위반으로
     * 500이 난다. 도구 설명이 "이전에 채운 값은 그대로 다시 넣으세요"라고 지시하므로
     * <b>갱신은 예외가 아니라 정상 흐름</b>이다 (2026-09-21 QA 실측).
     *
     * <p>값이 그대로면 최초 답변 시각을 유지한다. 같은 답을 다시 보냈다고 해서
     * "방금 답했다"로 바뀌면 안 된다 — 언제 확인했는지가 기록의 값이다.
     * 값이 바뀌었을 때만 시각을 갱신한다.
     */
    private void persistSlot(Long workPlanId, String slotKey, String value) {
        if (!RiskRuleEngine.isUsableAnswer(slotKey, value)) {
            if (value != null && !value.isBlank()) log.debug("[WORKPLAN] 판정에 쓸 수 없는 답이라 저장하지 않음 slot={} value={}", slotKey, value);
            return;
        }
        var existing = workPlanSlotRepository.findByWorkPlanIdAndSlotKey(workPlanId, slotKey);

        boolean changed = existing
                .map(s -> !value.equals(s.getAnsweredValue()))
                .orElse(true);
        OffsetDateTime answeredAt = changed
                ? OffsetDateTime.now()
                : existing.map(WorkPlanSlot::getAnsweredAt).orElse(OffsetDateTime.now());

        workPlanSlotRepository.save(WorkPlanSlot.builder()
                .id(existing.map(WorkPlanSlot::getId).orElse(null))
                .workPlanId(workPlanId)
                .slotKey(slotKey)
                .answeredValue(value)
                .ledgerValue(existing.map(WorkPlanSlot::getLedgerValue).orElse(null))
                .conflicted(existing.map(WorkPlanSlot::isConflicted).orElse(false))
                .answeredAt(answeredAt)
                .build());
    }

    /**
     * 작업 인원을 저장한다.
     *
     * <p>되묻기로 이 도구가 여러 번 호출되므로 <b>이미 있는 이름은 다시 넣지 않는다.</b>
     * 안 그러면 같은 사람이 계획서에 네 번 적힌다.
     *
     * <p>"작업자 2명"처럼 인원수만 온 값은 <b>이름이 아니므로 버린다.</b> 법정 서식의
     * 성명 칸에 "작업자 2명"이 찍히면 그대로 감점 사유다. 이름을 못 받았으면
     * 비워두는 게 맞다.
     */
    private void saveWorkers(Long workPlanId, String workers) {
        if (workers == null || workers.isBlank()) {
            return;
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String entry : workers.split(",")) {
            String[] parts = entry.trim().split("[/·]");
            if (parts.length == 0 || parts[0].isBlank()) {
                continue;
            }
            String name = parts[0].trim();
            if (name.matches("(?:[가-힣]*[ ]*)?[0-9]+[ ]*(?:명|인)")) {
                log.debug("[WORKPLAN] 인원수 표현은 성명으로 저장하지 않는다: {}", name);
                continue;
            }
            parsed.put(name, parts.length > 1 ? parts[1].trim() : null);
        }

        Set<String> already = workPlanWorkerRepository.findByWorkPlanId(workPlanId).stream()
                .map(WorkPlanWorker::getName)
                .collect(Collectors.toSet());

        parsed.forEach((name, position) -> {
            if (already.contains(name)) {
                return;
            }
            workPlanWorkerRepository.save(WorkPlanWorker.builder()
                    .workPlanId(workPlanId)
                    .name(name)
                    .position(position)
                    .build());
        });
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.debug("작업일자 파싱 실패: {}", raw);
            return null;
        }
    }

    private BigDecimal parseHours(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9.]", "");
        if (digits.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
