package io.saife.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.saife.evidence.ledger.EvidenceLedger;
import io.saife.evidence.live.Origin;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * fix round 1 (F2) — {@link AgentService#transcript(String)}의 근거 매핑을 검증한다.
 * DB 없이(Mockito만) — {@code conversationStateRepository}와 {@code evidenceLedger}를
 * 목으로 대체하고, k번째 assistant 줄 = turn_no k 매핑이 정확한지 확인한다.
 */
class AgentServiceTranscriptTest {

    private final ConversationStateRepository conversationStateRepository = mock(ConversationStateRepository.class);
    private final EvidenceLedger evidenceLedger = mock(EvidenceLedger.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentService service = new AgentService(
            mock(ChatClient.Builder.class),
            mock(ToolRegistry.class),
            mock(SseService.class),
            mock(ConversationRepository.class),
            conversationStateRepository,
            mock(ToolCallLogRepository.class),
            mock(DemoModeConfig.class),
            mock(DemoConversationScript.class),
            objectMapper,
            mock(EquipmentRepository.class),
            evidenceLedger);

    private Evidence evidence(int no) {
        return new Evidence(no, EvidenceKind.LAW, 1L, "L:" + no, "제목" + no, "s", null, null, null,
                Origin.CACHE, 0.5, OffsetDateTime.now(), Map.of());
    }

    /** conversation_state.messagesJson과 같은 포맷 — saveHistory()가 쓰는 것과 동일한 {type,text} 배열 */
    private void stubHistory(String conversationId, String... roleTextPairs) throws Exception {
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < roleTextPairs.length; i += 2) {
            rows.add(Map.of("type", roleTextPairs[i], "text", roleTextPairs[i + 1]));
        }
        String json = objectMapper.writeValueAsString(rows);
        when(conversationStateRepository.findById(conversationId))
                .thenReturn(Optional.of(ConversationState.builder()
                        .conversationId(conversationId).messagesJson(json).lastSeq(0).build()));
    }

    @Test
    void 턴별_근거가_assistant_줄에_순서대로_붙는다() throws Exception {
        stubHistory("c1", "user", "질문1", "assistant", "답변1", "user", "질문2", "assistant", "답변2");
        Evidence e1 = evidence(1);
        Evidence e2 = evidence(2);
        Evidence e3 = evidence(3);
        when(evidenceLedger.allGroupedByTurn("c1")).thenReturn(Map.of(1, List.of(e1, e2), 2, List.of(e3)));

        List<AgentService.TranscriptLine> lines = service.transcript("c1");

        assertThat(lines).hasSize(4);
        assertThat(lines.get(0).role()).isEqualTo("user");
        assertThat(lines.get(0).evidence()).isEmpty();
        assertThat(lines.get(1).role()).isEqualTo("assistant");
        assertThat(lines.get(1).evidence()).containsExactly(e1, e2);
        assertThat(lines.get(2).role()).isEqualTo("user");
        assertThat(lines.get(2).evidence()).isEmpty();
        assertThat(lines.get(3).role()).isEqualTo("assistant");
        assertThat(lines.get(3).evidence()).containsExactly(e3);
    }

    @Test
    void 근거가_없는_턴은_빈_리스트다() throws Exception {
        stubHistory("c2", "user", "질문1", "assistant", "답변1", "user", "질문2", "assistant", "답변2");
        Evidence e3 = evidence(3);
        // turn 1(첫 assistant 줄)에는 근거가 없고, turn 2(두 번째 assistant 줄)에만 있다
        when(evidenceLedger.allGroupedByTurn("c2")).thenReturn(Map.of(2, List.of(e3)));

        List<AgentService.TranscriptLine> lines = service.transcript("c2");

        assertThat(lines).hasSize(4);
        assertThat(lines.get(1).evidence()).isEmpty();          // 첫 assistant 줄(turn 1) — 근거 없음
        assertThat(lines.get(3).evidence()).containsExactly(e3); // 두 번째 assistant 줄(turn 2)
    }

    @Test
    void assistant_줄_수를_넘는_turn_no의_근거는_예외없이_경고만_남기고_무시된다() throws Exception {
        // assistant 줄이 1개뿐인데 원장에는 turn 1과 turn 3(존재하지 않는 줄)이 있다 — 비정상 상태
        stubHistory("c3", "user", "질문1", "assistant", "답변1");
        Evidence e1 = evidence(1);
        Evidence e3 = evidence(3);
        when(evidenceLedger.allGroupedByTurn("c3")).thenReturn(Map.of(1, List.of(e1), 3, List.of(e3)));

        Logger logger = (Logger) LoggerFactory.getLogger(AgentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        List<AgentService.TranscriptLine> lines;
        try {
            lines = service.transcript("c3");
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(lines).hasSize(2);
        assertThat(lines.get(1).evidence()).containsExactly(e1);   // turn 1은 정상 매핑
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(m -> assertThat(m).contains("turn_no=3").contains("c3"));

        // 예외 없이 끝난다 — 대응할 줄이 없어도 던지지 않는다는 것을 한 번 더 직접 확인
        assertThatCode(() -> service.transcript("c3")).doesNotThrowAnyException();
    }

    /**
     * fix round 1 (ruling R52) — Critical 회귀 테스트.
     *
     * <p>리뷰에서 발견: {@code transcript()}가 텍스트가 빈 assistant 줄을 세는 대상에서
     * 건너뛰고 있었던 반면, {@code AgentService.assistantTurnIndexOfMessages}(runTurn/
     * runDemoTurn이 씀)는 텍스트 내용과 무관하게 모든 assistant 메시지를 센다 —
     * {@code loadHistory()}가 빈 텍스트 assistant 메시지도 그대로 복원해 넣기 때문이다.
     * 빈 완결(모델이 도구만 부르고 문장은 없는 턴 — {@code AgentServiceEvidenceTest.
     * 텍스트가_빈칸이면_ai_token을_보내지_않지만_원장_턴은_넘긴다}가 유효한 상태임을 보여준다)이
     * 근거 있는 턴보다 먼저 오면, DB에 저장된 turnNo와 {@code transcript()}가 매기는
     * "k번째 assistant 줄" 번호가 어긋나 근거가 엉뚱한 줄에 붙거나 유실됐다.
     *
     * <p>고친 뒤에는 빈 assistant 줄도 건너뛰지 않고 {@code TranscriptLine("assistant", "", [])}로
     * 그대로 내보낸다 — 프론트가 이 줄을 "근거만 있는 말풍선"으로 렌더링한다(ruling R52).
     */
    @Test
    void 빈_assistant_턴도_줄로_유지되고_근거_turn_매핑이_어긋나지_않는다() throws Exception {
        // history: [user, assistant "a1", user, assistant "" (빈 완결), user, assistant "a3"]
        stubHistory("c4", "user", "질문1", "assistant", "a1", "user", "질문2", "assistant", "", "user", "질문3", "assistant", "a3");
        Evidence e1 = evidence(1);
        Evidence e2 = evidence(2);
        // 근거는 3번째 assistant 메시지(a3)에만 있다 — 2번째 assistant 메시지(빈 완결)를 포함해
        // 센 결과가 3이어야 한다(빈 완결을 건너뛰면 a3가 2번째로 잘못 세어진다).
        when(evidenceLedger.allGroupedByTurn("c4")).thenReturn(Map.of(3, List.of(e1, e2)));

        List<AgentService.TranscriptLine> lines = service.transcript("c4");

        // 6줄 전부 유지된다 — 빈 assistant 줄(line 4)이 통째로 드롭되지 않는다
        assertThat(lines).hasSize(6);

        // line 2: assistant "a1" (turn 1) — 근거 없음
        assertThat(lines.get(1).role()).isEqualTo("assistant");
        assertThat(lines.get(1).text()).isEqualTo("a1");
        assertThat(lines.get(1).evidence()).isEmpty();

        // line 4: assistant "" (turn 2, 빈 완결) — 줄 자체는 존재하고 근거는 빈 리스트
        assertThat(lines.get(3).role()).isEqualTo("assistant");
        assertThat(lines.get(3).text()).isEmpty();
        assertThat(lines.get(3).evidence()).isEmpty();

        // line 6: assistant "a3" (turn 3) — 근거 2건이 정확히 여기 붙는다
        assertThat(lines.get(5).role()).isEqualTo("assistant");
        assertThat(lines.get(5).text()).isEqualTo("a3");
        assertThat(lines.get(5).evidence()).containsExactly(e1, e2);
    }
}
