package io.saife.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.ai.agent.repository.ConversationRepository;
import io.saife.ai.agent.repository.ConversationStateRepository;
import io.saife.ai.agent.repository.ToolCallLogRepository;
import io.saife.ai.tools.ToolRegistry;
import io.saife.common.config.DemoModeConfig;
import io.saife.common.service.SseService;
import io.saife.core.domain.Equipment;
import io.saife.core.repository.EquipmentRepository;
import io.saife.evidence.ledger.EvidenceLedger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 연결성 2-1 — 시스템 프롬프트에 "첫 문장은 회상" 규칙과 [시작 설비] 힌트가
 * 올바른 자리에 들어가는지. 대화 오케스트레이션 자체는 건드리지 않고
 * {@link AgentService#systemPrompt(Long)} 텍스트만 검증한다.
 */
class AgentServiceTest {

    private final EquipmentRepository equipmentRepository = mock(EquipmentRepository.class);

    private final AgentService service = new AgentService(
            mock(ChatClient.Builder.class),
            mock(ToolRegistry.class),
            mock(SseService.class),
            mock(ConversationRepository.class),
            mock(ConversationStateRepository.class),
            mock(ToolCallLogRepository.class),
            mock(DemoModeConfig.class),
            mock(DemoConversationScript.class),
            new ObjectMapper(),
            equipmentRepository,
            mock(EvidenceLedger.class));

    @Test
    void 진행_순서_2번은_설비가_확인되면_첫_문장은_회상이어야_한다는_규칙이다() {
        String prompt = service.systemPrompt(null);

        assertThat(prompt).contains("[진행 순서]");
        assertThat(prompt).contains(
                "설비가 확인되면 답변의 첫 문장은 반드시 그 설비의 최근 평가 등급과 미이행 조치를");
        assertThat(prompt).contains("요약하는 문장이어야 합니다");
        assertThat(prompt).contains("그다음에 부족한 항목을 하나만 물어보세요");
    }

    @Test
    void equipmentId가_있으면_시작_설비_줄이_끝에_붙는다() {
        when(equipmentRepository.findById(5L))
                .thenReturn(Optional.of(Equipment.builder().id(5L).name("고소작업대").build()));

        String prompt = service.systemPrompt(5L);

        assertThat(prompt).contains("[시작 설비] id=5 고소작업대");
        assertThat(prompt).contains("이 설비로 findLocationEquipment를 먼저 확인하세요");
        // "끝에 덧붙인다" — 날짜 절보다 뒤에 와야 한다
        assertThat(prompt.indexOf("[오늘 날짜]")).isLessThan(prompt.indexOf("[시작 설비]"));
    }

    @Test
    void equipmentId가_없으면_시작_설비_줄이_없다() {
        String prompt = service.systemPrompt(null);

        assertThat(prompt).doesNotContain("[시작 설비]");
    }

    @Test
    void equipmentId가_있어도_설비를_못_찾으면_시작_설비_줄을_붙이지_않는다() {
        when(equipmentRepository.findById(999L)).thenReturn(Optional.empty());

        String prompt = service.systemPrompt(999L);

        assertThat(prompt).doesNotContain("[시작 설비]");
    }

    @Test
    void 절_순서는_원칙_진행순서_오늘날짜_순이다() {
        String prompt = service.systemPrompt(null);

        int principleIdx = prompt.indexOf("[원칙]");
        int orderIdx = prompt.indexOf("[진행 순서]");
        int dateIdx = prompt.indexOf("[오늘 날짜]");

        assertThat(principleIdx).isGreaterThanOrEqualTo(0);
        assertThat(orderIdx).isGreaterThan(principleIdx);
        assertThat(dateIdx).isGreaterThan(orderIdx);
    }
}
