package io.saife.ai.agent;

import io.saife.ai.tools.HazardAnalysisTools;
import io.saife.ai.tools.LocationEquipmentTools;
import io.saife.ai.tools.WorkPlanTools;
import io.saife.dashboard.service.EquipmentTimelineService;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.repository.WorkPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 데모 모드 대화 — <b>모델 없이 UC3를 끝까지 돈다.</b>
 *
 * <p><b>왜 필요한가.</b> {@code .claude/rules/deployment.md}의 제1원칙은
 * "심사위원은 키가 없다"이다. 그런데 키 없이 띄우면 앱은 뜨는데 UC3 대화가
 * {@code Failed to generate content}만 냈다 (2026-09-21 QA 실측, 도구 0/6 발화).
 * 앱이 뜨는 것과 앱을 볼 수 있는 것은 다르다.
 *
 * <p><b>무엇을 대체하는가.</b> <b>모델의 문장 생성만</b> 대체한다.
 * 도구는 실제로 실행되고, 데이터는 시드 DB에서 진짜로 나온다 — 미이행 조치도,
 * 위험성 등급도, MSDS 노출기준도 전부 실제 조회 결과다.
 * 대체되는 것은 "어떤 순서로 무엇을 부를지"를 정하는 판단뿐이다.
 *
 * <p>그래서 화면에 <b>데모 모드임을 명시</b>한다. 픽스처를 실제 모델 판단인 것처럼
 * 보이게 하면 그건 속이는 것이다.
 *
 * <p>사용자 입력에서 값을 뽑는 것도 정규식뿐이다. 못 뽑으면 되묻는다 —
 * 지어내지 않는 원칙은 데모 모드에서도 같다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoConversationScript {

    private static final Pattern DATE = Pattern.compile("(20\\d{2})[-./](\\d{1,2})[-./](\\d{1,2})");
    private static final Pattern HEIGHT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:m|M|미터)");
    private static final Pattern TOMORROW = Pattern.compile("내일");
    private static final Pattern DAY_AFTER = Pattern.compile("모레");

    private final LocationEquipmentTools locationEquipmentTools;
    private final WorkPlanTools workPlanTools;
    private final HazardAnalysisTools hazardAnalysisTools;
    private final WorkPlanRepository workPlanRepository;
    /** 연결성 2-1 — 설비가 확인되면 첫 문장은 회상이어야 한다. 모델 없이도 실제 DB 조회다 */
    private final EquipmentTimelineService equipmentTimelineService;

    /** 대화별로 모아둔 값. 모델 대신 여기가 슬롯을 기억한다 */
    private final Map<String, Map<String, String>> slotsByConversation = new HashMap<>();

    /**
     * 한 턴을 처리하고 화면에 보낼 문장을 돌려준다.
     *
     * @param userMessage 이번 턴의 사용자 발화
     * @param toolContext 도구에 넘길 컨텍스트 (트레이스 점등에 필요)
     */
    public String respond(String conversationId, Long siteId, String userMessage,
                          ToolContext toolContext) {
        Map<String, String> slots =
                slotsByConversation.computeIfAbsent(conversationId, k -> new HashMap<>());

        StringBuilder out = new StringBuilder();
        if (slots.containsKey("done")) {
            return out.append("**데모 모드**: 이 대화의 작업계획서는 이미 제출되었습니다. ")
                    .append("목록에서 확인하시거나, '새 대화'를 눌러 다른 작업을 등록하세요.")
                    .toString();
        }

        collect(slots, userMessage);

        // 1) 장소·설비 확인 — 아직 못 찾았으면 이번 발화로 찾는다. 이번 턴에 새로
        // 확인됐는지를 기억해 둔다 — 그 경우에만 회상 문장을 첫머리에 붙인다
        boolean justResolvedEquipment = false;
        if (!slots.containsKey("equipmentId")) {
            String found = locationEquipmentTools.findLocationEquipment(userMessage, null, toolContext);
            Long equipmentId = extractEquipmentId(found);
            if (equipmentId == null) {
                out.append("**데모 모드**: API 키가 없어 문장 생성은 고정 스크립트를 씁니다. ")
                        .append("도구 호출과 데이터 조회는 실제로 실행됩니다.\n\n");
                out.append(summarize(found)).append("\n\n");
                out.append("어느 장소, 설비에서 하는 작업인지 알려주세요. ")
                        .append("(예: 공장동 후면 차양부 이동식 사다리)");
                return out.toString();
            }
            slots.put("equipmentId", String.valueOf(equipmentId));
            slots.put("equipmentSummary", summarize(found));
            slots.put("equipmentName", extractEquipmentName(found));
            // 장소는 설비 조회 결과에서 가져온다. 서식의 "작업 장소"가 비면 안 된다
            String place = extractLocation(found);
            if (place != null) {
                slots.put("workPlace", place);
            }
            justResolvedEquipment = true;
        }

        // 설비가 확인되면 답변의 첫 문장은 반드시 회상 요약이어야 한다(연결성 2-1).
        // 모델 없이 도는 데모 모드도 예외가 아니다 — 스크립트가 직접 앞머리에 붙인다.
        // ai.recall SSE 발행은 findLocationEquipment(도구 레벨)이 이미 했다 — 여기서는
        // 화면에 보이는 "문장"만 맞춘다.
        if (justResolvedEquipment) {
            out.append(recallHeadline(Long.valueOf(slots.get("equipmentId")))).append("\n\n");
        }

        out.append("**데모 모드**: API 키가 없어 문장 생성은 고정 스크립트를 씁니다. ")
                .append("도구 호출과 데이터 조회는 실제로 실행됩니다.\n\n");
        out.append(slots.get("equipmentSummary")).append("\n\n");

        // 2) 작업 일자 — 없으면 묻는다. 지어내지 않는다
        if (!slots.containsKey("workDate")) {
            out.append("작업 일자를 알려주세요. (예: ")
                    .append(LocalDate.now().plusDays(1)).append(")");
            return out.toString();
        }

        // 3) 항목 구조화 — 부족한 슬롯이 있으면 도구가 INCOMPLETE를 돌려준다
        String extracted = workPlanTools.extractWorkPlan(
                slots.getOrDefault("workName", "천장 페인트 작업"),
                slots.get("workPlace"),
                slots.get("workDate"),
                slots.get("workHours"),
                null, null,
                Long.valueOf(slots.get("equipmentId")),
                slots.get("workHeight"),
                slots.get("anchorInstalled"),
                slots.get("productName"),
                toolContext);

        if (extracted.contains("INCOMPLETE")) {
            out.append(askForMissing(extracted));
            return out.toString();
        }

        // 4) 근거 수집 — 전부 로컬 캐시 조회다
        hazardAnalysisTools.analyzeHazards(
                slots.getOrDefault("workName", "도장"),
                slots.get("workPlace"),
                slots.get("equipmentName"),
                slots.get("productName"),
                toolContext);
        // 재해 원문에는 사업장 위치가 없다 — 위치 태그(workPlace)가 아니라 설비명·작업유형으로 질의한다
        hazardAnalysisTools.searchCases("FALL", "제조업", null, slots.get("equipmentName"), "페인트", toolContext);
        if (slots.containsKey("productName")) {
            hazardAnalysisTools.getMsds(slots.get("productName"), toolContext);
        }

        // 5) 제출 + 브리핑
        Long planId = latestDraftId(conversationId);
        if (planId == null) {
            out.append("작업계획서 초안을 찾지 못했습니다. 처음부터 다시 진행해 주세요.");
            return out.toString();
        }
        String created = workPlanTools.createWorkPlan(planId, toolContext);
        out.append(summarize(created));

        // 상태를 지우지 않고 "완료"로 표시한다. 지워버리면 이어지는 발화가
        // 처음부터 다시 시작하고, 화면에 "등록되지 않은 설비입니다"가 뜬다
        slots.put("done", "true");
        return out.toString();
    }

    /** 대화가 끝나거나 버려지면 메모리에서 지운다 */
    public void discard(String conversationId) {
        slotsByConversation.remove(conversationId);
    }

    /**
     * 연결성 2-1 — 설비가 확인된 첫 문장. 대시보드 회상 API와 같은 경로
     * ({@code EquipmentTimelineService.recall})를 그대로 써서 실제 DB 조회 결과를 낸다.
     * 실패해도 나머지 응답은 이어가야 하므로 예외를 삼키고 빈 문자열을 돌려준다.
     */
    private String recallHeadline(Long equipmentId) {
        try {
            return equipmentTimelineService.recall(equipmentId).headline();
        } catch (Exception e) {
            log.warn("[DEMO] 회상 헤드라인 생성 실패 equipmentId={}: {}", equipmentId, e.getMessage());
            return "";
        }
    }

    // ---------- 값 뽑기 ----------

    /**
     * 사용자 발화에서 값을 뽑는다.
     *
     * <p>정규식으로 확실한 것만 가져온다. 애매하면 안 가져오고 되묻는다 —
     * <b>데모 모드라고 값을 지어내면 안 된다.</b>
     */
    private void collect(Map<String, String> slots, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

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
            slots.put("workHeight", height.group(1));
        }

        if (message.matches("(?s).*(안전대 부착설비|앵커).*")) {
            slots.put("anchorInstalled",
                    message.matches("(?s).*(없|미설치|안 ?되|아니).*") ? "없음" : "있음");
        }

        // 제품명은 구체적일 때만 채운다.
        //
        // "천장 페인트 칠할 건데요"는 <b>작업 설명이지 제품명이 아니다.</b> 이걸
        // 제품명으로 받아버리면 되묻기가 사라지고, MSDS를 못 찾고, 유기용제 추론이
        // 실패해 화재 등급이 '하'로 나온다 (2026-09-21 QA 실측).
        // 비워두면 도구가 INCOMPLETE를 돌려주고 스크립트가 제품명을 묻는다.
        String product = specificProductName(message);
        if (product != null) {
            slots.put("productName", product);
        }

        if (message.matches("(?s).*(천장|지붕|도장|페인트).*") && !slots.containsKey("workName")) {
            slots.put("workName", "천장 페인트 작업");
        }

        Matcher hours = Pattern.compile("(\\d+)\\s*시간").matcher(message);
        if (hours.find()) {
            slots.put("workHours", hours.group(1));
        }
    }

    /**
     * 제품명만 뽑는다.
     *
     * <p>발화 전체를 제품명으로 넣으면 안 된다. "제품은 ○○ 유성페인트 씁니다"가
     * 통째로 들어가면 MSDS 조회도 서식 출력도 망가진다 (2026-09-21 QA 실측).
     */
    private String specificProductName(String message) {
        // 유성/수성 같은 수식어가 붙었을 때만 제품명으로 본다
        Matcher specific = Pattern.compile("([가-힣A-Za-z○0-9]+[ ]*)?(유성|수성)[ ]*(페인트|도료|시너|락카|에나멜)")
                .matcher(message);
        if (specific.find()) {
            return specific.group().trim();
        }
        // 용제류는 그 자체로 물질명이다
        Matcher solvent = Pattern.compile("(톨루엔|크실렌|신너|시너|락카|에나멜|아세톤)").matcher(message);
        return solvent.find() ? solvent.group() : null;
    }

    /** INCOMPLETE 결과에서 첫 번째 빠진 항목의 질문을 만든다 */
    private String askForMissing(String toolResult) {
        String field = io.saife.ai.tools.IncompleteResult.firstMissingField(toolResult);
        if (field == null) {
            return "작업계획서를 채우려면 정보가 더 필요합니다.";
        }
        return switch (field) {
            case "work_height" -> "작업 높이는 대략 몇 m인가요? (2m 초과 여부로 추락 위험성 등급이 갈립니다)";
            case "anchor_installed" -> "안전대 부착설비가 설치되어 있습니까? (있음/없음)";
            case "product_name" -> "사용하는 제품명이 무엇인가요? 유기용제 여부를 확인하겠습니다.";
            case "guard_installed" -> "회전부나 구동부에 방호덮개가 설치되어 있습니까? (있음/없음)";
            default -> "'%s' 값을 알려주세요.".formatted(field);
        };
    }

    /** 이 대화가 만든 최신 초안 */
    private Long latestDraftId(String conversationId) {
        return workPlanRepository
                .findFirstByConversationIdAndStatusOrderByIdDesc(
                        conversationId, io.saife.workplan.domain.WorkPlanStatus.DRAFT)
                .map(WorkPlan::getId)
                .orElse(null);
    }

    /** 도구 결과는 {@code {"result":"..."}} 형태다. 화면에는 본문만 보인다 */
    private String summarize(String toolResult) {
        if (toolResult == null) {
            return "";
        }
        String marker = "\"result\":\"";
        int at = toolResult.indexOf(marker);
        if (at < 0) {
            return toolResult;
        }
        String body = toolResult.substring(at + marker.length());
        int end = body.lastIndexOf('"');
        if (end > 0) {
            body = body.substring(0, end);
        }
        return body.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** 도구 결과 문장에서 설비 ID를 읽는다. 없으면 null — 못 찾았다는 뜻이다 */
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
     * <p>{@code findLocationEquipment}는 {@code ToolResult.of()}로 감싼 JSON을 돌려주므로 "위치: …" 뒤의
     * 줄바꿈이 백슬래시+n 리터럴이다. 예전 정규식({@code [^,]{1,40}})은 그 리터럴을 지나쳐
     * "공장동 후면 차양부\n공정/장소: 표면처리 라인 (도장)\n\n기존 위험"까지 삼켰고, 그 문자열이
     * {@code work_plan.work_place}에 저장돼 브리핑·상세·법정 서식 머리줄에 {@code \n}이 그대로
     * 찍혔다(2026-09-29 데모 워크스루 실측, 데모 모드 전용). 쉼표·실제 개행·백슬래시 앞에서 멈춘다.
     */
    private String extractLocation(String toolResult) {
        Matcher m = Pattern.compile("위치[: ]+([^,\\n\\\\]{1,40})").matcher(String.valueOf(toolResult));
        if (m.find()) {
            return m.group(1).trim();
        }
        m = Pattern.compile("\\(([가-힣· 0-9]{2,30})\\)").matcher(String.valueOf(toolResult));
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 도구 결과에서 설비명을 읽는다. 못 읽으면 null — 없는 이름을 만들지 않는다.
     *
     * <p>{@code describe()}(성공 매칭)의 실제 포맷은 {@code "설비 확인됨 [id=1] 이동식 사다리 A\n위치: ..."}로
     * 괄호가 없다 — 예전 정규식은 후보 목록 포맷({@code "- [id=1] 이름 (위치)"})만 잡을 수 있었고
     * 이 경로는 늘 null이었다(2026-09-29 실측, H3). {@code [id=N]} 뒤 다음 개행(실제 개행 또는
     * {@code ToolResult.of()}가 JSON 이스케이프한 {@code \n} 리터럴)이나 "위치" 앞까지를 설비명으로 본다.
     */
    private String extractEquipmentName(String toolResult) {
        Matcher m = Pattern.compile("\\[id=\\d+\\]\\s*(.+?)(?=(?:\\\\n|\\n|위치|$))")
                .matcher(String.valueOf(toolResult));
        return m.find() ? m.group(1).trim() : null;
    }

    /** 데모 모드 안내에 쓰는 도구 목록 (화면 표시용) */
    public List<String> toolNames() {
        return List.of("findLocationEquipment", "extractWorkPlan", "analyzeHazards",
                "searchCases", "getMsds", "createWorkPlan");
    }
}
