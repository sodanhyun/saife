package io.saife.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.ai.agent.AgentContextKeys;
import io.saife.common.service.SseService;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.WorkPlanEvidence;
import io.saife.evidence.ledger.EvidenceLedger;
import io.saife.evidence.live.Origin;
import io.saife.evidence.repository.WorkPlanEvidenceRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanSlot;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.repository.WorkPlanSlotRepository;
import io.saife.workplan.repository.WorkPlanWorkerRepository;
import io.saife.workplan.service.BriefingComposer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

/**
 * B1 T4 fix round 1 (ruling R53) — {@code createWorkPlan}이 원장 근거를
 * {@code work_plan_evidence}에 저장하는 경로. 저장 실패가 제출 자체를 막지 않아야 한다.
 */
class WorkPlanToolsTest {
    private final WorkPlanRepository workPlanRepository = mock(WorkPlanRepository.class);
    private final WorkPlanWorkerRepository workPlanWorkerRepository = mock(WorkPlanWorkerRepository.class);
    private final WorkPlanSlotRepository workPlanSlotRepository = mock(WorkPlanSlotRepository.class);
    private final BriefingComposer briefingComposer = mock(BriefingComposer.class);
    private final SseService sseService = mock(SseService.class);
    private final EvidenceLedger evidenceLedger = mock(EvidenceLedger.class);
    private final WorkPlanEvidenceRepository workPlanEvidenceRepository = mock(WorkPlanEvidenceRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final ToolContext ctx = new ToolContext(Map.of(
            AgentContextKeys.CONVERSATION_ID, "c1", AgentContextKeys.SESSION_ID, "s1"));

    private WorkPlanTools tools() {
        return new WorkPlanTools(workPlanRepository, workPlanWorkerRepository, workPlanSlotRepository,
                briefingComposer, sseService, evidenceLedger, workPlanEvidenceRepository, objectMapper,
                mock(io.saife.core.repository.EquipmentRepository.class), mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    /** 필수 슬롯(work_height)이 채워진, 방금 로드된 DRAFT 계획서 */
    private WorkPlan draftPlan() {
        return WorkPlan.builder().id(42L).siteId(1L).workName("일반 작업").workDate(LocalDate.now())
                .status(WorkPlanStatus.DRAFT).build();
    }

    private void stubRequiredSlotFilled() {
        when(workPlanSlotRepository.findByWorkPlanIdAndSlotKey(eq(42L), anyString()))
                .thenReturn(Optional.of(WorkPlanSlot.builder().id(1L).workPlanId(42L)
                        .slotKey("work_height").answeredValue("1.5").answeredAt(OffsetDateTime.now()).build()));
    }

    private Evidence evidence(int no, String refKey) {
        return new Evidence(no, EvidenceKind.CASE_FATALITY, 1L, refKey, "사례" + no, "s", null, null, null,
                Origin.CACHE, 0.8, OffsetDateTime.now(), Map.of());
    }

    @Test
    void 성공시_원장의_근거를_번호순으로_work_plan_evidence에_저장한다() {
        WorkPlan plan = draftPlan();
        when(workPlanRepository.findById(42L)).thenReturn(Optional.of(plan));
        when(workPlanRepository.save(any(WorkPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRequiredSlotFilled();
        when(briefingComposer.compose(plan)).thenReturn("브리핑");
        when(evidenceLedger.all("c1")).thenReturn(List.of(evidence(1, "F:1"), evidence(2, "L:1")));

        String out = tools().createWorkPlan(42L, ctx);

        assertThat(out).contains("점검표 제출 완료");
        ArgumentCaptor<WorkPlanEvidence> cap = ArgumentCaptor.forClass(WorkPlanEvidence.class);
        verify(workPlanEvidenceRepository, times(2)).save(cap.capture());
        assertThat(cap.getAllValues()).extracting(WorkPlanEvidence::getEvidenceNo).containsExactly(1, 2);
        assertThat(cap.getAllValues()).allMatch(e -> e.getWorkPlanId().equals(42L));
        assertThat(cap.getAllValues().get(0).getPayload()).contains("사례1").contains("F:1");
        assertThat(cap.getAllValues().get(1).getPayload()).contains("사례2").contains("L:1");
    }

    @Test
    void 근거_저장이_실패해도_계획서는_그대로_제출되고_결과가_바뀌지_않는다() {
        WorkPlan plan = draftPlan();
        when(workPlanRepository.findById(42L)).thenReturn(Optional.of(plan));
        when(workPlanRepository.save(any(WorkPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRequiredSlotFilled();
        when(briefingComposer.compose(plan)).thenReturn("브리핑");
        when(evidenceLedger.all("c1")).thenReturn(List.of(evidence(1, "F:1")));
        when(workPlanEvidenceRepository.save(any(WorkPlanEvidence.class)))
                .thenThrow(new RuntimeException("DB 장애"));

        String out = tools().createWorkPlan(42L, ctx);

        assertThat(out).contains("점검표 제출 완료").contains("브리핑");
        verify(workPlanRepository).save(argThat(p -> p.getStatus() == WorkPlanStatus.SUBMITTED));
    }

    @Test
    void 이동식_사다리면_발판_높이_최상부_디딤대_넘어짐_방지_제품명_순으로_묻는다() {
        assertThat(WorkPlanTools.requiredSlotsFor("천장 페인트 작업", io.saife.core.service.RiskRuleEngine.KIND_LADDER))
                .containsExactly("work_height", "top_step", "tip_guard", "product_name");
    }

    @Test
    void 사다리_흐름에서는_안전대_부착설비를_묻지_않는다() {
        assertThat(WorkPlanTools.requiredSlotsFor("천장 페인트 작업", io.saife.core.service.RiskRuleEngine.KIND_LADDER))
                .doesNotContain("anchor_installed");
    }

    @Test
    void 고소작업대는_작업대_안전난간을_묻는다() {
        assertThat(WorkPlanTools.requiredSlotsFor("조명 교체", io.saife.core.service.RiskRuleEngine.KIND_AERIAL_PLATFORM))
                .containsExactly("work_height", "platform_guardrail");
    }
}
