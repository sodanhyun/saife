package io.saife.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.ai.agent.domain.ConversationState;
import io.saife.ai.agent.repository.ConversationRepository;
import io.saife.ai.agent.repository.ConversationStateRepository;
import io.saife.ai.agent.repository.ToolCallLogRepository;
import io.saife.ai.tools.ToolRegistry;
import io.saife.common.config.DemoModeConfig;
import io.saife.common.service.SseService;
import io.saife.core.repository.EquipmentRepository;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.ConversationEvidence;
import io.saife.evidence.ledger.EvidenceLedger;
import io.saife.evidence.live.Origin;
import io.saife.evidence.repository.ConversationEvidenceRepository;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

/**
 * {@link AgentService.TurnFinisher} — 턴 종료 시 인용 후처리·ai.evidence 발행 순서.
 *
 * <p><b>payload 계약</b>: task-2-brief.md의 예시 코드는 {@code Map.of("items", fresh)}로 감쌌지만,
 * 프론트 B2(Task 3)가 이미 코디네이터 룰링에 따라 {@code types/sse.ts}의
 * {@code EvidencePayload = Evidence[]}(감싸지 않은 배열)로 구현을 끝냈다
 * (task-3-report.md: "브리프 예시는 payload: { items: [...] }로 감쌌지만, 코디네이터 룰링과
 * types/sse.ts의 EvidencePayload = Evidence[](bare)를 따라 감싸지 않은 배열로 emit했다").
 * 그래서 이 테스트는 브리프 예시가 아니라 실제로 맞춰야 하는 프론트 계약(바로 이 파일의
 * assertion)을 기준으로 삼는다 — payload는 {@code fresh} 리스트 그 자체다.
 */
class AgentServiceEvidenceTest {

    @Test
    void 턴_종료_시_새_근거를_ai_evidence로_보내고_없는_번호를_지운다() {
        SseService sse = mock(SseService.class);
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        Evidence e1 = new Evidence(1, EvidenceKind.CASE_FATALITY, 1L, "F:1", "t", "s", null, null, null, Origin.CACHE, 0.5, OffsetDateTime.now(), Map.of());
        when(ledger.newInTurn("c")).thenReturn(List.of(e1));
        when(ledger.knownNumbers("c")).thenReturn(Set.of(1));
        when(ledger.endTurn("c", 1)).thenReturn(1);

        AgentService.TurnFinisher finisher = new AgentService.TurnFinisher(sse, ledger);
        String text = finisher.finish("sess", "c", "사례 [#1]와 지침 [#9]를 보세요.", 1);

        assertThat(text).isEqualTo("사례 [#1]와 지침를 보세요.");   // [#9] 제거

        ArgumentCaptor<SseService.SseEvent> cap = ArgumentCaptor.forClass(SseService.SseEvent.class);
        verify(sse, atLeastOnce()).send(eq("sess"), cap.capture());
        List<String> types = cap.getAllValues().stream().map(SseService.SseEvent::type).toList();
        assertThat(types).containsExactly("ai.evidence", "ai.token");

        // 프론트 계약(bare Evidence[]) — Map으로 감싸지 않는다
        Object payload = cap.getAllValues().get(0).payload();
        assertThat(payload).isEqualTo(List.of(e1));
        assertThat(cap.getAllValues().get(0).targetId()).isEqualTo("c");

        verify(ledger).endTurn("c", 1);
        // ruling R37: 정상 턴 종료 경로는 discard()를 절대 부르지 않는다
        verify(ledger, never()).discard(anyString());
    }

    @Test
    void 근거가_없으면_ai_evidence를_보내지_않는다() {
        SseService sse = mock(SseService.class);
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.newInTurn("c")).thenReturn(List.of());
        when(ledger.knownNumbers("c")).thenReturn(Set.of());

        new AgentService.TurnFinisher(sse, ledger).finish("sess", "c", "안녕하세요", 1);

