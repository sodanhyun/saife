package io.saife.ai.agent;

import io.saife.ai.tools.HazardAnalysisTools;
import io.saife.ai.tools.IncompleteResult;
import io.saife.ai.tools.LocationEquipmentTools;
import io.saife.ai.tools.WorkPlanTools;
import io.saife.core.service.RiskRuleEngine;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.dashboard.service.EquipmentTimelineService;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 시연 모드 대화. <b>모델 없이 작업 전 점검을 끝까지 돈다.</b>
 *
 * <p><b>왜 필요한가.</b> {@code .claude/rules/deployment.md}의 제1원칙은 "심사위원은 키가 없다"이다.
 * 키 없이 띄우면 앱은 뜨는데 대화가 {@code Failed to generate content}만 냈다(2026-09-21 QA 실측).
 *
 * <p><b>무엇을 대체하는가.</b> 모델의 문장 생성과 "무엇을 언제 부를지" 판단만 대체한다.
 * 도구는 실제로 실행되고, 데이터는 시드 DB에서 나온다. 미이행 조치도, 등급도, MSDS도 실제 조회 결과다.
 * 첫 답변에 시연 모드임을 한 줄로 밝힌다.
 *
 * <p>사용자 입력에서 값을 뽑는 것도 정규식뿐이다. 못 뽑으면 되묻는다. 지어내지 않는 원칙은 같다.
 * 되묻는 항목과 문구는 라이브 경로와 같다({@link RiskRuleEngine.SlotKeys#question}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoConversationScript {

    static final String DEMO_NOTICE = "시연 모드(오프라인)로 진행합니다.";

    private static final Pattern DATE = Pattern.compile("(20\\d{2})[-./](\\d{1,2})[-./](\\d{1,2})");
    private static final Pattern HEIGHT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:m|M|미터)");
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*$");
    private static final Pattern TOMORROW = Pattern.compile("내일");
    private static final Pattern DAY_AFTER = Pattern.compile("모레");
    /** 사람 이름(성 + 두 글자)과 뒤따르는 직책. 장소 낱말(공장동, 차양부)은 끝 글자로 거른다 */
    private static final Pattern PERSON = Pattern.compile(
            "(?<![가-힣])([김이박최정강조윤장임한오서신권황안송류전홍고문양손배백허유남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용][가-힣]{2})"
                    + "(?:\\s*(반장|조장|팀장|주임|기사|대리|과장|작업자))?(?=$|[\\s,.]|이랑|랑|과|와|하고|이|가|는|씨|님)");
    private static final String NOT_A_NAME_SUFFIX = "(동|부|면|장|실|층|구|트|료|너|업|소)";

    private final LocationEquipmentTools locationEquipmentTools;
    private final WorkPlanTools workPlanTools;
    private final HazardAnalysisTools hazardAnalysisTools;
    private final WorkPlanRepository workPlanRepository;
    /** 설비가 확인되면 첫 문장은 회상이어야 한다. 모델 없이도 실제 DB 조회다 */
    private final EquipmentTimelineService equipmentTimelineService;

    /** 대화별로 모아둔 값. 모델 대신 여기가 슬롯을 기억한다 */
    private final Map<String, Map<String, String>> slotsByConversation = new HashMap<>();

    /**
     * 한 턴을 처리하고 화면에 보낼 문장을 돌려준다.
     *
     * @param userMessage 이번 턴의 사용자 발화
     * @param toolContext 도구에 넘길 컨텍스트 (진행 표시 점등에 필요)
     */
    public String respond(String conversationId, Long siteId, String userMessage,
                          ToolContext toolContext) {
        Map<String, String> slots =
                slotsByConversation.computeIfAbsent(conversationId, k -> new HashMap<>());

        if (slots.containsKey("done")) {
            return "이 대화의 점검표는 이미 제출했습니다. 다른 작업은 새 대화로 시작하세요.";
        }

        collect(slots, userMessage);

        StringBuilder out = new StringBuilder();
        // 1) 장소, 설비 확인. 이번 턴에 새로 확인됐을 때만 회상 문장을 첫머리에 붙인다
        if (!slots.containsKey("equipmentId")) {
            String found = locationEquipmentTools.findLocationEquipment(userMessage, null, toolContext);
            Long equipmentId = extractEquipmentId(found);
            if (equipmentId == null) {
                return out.append(DEMO_NOTICE).append("\n\n")
                        .append("어느 장소, 어떤 설비로 하는 작업입니까? (예: 공장동 후면 차양부 이동식 사다리)")
                        .toString();
            }
            slots.put("equipmentId", String.valueOf(equipmentId));
            slots.put("equipmentName", extractEquipmentName(found));
            // 장소는 설비 조회 결과에서 가져온다. 서식의 "작업 장소"가 비면 안 된다
            String place = extractLocation(found);
            if (place != null) {
                slots.put("workPlace", place);
            }
            String recall = recallHeadline(equipmentId);
            if (!recall.isBlank()) {
                out.append(recall).append("\n\n");
            }
            out.append(DEMO_NOTICE).append("\n\n");
        }

        // 2) 작업 일자: 없으면 묻는다. 지어내지 않는다
        if (!slots.containsKey("workDate")) {
            slots.put("asking", "workDate");
            return out.append("작업 일자를 알려 주세요. (예: ").append(LocalDate.now().plusDays(1)).append(")").toString();
        }

        // 3) 항목 정리. 비어 있는 현장 확인 값이 있으면 도구가 INCOMPLETE를 돌려준다
        String extracted = workPlanTools.extractWorkPlan(
                slots.getOrDefault("workName", "천장 페인트 작업"),
                slots.get("workPlace"),
                slots.get("workDate"),
                slots.get("workHours"),
                null,
                slots.get("workers"),
                Long.valueOf(slots.get("equipmentId")),
                slots.get(RiskRuleEngine.SlotKeys.WORK_HEIGHT),
                slots.get(RiskRuleEngine.SlotKeys.TOP_STEP),
                slots.get(RiskRuleEngine.SlotKeys.TIP_GUARD),
                slots.get(RiskRuleEngine.SlotKeys.PLATFORM_GUARDRAIL),
                slots.get(RiskRuleEngine.SlotKeys.ANCHOR_INSTALLED),
                slots.get(RiskRuleEngine.SlotKeys.PRODUCT_NAME),
                toolContext);

        if (IncompleteResult.isIncomplete(extracted) || extracted.contains("INCOMPLETE")) {
            String field = IncompleteResult.firstMissingField(extracted);
            slots.put("asking", field == null ? "" : field);
            return out.append(question(field, slots)).toString();
        }
        slots.remove("asking");

        // 4) 근거 수집: 전부 로컬 조회다
        hazardAnalysisTools.analyzeHazards(
                slots.getOrDefault("workName", "도장"),
                slots.get("workPlace"),
                slots.get("equipmentName"),
                slots.get(RiskRuleEngine.SlotKeys.PRODUCT_NAME),
                toolContext);
        // 재해 원문에는 사업장 위치가 없다. 위치 태그가 아니라 설비명과 작업 유형으로 질의한다
        hazardAnalysisTools.searchCases("FALL", "제조업", null, slots.get("equipmentName"), "페인트", toolContext);
        if (slots.containsKey(RiskRuleEngine.SlotKeys.PRODUCT_NAME)) {
            hazardAnalysisTools.getMsds(slots.get(RiskRuleEngine.SlotKeys.PRODUCT_NAME), toolContext);
        }

        // 5) 제출과 TBM
        Long planId = latestDraftId(conversationId);
        if (planId == null) {
            return out.append("점검표 초안을 찾지 못했습니다. 새 대화로 다시 시작해 주세요.").toString();
        }
        workPlanTools.createWorkPlan(planId, toolContext);
        out.append("작업 전 안전점검표를 작성해 관리감독자 승인 대기에 올렸습니다. 작업 전에 TBM으로 내용을 공유해 주세요.");

        // 상태를 지우지 않고 "완료"로 표시한다. 지우면 이어지는 발화가 처음부터 다시 시작한다
        slots.put("done", "true");
        return out.toString();
    }

    /** 대화가 끝나거나 버려지면 메모리에서 지운다 */
    public void discard(String conversationId) {
        slotsByConversation.remove(conversationId);
    }

    /**
     * 설비가 확인된 첫 문장. 대시보드 회상 API와 같은 조회({@code EquipmentTimelineService.recall})에서
     * 지난 지적 사항과 미이행 조치를 한 문장으로 만든다. 실패해도 나머지 응답은 이어간다.
     */
    private String recallHeadline(Long equipmentId) {
        try {
            TimelineDtos.RecallView r = equipmentTimelineService.recall(equipmentId);
            List<String> parts = new ArrayList<>();
            r.priorHazards().stream()
                    .filter(h -> h.missingControl() != null && !h.missingControl().isBlank())
                    .findFirst()
                    .ifPresent(h -> parts.add("지난 평가 지적 사항 " + h.missingControl()));
            r.unfinishedActions().stream().findFirst().ifPresent(a -> parts.add("미이행 조치 " + a.content()
                    + (a.overdueDays() != null && a.overdueDays() > 0 ? " (" + a.overdueDays() + "일 경과)" : "")));
            if (parts.isEmpty()) {
                return r.equipmentName() + ": 등록된 지적 사항이 없습니다.";
            }
            return r.equipmentName() + ": " + String.join(", ", parts) + ".";
        } catch (Exception e) {
            log.warn("[DEMO] 회상 문장 생성 실패 equipmentId={}: {}", equipmentId, e.getMessage());
            return "";
        }
    }

    // ---------- 값 뽑기 ----------

    /**
     * 사용자 발화에서 값을 뽑는다. 확실한 것만 가져오고, 애매하면 되묻는다.
     * 바로 앞 턴에 물었던 항목이 있으면 이번 답을 그 항목으로 받는다.
     */
    void collect(Map<String, String> slots, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String asking = slots.getOrDefault("asking", "");

        Matcher date = DATE.matcher(message);
        if (date.find()) {
            slots.put("workDate", "%s-%02d-%02d".formatted(date.group(1),
                    Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3))));
        } else if (TOMORROW.matcher(message).find() && !slots.containsKey("workDate")) {
            slots.put("workDate", LocalDate.now().plusDays(1).toString());
        } else if (DAY_AFTER.matcher(message).find() && !slots.containsKey("workDate")) {
            slots.put("workDate", LocalDate.now().plusDays(2).toString());
        }

        Matcher height = HEIGHT.matcher(message);
        if (height.find()) {
            slots.put(RiskRuleEngine.SlotKeys.WORK_HEIGHT, height.group(1));
        } else if (RiskRuleEngine.SlotKeys.WORK_HEIGHT.equals(asking)) {
            Matcher bare = BARE_NUMBER.matcher(message);
            if (bare.find()) slots.put(RiskRuleEngine.SlotKeys.WORK_HEIGHT, bare.group(1));
        }

        if (RiskRuleEngine.SlotKeys.TOP_STEP.equals(asking)
                || message.matches("(?s).*(맨 ?위|최상부|꼭대기|바로 ?아래 ?칸|디딤대).*")) {
            slots.put(RiskRuleEngine.SlotKeys.TOP_STEP, message.trim());
        }
        if (RiskRuleEngine.SlotKeys.TIP_GUARD.equals(asking)
                || message.matches("(?s).*(잡아|아웃트리거|넘어짐 ?방지|고정해|지지).*")) {
            slots.put(RiskRuleEngine.SlotKeys.TIP_GUARD, message.trim());
        }
        if (RiskRuleEngine.SlotKeys.PLATFORM_GUARDRAIL.equals(asking)) {
            slots.put(RiskRuleEngine.SlotKeys.PLATFORM_GUARDRAIL, message.trim());
        }
        if (RiskRuleEngine.SlotKeys.ANCHOR_INSTALLED.equals(asking)
                || message.matches("(?s).*(안전대 부착설비|앵커).*")) {
            slots.put(RiskRuleEngine.SlotKeys.ANCHOR_INSTALLED, message.trim());
        }

        // 제품명은 구체적일 때만 채운다. "천장 페인트 칠할 건데요"는 작업 설명이지 제품명이 아니다.
        // 제품명을 물은 직후의 답은 그대로 제품명으로 받는다.
        String product = specificProductName(message);
        if (product != null) {
            slots.put(RiskRuleEngine.SlotKeys.PRODUCT_NAME, product);
        } else if (RiskRuleEngine.SlotKeys.PRODUCT_NAME.equals(asking)) {
            slots.put(RiskRuleEngine.SlotKeys.PRODUCT_NAME, message.trim());
        }

        if (message.matches("(?s).*(천장|지붕|도장|페인트).*") && !slots.containsKey("workName")) {
            slots.put("workName", "천장 페인트 작업");
        }

        String workers = workers(message);
        if (workers != null && !slots.containsKey("workers")) {
            slots.put("workers", workers);
        }

        Matcher hours = Pattern.compile("(\\d+)\\s*시간").matcher(message);
        if (hours.find()) {
            slots.put("workHours", hours.group(1));
        }
    }

    /** "김철수 반장이랑 이영호 둘이" 에서 "김철수/반장, 이영호" */
    static String workers(String message) {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher m = PERSON.matcher(message);
        while (m.find()) {
            String name = m.group(1);
            if (name.matches(".*" + NOT_A_NAME_SUFFIX)) continue;
            found.putIfAbsent(name, m.group(2));
        }
        if (found.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        found.forEach((name, position) -> out.add(position == null ? name : name + "/" + position));
        return String.join(", ", out);
    }

    /** 제품명만 뽑는다. 발화 전체를 제품명으로 넣으면 MSDS 조회와 서식이 망가진다 */
    private String specificProductName(String message) {
        // 유성/수성 같은 수식어가 붙었을 때만 제품명으로 본다
        Matcher specific = Pattern.compile("([가-힣A-Za-z0-9]+[ ]*)?(유성|수성)[ ]*(페인트|도료|시너|락카|에나멜)")
                .matcher(message);
        if (specific.find()) {
            return specific.group().trim();
        }
        // 용제류는 그 자체로 물질명이다
        Matcher solvent = Pattern.compile("(톨루엔|크실렌|신너|시너|락카|에나멜|아세톤)").matcher(message);
        return solvent.find() ? solvent.group() : null;
    }

    /** 라이브 경로와 같은 질문 문구 */
    private String question(String field, Map<String, String> slots) {
        if (field == null) {
            return "점검표를 채우려면 정보가 더 필요합니다.";
        }
        String kind = RiskRuleEngine.equipmentKind(slots.get("equipmentName"), slots.get("workName"));
        return RiskRuleEngine.SlotKeys.question(field, kind);
    }

    /** 이 대화가 만든 최신 초안 */
    private Long latestDraftId(String conversationId) {
        return workPlanRepository
                .findFirstByConversationIdAndStatusOrderByIdDesc(conversationId, WorkPlanStatus.DRAFT)
                .map(WorkPlan::getId)
                .orElse(null);
    }

    /** 도구 결과 문장에서 설비 ID를 읽는다. 없으면 null(못 찾았다는 뜻) */
    private Long extractEquipmentId(String toolResult) {
        Matcher m = Pattern.compile("설비 ID[: ]+(\\d+)").matcher(String.valueOf(toolResult));
        if (m.find()) {
            return Long.valueOf(m.group(1));
        }
        m = Pattern.compile("\\[id=(\\d+)\\]").matcher(String.valueOf(toolResult));
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    /**
     * 도구 결과에서 위치 태그를 읽는다.
     *
     * <p>{@code ToolResult.of()}로 감싼 JSON이라 개행이 백슬래시+n 리터럴이다. 쉼표, 실제 개행,
     * 백슬래시 앞에서 멈춘다(예전 정규식은 다음 줄까지 삼켜 장소에 \n이 찍혔다, 2026-09-29 실측).
     */
    private String extractLocation(String toolResult) {
        Matcher m = Pattern.compile("위치[: ]+([^,\\n\\\\]{1,40})").matcher(String.valueOf(toolResult));
        if (m.find()) {
            return m.group(1).trim();
        }
        m = Pattern.compile("\\(([가-힣 0-9]{2,30})\\)").matcher(String.valueOf(toolResult));
        return m.find() ? m.group(1).trim() : null;
    }

    /** 도구 결과에서 설비명을 읽는다. 못 읽으면 null(없는 이름을 만들지 않는다) */
    private String extractEquipmentName(String toolResult) {
        Matcher m = Pattern.compile("\\[id=\\d+\\]\\s*(.+?)(?=(?:\\\\n|\\n|위치|$))")
                .matcher(String.valueOf(toolResult));
        return m.find() ? m.group(1).trim() : null;
    }

    /** 진행 단계 이름(화면 표시용) */
    public List<String> toolNames() {
        return List.of("findLocationEquipment", "extractWorkPlan", "analyzeHazards",
                "searchCases", "getMsds", "createWorkPlan");
    }
}
