package io.saife.ai.tools;

import io.saife.ai.agent.AgentContextKeys;
import io.saife.common.service.SseService;
import io.saife.core.domain.Equipment;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.core.service.EquipmentMatcher;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.dashboard.service.EquipmentTimelineService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 연결성 2-1 — 설비 매칭 성공 시 {@code ai.recall}이 <b>도구 레벨</b>에서 발행되는지의
 * 백엔드 절반. 모델 문장과 무관하게 카드가 떠야 하므로, 여기서 검증하는 건 모델이 아니라
 * {@link LocationEquipmentTools#findLocationEquipment} 그 자체다.
 */
class LocationEquipmentToolsTest {

    private final EquipmentMatcher equipmentMatcher = mock(EquipmentMatcher.class);
    private final HazardRepository hazardRepository = mock(HazardRepository.class);
    private final AssessmentHazardRepository assessmentHazardRepository = mock(AssessmentHazardRepository.class);
    private final ActionRepository actionRepository = mock(ActionRepository.class);
    private final SseService sseService = mock(SseService.class);
    private final EquipmentTimelineService equipmentTimelineService = mock(EquipmentTimelineService.class);

    private final LocationEquipmentTools tools = new LocationEquipmentTools(
            equipmentMatcher, hazardRepository, assessmentHazardRepository, actionRepository,
            sseService, equipmentTimelineService);

    private ToolContext contextWith(String conversationId, String sessionId) {
        return new ToolContext(Map.of(
                AgentContextKeys.CONVERSATION_ID, conversationId,
                AgentContextKeys.SESSION_ID, sessionId,
                AgentContextKeys.SITE_ID, 1L));
    }

    @Test
    void 설비_매칭_성공하면_ai_recall을_한_번_발행하고_ai_tool_done보다_먼저_보낸다() {
        Equipment equipment = Equipment.builder()
                .id(1L).name("이동식 사다리 A").locationTag("공장동 후면 차양부").build();
        EquipmentMatcher.MatchResult match =
                new EquipmentMatcher.MatchResult(equipment, null, List.of(), false);
        when(equipmentMatcher.match(anyLong(), any(), any())).thenReturn(match);
        when(hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        TimelineDtos.RecallView recallView = new TimelineDtos.RecallView(
                1L, "이동식 사다리 A", "공장동 후면 차양부",
                "최근 평가 '상'(추락) · 미이행 조치 1건(기한 32일 경과)",
                false, null, List.of(), List.of(), List.of(), List.of(),
                List.of("장소", "설비", "최근 평가 등급", "미이행 조치"));
        when(equipmentTimelineService.recall(1L)).thenReturn(recallView);

        ToolContext ctx = contextWith("conv-1", "sess-1");
        tools.findLocationEquipment("공장동 후면 차양부 이동식 사다리", null, ctx);

        ArgumentCaptor<SseService.SseEvent> captor = ArgumentCaptor.forClass(SseService.SseEvent.class);
        verify(sseService, org.mockito.Mockito.atLeastOnce()).send(org.mockito.ArgumentMatchers.eq("sess-1"),
                captor.capture());

        List<SseService.SseEvent> events = captor.getAllValues();
        List<String> types = events.stream().map(SseService.SseEvent::type).toList();

        List<SseService.SseEvent> recallEvents = events.stream()
                .filter(e -> "ai.recall".equals(e.type())).toList();
        assertThat(recallEvents).hasSize(1);

        SseService.SseEvent recallEvent = recallEvents.get(0);
        assertThat(recallEvent.correlationId()).isEqualTo("conv-1");
        assertThat(recallEvent.targetId()).isEqualTo("conv-1");
        assertThat(recallEvent.payload()).isSameAs(recallView);

        int recallIdx = types.indexOf("ai.recall");
        int toolDoneIdx = types.indexOf("ai.tool.done");
        assertThat(recallIdx).as("ai.recall이 ai.tool.done보다 먼저 나가야 한다")
                .isGreaterThanOrEqualTo(0)
                .isLessThan(toolDoneIdx);
    }

    @Test
    void 매칭_실패하면_ai_recall을_발행하지_않는다() {
        EquipmentMatcher.MatchResult unmatched =
                new EquipmentMatcher.MatchResult(null, null, List.of(), true);
        when(equipmentMatcher.match(anyLong(), any(), any())).thenReturn(unmatched);

        ToolContext ctx = contextWith("conv-2", "sess-2");
        tools.findLocationEquipment("존재하지 않는 설비 질의", null, ctx);

        verify(sseService, never()).send(any(), argThat(e -> "ai.recall".equals(e.type())));
        verifyNoInteractions(equipmentTimelineService);
    }

    @Test
    void 후보가_여러_건이라_되물어야_하면_ai_recall을_발행하지_않는다() {
        EquipmentMatcher.MatchResult needsConfirmation = new EquipmentMatcher.MatchResult(
                null, null,
                List.of(new EquipmentMatcher.Candidate(1L, "이동식 사다리 A", "공장동", 0.8)),
                false);
        when(equipmentMatcher.match(anyLong(), any(), any())).thenReturn(needsConfirmation);

        ToolContext ctx = contextWith("conv-3", "sess-3");
        tools.findLocationEquipment("사다리", null, ctx);

        verify(sseService, never()).send(any(), argThat(e -> "ai.recall".equals(e.type())));
        verifyNoInteractions(equipmentTimelineService);
    }

    @Test
    void 진입_설비_컨텍스트가_있으면_자유_텍스트_매칭이_실패해도_단축해서_회상을_발행한다() {
        EquipmentMatcher.MatchResult unmatched =
                new EquipmentMatcher.MatchResult(null, null, List.of(), true);
        when(equipmentMatcher.match(anyLong(), any(), any())).thenReturn(unmatched);

        Equipment equipment = Equipment.builder()
                .id(1L).name("이동식 사다리 A").locationTag("공장동 후면 차양부").build();
        when(equipmentMatcher.matchById(1L))
                .thenReturn(new EquipmentMatcher.MatchResult(equipment, null, List.of(), false));
        when(hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        TimelineDtos.RecallView recallView = new TimelineDtos.RecallView(
                1L, "이동식 사다리 A", "공장동 후면 차양부", "최근 평가 '상'",
                false, null, List.of(), List.of(), List.of(), List.of(), List.of("장소", "설비"));
        when(equipmentTimelineService.recall(1L)).thenReturn(recallView);

        ToolContext ctx = new ToolContext(Map.of(
                AgentContextKeys.CONVERSATION_ID, "conv-4",
                AgentContextKeys.SESSION_ID, "sess-4",
                AgentContextKeys.SITE_ID, 1L,
                AgentContextKeys.EQUIPMENT_ID, 1L));

        // baseline_connectivity.py B2 실측대로, 이 문구만으로는 자유 텍스트 매칭이
        // 실패한다(같은 공정에 설비 2건) — equipmentId 단축이 없으면 여기서 회상이 안 뜬다
        tools.findLocationEquipment("공장동 후면 차양부 천장 페인트 작업", null, ctx);

        ArgumentCaptor<SseService.SseEvent> captor = ArgumentCaptor.forClass(SseService.SseEvent.class);
        verify(sseService, org.mockito.Mockito.atLeastOnce()).send(org.mockito.ArgumentMatchers.eq("sess-4"),
                captor.capture());
        List<SseService.SseEvent> recallEvents = captor.getAllValues().stream()
                .filter(e -> "ai.recall".equals(e.type())).toList();
        assertThat(recallEvents).hasSize(1);
        assertThat(recallEvents.get(0).payload()).isSameAs(recallView);
    }
}