        ArgumentCaptor<SseService.SseEvent> cap = ArgumentCaptor.forClass(SseService.SseEvent.class);
        verify(sse).send(eq("sess"), cap.capture());
        assertThat(cap.getValue().type()).isEqualTo("ai.token");
        verify(ledger, never()).discard(anyString());
    }

    @Test
    void 텍스트가_빈칸이면_ai_token을_보내지_않지만_원장_턴은_넘긴다() {
        SseService sse = mock(SseService.class);
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        Evidence e1 = new Evidence(1, EvidenceKind.LAW, 1L, "L:1", "t", "s", null, null, null, Origin.CACHE, 0.5, OffsetDateTime.now(), Map.of());
        when(ledger.newInTurn("c")).thenReturn(List.of(e1));
        when(ledger.knownNumbers("c")).thenReturn(Set.of(1));

        String text = new AgentService.TurnFinisher(sse, ledger).finish("sess", "c", "   ", 1);

        assertThat(text).isBlank();
        ArgumentCaptor<SseService.SseEvent> cap = ArgumentCaptor.forClass(SseService.SseEvent.class);
        verify(sse).send(eq("sess"), cap.capture());
        assertThat(cap.getValue().type()).isEqualTo("ai.evidence");
        verify(ledger).endTurn("c", 1);
        verify(ledger, never()).discard(anyString());
    }

    /**
     * fix round 1 (F1): {@code knownNumbers}/{@code newInTurn}/{@code endTurn} 중 어느 것이
     * 던져도 모델의 답변을 삼키면 안 된다 — 텍스트는 항상 반환·스트리밍되고, {@code finish()}
     * 자체는 절대 예외를 밖으로 던지지 않는다(호출부가 이를 잡아 ai.error로 바꾸는 일이 없다).
     */
    @Test
    void 원장_예외에도_답변은_스트리밍된다() {
        // case 1: knownNumbers 실패 — sanitize를 건너뛰고 원문을 그대로 내보낸다(빈 known 집합으로
        // sanitize하면 모든 인용이 지워지므로 그게 더 나쁘다)
        {
            SseService sse = mock(SseService.class);
            EvidenceLedger ledger = mock(EvidenceLedger.class);
            when(ledger.knownNumbers("c")).thenThrow(new RuntimeException("DB down"));
            when(ledger.newInTurn("c")).thenReturn(List.of());

            String text = new AgentService.TurnFinisher(sse, ledger).finish("sess", "c", "원문 그대로 [#9]", 1);

            assertThat(text).isEqualTo("원문 그대로 [#9]");
            ArgumentCaptor<SseService.SseEvent> cap = ArgumentCaptor.forClass(SseService.SseEvent.class);
            verify(sse).send(eq("sess"), cap.capture());
            assertThat(cap.getValue().type()).isEqualTo("ai.token");
            assertThat(cap.getAllValues()).extracting(SseService.SseEvent::type).doesNotContain("ai.error");
            verify(ledger).endTurn("c", 1);   // knownNumbers만 실패해도 나머지 단계는 계속 진행된다
        }

        // case 2: newInTurn 실패 — ai.evidence만 생략, ai.token은 정상 발행
        {
            SseService sse = mock(SseService.class);
            EvidenceLedger ledger = mock(EvidenceLedger.class);
            when(ledger.knownNumbers("c")).thenReturn(Set.of());
            when(ledger.newInTurn("c")).thenThrow(new RuntimeException("DB down"));

            String text = new AgentService.TurnFinisher(sse, ledger).finish("sess", "c", "정상 답변", 1);

            assertThat(text).isEqualTo("정상 답변");
            ArgumentCaptor<SseService.SseEvent> cap = ArgumentCaptor.forClass(SseService.SseEvent.class);
            verify(sse).send(eq("sess"), cap.capture());
            assertThat(cap.getValue().type()).isEqualTo("ai.token");
            assertThat(cap.getAllValues()).extracting(SseService.SseEvent::type).doesNotContain("ai.error", "ai.evidence");
            verify(ledger).endTurn("c", 1);
        }

        // case 3: endTurn 실패 — 텍스트·ai.token은 이미 나간 뒤라 영향받지 않는다
        {
            SseService sse = mock(SseService.class);
            EvidenceLedger ledger = mock(EvidenceLedger.class);
            when(ledger.knownNumbers("c")).thenReturn(Set.of());
            when(ledger.newInTurn("c")).thenReturn(List.of());
            doThrow(new RuntimeException("DB down")).when(ledger).endTurn("c", 1);

            String text = new AgentService.TurnFinisher(sse, ledger).finish("sess", "c", "정상 답변", 1);

            assertThat(text).isEqualTo("정상 답변");
            ArgumentCaptor<SseService.SseEvent> cap = ArgumentCaptor.forClass(SseService.SseEvent.class);
            verify(sse).send(eq("sess"), cap.capture());
            assertThat(cap.getValue().type()).isEqualTo("ai.token");
            assertThat(cap.getAllValues()).extracting(SseService.SseEvent::type).doesNotContain("ai.error");
            verify(ledger).endTurn("c", 1);   // 호출은 됐다 — 예외가 finish() 안에서 잡혔을 뿐
        }
    }

    /**
     * H1 회귀 테스트 — {@code AgentService.assistantTurnIndex()}(패키지 접근)를 직접 검증한다.
     * 이 메서드가 endTurn()에 넘기는 turnNo와 transcript()가 매기는 "k번째 assistant 줄"의
     * 유일한 공통 정의다. 근거 유무와 무관하게 assistant 메시지가 나올 때마다 1씩 증가해야
     * 하므로, "assistant"가 하나도 없는 목록·여러 개 섞인 목록 모두 단순 개수 세기와 같아야
     * 한다.
     */
    @Test
    void assistantTurnIndex는_근거_유무와_무관하게_assistant_개수를_센다() {
        assertThat(AgentService.assistantTurnIndex(List.of())).isEqualTo(0);
        assertThat(AgentService.assistantTurnIndex(List.of("user"))).isEqualTo(0);
        assertThat(AgentService.assistantTurnIndex(List.of("user", "assistant"))).isEqualTo(1);
        assertThat(AgentService.assistantTurnIndex(List.of("user", "assistant", "user", "assistant", "user", "assistant")))
                .isEqualTo(3);
    }

    /**
     * H1 통합 테스트(브리핑 지정 케이스) — 근거 없는 턴 뒤에도 transcript() 매핑이 맞다.
     *
     * <p>UC3 고전 시나리오를 재현한다: 3턴 대화에서 근거는 3번째 턴에만 있다(1·2턴은
     * 정보 수집 턴이라 근거가 없다). {@code conversation_evidence}에는 turn_no=3으로 카드
     * 2건이 저장돼 있다 — {@code EvidenceLedger.endTurn()}이 더 이상 "근거가 있었던 턴
     * 순번"이 아니라 호출자가 넘긴 실제 assistant 순번을 그대로 저장하기 때문이다(H1 수정).
     * {@code transcript()}는 이 turn_no=3을 "3번째 assistant 줄"(a3)에 정확히 매핑해야
     * 하고, 1·2번째 assistant 줄(a1, a2)은 비어 있어야 한다.
     */
    @Test
    void 근거_없는_턴_뒤에도_transcript_매핑이_맞다() throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        ConversationEvidenceRepository evRepo = mock(ConversationEvidenceRepository.class);
        EvidenceLedger realLedger = new EvidenceLedger(evRepo, om);

        Evidence card1 = new Evidence(1, EvidenceKind.CASE_FATALITY, 1L, "F:1", "t1", "s", null, null, null,
                Origin.CACHE, 0.5, OffsetDateTime.now(), Map.of());
        Evidence card2 = new Evidence(2, EvidenceKind.LAW, 1L, "L:1", "t2", "s", null, null, null,
                Origin.CACHE, 0.5, OffsetDateTime.now(), Map.of());
        ConversationEvidence row1 = ConversationEvidence.builder()
                .conversationId("uc3").turnNo(3).evidenceNo(1).payload(om.writeValueAsString(card1)).build();
        ConversationEvidence row2 = ConversationEvidence.builder()
                .conversationId("uc3").turnNo(3).evidenceNo(2).payload(om.writeValueAsString(card2)).build();
        when(evRepo.findByConversationIdOrderByEvidenceNo("uc3")).thenReturn(List.of(row1, row2));

        // 저장된 대화 — user/assistant가 교대로 3턴, 근거는 3번째 턴(a3)에만 있다
        String messagesJson = om.writeValueAsString(List.of(
                Map.of("type", "user", "text", "u1"),
                Map.of("type", "assistant", "text", "a1"),
                Map.of("type", "user", "text", "u2"),
                Map.of("type", "assistant", "text", "a2"),
                Map.of("type", "user", "text", "u3"),
                Map.of("type", "assistant", "text", "a3")));
        ConversationState state = ConversationState.builder()
                .conversationId("uc3").messagesJson(messagesJson).lastSeq(0).build();
        ConversationStateRepository stateRepo = mock(ConversationStateRepository.class);
        when(stateRepo.findById("uc3")).thenReturn(Optional.of(state));

        AgentService agentService = new AgentService(
                mock(ChatClient.Builder.class),
                mock(ToolRegistry.class),
                mock(SseService.class),
                mock(ConversationRepository.class),
                stateRepo,
                mock(ToolCallLogRepository.class),
                mock(DemoModeConfig.class),
                mock(DemoConversationScript.class),
                om,
                mock(EquipmentRepository.class),
                realLedger);

        List<AgentService.TranscriptLine> lines = agentService.transcript("uc3");

        assertThat(lines).hasSize(6);
        assertThat(lines.get(1).role()).isEqualTo("assistant");
        assertThat(lines.get(1).text()).isEqualTo("a1");
        assertThat(lines.get(1).evidence()).isEmpty();
        assertThat(lines.get(3).text()).isEqualTo("a2");
        assertThat(lines.get(3).evidence()).isEmpty();
        assertThat(lines.get(5).text()).isEqualTo("a3");
        assertThat(lines.get(5).evidence()).extracting(Evidence::no).containsExactly(1, 2);
    }

    /**
     * fix round 1 (ruling R52) — 빈 assistant 완결이 중간 턴에 와도 {@code endTurn}에
     * 넘기는 turnNo가 실제 턴 순서(1, 2, 3)를 그대로 따라가는지 {@code AgentService.chat()}
     * 전체 경로(데모 모드)로 검증한다. 2번째 턴의 {@code DemoConversationScript.respond()}가
     * 빈 문자열을 돌려줘도(정상 상태) 3번째 턴은 turnNo=3으로 끝나야 한다 — turnNo=2로
     * 잘못 세어지면(빈 턴을 건너뛰는 옛 버그) 3번째 턴의 근거가 transcript()에서 2번째
     * assistant 줄에 붙어버린다.
     */
    @Test
    void 빈_턴_뒤에도_endTurn이_실제_턴_순서대로_불린다() throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.knownNumbers(anyString())).thenReturn(Set.of());
        when(ledger.newInTurn(anyString())).thenReturn(List.of());
        when(ledger.summaryLine(anyString())).thenReturn("");

        // conversation·conversation_state는 실제 DB 없이 메모리에 흉내 낸다 — save()가
        // 다음 findById()에 그대로 반영돼야 chat()을 여러 번 연달아 불러도 히스토리가 이어진다.
        Map<String, io.saife.ai.agent.domain.Conversation> conversations = new HashMap<>();
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        when(conversationRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(conversations.get(inv.getArgument(0))));
        when(conversationRepository.save(any(io.saife.ai.agent.domain.Conversation.class)))
                .thenAnswer(inv -> {
                    io.saife.ai.agent.domain.Conversation c = inv.getArgument(0);
                    conversations.put(c.getId(), c);
                    return c;
                });

        Map<String, ConversationState> states = new HashMap<>();
        ConversationStateRepository stateRepo = mock(ConversationStateRepository.class);
        when(stateRepo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(states.get(inv.getArgument(0))));
        when(stateRepo.save(any(ConversationState.class)))
                .thenAnswer(inv -> {
                    ConversationState s = inv.getArgument(0);
                    states.put(s.getConversationId(), s);
                    return s;
                });

        DemoModeConfig demoModeConfig = mock(DemoModeConfig.class);
        when(demoModeConfig.isDemoMode()).thenReturn(true);
        DemoConversationScript script = mock(DemoConversationScript.class);
        // 1번째 턴 "a1", 2번째 턴 빈 완결(정상 상태), 3번째 턴 "a3"
        when(script.respond(anyString(), any(), anyString(), any())).thenReturn("a1", "", "a3");

        AgentService agentService = new AgentService(
                mock(ChatClient.Builder.class),
                mock(ToolRegistry.class),
                mock(SseService.class),
                conversationRepository,
                stateRepo,
                mock(ToolCallLogRepository.class),
                demoModeConfig,
                script,
                om,
                mock(EquipmentRepository.class),
                ledger);

        agentService.chat("t1", null, 1L, "첫 메시지");
        agentService.chat("t1", null, 1L, "둘째 메시지");
        agentService.chat("t1", null, 1L, "셋째 메시지");

        verify(ledger).endTurn("t1", 1);
        verify(ledger).endTurn("t1", 2);
        verify(ledger).endTurn("t1", 3);
    }

    /**
     * 최종 리뷰 F8 — 실패한 턴(ai.error)이 원장에 남긴 근거가 다음 턴의 ai.evidence로 새지 않도록
     * 에러 경로가 원장 상태를 버린다. 정상 턴은 여전히 discard를 부르지 않는다(R37).
     */
    @Test
    void 턴이_실패하면_ai_error_뒤에_원장_상태를_버린다() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.knownNumbers(anyString())).thenReturn(Set.of());
        when(ledger.newInTurn(anyString())).thenReturn(List.of());
        when(ledger.summaryLine(anyString())).thenReturn("");

        Map<String, io.saife.ai.agent.domain.Conversation> conversations = new HashMap<>();
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        when(conversationRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(conversations.get(inv.getArgument(0))));
        when(conversationRepository.save(any(io.saife.ai.agent.domain.Conversation.class)))
                .thenAnswer(inv -> {
                    io.saife.ai.agent.domain.Conversation c = inv.getArgument(0);
                    conversations.put(c.getId(), c);
                    return c;
                });
        Map<String, ConversationState> states = new HashMap<>();
        ConversationStateRepository stateRepo = mock(ConversationStateRepository.class);
        when(stateRepo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(states.get(inv.getArgument(0))));
        when(stateRepo.save(any(ConversationState.class))).thenAnswer(inv -> {
            ConversationState st = inv.getArgument(0);
            states.put(st.getConversationId(), st);
            return st;
        });
        DemoModeConfig demoModeConfig = mock(DemoModeConfig.class);
        when(demoModeConfig.isDemoMode()).thenReturn(true);
        DemoConversationScript script = mock(DemoConversationScript.class);
        when(script.respond(anyString(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("모델 장애"))
                .thenReturn("둘째 턴 정상");
        SseService sse = mock(SseService.class);

        AgentService agentService = new AgentService(mock(ChatClient.Builder.class), mock(ToolRegistry.class), sse,
                conversationRepository, stateRepo, mock(ToolCallLogRepository.class), demoModeConfig, script,
                new ObjectMapper().findAndRegisterModules(), mock(EquipmentRepository.class), ledger);

        agentService.chat("t9", "s1", 1L, "첫 메시지");
        verify(ledger).discard("t9");
        verify(ledger, never()).endTurn(eq("t9"), anyInt());

        agentService.chat("t9", "s1", 1L, "둘째 메시지");
        verify(ledger, times(1)).discard("t9");   // 정상 턴은 discard를 부르지 않는다
        verify(ledger).endTurn("t9", 1);
    }
}
