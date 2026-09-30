package io.saife.ai.tools;

import io.saife.ai.agent.AgentContextKeys;
import io.saife.common.service.SseService;
import io.saife.core.domain.*;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.core.service.EquipmentMatcher;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.dashboard.service.EquipmentTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 도구 1 — 장소·설비를 찾고 <b>그 설비의 기존 위험요인·조치 이력을 함께 반환한다.</b>
 *
 * <p>이 도구가 이 출품작의 논지를 실행하는 지점이다. 작업자가 장소를 말하면
 * 에이전트는 묻기도 전에 "3개월 전 평가에서 '상'이었고 안전대 부착설비가 미이행입니다"를
 * 말할 수 있어야 한다. 그 재료가 여기서 나온다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocationEquipmentTools {

    private final EquipmentMatcher equipmentMatcher;
    private final HazardRepository hazardRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final ActionRepository actionRepository;
    private final SseService sseService;
    /** 회상 카드 생성 — 대시보드 회상 API와 같은 경로를 재사용한다(중복 로직 없음) */
    private final EquipmentTimelineService equipmentTimelineService;

    @Tool(description = """
            <tool-description>
            <purpose>작업할 장소나 설비를 찾고, 그 설비에 이미 등록된 위험요인·평가 등급·미이행 조치를 함께 조회합니다. 작업계획 대화의 첫 도구로 사용하세요.</purpose>
            <returns>설비 ID와 명칭, 위치, 기존 위험요인 목록(발생형태·최근 등급), 미이행 조치 목록을 반환합니다. 설비가 등록되어 있지 않으면 unmatched=true를, 비슷한 설비가 여러 개면 candidates를 반환합니다.</returns>
            <prerequisites>없음. 작업자가 장소나 설비를 언급하면 바로 호출하세요.</prerequisites>
            </tool-description>
            """)
    public String findLocationEquipment(
            @ToolParam(description = "작업자가 말한 장소 또는 설비 (예: 공장동 후면 차양부, 이동식 사다리)")
            String query,
            @ToolParam(description = "위치 태그가 따로 언급되었다면 전달. 없으면 생략", required = false)
            String locationTag,
            ToolContext toolContext) {

        // Gemini가 파라미터 없이 도구를 호출하는 경우가 있다
        if (query == null || query.isBlank()) {
            return ToolResult.of("장소나 설비 정보가 부족합니다. 어디에서 하는 작업인지 물어보세요.");
        }

        Long siteId = AgentContextKeys.siteId(toolContext);

        return ToolCallTracker.execute("findLocationEquipment",
                Map.of("query", query, "locationTag", String.valueOf(locationTag)),
                sseService, toolContext, () -> {

            EquipmentMatcher.MatchResult match = equipmentMatcher.match(siteId, query, locationTag);

            // 진입 컨텍스트(?equipmentId=)로 들어온 대화는 자유 텍스트 매칭이 실패·애매해도
            // 이미 화면에서 고른 설비가 있다. "공장동 후면 차양부 천장 페인트 작업"처럼
            // 같은 공정에 설비가 둘 이상이면 자유 텍스트만으로는 못 가른다(2026-09-29 실측,
            // baseline_connectivity.py B2) — 그 문맥을 매칭기의 한계 때문에 버리지 않는다.
            Long entryEquipmentId = AgentContextKeys.equipmentId(toolContext);
            if (entryEquipmentId != null && (match.unmatched() || match.needsConfirmation())) {
                EquipmentMatcher.MatchResult byId = equipmentMatcher.matchById(entryEquipmentId);
                if (byId.isConfirmed()) {
                    match = byId;
                }
            }

            if (match.unmatched()) {
                return ToolResult.of("""
                        등록되지 않은 설비입니다. (unmatched)
                        작업자에게 설비명·위치·종류를 확인해 등록해야 합니다.
                        %s""".formatted(processLine(match.process())));
            }

            if (match.needsConfirmation()) {
                StringBuilder sb = new StringBuilder("비슷한 설비가 여러 건입니다. 어느 것인지 확인이 필요합니다.\n");
                for (EquipmentMatcher.Candidate c : match.candidates()) {
                    sb.append("- [id=%d] %s (%s)\n".formatted(
                            c.equipmentId(), c.name(), nvl(c.locationTag(), "위치 미지정")));
                }
                return ToolResult.of(sb.toString());
            }

            Equipment eq = match.equipment();
            // ai.tool.done보다 먼저 나가야 한다 — ToolCallTracker의 finally가 그 이벤트를
            // 쏘기 전, 이 성공 분기 안에서 emitRecall을 호출한다(도구 레벨 발행이라
            // 모델이 문장에 담든 말든 회상 카드는 뜬다).
            emitRecall(eq.getId(), toolContext);
            return ToolResult.of(describe(eq, match.process()));
        });
    }

    /**
     * 설비 매칭 성공 시 회상 이벤트를 발행한다 — <b>모델 문장과 무관하게 카드가 뜬다.</b>
     *
     * <p>대시보드 회상 API({@code EquipmentTimelineService.recall})와 같은 경로를 그대로
     * 재사용해 PRE_WORK 문맥의 {@code RecallView}(+knownSlots)를 만든다. 회상 발행 실패가
     * 도구 실행 실패로 번지면 안 되므로 예외를 삼키고 경고만 남긴다.
     */
    private void emitRecall(Long equipmentId, ToolContext toolContext) {
        String conversationId = AgentContextKeys.conversationId(toolContext);
        String sessionId = AgentContextKeys.sessionId(toolContext);
        if (conversationId == null || sessionId == null) {
            return; // 비-스트리밍 경로(테스트, 배치)에서는 조용히 넘어간다
        }
        try {
            TimelineDtos.RecallView recallView = equipmentTimelineService.recall(equipmentId);
            sseService.send(sessionId,
                    SseService.SseEvent.of("ai.recall", conversationId, conversationId, recallView));
        } catch (Exception e) {
            log.warn("[RECALL] 회상 이벤트 발행 실패 equipmentId={}: {}", equipmentId, e.getMessage());
        }
    }

    /** 설비 + 그 설비에 걸린 이력을 사람이 읽는 형태로 */
    private String describe(Equipment eq, WorkProcess process) {
        StringBuilder sb = new StringBuilder();
        sb.append("설비 확인됨 [id=%d] %s\n".formatted(eq.getId(), eq.getName()));
        sb.append("위치: %s\n".formatted(nvl(eq.getLocationTag(), "미지정")));
        sb.append(processLine(process));

        List<Hazard> hazards = hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(eq.getId());
        if (hazards.isEmpty()) {
            sb.append("\n기존 등록된 위험요인 없음.\n");
            return sb.toString();
        }

        sb.append("\n기존 위험요인 %d건:\n".formatted(hazards.size()));
        List<Long> hazardIds = new ArrayList<>();
        for (Hazard h : hazards) {
            hazardIds.add(h.getId());
            String grade = latestGrade(h.getId());
            sb.append("- [%s] %s%s\n".formatted(
                    h.getAccidentType().getLabel(),
                    h.getMissingControl() != null ? h.getMissingControl() : h.getDescription(),
                    grade));
        }

        List<Action> pending = actionRepository.findPendingByHazardIds(hazardIds, ActionStatus.DONE);
        if (!pending.isEmpty()) {
            sb.append("\n미이행 조치 %d건:\n".formatted(pending.size()));
            for (Action a : pending) {
                sb.append("- %s (담당 %s, 기한 %s)\n".formatted(
                        a.getContent(), nvl(a.getOwner(), "미지정"),
                        a.getDueDate() != null ? a.getDueDate().toString() : "미지정"));
            }
        }
        return sb.toString();
    }

    /** 가장 최근 평가의 등급. UC3 브리핑이 "3개월 전 '상'이었다"를 말하는 근거 */
    private String latestGrade(Long hazardId) {
        List<AssessmentHazard> history = assessmentHazardRepository.findHistoryByHazardId(hazardId);
        if (history.isEmpty()) {
            return "";
        }
        return " — 최근 평가 등급 '%s'".formatted(history.get(0).getRiskLevel().getLabel());
    }

    private String processLine(WorkProcess p) {
        return p == null ? "" : "공정/장소: %s (%s)\n".formatted(p.getName(), nvl(p.getWorkType(), "작업유형 미지정"));
    }

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
