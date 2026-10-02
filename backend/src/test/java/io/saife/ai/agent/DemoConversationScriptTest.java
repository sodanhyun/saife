package io.saife.ai.agent;

import io.saife.ai.tools.HazardAnalysisTools;
import io.saife.ai.tools.LocationEquipmentTools;
import io.saife.ai.tools.ToolResult;
import io.saife.ai.tools.WorkPlanTools;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.dashboard.service.EquipmentTimelineService;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.ActionStatus;
import io.saife.core.domain.RiskLevel;
import io.saife.incident.service.EquipmentHistoryRecaller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 연결성 2-1 — 데모 모드(키 없음)에서도 첫 턴 첫 토큰이 회상 문장으로 시작해야 한다.
 * 모델이 없으므로 스크립트가 직접 앞머리에 붙이는지 확인한다.
 */
class DemoConversationScriptTest {

    private final LocationEquipmentTools locationEquipmentTools = mock(LocationEquipmentTools.class);
    private final WorkPlanTools workPlanTools = mock(WorkPlanTools.class);
    private final HazardAnalysisTools hazardAnalysisTools = mock(HazardAnalysisTools.class);
    private final WorkPlanRepository workPlanRepository = mock(WorkPlanRepository.class);
    private final EquipmentTimelineService equipmentTimelineService = mock(EquipmentTimelineService.class);

    private final DemoConversationScript script = new DemoConversationScript(
            locationEquipmentTools, workPlanTools, hazardAnalysisTools, workPlanRepository,
            equipmentTimelineService);

    @Test
    void 설비가_확인되면_첫_문장은_지난_지적_사항과_미이행_조치다() {
        when(locationEquipmentTools.findLocationEquipment(anyString(), any(), any(ToolContext.class)))
                .thenReturn("설비 확인됨 [id=1] 이동식 사다리 A\n위치: 공장동 후면 차양부\n"
                        + "기존 등록된 위험요인 없음.\n");

        TimelineDtos.RecallView recallView = new TimelineDtos.RecallView(
                1L, "이동식 사다리 A", "공장동 후면 차양부", "헤드라인",
                false, null,
                List.of(new EquipmentHistoryRecaller.PriorHazard(1L, AccidentType.FALL, "작업발판 미확보", "d",
                        RiskLevel.HIGH, LocalDate.now().minusMonths(1), "근거", false)),
                List.of(new EquipmentHistoryRecaller.UnfinishedAction(1L, "차양부 천장 작업 시 이동식 비계(안전난간) 사용",
                        LocalDate.now().minusDays(30), ActionStatus.OVERDUE, 30L, null, "생산반장 김철수")),
                List.of(), List.of(), List.of());
        when(equipmentTimelineService.recall(1L)).thenReturn(recallView);

        String answer = script.respond("conv-1", 1L, "공장동 후면 차양부 천장 페인트 작업",
                new ToolContext(Map.of()));

        assertThat(answer).startsWith("이동식 사다리 A: 지난 평가 지적 사항 작업발판 미확보, "
                + "미이행 조치 차양부 천장 작업 시 이동식 비계(안전난간) 사용 (30일 경과).");
        // 시연 모드 표식은 회상 문장 뒤에 한 번만 온다
        assertThat(answer.indexOf("이동식 사다리 A:")).isLessThan(answer.indexOf(DemoConversationScript.DEMO_NOTICE));
        assertThat(answer).doesNotContain("데모 모드").doesNotContain("API").doesNotContain("도구");
    }

    @Test
    void 사다리_시연_입력에서_되묻기_슬롯과_작업자를_뽑는다() {
        java.util.Map<String, String> slots = new java.util.HashMap<>();
        script.collect(slots, "내일 공장동 후면 차양부에서 사다리 놓고 천장 페인트 칠할 건데요. "
                + "유성 에나멜 페인트 쓰고, 김철수 반장이랑 이영호 둘이 합니다.");
        assertThat(slots).containsEntry("product_name", "유성 에나멜 페인트");
        assertThat(slots).containsEntry("workers", "김철수/반장, 이영호");
        assertThat(slots).doesNotContainKey("work_height").doesNotContainKey("top_step").doesNotContainKey("tip_guard");

        slots.put("asking", "work_height");
        script.collect(slots, "3.2m요");
        assertThat(slots).containsEntry("work_height", "3.2");

        slots.put("asking", "top_step");
        script.collect(slots, "맨 위 바로 아래 칸까지 올라가요");
        assertThat(slots).containsEntry("top_step", "맨 위 바로 아래 칸까지 올라가요");

        slots.put("asking", "tip_guard");
        script.collect(slots, "따로 잡아주는 사람은 없어요");
        assertThat(slots).containsEntry("tip_guard", "따로 잡아주는 사람은 없어요");
    }

    @Test
    void 이미_설비가_확인된_두번째_턴에는_회상_문장을_다시_붙이지_않는다() {
        when(locationEquipmentTools.findLocationEquipment(anyString(), any(), any(ToolContext.class)))
                .thenReturn("설비 확인됨 [id=1] 이동식 사다리 A\n위치: 공장동 후면 차양부\n"
                        + "기존 등록된 위험요인 없음.\n");
        TimelineDtos.RecallView recallView = new TimelineDtos.RecallView(
                1L, "이동식 사다리 A", "공장동 후면 차양부", "최근 평가 '상'",
                false, null, List.of(), List.of(), List.of(), List.of(), List.of("장소", "설비"));
        when(equipmentTimelineService.recall(1L)).thenReturn(recallView);
        // 2턴에서 workDate까지 채워지면 extractWorkPlan까지 이어진다 — NPE 방지용 스텁
        // (이 테스트의 관심사는 회상 문장 중복 여부이지 이후 흐름이 아니다)
        when(workPlanTools.extractWorkPlan(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(ToolContext.class))).thenReturn("{\"result\":\"ok\"}");

        // 1턴 — 설비 확인
        script.respond("conv-2", 1L, "공장동 후면 차양부 천장 페인트 작업", new ToolContext(Map.of()));
        // 2턴 — 작업 일자만 채운다. 이번 턴엔 설비를 "새로" 확인하지 않는다
        String secondAnswer = script.respond("conv-2", 1L, "2026-10-01", new ToolContext(Map.of()));

        assertThat(secondAnswer).doesNotStartWith("이동식 사다리 A:");
    }

    /** {@code describe()}가 실제로 만드는 텍스트 — 개행은 아직 실제 줄바꿈이다 */
    private static final String DESCRIBE_TEXT =
            "설비 확인됨 [id=1] 이동식 사다리 A\n위치: 공장동 후면 차양부\n기존 등록된 위험요인 없음.\n";

    @Test
    void 근거_수집_단계의_searchCases_호출은_설비명을_쓰고_위치_태그를_쓰지_않는다() {
        // fix round 1 — 실제 findLocationEquipmentTools.findLocationEquipment()는
        // ToolResult.of()로 감싼 JSON 문자열을 돌려준다. 그 안의 개행은 실제 줄바꿈이 아니라
        // Jackson이 이스케이프한 "\n"(백슬래시+n) 리터럴이다 — extractEquipmentName이
        // 실제로 받는 형태는 이쪽이다. 이전 라운드는 여기를 raw 개행으로 목킹해
        // extractEquipmentName의 JSON-이스케이프 분기를 전혀 실행하지 않고 있었다.
        when(locationEquipmentTools.findLocationEquipment(anyString(), any(), any(ToolContext.class)))
                .thenReturn(ToolResult.of(DESCRIBE_TEXT));
        TimelineDtos.RecallView recallView = new TimelineDtos.RecallView(
                1L, "이동식 사다리 A", "공장동 후면 차양부", "최근 평가 '상'",
                false, null, List.of(), List.of(), List.of(), List.of(), List.of("장소", "설비"));
        when(equipmentTimelineService.recall(1L)).thenReturn(recallView);
        when(workPlanTools.extractWorkPlan(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(ToolContext.class))).thenReturn("{\"result\":\"ok\"}");
        when(hazardAnalysisTools.analyzeHazards(any(), any(), any(), any(), any(ToolContext.class)))
                .thenReturn("{\"result\":\"ok\"}");
        when(hazardAnalysisTools.searchCases(any(), any(), any(), any(), any(), any(ToolContext.class)))
                .thenReturn("{\"result\":\"ok\"}");
        WorkPlan draft = WorkPlan.builder().id(9L).status(WorkPlanStatus.DRAFT).build();
        when(workPlanRepository.findFirstByConversationIdAndStatusOrderByIdDesc("conv-4", WorkPlanStatus.DRAFT))
                .thenReturn(Optional.of(draft));
        when(workPlanTools.createWorkPlan(eq(9L), any(ToolContext.class))).thenReturn("{\"result\":\"ok\"}");

        // 1턴 — 설비 확인, 2턴 — 작업 일자까지 채워 근거 수집 단계(searchCases 호출)에 도달한다
        script.respond("conv-4", 1L, "공장동 후면 차양부 천장 페인트 작업", new ToolContext(Map.of()));
        script.respond("conv-4", 1L, "2026-10-01", new ToolContext(Map.of()));

        ArgumentCaptor<String> equipmentCap = ArgumentCaptor.forClass(String.class);
        verify(hazardAnalysisTools).searchCases(eq("FALL"), eq("제조업"), any(),
                equipmentCap.capture(), eq("페인트"), any(ToolContext.class));
        assertThat(equipmentCap.getValue()).contains("이동식 사다리 A");
        assertThat(equipmentCap.getValue()).doesNotContain("공장동").doesNotContain("차양부");
    }

    @Test
    void extractEquipmentName은_raw_개행_형태에도_대응한다() {
        // 두 번째 assertion — JSON-이스케이프 형태(위 테스트)뿐 아니라 raw 개행 형태(예:
        // 다른 도구가 언젠가 감싸지 않은 텍스트를 돌려주는 경우 대비)에도 똑같이 동작해야
        // 회귀가 없다. describe()가 실제로 반환하는 형태는 아니지만 방어적으로 유지한다.
        when(locationEquipmentTools.findLocationEquipment(anyString(), any(), any(ToolContext.class)))
                .thenReturn(DESCRIBE_TEXT);
        TimelineDtos.RecallView recallView = new TimelineDtos.RecallView(
                1L, "이동식 사다리 A", "공장동 후면 차양부", "최근 평가 '상'",
                false, null, List.of(), List.of(), List.of(), List.of(), List.of("장소", "설비"));
        when(equipmentTimelineService.recall(1L)).thenReturn(recallView);
        when(workPlanTools.extractWorkPlan(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(ToolContext.class))).thenReturn("{\"result\":\"ok\"}");
        when(hazardAnalysisTools.analyzeHazards(any(), any(), any(), any(), any(ToolContext.class)))
                .thenReturn("{\"result\":\"ok\"}");
        when(hazardAnalysisTools.searchCases(any(), any(), any(), any(), any(), any(ToolContext.class)))
                .thenReturn("{\"result\":\"ok\"}");
        WorkPlan draft = WorkPlan.builder().id(10L).status(WorkPlanStatus.DRAFT).build();
        when(workPlanRepository.findFirstByConversationIdAndStatusOrderByIdDesc("conv-5", WorkPlanStatus.DRAFT))
                .thenReturn(Optional.of(draft));
        when(workPlanTools.createWorkPlan(eq(10L), any(ToolContext.class))).thenReturn("{\"result\":\"ok\"}");

        script.respond("conv-5", 1L, "공장동 후면 차양부 천장 페인트 작업", new ToolContext(Map.of()));
        script.respond("conv-5", 1L, "2026-10-01", new ToolContext(Map.of()));

        ArgumentCaptor<String> equipmentCap = ArgumentCaptor.forClass(String.class);
        verify(hazardAnalysisTools).searchCases(eq("FALL"), eq("제조업"), any(),
                equipmentCap.capture(), eq("페인트"), any(ToolContext.class));
        assertThat(equipmentCap.getValue()).contains("이동식 사다리 A");
        assertThat(equipmentCap.getValue()).doesNotContain("공장동").doesNotContain("차양부");
    }

    @Test
    void extractLocation은_JSON_이스케이프_개행_앞에서_멈춰_공정_줄을_장소에_섞지_않는다() {
        // 2026-09-29 데모 워크스루 실측 — describe()의 "위치:" 줄 뒤에 "공정/장소:" 줄과 빈 줄이 오고,
        // ToolResult.of()가 그 개행을 "\n" 리터럴로 이스케이프한다. 예전 정규식은 쉼표만 경계로 봐서
        // 장소 슬롯이 "공장동 후면 차양부\n공정/장소: 표면처리 라인 (도장)\n\n기존 위험"이 됐다.
        String describe = "설비 확인됨 [id=1] 이동식 사다리 A\n위치: 공장동 후면 차양부\n"
                + "공정/장소: 표면처리 라인 (도장)\n\n기존 위험요인 2건:\n- [추락] 안전대 부착설비 미설치\n";
        when(locationEquipmentTools.findLocationEquipment(anyString(), any(), any(ToolContext.class)))
                .thenReturn(ToolResult.of(describe));
        TimelineDtos.RecallView recallView = new TimelineDtos.RecallView(
                1L, "이동식 사다리 A", "공장동 후면 차양부", "최근 평가 '상'",
                false, null, List.of(), List.of(), List.of(), List.of(), List.of("장소", "설비"));
        when(equipmentTimelineService.recall(1L)).thenReturn(recallView);
        when(workPlanTools.extractWorkPlan(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(ToolContext.class))).thenReturn("{\"result\":\"INCOMPLETE\"}");

        script.respond("conv-6", 1L, "공장동 후면 차양부 천장 페인트 작업", new ToolContext(Map.of()));
        script.respond("conv-6", 1L, "2026-10-01", new ToolContext(Map.of()));

        ArgumentCaptor<String> placeCap = ArgumentCaptor.forClass(String.class);
        verify(workPlanTools).extractWorkPlan(any(), placeCap.capture(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(ToolContext.class));
        assertThat(placeCap.getValue()).isEqualTo("공장동 후면 차양부");
    }

    @Test
    void 설비를_못_찾으면_회상_문장_없이_시연_모드_표식으로_시작한다() {
        when(locationEquipmentTools.findLocationEquipment(anyString(), any(), any(ToolContext.class)))
                .thenReturn("등록되지 않은 설비입니다. (unmatched)");

        String answer = script.respond("conv-3", 1L, "알 수 없는 곳에서 작업", new ToolContext(Map.of()));

        assertThat(answer).startsWith(DemoConversationScript.DEMO_NOTICE);
    }
}
